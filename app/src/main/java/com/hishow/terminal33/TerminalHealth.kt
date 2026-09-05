package com.hishow.terminal33

import java.io.File

object TerminalHealth {
    fun rootfsLooksHealthy(rootfs: File): Boolean =
        File(rootfs, "bin/bash").isFile &&
        File(rootfs, "etc/os-release").isFile &&
        File(rootfs, "usr").isDirectory &&
        File(rootfs, "tmp").isDirectory
}
