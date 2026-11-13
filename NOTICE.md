# Notices and third-party components

## Hermes Loader NG runtime components

Selected Android runtime, terminal, installer, and chroot integration code was
adapted from [santoni-star/hermes-loader-ng](https://github.com/santoni-star/hermes-loader-ng).
The app's Hermes-specific agent and dashboard code was not copied. The upstream
project identifies its code as GNU GPL version 2 or later
(GPL-2.0-or-later); the corresponding source is available from the repository
above. Adapted components include the session/ANSI terminal, foreground
service, `chroot-ng` command integration, Debian OCI installer, archive
extractor, and downloader.

The `libchrootng.so` executable in
`app/src/main/jniLibs/arm64-v8a/` comes from
[santoni-star/fake-chroot-ng](https://github.com/santoni-star/fake-chroot-ng)
and is distributed under GPL-2.0-or-later. It is executed as a separate
process. Consult that source project for corresponding source and license
terms.

## Debian root filesystem

Debian Trixie is downloaded at first setup from the official
`library/debian:trixie-slim` image on Docker Hub. The APK contains no rootfs.
Each OCI layer is verified against its SHA-256 digest before extraction.
Debian packages carry their respective licenses; the installed image includes
their copyright notices under `/usr/share/doc/*/copyright`.

## Java archive libraries

- Apache Commons Compress 1.26.2 — Apache License 2.0.
- XZ for Java 1.9 — public domain.

## Fonts

The configurable typefaces and their individual license notices are listed in
[FONTS.md](FONTS.md). Cascadia Mono is bundled as the terminal's fallback font
under the SIL Open Font License 1.1; its notice is in
`app/src/main/assets/fonts/Cascadia-OFL.txt`.

## OpenCode

The app installs `@opencode/cli` from the npm registry inside Debian; it is not
bundled in the APK. OpenCode and its dependencies are governed by their
respective upstream licenses.
