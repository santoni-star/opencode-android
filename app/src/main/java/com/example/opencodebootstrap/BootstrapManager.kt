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
    private val binDir = File(ctx.filesDir, "env/bin").apply { mkdirs() }
    private val opencode = File(binDir, "opencode")
    private val cng = File(ctx.applicationInfo.nativeLibraryDir, "libchroot_ng.so").let {
        if (it.exists()) it else File(binDir, "chroot-ng")
    }
    fun backend(): RuntimeBackend = FakeChrootNgBackend(cng)

    fun ensureInstalled(log: (String) -> Unit): File {
        require(cng.exists()) { "fake-chroot-ng binary missing. Bundle it as jniLibs/arm64-v8a/libchroot_ng.so." }
        val probe = backend().probe()
        log("Runtime probe: ${probe.fold({ it }, { "FAILED: ${it.message}" })}")
        probe.getOrThrow()
        if (!opencode.exists()) {
            log("Downloading OpenCode CLI (Linux ARM64 musl)…")
            val archive = File(binDir, "opencode.tar.gz")
            download(OPENCODE_URL, archive)
            try { extractOpencode(archive, opencode) } finally { archive.delete() }
            check(opencode.setExecutable(true)) { "Could not make executable: ${opencode.absolutePath}" }
        } else log("OpenCode ready: ${opencode.absolutePath}")
        return opencode
    }

    /** OpenCode's `run` command is non-interactive; use it because this UI has pipes, not a PTY. */
    fun runPrompt(prompt: String): Process = backend().run(
        File("/"), listOf(opencode.absolutePath, "run", prompt)
    )
    fun opencodePath(): File = opencode

    private fun download(url: String, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 60000
            try {
                check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} for $url" }
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally { conn.disconnect() }
            check(tmp.renameTo(dst)) { "Could not move downloaded archive into place" }
        } finally { tmp.delete() }
    }

    private fun extractOpencode(archive: File, out: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { gzi ->
            val hdr = ByteArray(512)
            while (gzi.readN(hdr) == 512) {
                if (hdr.all { it == 0.toByte() }) break
                val name = String(hdr, 0, 100).trimEnd('\u0000', ' ')
                val size = String(hdr, 124, 12).trimEnd('\u0000', ' ').toLongOrNull(8) ?: 0L
                if (name.endsWith("/opencode") || name == "opencode") {
                    FileOutputStream(out).use { copyExact(gzi, it, size) }; return
                }
                skipExact(gzi, size + ((512 - (size % 512)) % 512))
            }
        }
        error("opencode binary not found in archive")
    }
    private fun InputStream.readN(buf: ByteArray): Int { var off=0; while(off<buf.size){val n=read(buf,off,buf.size-off);if(n<=0)break;off+=n};return off }
    private fun copyExact(src: InputStream, dst: java.io.OutputStream, n: Long) { val b=ByteArray(8192);var left=n;while(left>0){val r=src.read(b,0,minOf(b.size.toLong(),left).toInt());if(r<=0)error("unexpected EOF");dst.write(b,0,r);left-=r} }
    private fun skipExact(src: InputStream, n: Long) { var left=n;while(left>0){val s=src.skip(left);if(s<=0){if(src.read()==-1)error("unexpected EOF");left--}else left-=s} }
    companion object { const val OPENCODE_URL = "https://opencode.ai/update/api/latest/cli/opencode/opencode-linux-arm64-musl.tar.gz" }
}
