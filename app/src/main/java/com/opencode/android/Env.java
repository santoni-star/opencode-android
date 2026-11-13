package com.opencode.android;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** App-private Linux files and persistent OpenCode user data. */
public final class Env {

    public static final String ROOTFS_MARKER = ".opencode-rootfs-trixie";
    public static final int ROOTFS_DEBIAN_MAJOR = 13;

    private static volatile Env instance;

    public final File files;
    public final File storage;
    public final File rootfs;
    public final File nativeLib;
    public final File cache;
    public final File tmp;
    public final File scripts;
    public final File home;
    public final File config;
    public final File data;
    public final File state;
    public final File workspace;
    public final File androidRoot;
    public final File storageEtc;
    public final File storageTmp;

    private Env(File files, File nativeLib) {
        this.files = files;
        this.nativeLib = nativeLib;
        storage = new File(files, "storage");
        rootfs = new File(files, "rootfs");
        cache = new File(files, "cache");
        tmp = new File(files, "tmp");
        scripts = new File(files, "scripts");
        home = new File(storage, "home");
        config = new File(storage, "config");
        data = new File(storage, "data");
        state = new File(storage, "state");
        workspace = new File(storage, "workspace");
        androidRoot = new File(storage, "android");
        storageEtc = new File(storage, "etc");
        storageTmp = new File(storage, "tmp");
    }

    public static Env get(Context ctx) {
        Env local = instance;
        if (local == null) {
            synchronized (Env.class) {
                local = instance;
                if (local == null) {
                    Context app = ctx.getApplicationContext();
                    local = new Env(app.getFilesDir(),
                            new File(app.getApplicationInfo().nativeLibraryDir));
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void forget() {
        instance = null;
    }

    public void ensure() throws IOException {
        File[] dirs = {storage, rootfs, cache, tmp, scripts, home, config, data, state,
                workspace, androidRoot, storageEtc, storageTmp};
        for (File dir : dirs) mkdirs(dir);
    }

    public boolean rootfsInstalled() {
        return new File(rootfs, "bin/bash").isFile()
                && new File(rootfs, ROOTFS_MARKER).isFile()
                && isRequiredDistro(rootfs);
    }

    public boolean openCodeInstalled() {
        return new File(rootfs, "usr/local/bin/opencode").isFile()
                || new File(rootfs, "usr/bin/opencode").isFile()
                || new File(rootfs, "bin/opencode").isFile();
    }

    public static boolean isRequiredDistro(File root) {
        return debianMajor(root) == ROOTFS_DEBIAN_MAJOR;
    }

    public static int debianMajor(File root) {
        File version = new File(new File(root, "etc"), "debian_version");
        if (!version.isFile()) return -1;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(version), StandardCharsets.US_ASCII))) {
            String line = in.readLine();
            if (line == null) return -1;
            line = line.trim();
            int end = 0;
            while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
            return end == 0 ? -1 : Integer.parseInt(line.substring(0, end));
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** This build ships the chroot-ng executable for arm64-v8a only. */
    public static String abiDir() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return abi;
        }
        return "";
    }

    public static String debianArch() {
        return "arm64-v8a".equals(abiDir()) ? "arm64" : "";
    }

    public File guestBinary() {
        return new File(nativeLib, "libchrootng.so");
    }

    public static void mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }
}
