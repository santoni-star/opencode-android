package com.example.opencodebootstrap

import java.io.File

/** Primary backend: sylirre/fake-chroot-ng (no ptrace, AArch64). */
class FakeChrootNgBackend(private val bin: File) : RuntimeBackend {
    override fun probe(): Result<String> = exec(listOf(bin.absolutePath, "--probe"))

    override fun run(rootfs: File, command: List<String>, env: Map<String, String>): Process {
        val args = mutableListOf(bin.absolutePath, rootfs.absolutePath) + command
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(env)
        // shortcut: inherits app env/PATH; refine with explicit PATH/CA vars when rootfs is finalized
        return pb.start()
    }

    private fun exec(cmd: List<String>): Result<String> = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) error("exit $code: $out") else out.ifBlank { "ok" }
    }
}
