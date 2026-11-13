package com.opencode.android.bootstrap;

import android.system.ErrnoException;
import android.system.Os;

import com.opencode.android.Env;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Safely overlays a gzip/xz tar archive into the app-private rootfs. */
public final class TarExtract {
    private static final long MAX_ENTRY_BYTES = 1L << 30;
    private static final long PROGRESS_STEP = 32L << 20;

    private TarExtract() {
    }

    public static void extract(File archive, boolean gzip, File destDir, String label, Log log)
            throws IOException {
        Env.mkdirs(destDir);
        Path root = destDir.toPath().toAbsolutePath().normalize();
        List<String> whiteouts = readWhiteouts(archive, gzip);
        for (String whiteout : whiteouts) applyWhiteout(root, whiteout);

        List<LinkEntry> symlinks = new ArrayList<>();
        List<LinkEntry> hardlinks = new ArrayList<>();
        int files = 0;
        long written = 0;
        long nextReport = PROGRESS_STEP;
        byte[] buffer = new byte[64 * 1024];

        try (TarArchiveInputStream tar = open(archive, gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = sanitize(entry.getName());
                if (name == null || name.isEmpty() || isWhiteout(name)) continue;
                File out = resolve(root, name, false);

                if (entry.isSymbolicLink()) {
                    symlinks.add(new LinkEntry(name, entry.getLinkName()));
                    continue;
                }
                if (entry.isLink()) {
                    hardlinks.add(new LinkEntry(name, entry.getLinkName()));
                    continue;
                }
                if (entry.isDirectory()) {
                    if (!Files.isDirectory(out.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        deleteTree(out.toPath());
                    }
                    Env.mkdirs(out);
                    chmod(out, entry.getMode() | 0700);
                    continue;
                }
                if (!entry.isFile()) {
                    deleteTree(out.toPath());
                    continue;
                }

                long size = entry.getSize();
                if (size < 0 || size > MAX_ENTRY_BYTES) {
                    throw new IOException("Archive member " + entry.getName()
                            + " has an invalid or excessive size.");
                }
                File parent = resolve(root, parentOf(name), true);
                Env.mkdirs(parent);
                deleteTree(out.toPath());
                try (OutputStream output = new FileOutputStream(out)) {
                    long remaining = size;
                    while (remaining > 0) {
                        int count = tar.read(buffer, 0,
                                (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("Truncated archive member: " + name);
                        output.write(buffer, 0, count);
                        remaining -= count;
                        written += count;
                        if (written >= nextReport) {
                            nextReport = written + PROGRESS_STEP;
                            log.raw("\r[FS] extracting… " + (written >> 20) + " MB written");
                        }
                    }
                }
                chmod(out, entry.getMode() | 0600);
                files++;
            }
        }

        for (LinkEntry link : hardlinks) {
            String targetName = sanitize(link.target);
            if (targetName == null || targetName.isEmpty()) {
                throw new IOException("Invalid hard-link target in rootfs archive.");
            }
            File source = resolve(root, targetName, true);
            File target = resolve(root, link.name, false);
            File parent = resolve(root, parentOf(link.name), true);
            Env.mkdirs(parent);
            deleteTree(target.toPath());
            hardlink(source, target);
        }
        for (LinkEntry link : symlinks) {
            if (link.target == null || link.target.indexOf('\0') >= 0) {
                throw new IOException("Invalid symbolic-link target in rootfs archive.");
            }
            File target = resolve(root, link.name, false);
            File parent = resolve(root, parentOf(link.name), true);
            Env.mkdirs(parent);
            deleteTree(target.toPath());
            symlink(link.target, target);
        }

        log.line("");
        log.line("[FS] Extracted " + files + " files into " + label + ".");
    }

    private static List<String> readWhiteouts(File archive, boolean gzip) throws IOException {
        List<String> out = new ArrayList<>();
        try (TarArchiveInputStream tar = open(archive, gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = sanitize(entry.getName());
                if (name != null && isWhiteout(name)) out.add(name);
            }
        }
        return out;
    }

    private static void applyWhiteout(Path root, String marker) throws IOException {
        String base = marker.substring(marker.lastIndexOf('/') + 1);
        String parent = parentOf(marker);
        File directory = resolve(root, parent, true);
        if (".wh..wh..opq".equals(base)) {
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
            File[] children = directory.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child.toPath());
            }
            return;
        }
        String removed = (parent.isEmpty() ? "" : parent + "/")
                + base.substring(".wh.".length());
        deleteTree(resolve(root, removed, false).toPath());
    }

    private static boolean isWhiteout(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        return base.startsWith(".wh.");
    }

    private static TarArchiveInputStream open(File archive, boolean gzip) throws IOException {
        InputStream input = new BufferedInputStream(new FileInputStream(archive), 64 * 1024);
        try {
            InputStream decompressed = gzip
                    ? new GZIPInputStream(input, 64 * 1024)
                    : new XZInputStream(input, 64 * 1024);
            return new TarArchiveInputStream(decompressed);
        } catch (IOException e) {
            input.close();
            throw e;
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.indexOf('\0') >= 0) return null;
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) return null;
        StringBuilder safe = new StringBuilder();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return null;
            if (safe.length() > 0) safe.append('/');
            safe.append(part);
        }
        return safe.toString();
    }

    private static File resolve(Path root, String name, boolean followFinal) throws IOException {
        Path current = root;
        if (name.isEmpty()) return current.toFile();
        String[] parts = name.split("/");
        for (int i = 0; i < parts.length; i++) {
            current = current.resolve(parts[i]).normalize();
            requireWithin(root, current);
            if (i < parts.length - 1 || followFinal) {
                current = resolveExistingLinks(root, current);
            }
        }
        return current.toFile();
    }

    private static Path resolveExistingLinks(Path root, Path path) throws IOException {
        Path current = path;
        for (int count = 0; Files.isSymbolicLink(current); count++) {
            if (count >= 40) throw new IOException("Too many rootfs symbolic links: " + path);
            String rawTarget;
            try {
                rawTarget = Os.readlink(current.toString());
            } catch (ErrnoException e) {
                throw new IOException("Cannot inspect rootfs symbolic link: " + current, e);
            }
            Path target = Paths.get(rawTarget);
            Path resolved;
            if (target.isAbsolute()) {
                String relative = target.toString().replaceFirst("^/+", "");
                resolved = root.resolve(relative);
            } else {
                resolved = current.getParent().resolve(target);
            }
            current = resolved.normalize();
            requireWithin(root, current);
        }
        return current;
    }

    private static void requireWithin(Path root, Path path) throws IOException {
        if (!path.startsWith(root)) {
            throw new IOException("Archive path escapes rootfs: " + path);
        }
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            File[] children = path.toFile().listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child.toPath());
            }
        }
        Files.deleteIfExists(path);
    }

    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
        } catch (ErrnoException | RuntimeException ignored) {
            // Some filesystems reject permission updates; execution checks catch unusable trees.
        }
    }

    private static void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Cannot create rootfs symbolic link: " + link, e);
        }
    }

    private static void hardlink(File source, File link) throws IOException {
        if (!source.isFile()) throw new IOException("Missing hard-link target: " + source);
        try {
            Os.link(source.getAbsolutePath(), link.getAbsolutePath());
        } catch (ErrnoException | RuntimeException e) {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(link)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (IOException copyError) {
                copyError.addSuppressed(e);
                throw copyError;
            }
            chmod(link, 0644);
        }
    }

    private static final class LinkEntry {
        final String name;
        final String target;

        LinkEntry(String name, String target) {
            this.name = name;
            this.target = target;
        }
    }
}
