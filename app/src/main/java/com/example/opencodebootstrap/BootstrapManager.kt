package com.example.opencodebootstrap

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class BootstrapManager(private val ctx: Context) {
    private val base = File(ctx.filesDir, "env")
    private val binDir = File(base, "bin").apply { mkdirs() }
    private val rootfs = File(base, "rootfs").apply { mkdirs() } // minimal; "/" works for static bins too
    private val opencode = File(binDir, "opencode")
    private val cng = File(ctx.applicationInfo.nativeLibraryDir, "libchroot_ng.so").let {
        if (it.exists()) it else File(binDir, "chroot-ng")
    }

    fun backend(): RuntimeBackend = FakeChrootNgBackend(cng)

    fun ensureInstalled(log: (String) -> Unit): File {
        if (!cng.exists()) error("fake-chroot-ng binary missing (bundle as jniLibs/libchroot_ng.so for arm64-v8a). Probe not possible.")
        val probe = backend().probe()
        log("probe: ${probe.fold({ it }, { "FAILED: ${it.message}" })}")
        probe.getOrThrow()

        if (!opencode.exists()) {
            log("downloading opencode (linux-arm64)...")
            val archive = File(binDir, "opencode.tar.gz")
            download(OPENCODE_URL, archive)
            extractOpencode(archive, opencode)
            archive.delete()
            check(opencode.setExecutable(true)) { "chmod failed: $opencode" }
        } else {
            log("opencode present: ${opencode.absolutePath}")
        }
        // shortcut: no checksum/signature verify yet; add pinned hash before production release
        return opencode
    }

    fun launchOpencode(args: List<String> = emptyList()): Process {
        // Run the host-downloaded binary inside rootfs context; adjust path mapping once rootfs layout is fixed.
        val cmd = listOf(opencode.absolutePath) + args
        return backend().run(rootfs, cmd)
    }

    fun opencodePath(): File = opencode

    private fun download(url: String, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15000; readTimeout = 60000
            inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (responseCode !in 200..299) error("HTTP $responseCode for $url")
        }
        check(tmp.renameTo(dst)) { "rename failed" }
    }

    /** Extracts the `opencode` entry from a .tar.gz release archive. */
    private fun extractOpencode(archive: File, out: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { gzi ->
            val hdr = ByteArray(512)
            while (gzi.readN(hdr) == 512) {
                if (hdr.all { it == 0.toByte() }) break
                val name = String(hdr, 0, 100).trimEnd('\u0000', ' ')
                val size = String(hdr, 124, 12).trimEnd('\u0000', ' ').toLongOrNull(8) ?: 0L
                if (name.endsWith("/opencode") || name == "opencode") {
                    FileOutputStream(out).use { copyExact(gzi, it, size) }
                    return
                }
                skipExact(gzi, size + ((512 - (size % 512)) % 512))
            }
        }
        error("opencode binary not found in archive")
    }

    private fun InputStream.readN(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) { val n = read(buf, off, buf.size - off); if (n <= 0) break; off += n }
        return off
    }
    private fun copyExact(src: java.io.InputStream, dst: java.io.OutputStream, n: Long) {
        val buf = ByteArray(8192); var left = n
        while (left > 0) { val r = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r <= 0) error("unexpected EOF"); dst.write(buf, 0, r); left -= r }
    }
    private fun skipExact(src: java.io.InputStream, n: Long) { var left = n; while (left > 0) { val s = src.skip(left); if (s <= 0) { if (src.read() == -1) error("unexpected EOF"); left-- } else left -= s } }

    companion object {
        // Standalone linux-arm64 musl binary archive; pin to a known-good release for reproducibility.
        const val OPENCODE_URL =
            "https://opencode.ai/update/api/latest/cli/opencode/opencode-linux-arm64-musl.tar.gz"
    }
}
