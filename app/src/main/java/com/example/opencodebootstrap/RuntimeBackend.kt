package com.example.opencodebootstrap

import java.io.File

interface RuntimeBackend {
    /** Quick capability/compat check on this device. */
    fun probe(): Result<String>

    /** Run [command] inside [rootfs] (or host root "/"). Returns exit code + output. */
    fun run(rootfs: File, command: List<String>, env: Map<String, String> = emptyMap()): Process
}
