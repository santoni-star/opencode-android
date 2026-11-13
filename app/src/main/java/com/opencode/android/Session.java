package com.opencode.android;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single long-running guest process (chroot-ng -> bash -> OpenCode).
 *
 * <p>Lives outside the Activity on purpose: the foreground service keeps it
 * alive while the app is in the background, and a re-created Activity can
 * re-attach and replay the buffered output.
 */
public final class Session {

    public interface Listener {
        void onOutput(String chunk);

        void onExit(int code);
    }

    private static final Session INSTANCE = new Session();
    private static final int BACKLOG_LIMIT = 200_000;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder backlog = new StringBuilder();

    private Process process;
    private OutputStream stdin;
    private String mode = "";
    private String lastMode = "";
    private boolean pty;
    private boolean stopRequested;
    private int lastExit = -1;

    private Session() {
    }

    public static Session get() {
        return INSTANCE;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized boolean running() {
        return process != null;
    }

    public synchronized String mode() {
        return mode;
    }

    /** The mode of the session that just ended (for diagnostics after it is gone). */
    public synchronized String lastMode() {
        return lastMode;
    }

    public synchronized int lastExitCode() {
        return lastExit;
    }

    public synchronized String backlog() {
        return backlog.toString();
    }

    public synchronized void start(String mode, boolean pty, List<String> argv,
                                   Map<String, String> env, File cwd) throws IOException {
        if (running()) {
            throw new IllegalStateException("A session is already running");
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        Map<String, String> penv = pb.environment();
        penv.clear();
        penv.putAll(env);

        process = pb.start();
        stdin = process.getOutputStream();
        this.mode = mode;
        this.pty = pty;
        this.stopRequested = false;
        this.lastExit = -1;
        backlog.setLength(0);

        Thread pump = new Thread(this::pump, "opencode-session");
        pump.setDaemon(true);
        pump.start();
    }

    private void pump() {
        Process p = process;
        int code = -1;
        try (Reader reader = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                String chunk = new String(buf, 0, n);
                synchronized (this) {
                    backlog.append(chunk);
                    int overflow = backlog.length() - BACKLOG_LIMIT;
                    if (overflow > 0) {
                        backlog.delete(0, overflow);
                    }
                }
                for (Listener l : listeners) {
                    l.onOutput(chunk);
                }
            }
            code = p.waitFor();
        } catch (IOException e) {
            boolean expectedStop;
            synchronized (this) {
                expectedStop = stopRequested;
            }
            if (expectedStop) {
                // Closing a process stream during a user-requested stop can
                // interrupt the reader. It is an expected shutdown, not a
                // guest error to print into the terminal.
                code = 0;
            } else {
                for (Listener l : listeners) {
                    l.onOutput("\n[loader] stream error: " + e.getMessage() + "\n");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (stopRequested) code = 0;
                lastExit = code;
                lastMode = mode;
                process = null;
                stdin = null;
                mode = "";
                stopRequested = false;
            }
            for (Listener l : listeners) {
                l.onExit(code);
            }
        }
    }

    /** Sends raw text to the guest (no newline is appended). */
    public void write(String data) {
        OutputStream out;
        synchronized (this) {
            out = stdin;
        }
        if (out == null) return;
        try {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // The session is going away; the exit callback reports it.
        }
    }

    /** Sends a whole line, using the line ending the guest terminal expects. */
    public void submit(String line) {
        write(line + (pty ? "\r" : "\n"));
    }

    /** Ends the guest process and sweeps up child processes that outlived it. */
    public void stop() {
        Process p;
        synchronized (this) {
            p = process;
            if (p != null) stopRequested = true;
        }
        if (p == null) return;

        p.destroy();
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && alive(p)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (alive(p)) killForcibly(p);
        killLeftovers();
    }

    /** Sends a graceful termination first, followed by a forceful fallback. */
    private static void sweep(String... patterns) {
        StringBuilder term = new StringBuilder();
        StringBuilder kill = new StringBuilder();
        for (String pattern : patterns) {
            // The bracketed first letter keeps pkill from matching this very
            // `sh -c` command line, which contains the pattern as text.
            term.append("pkill -TERM -f '").append(pattern).append("' 2>/dev/null; ");
            kill.append("pkill -KILL -f '").append(pattern).append("' 2>/dev/null; ");
        }
        // Half a second is enough for a daemon to run its atexit hooks and is
        // paid only by STOP, never by START (Launcher sweeps off the UI thread).
        String cmd = term + "sleep 0.5; " + kill + "true";
        try {
            new ProcessBuilder("sh", "-c", cmd).start().waitFor();
        } catch (Exception ignored) {
            // A missing pkill or a denied signal is not fatal.
        }
    }

    /** Best-effort cleanup for OpenCode and chroot processes left by Android. */
    public static void killLeftovers() {
        // Match guest executables only; a generic "opencode" pattern also matches this app.
        sweep("[l]ibchrootng.so", "[/]usr/local/bin/opencode",
                "[/]usr/bin/opencode", "[/]usr/local/lib/node_modules/@opencode/cli/");
    }

    /** {@code Process.isAlive()} is API 26, so probe the exit value instead. */
    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    static void killForcibly(Process p) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            p.destroyForcibly();
            return;
        }
        // API 24-25 have no Process.destroyForcibly(); SIGKILL the tracee tree.
        try {
            String pid = String.valueOf(pidOf(p));
            if ("-1".equals(pid)) return;
            new ProcessBuilder("sh", "-c", "kill -9 " + pid + " 2>/dev/null").start().waitFor();
        } catch (Exception ignored) {
            // Best effort: chroot-ng may leave the guest process tree running.
        }
    }

    /** libcore keeps the pid in a private field on every API level. */
    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = Process.class.getDeclaredField("pid");
            f.setAccessible(true);
            return ((Number) f.get(p)).intValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
