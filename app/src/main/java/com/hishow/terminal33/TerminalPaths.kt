package com.hishow.terminal33

import android.content.Context
import java.io.File

object TerminalPaths {
    fun ubuntuHome(context: Context): File = File(context.filesDir, "ubuntu")
    fun rootfs(context: Context): File = File(ubuntuHome(context), "rootfs")
    fun shared(context: Context): File = File(rootfs(context), "mnt/shared")
}
