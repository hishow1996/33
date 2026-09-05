package com.hishow.terminal33

import android.content.Context
import android.net.Uri
import java.io.File

/** Copies user-selected SAF content into the terminal's shared directory. */
object SafFileBridge {
    fun copyIntoShared(context: Context, uri: Uri, destination: File): Boolean = runCatching {
        destination.mkdirs()
        val name = context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "imported-file"
        context.contentResolver.openInputStream(uri)?.use { input ->
            File(destination, name).outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: error("Unable to open selected file")
        true
    }.getOrDefault(false)
}
