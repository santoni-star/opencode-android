# OpenCode Android

OpenCode Android runs the official OpenCode CLI in a Debian Trixie ARM64
environment on-device. It includes a touch-friendly terminal, a two-row
Termux-inspired key panel, focus mode, an authenticated loopback web UI, a
persistent workspace, and light, dark, and Dracula visual themes.

The interface language follows the Android system language (Ukrainian or
English). Terminal typefaces and size are configurable in Visual settings.

## Runtime

- Supported devices: Android 8.0+ on `arm64-v8a`.
- Linux distribution: Debian Trixie (Debian 13), fetched from the official
  `library/debian:trixie-slim` OCI image. Each layer is SHA-256 verified before
  extraction; there is no user-supplied URL or unverified fallback.
- Linux execution: `chroot-ng`, shipped as `libchrootng.so`.
- OpenCode: installed in Debian from the official npm package
  `@opencode/cli@2.3.0`.
- First setup needs internet access and several hundred megabytes of free
  device storage. Debian and OpenCode user data remain app-private.
- The default workspace is persistent. Shared folders are not exposed
  automatically; use the app's workspace directory or copy files into it.

The terminal provides OpenCode TUI and a Linux shell. The Web button starts
`opencode serve` bound to `127.0.0.1` only. Its password is generated and kept
in Android app-private preferences; it is not shown in logs or exposed to the
local network.

## Build

Install JDK 17 and Android SDK platform 35, then run on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

The Android interface uses framework views. Apache Commons Compress and XZ for
Java are used to unpack the verified Debian layers.

## License and third-party notices

See [NOTICE.md](NOTICE.md) for runtime provenance and third-party licenses.
The terminal font sources and licenses are listed in [FONTS.md](FONTS.md).
The reference runtime and bundled chroot-ng are GPL-2.0-or-later; preserve
their notices and corresponding source links when redistributing.
