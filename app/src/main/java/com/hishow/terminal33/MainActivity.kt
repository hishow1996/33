package com.hishow.terminal33

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalApp(applicationContext) }
    }
}

private data class BootstrapState(val ready: Boolean, val message: String, val progress: Int)

@Composable
private fun TerminalApp(context: Context) {
    var state by remember { mutableStateOf(BootstrapState(false, "Preparing Ubuntu…", 0)) }
    var terminal by remember { mutableStateOf<UbuntuTerminal?>(null) }
    var output by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                UbuntuBootstrap(context).prepare { message, progress ->
                    state = BootstrapState(false, message, progress)
                }
                withContext(Dispatchers.Main) {
                    val session = UbuntuTerminal(context)
                    terminal = session
                    state = BootstrapState(true, "Ubuntu 24.04 ready", 100)
                    session.start { chunk ->
                        output += chunk
                        if (output.length > 120_000) output = output.takeLast(100_000)
                    }
                }
            } catch (t: Throwable) {
                state = BootstrapState(false, "Setup failed: ${t.message ?: "unknown error"}", -1)
            }
        }
    }

    Surface(Modifier.fillMaxSize(), Color(0xFF0B0D10)) {
        if (!state.ready) BootstrapScreen(state) else {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                TerminalHeader()
                TerminalOutput(output)
                CommandBar(
                    value = command,
                    onValueChange = { command = it },
                    onSend = {
                        val text = command
                        command = ""
                        if (text.isNotBlank()) terminal?.write(text + "\n")
                    },
                    onInterrupt = { terminal?.write("\u0003") }
                )
                QuickBar { terminal?.write(it + "\n") }
            }
        }
    }
}

@Composable
private fun BootstrapScreen(state: BootstrapState) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ubuntu Terminal", color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Text("Native Android terminal · Ubuntu 24.04 arm64", color = Color(0xFF9AA3AD), fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
        Text(state.message, color = Color(0xFFE8EAED), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        if (state.progress >= 0) Text("${state.progress}%", color = Color(0xFF7DD3FC), fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
        Text(
            "Ubuntu Base is bundled in the APK. The first launch only extracts the bundled system into private storage.",
            color = Color(0xFF727B86), fontSize = 12.sp
        )
    }
}

@Composable
private fun TerminalHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("ubuntu", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text("24.04", color = Color(0xFF7DD3FC), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text("arm64", color = Color(0xFF66717D), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun TerminalOutput(text: String) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    LaunchedEffect(text) { vertical.scrollTo(vertical.maxValue) }
    Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF050608)).padding(14.dp)) {
        Text(
            text.ifEmpty { "Starting shell…\n" },
            Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal),
            color = Color(0xFFE5E7EB), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp
        )
    }
}

@Composable
private fun CommandBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, onInterrupt: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.weight(1f).background(Color(0xFF171A1F), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onInterrupt, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A31)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Ctrl+C", fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B7FFF)), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Run")
        }
    }
}

@Composable
private fun QuickBar(onCommand: (String) -> Unit) {
    val items = listOf("pwd", "ls -la", "uname -a", "whoami", "clear")
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            Button(
                onClick = { onCommand(item) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15181D)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
            ) { Text(item, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

private class UbuntuBootstrap(private val context: Context) {
    companion object {
        private const val ROOTFS_ASSET = "ubuntu-base-24.04.4-base-arm64.tar.gz"
        private const val ROOTFS_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
        private const val PROOT_URL = "https://github.com/proot-me/proot-rs/releases/download/v0.1.0/proot-rs-v0.1.0-aarch64-linux-android.tar.gz"
    }

    private val home = File(context.filesDir, "ubuntu")
    private val rootfs = File(home, "rootfs")
    private val proot = File(home, "proot")

    fun prepare(progress: (String, Int) -> Unit) {
        home.mkdirs()
        if (!rootfsReady()) {
            val archive = File(home, "rootfs.tar.gz")
            copyBundledRootfs(archive) { p -> progress("Preparing bundled Ubuntu…", 5 + p * 45 / 100) }
            progress("Verifying Ubuntu package…", 52)
            verifySha256(archive, ROOTFS_SHA256)
            progress("Extracting Ubuntu…", 55)
            TarGzExtractor.extract(archive, rootfs) { p -> progress("Extracting Ubuntu…", 55 + p * 30 / 100) }
            archive.delete()
            configureRootfs()
        }
        if (!proot.exists()) {
            val archive = File(home, "proot.tar.gz")
            progress("Installing PRoot runtime…", 87)
            download(PROOT_URL, archive) { p -> progress("Installing PRoot runtime…", 87 + p * 10 / 100) }
            TarGzExtractor.extractFirstNamed(archive, proot)
            archive.delete()
            proot.setExecutable(true, false)
        }
        progress("Finishing…", 100)
    }

    private fun rootfsReady(): Boolean = File(rootfs, "bin/bash").exists() && File(rootfs, "etc/os-release").exists()

    private fun copyBundledRootfs(target: File, progress: (Int) -> Unit) {
        val total = runCatching { context.assets.openFd(ROOTFS_ASSET).length }.getOrDefault(-1L)
        context.assets.open(ROOTFS_ASSET, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
    }

    private fun configureRootfs() {
        val resolv = File(rootfs, "etc/resolv.conf")
        runCatching {
            if (resolv.exists() || java.nio.file.Files.isSymbolicLink(resolv.toPath())) resolv.delete()
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "tmp").mkdirs()
    }

    private fun download(url: String, target: File, progress: (Int) -> Unit) {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally { connection.disconnect() }
    }

    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expected, ignoreCase = true)) { "Ubuntu archive checksum mismatch" }
    }
}

private class UbuntuTerminal(private val context: Context) {
    private var process: Process? = null
    private var writer: java.io.OutputStream? = null

    fun start(onOutput: (String) -> Unit) {
        val base = File(context.filesDir, "ubuntu")
        val rootfs = File(base, "rootfs")
        val proot = File(base, "proot")
        val tmp = File(base, "tmp").apply { mkdirs() }
        val command = listOf(
            proot.absolutePath, "-0", "-r", rootfs.absolutePath,
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            "-b", "/sdcard:/mnt/shared", "-w", "/root", "/bin/bash", "--login"
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["TERM"] = "xterm-256color"
        builder.environment()["HOME"] = "/root"
        builder.environment()["LANG"] = "C.UTF-8"
        builder.environment()["LC_ALL"] = "C.UTF-8"
        builder.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        builder.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
        process = builder.start()
        writer = process?.outputStream
        Thread {
            val input = process?.inputStream ?: return@Thread
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                onOutput(String(buffer, 0, n, Charsets.UTF_8))
            }
        }.apply { isDaemon = true }.start()
    }

    fun write(text: String) {
        runCatching {
            writer?.write(text.toByteArray(Charsets.UTF_8))
            writer?.flush()
        }
    }
}

private object TarGzExtractor {
    fun extract(archive: File, destination: File, progress: (Int) -> Unit) {
        destination.mkdirs()
        GZIPInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024)).use { input ->
            extractTar(input, destination, progress)
        }
    }

    fun extractFirstNamed(archive: File, output: File) {
        val temp = File(output.parentFile, "proot.tmp")
        GZIPInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024)).use { input ->
            extractTarToSingle(input, temp)
        }
        check(temp.renameTo(output) || temp.copyTo(output, true).let { temp.delete(); true })
    }

    private fun extractTar(input: InputStream, destination: File, progress: (Int) -> Unit) {
        val header = ByteArray(512)
        var bytes = 0L
        while (true) {
            if (readFully(input, header) == null) break
            if (header.all { it.toInt() == 0 }) break
            val size = octal(header, 124, 12)
            val name = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val path = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val type = header[156].toInt().toChar()
            val out = safePath(destination, path)
            when (type) {
                '5' -> out.mkdirs()
                '2' -> {
                    skip(input, size)
                    out.parentFile?.mkdirs()
                    runCatching { android.system.Os.symlink(tarString(header, 157, 100), out.absolutePath) }
                }
                '0', '\u0000' -> {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { copyExactly(input, it, size) }
                }
                else -> skip(input, size)
            }
            val pad = (512 - (size % 512)) % 512
            if (pad > 0) skip(input, pad)
            bytes += size + 512 + pad
            if (bytes % (512L * 128L) < 512) progress((bytes % 10000 / 100).toInt().coerceIn(0, 99))
        }
        progress(100)
    }

    private fun extractTarToSingle(input: InputStream, output: File) {
        val header = ByteArray(512)
        while (true) {
            if (readFully(input, header) == null) break
            if (header.all { it.toInt() == 0 }) break
            val size = octal(header, 124, 12)
            val name = tarString(header, 0, 100)
            val type = header[156].toInt().toChar()
            if ((type == '0' || type == '\u0000') && (name == "proot" || name.endsWith("/proot"))) {
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { copyExactly(input, it, size) }
                return
            }
            skip(input, size)
            val pad = (512 - (size % 512)) % 512
            if (pad > 0) skip(input, pad)
        }
        error("PRoot binary not found in archive")
    }

    private fun readFully(input: InputStream, buffer: ByteArray): ByteArray? {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return if (offset == 0) null else error("Unexpected end of tar archive")
            offset += n
        }
        return buffer
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) error("Unexpected end of tar entry")
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) error("Unexpected end of tar archive")
                remaining--
            } else remaining -= skipped
        }
    }

    private fun octal(buffer: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in offset until offset + length) {
            val c = buffer[i].toInt().and(0xFF)
            if (c in 48..55) value = (value shl 3) + (c - 48)
        }
        return value
    }

    private fun tarString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buffer[end].toInt() != 0) end++
        return String(buffer, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun safePath(root: File, entry: String): File {
        val clean = entry.trimStart('/').replace("..", "_")
        val file = File(root, clean).canonicalFile
        check(file.path == root.canonicalPath || file.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe tar path" }
        return file
    }
}
