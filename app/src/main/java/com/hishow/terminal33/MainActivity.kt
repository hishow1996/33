package com.hishow.terminal33

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    var command by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    val screen = remember { TerminalScreenModel() }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val terminalFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun send(text: String, rememberCommand: Boolean = false) {
        terminal?.write(text)
        if (rememberCommand && text.trim().isNotEmpty()) {
            val clean = text.trimEnd('\n', '\r')
            if (history.lastOrNull() != clean) history = (history + clean).takeLast(100)
            historyIndex = -1
        }
    }

    fun focusTerminal() {
        runCatching { terminalFocus.requestFocus() }
        keyboard?.show()
    }

    fun pasteClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            send(TerminalInput.normalizePaste(text))
            focusTerminal()
        }
    }

    fun startSession() {
        scope.launch(Dispatchers.IO) {
            try {
                UbuntuBootstrap(context).prepare { message, progress -> main.post { state = BootstrapState(false, message, progress) } }
                val session = UbuntuTerminal(context)
                withContext(Dispatchers.Main) {
                    terminal?.close()
                    terminal = session
                    screen.clear()
                    screen.resize(120, 32)
                    state = BootstrapState(true, "Ubuntu 24.04 ready", 100)
                }
                session.start(
                    onOutput = { chunk -> screen.feed(chunk) },
                    onExit = { main.post { state = BootstrapState(true, "Shell exited · press Restart", 100) } }
                )
            } catch (t: Throwable) {
                main.post { state = BootstrapState(false, "Setup failed: ${t.message ?: "unknown error"}", -1) }
            }
        }
    }

    LaunchedEffect(Unit) { startSession() }
    DisposableEffect(Unit) { onDispose { terminal?.close() } }

    Surface(Modifier.fillMaxSize(), Color(0xFF090B0E)) {
        if (!state.ready) BootstrapScreen(state) else {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                TerminalHeader(
                    onRestart = { terminal?.close(); startSession() },
                    onClear = { screen.clear() },
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", screen.copyText()))
                    },
                    onPaste = ::pasteClipboard
                )
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 7.dp)) {
                    TerminalCanvas(
                        model = screen,
                        modifier = Modifier.fillMaxSize(),
                        onSizeChanged = { rows, columns -> screen.resize(columns, rows); terminal?.resize(rows, columns) },
                        onTap = ::focusTerminal
                    )
                    DirectTerminalInput(
                        focusRequester = terminalFocus,
                        onSend = { text -> send(text) }
                    )
                }
                CommandBar(
                    value = command,
                    onValueChange = { command = it },
                    onSend = { val text = command; command = ""; if (text.isNotBlank()) send(text + "\n", true) },
                    onInterrupt = { send(TerminalInput.CTRL_C) },
                    onKey = { send(it) },
                    onHistory = { direction ->
                        if (history.isEmpty()) return@CommandBar
                        val next = (historyIndex + direction).coerceIn(-1, history.lastIndex)
                        historyIndex = next
                        command = if (next < 0) "" else history[history.lastIndex - next]
                    },
                    onPaste = ::pasteClipboard
                )
                QuickBar { send(it + "\n", true) }
            }
        }
    }
}

@Composable
private fun DirectTerminalInput(focusRequester: FocusRequester, onSend: (String) -> Unit) {
    var buffer by remember { mutableStateOf("") }
    BasicTextField(
        value = buffer,
        onValueChange = { value ->
            when {
                value.length > buffer.length && value.startsWith(buffer) -> { onSend(value.substring(buffer.length)); buffer = value }
                value.length < buffer.length && buffer.startsWith(value) -> { repeat(buffer.length - value.length) { onSend(TerminalInput.BACKSPACE) }; buffer = value }
                else -> { if (value.isNotEmpty()) onSend(value); buffer = value.takeLast(32) }
            }
            if (buffer.length > 32) buffer = buffer.takeLast(32)
        },
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = androidx.compose.ui.text.SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
        keyboardActions = KeyboardActions(),
        modifier = Modifier.size(1.dp).focusRequester(focusRequester).onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val sequence = when (event.key) {
                Key.Enter -> TerminalInput.ENTER
                Key.Backspace -> TerminalInput.BACKSPACE
                Key.Delete -> TerminalInput.delete()
                Key.DirectionUp -> TerminalInput.arrowUp()
                Key.DirectionDown -> TerminalInput.arrowDown()
                Key.DirectionLeft -> TerminalInput.arrowLeft()
                Key.DirectionRight -> TerminalInput.arrowRight()
                Key.MoveHome -> TerminalInput.home()
                Key.MoveEnd -> TerminalInput.end()
                Key.PageUp -> TerminalInput.pageUp()
                Key.PageDown -> TerminalInput.pageDown()
                Key.Tab -> TerminalInput.TAB
                Key.Escape -> TerminalInput.ESC
                else -> null
            }
            if (sequence != null) { onSend(sequence); if (sequence == TerminalInput.ENTER) buffer = ""; true } else false
        }
    )
}

@Composable
private fun BootstrapScreen(state: BootstrapState) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Ubuntu Terminal", color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp)); Text("Ubuntu 24.04 · arm64", color = Color(0xFF8D98A5), fontSize = 13.sp)
        Spacer(Modifier.height(28.dp)); Text(state.message, color = Color(0xFFE8EAED), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp)); if (state.progress >= 0) Text("${state.progress}%", color = Color(0xFF7DD3FC), fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp)); Text("Ubuntu Base is bundled in the APK. First launch unpacks it into private storage.", color = Color(0xFF68727D), fontSize = 12.sp)
    }
}

@Composable
private fun TerminalHeader(onRestart: () -> Unit, onClear: () -> Unit, onCopy: () -> Unit, onPaste: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ubuntu", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Spacer(Modifier.width(9.dp)); Text("24.04", color = Color(0xFF7DD3FC), fontFamily = FontFamily.Monospace, fontSize = 12.sp); Spacer(Modifier.weight(1f))
        IconButton(onClick = onPaste) { Text("PA", color = Color(0xFF87919D), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        IconButton(onClick = onRestart) { Text("RS", color = Color(0xFF87919D), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        IconButton(onClick = onCopy) { Text("CP", color = Color(0xFF87919D), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        IconButton(onClick = onClear) { Text("CL", color = Color(0xFF87919D), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun CommandBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, onInterrupt: () -> Unit, onKey: (String) -> Unit, onHistory: (Int) -> Unit, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0D1014)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
                modifier = Modifier.weight(1f).background(Color(0xFF171B20), RoundedCornerShape(9.dp)).padding(horizontal = 11.dp, vertical = 10.dp),
                textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onSend() }))
            Spacer(Modifier.width(5.dp)); Button(onClick = onPaste, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242A31)), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)) { Text("Paste", fontSize = 12.sp) }
            Spacer(Modifier.width(5.dp)); Button(onClick = onInterrupt, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242A31)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text("^C", fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.width(5.dp)); Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF287CF0)), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)) { Text("Run") }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SmallKey("Ctrl-C") { onKey(TerminalInput.CTRL_C) }; SmallKey("Ctrl-D") { onKey(TerminalInput.CTRL_D) }; SmallKey("Ctrl-Z") { onKey(TerminalInput.CTRL_Z) }
            SmallKey("Alt-") { onKey(TerminalInput.alt("")) }; SmallKey("↑") { onHistory(1) }; SmallKey("↓") { onHistory(-1) }
            SmallKey("Tab") { onKey(TerminalInput.TAB) }; SmallKey("Esc") { onKey(TerminalInput.ESC) }; SmallKey("←") { onKey(TerminalInput.arrowLeft()) }; SmallKey("→") { onKey(TerminalInput.arrowRight()) }
            SmallKey("Home") { onKey(TerminalInput.home()) }; SmallKey("End") { onKey(TerminalInput.end()) }; SmallKey("PgUp") { onKey(TerminalInput.pageUp()) }; SmallKey("PgDn") { onKey(TerminalInput.pageDown()) }
        }
    }
}

@Composable
private fun SmallKey(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF171B20)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
}

@Composable
private fun QuickBar(onCommand: (String) -> Unit) {
    val items = listOf("pwd", "ls -la", "whoami", "uname -a", "id", "clear")
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEach { item -> Button(onClick = { onCommand(item) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14181D)), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp)) { Text(item, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
    }
}

private class UbuntuBootstrap(private val context: Context) {
    companion object {
        private const val ROOTFS_ASSET = "ubuntu-base-24.04.3-base-arm64.tar.gz"
        private const val ROOTFS_SHA256 = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
        private const val PROOT_URL = "https://github.com/proot-me/proot-rs/releases/download/v0.1.0/proot-rs-v0.1.0-aarch64-linux-android.tar.gz"
    }
    private val home = File(context.filesDir, "ubuntu"); private val rootfs = File(home, "rootfs"); private val proot = File(home, "proot")
    fun prepare(progress: (String, Int) -> Unit) {
        home.mkdirs()
        if (!rootfsReady()) {
            val archive = File(home, "rootfs.tar.gz"); runCatching { archive.delete() }
            copyAsset(ROOTFS_ASSET, archive) { p -> progress("Copying bundled Ubuntu package…", 5 + p * 45 / 100) }; progress("Checking Ubuntu package…", 52); verifySha256(archive, ROOTFS_SHA256)
            progress("Extracting Ubuntu filesystem…", 55); runCatching { rootfs.deleteRecursively() }; TarGzExtractor.extract(archive, rootfs) { p -> progress("Extracting Ubuntu filesystem…", 55 + p * 30 / 100) }; archive.delete(); configureRootfs()
        }
        if (!proot.exists()) {
            val archive = File(home, "proot.tar.gz"); progress("Installing PRoot runtime…", 87); download(PROOT_URL, archive) { p -> progress("Installing PRoot runtime…", 87 + p * 10 / 100) }; TarGzExtractor.extractFirstNamed(archive, proot); archive.delete(); proot.setExecutable(true, false)
        }
        progress("Ready", 100)
    }
    private fun rootfsReady() = File(rootfs, "bin/bash").exists() && File(rootfs, "etc/os-release").exists()
    private fun copyAsset(name: String, target: File, progress: (Int) -> Unit) {
        val total = runCatching { context.assets.openFd(name).length }.getOrDefault(-1L)
        context.assets.open(name, android.content.res.AssetManager.ACCESS_STREAMING).use { input -> FileOutputStream(target).use { output -> val buffer = ByteArray(128 * 1024); var done = 0L; while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100)) } } }
    }
    private fun configureRootfs() {
        val resolv = File(rootfs, "etc/resolv.conf")
        runCatching { if (resolv.exists() || java.nio.file.Files.isSymbolicLink(resolv.toPath())) resolv.delete(); resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(rootfs, "tmp").mkdirs(); File(rootfs, "root").mkdirs()
    }
    private fun download(url: String, target: File, progress: (Int) -> Unit) {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; requestMethod = "GET" }
        try { connection.connect(); if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}"); val total = connection.contentLengthLong; connection.inputStream.buffered(64 * 1024).use { input -> FileOutputStream(target).use { output -> val buffer = ByteArray(64 * 1024); var done = 0L; while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100)) } } } } finally { connection.disconnect() }
    }
    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }; val actual = digest.digest().joinToString("") { "%02x".format(it) }; check(actual.equals(expected, ignoreCase = true)) { "Ubuntu archive checksum mismatch" }
    }
}

private class UbuntuTerminal(private val context: Context) {
    private var pty: NativePty? = null
    fun start(onOutput: (String) -> Unit, onExit: () -> Unit) {
        val base = File(context.filesDir, "ubuntu"); val rootfs = File(base, "rootfs"); val proot = File(base, "proot")
        val args = listOf(proot.absolutePath, "-0", "-r", rootfs.absolutePath, "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys", "-b", "/sdcard:/mnt/shared", "/bin/bash", "--login")
        pty = NativePty().also { session -> session.start(args, rootfs.absolutePath, onOutput, onExit); session.resize(32, 120) }
    }
    fun write(text: String) { pty?.write(text) }; fun resize(rows: Int, columns: Int) { pty?.resize(rows, columns) }; fun close() { pty?.close(); pty = null }
}

private object TarGzExtractor {
    private const val BLOCK = 512
    fun extract(archive: File, destination: File, progress: (Int) -> Unit) { destination.mkdirs(); GZIPInputStream(archive.inputStream().buffered(), 64 * 1024).use { input -> extractTar(input, destination, progress) } }
    fun extractFirstNamed(archive: File, target: File) {
        GZIPInputStream(archive.inputStream().buffered(), 64 * 1024).use { input -> val buffer = ByteArray(BLOCK); while (true) { if (!readFully(input, buffer)) break; if (buffer.all { it.toInt() == 0 }) break; val name = tarString(buffer, 0, 100); val size = tarOctal(buffer, 124, 12); val type = buffer[156].toInt().toChar(); val skip = (size + BLOCK - 1) / BLOCK * BLOCK; if ((type == '0' || type == '\u0000') && (File(name).name == "proot" || name.endsWith("/proot"))) { target.parentFile?.mkdirs(); target.outputStream().use { output -> copyExactly(input, output, size) }; target.setExecutable(true, false); return }; skipFully(input, skip) } }
        error("PRoot binary not found in archive")
    }
    private fun extractTar(input: java.io.InputStream, destination: File, progress: (Int) -> Unit) {
        val header = ByteArray(BLOCK); var entries = 0L
        while (true) {
            if (!readFully(input, header)) break; if (header.all { it.toInt() == 0 }) break
            val name = tarString(header, 0, 100); val prefix = tarString(header, 345, 155); val fullName = if (prefix.isEmpty()) name else "$prefix/$name"; val safe = safePath(destination, fullName); val size = tarOctal(header, 124, 12); val mode = tarOctal(header, 100, 8).toInt(); val type = header[156].toInt().toChar()
            when (type) {
                '5' -> safe.mkdirs()
                '2' -> { val link = tarString(header, 157, 100); safe.parentFile?.mkdirs(); runCatching { java.nio.file.Files.deleteIfExists(safe.toPath()) }; require(!File(link).isAbsolute && !link.split('/').contains("..")) { "Unsafe symbolic link" }; java.nio.file.Files.createSymbolicLink(safe.toPath(), java.nio.file.Paths.get(link)) }
                '0', '\u0000' -> { safe.parentFile?.mkdirs(); FileOutputStream(safe).use { output -> copyExactly(input, output, size) }; if (mode and 0b001_001_001 != 0) safe.setExecutable(true, false) }
                else -> skipFully(input, size)
            }
            skipFully(input, (BLOCK - (size % BLOCK)) % BLOCK); entries++; if (entries % 1000L == 0L) progress((entries % 100).toInt())
        }
        progress(100)
    }
    private fun safePath(root: File, entry: String): File { val normalized = entry.replace('\\', '/').trimStart('/'); require(normalized.split('/').none { it == ".." }) { "Unsafe archive path" }; val target = File(root, normalized); val canonicalRoot = root.canonicalFile; val canonicalTarget = target.canonicalFile; require(canonicalTarget.path == canonicalRoot.path || canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) { "Unsafe archive path" }; return target }
    private fun tarString(buffer: ByteArray, offset: Int, length: Int): String { var end = offset; while (end < offset + length && buffer[end].toInt() != 0) end++; return buffer.copyOfRange(offset, end).toString(Charsets.UTF_8).trim() }
    private fun tarOctal(buffer: ByteArray, offset: Int, length: Int): Long { val text = tarString(buffer, offset, length).trim(); return if (text.isEmpty()) 0 else text.toLongOrNull(8) ?: 0 }
    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean { var offset = 0; while (offset < buffer.size) { val n = input.read(buffer, offset, buffer.size - offset); if (n < 0) return false; offset += n }; return true }
    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) { var remaining = size; val buffer = ByteArray(128 * 1024); while (remaining > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) error("Unexpected end of archive"); output.write(buffer, 0, n); remaining -= n } }
    private fun skipFully(input: java.io.InputStream, size: Long) { var remaining = size; while (remaining > 0) { val skipped = input.skip(remaining); if (skipped > 0) remaining -= skipped else { if (input.read() < 0) error("Unexpected end of archive"); remaining-- } } }
}
