package com.opencode.android.bootstrap;

import com.opencode.android.Env;
import com.opencode.android.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the isolated chroot-ng invocation and persistent guest mounts. */
public final class GuestCommand {
    public static final String MODE_INSTALL = "install";
    public static final String MODE_BOOT = "boot";
    public static final String MODE_SHELL = "shell";
    public static final String MODE_WEB = "web";
    public static final int WEB_PORT = Prefs.WEB_PORT;

    public static final class Spec {
        public final List<String> argv;
        public final Map<String, String> env;
        public final boolean pty;

        Spec(List<String> argv, Map<String, String> env, boolean pty) {
            this.argv = argv;
            this.env = env;
            this.pty = pty;
        }
    }

    private GuestCommand() {
    }

    public static Spec build(Env env, Prefs prefs, String mode, boolean pty,
                             int columns, int rows) throws Exception {
        File chrootNg = env.guestBinary();
        if (!chrootNg.isFile()) {
            throw new IllegalStateException("Bundled chroot-ng is missing: " + chrootNg);
        }

        List<String> args = new ArrayList<>();
        args.add(chrootNg.getAbsolutePath());
        args.add("--fake-id=0:0");
        args.add("--link2symlink");
        args.add("--shared-proc");
        if (prefs.compatMode()) args.add("-R");
        args.add("-w");
        args.add("/home/opencode/workspace");
        args.add("-b");
        args.add("/sys:/sys");
        bind(args, env.home, "/home/opencode");
        bind(args, env.config, "/home/opencode/.config/opencode");
        bind(args, env.data, "/home/opencode/.local/share/opencode");
        bind(args, env.state, "/home/opencode/.local/state/opencode");
        bind(args, env.workspace, "/home/opencode/workspace");
        bind(args, env.androidRoot, "/mnt/android");
        bind(args, env.scripts, "/opt/loader");
        bind(args, env.storageTmp, "/tmp");
        bind(args, new File(env.storageEtc, "resolv.conf"), "/etc/resolv.conf");
        bind(args, new File(env.storageEtc, "hosts"), "/etc/hosts");
        bind(args, new File(env.storageEtc, "hostname"), "/etc/hostname");

        args.add(env.rootfs.getAbsolutePath());
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/home/opencode");
        args.add("USER=root");
        args.add("LOGNAME=root");
        args.add("SHELL=/bin/bash");
        args.add("TERM=xterm-256color");
        args.add("LANG=C.UTF-8");
        args.add("LC_ALL=C.UTF-8");
        args.add("TMPDIR=/tmp");
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("COLUMNS=" + columns);
        args.add("LINES=" + rows);
        boolean usePty = pty && scriptAvailable(env);
        if (usePty) {
            args.add("/usr/bin/script");
            args.add("-q");
            args.add("-f");
            args.add("-e");
            args.add("-c");
            args.add("/bin/bash /opt/loader/init.sh " + mode);
            args.add("/dev/null");
        } else {
            args.add("/bin/bash");
            args.add("/opt/loader/init.sh");
            args.add(mode);
        }

        Map<String, String> processEnv = new LinkedHashMap<>();
        processEnv.put("TMPDIR", env.tmp.getAbsolutePath());
        processEnv.put("HOME", env.home.getAbsolutePath());
        processEnv.put("PATH", "/system/bin:/system/xbin");
        for (String key : new String[]{"ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE",
                "EXTERNAL_STORAGE", "BOOTCLASSPATH"}) {
            String value = System.getenv(key);
            if (value != null) processEnv.put(key, value);
        }
        return new Spec(args, processEnv, usePty);
    }

    public static boolean scriptAvailable(Env env) {
        return new File(env.rootfs, "usr/bin/script").isFile()
                || new File(env.rootfs, "bin/script").isFile();
    }

    private static void bind(List<String> args, File host, String guest) {
        args.add("-b");
        args.add(host.getAbsolutePath() + ":" + guest);
    }
}
