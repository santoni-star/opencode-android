package com.opencode.android.bootstrap;

import java.util.List;

/** Resolves the sole digest-verified Debian Trixie rootfs source. */
public final class RootfsSource {

    private RootfsSource() {
    }

    public static List<RemoteArchive> resolve(String debianArch, Log log) throws Exception {
        if (!"arm64".equals(debianArch)) {
            throw new IllegalArgumentException("Only Debian arm64 images are supported.");
        }
        List<RemoteArchive> parts = DockerHub.resolve(debianArch);
        for (RemoteArchive part : parts) {
            if (part.sha256 == null || part.sha256.length() != 64) {
                throw new IllegalStateException("Debian image layer has no valid SHA-256 digest.");
            }
        }
        log.line("[NET] Source: official Debian Trixie image, verified by OCI SHA-256 digests.");
        return parts;
    }
}
