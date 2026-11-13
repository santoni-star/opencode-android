package com.opencode.android;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.opencode.android.bootstrap.GuestCommand;
import com.opencode.android.bootstrap.Log;
import com.opencode.android.bootstrap.NetFiles;
import com.opencode.android.bootstrap.NgInstaller;
import com.opencode.android.bootstrap.RootfsInstaller;
import com.opencode.android.bootstrap.Scripts;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Prepares the Debian guest and starts an OpenCode or shell session. */
public final class Launcher {

    public interface Ui {
        void line(String text);
        void raw(String text);
        void busy(boolean busy);
        void status(String text);
    }

    private Launcher() {
    }

    public static void start(Context context, Prefs prefs, String mode, Ui ui) {
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> run(app, prefs, mode, ui), "opencode-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(Context app, Prefs prefs, String mode, Ui ui) {
        Log log = new Log() {
            @Override
            public void line(String text) {
                ui.line(text);
            }

            @Override
            public void raw(String text) {
                ui.raw(text);
            }
        };
        ui.busy(true);
        boolean keepService = false;
        try {
            if (Session.get().running()) {
                throw new IllegalStateException("A terminal session is already running.");
            }
            if (!"arm64-v8a".equals(Env.abiDir())) {
                throw new IOException("This build supports ARM64 devices only.");
            }

            ui.status("Preparing Debian");
            Session.killLeftovers();
            Env env = Env.get(app);
            env.ensure();
            log.line("[OpenCode Android] Debian Trixie · arm64-v8a");
            log.line("[INIT] Persistent workspace: " + env.workspace);
            log.line("[DISK] " + (env.files.getUsableSpace() >> 20) + " MB free.");

            ui.status("Checking runtime");
            NgInstaller.ensure(env, log);
            Scripts.sync(app, env);
            writeWebPassword(env, prefs);
            NetFiles.write(app, env);

            ui.status("Preparing Debian");
            RootfsInstaller.ensure(env, log);
            RunnerService.start(app);
            keepService = true;

            if (GuestCommand.MODE_INSTALL.equals(mode)) {
                ui.status("Installing OpenCode");
                if (!runBlocking(env, prefs, GuestCommand.MODE_INSTALL, log)) {
                    throw new IOException("OpenCode installation failed; see terminal output.");
                }
                if (!env.openCodeInstalled()) {
                    throw new IOException("Installation finished without an OpenCode executable.");
                }
                log.line("[DONE] OpenCode is installed.");
                return;
            }

            if (!GuestCommand.MODE_SHELL.equals(mode) && !env.openCodeInstalled()) {
                ui.status("Installing OpenCode");
                log.line("[INIT] First run: installing Node.js and OpenCode CLI.");
                if (!runBlocking(env, prefs, GuestCommand.MODE_INSTALL, log)) {
                    throw new IOException("OpenCode installation failed; see terminal output.");
                }
                if (!env.openCodeInstalled()) {
                    throw new IOException("Installation finished without an OpenCode executable.");
                }
            }

            boolean pty = prefs.pty()
                    && !GuestCommand.MODE_WEB.equals(mode)
                    && GuestCommand.scriptAvailable(env);
            startSession(env, prefs, mode, pty);
            ui.status(GuestCommand.MODE_WEB.equals(mode) ? "Starting web UI" : "Running");
            keepService = true;
            if (pty && ptyLooksBroken(2500)) {
                log.line("[PTY] PTY startup failed; retrying with a plain terminal stream.");
                startSession(env, prefs, mode, false);
            }
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
            log.line("\n[ERROR] " + message);
            if (message.contains("error=13") || message.toLowerCase(java.util.Locale.ROOT)
                    .contains("permission denied")) {
                log.line("[HINT] Reinstall the app so Android can extract chroot-ng.");
            } else if (message.contains("Function not implemented")) {
                log.line("[HINT] Enable compatibility mode in the runtime settings and retry.");
            }
        } finally {
            boolean running = Session.get().running();
            ui.status(running ? "Running" : null);
            ui.busy(false);
            if (!running || !keepService) RunnerService.stop(app);
        }
    }

    private static boolean runBlocking(Env env, Prefs prefs, String mode, Log log)
            throws Exception {
        startSession(env, prefs, mode, false);
        while (Session.get().running()) Thread.sleep(300);
        int code = Session.get().lastExitCode();
        if (code != 0) log.line("[ERROR] " + mode + " exited with code " + code + ".");
        return code == 0;
    }

    private static void startSession(Env env, Prefs prefs, String mode, boolean pty)
            throws Exception {
        GuestCommand.Spec spec = GuestCommand.build(env, prefs, mode, pty, 100, 40);
        Session.get().start(mode, spec.pty, spec.argv, spec.env, env.workspace);
    }

    private static boolean ptyLooksBroken(long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (Session.get().running() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !Session.get().running() && Session.get().lastExitCode() != 0;
    }

    private static void writeWebPassword(Env env, Prefs prefs) throws IOException {
        File passwordFile = new File(env.scripts, "web-auth.env");
        String content = "export OPENCODE_PASSWORD='" + prefs.webPassword() + "'\n";
        try (FileOutputStream output = new FileOutputStream(passwordFile, false)) {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        try {
            Os.chmod(passwordFile.getAbsolutePath(), 0600);
        } catch (ErrnoException e) {
            throw new IOException("Cannot protect the local web password file.", e);
        }
    }
}
