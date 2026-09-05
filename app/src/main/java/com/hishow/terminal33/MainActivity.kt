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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private val Ink = Color(0xFFF4F6F8)
private val Muted = Color(0xFF89929D)
private val Panel = Color(0xFF11151A)
private val Panel2 = Color(0xFF171C22)
private val Line = Color(0xFF252C34)
private val Accent = Color(0xFF8B7CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalApp(applicationContext) }
    }
}

private data class BootstrapState(val ready: Boolean, val message: String, val progress: Int)

@Composable
private fun TerminalApp(context: Context) {
    var state by remember { mutableStateOf(BootstrapState(false, "Preparing terminal…", 0)) }
    var terminal by remember { mutableStateOf<ShellTerminal?>(null) }
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
                val bootstrap = UbuntuBootstrap(context)
                if (bootstrap.hasRootfsAsset()) {
                    bootstrap.prepare { message, progress -> main.post { state = BootstrapState(false, message, progress) } }
                }
                val session = ShellTerminal(context)
                session.start({ chunk -> screen.feed(chunk) }, { main.post { state = BootstrapState(true, "Shell exited · tap Restart", 100) } })
                withContext(Dispatchers.Main) {
                    terminal?.close()
                    terminal = session
                    screen.clear()
                    screen.resize(120, 32)
                    state = BootstrapState(true, if (session.isUbuntu) "Ubuntu 24.04" else "System shell", 100)
                }
            } catch (t: Throwable) {
                main.post { state = BootstrapState(false, "Setup failed: ${t.message ?: "unknown error"}", -1) }
            }
        }
    }

    LaunchedEffect(Unit) { startSession() }
    DisposableEffect(Unit) { onDispose { terminal?.close() } }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF090B0E)) {
        if (!state.ready) BootstrapScreen(state) else {
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 10.dp)) {
                ModernHeader(
                    isUbuntu = terminal?.isUbuntu == true,
                    onRestart = { terminal?.close(); startSession() },
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", screen.copyText()))
                    },
                    onClear = { screen.clear() }
                )

                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF050607)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        TerminalCanvas(
                            model = screen,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            onSizeChanged = { rows, columns -> screen.resize(columns, rows); terminal?.resize(rows, columns) },
                            onTap = ::focusTerminal
                        )
                        DirectTerminalInput(terminalFocus) { send(it) }
                    }
                }

                Spacer(Modifier.height(8.dp))
                CommandDock(
                    value = command,
                    onValueChange = { command = it },
                    onSend = { val text = command; command = ""; if (text.isNotBlank()) send(text + "\n", true); focusTerminal() },
                    onPaste = ::pasteClipboard,
                    onInterrupt = { send(TerminalInput.CTRL_C); focusTerminal() }
                )
                Spacer(Modifier.height(7.dp))
                KeyDock(
                    onKey = { send(it); focusTerminal() },
                    onHistory = { direction ->
                        if (history.isEmpty()) return@KeyDock
                        val next = (historyIndex + direction).coerceIn(-1, history.lastIndex)
                        historyIndex = next
                        command = if (next < 0) "" else history[history.lastIndex - next]
                    }
                )
                Spacer(Modifier.height(5.dp))
                QuickDock { send(it + "\n", true); focusTerminal() }
            }
        }
    }
}

@Composable
private fun ModernHeader(isUbuntu: Boolean, onRestart: () -> Unit, onCopy: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Luma", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("•", color = Accent, fontSize = 16.sp)
                Spacer(Modifier.width(7.dp))
                Text(if (isUbuntu) "Ubuntu 24.04" else "Shell", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(if (isUbuntu) "arm64  ·  ready" else "local session", color = Color(0xFF5F6974), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        HeaderAction("⌘", onCopy)
        HeaderAction("×", onClear)
        HeaderAction("↻", onRestart)
    }
}

@Composable
private fun HeaderAction(symbol: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)) {
        Text(symbol, color = Muted, fontSize = 19.sp)
    }
}

@Composable
private fun CommandDock(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, onPaste: () -> Unit, onInterrupt: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp, end = 7.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                textStyle = TextStyle(color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSend() }),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("Run a command…", color = Color(0xFF59636E), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    inner()
                }
            )
            DockButton("Paste", onPaste, false)
            DockButton("^C", onInterrupt, false)
            DockButton("Run", onSend, true)
        }
    }
}

@Composable
private fun DockButton(text: String, onClick: () -> Unit, primary: Boolean) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Accent else Panel2, contentColor = Ink),
        contentPadding = PaddingValues(horizontal = if (primary) 15.dp else 11.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 12.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal) }
}

@Composable
private fun KeyDock(onKey: (String) -> Unit, onHistory: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        KeyChip("↑") { onHistory(1) }; KeyChip("↓") { onHistory(-1) }
        KeyChip("Tab") { onKey(TerminalInput.TAB) }; KeyChip("Esc") { onKey(TerminalInput.ESC) }
        KeyChip("^C") { onKey(TerminalInput.CTRL_C) }; KeyChip("^D") { onKey(TerminalInput.CTRL_D) }
        KeyChip("←") { onKey(TerminalInput.arrowLeft()) }; KeyChip("→") { onKey(TerminalInput.arrowRight()) }
        KeyChip("Home") { onKey(TerminalInput.home()) }; KeyChip("End") { onKey(TerminalInput.end()) }
        KeyChip("PgUp") { onKey(TerminalInput.pageUp()) }; KeyChip("PgDn") { onKey(TerminalInput.pageDown()) }
    }
}

@Composable
private fun KeyChip(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = Color(0xFFB8C0C9)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
}

@Composable
private fun QuickDock(onCommand: (String) -> Unit) {
    val items = listOf("pwd", "ls", "ls -la", "whoami", "uname -a", "clear")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEach { item ->
            TextButton(onClick = { onCommand(item) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)) {
                Text(item, color = Color(0xFF68727D), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
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
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
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
        Text("Luma", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("A quiet Ubuntu workspace", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(30.dp))
        Text(state.message, color = Ink, fontSize = 15.sp)
        if (state.progress >= 0) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Panel2)
            Spacer(Modifier.height(7.dp))
            Text("${state.progress}%", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Ubuntu Base is bundled in the APK and unpacked into private storage on first launch.", color = Color(0xFF59636E), fontSize = 12.sp)
    }
}

private class UbuntuBootstrap(private val context: Context) {
    companion object {
        private const val ROOTFS_ASSET = "ubuntu-base-24.04.3-base-arm64.tar.gz"
        private const val ROOTFS_SHA256 = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
        private const val PROOT_URL = "https://github.com/proot-me/proot-rs/releases/download/v0.1.0/proot-rs-v0.1.0-aarch64-linux-android.tar.gz"
    }
    private val home = File(context.filesDir, "ubuntu")
    private val rootfs = File(home, "rootfs")
    private val proot = File(home, "proot")

    fun hasRootfsAsset() = runCatching { context.assets.list("")?.contains(ROOTFS_ASSET) == true }.getOrDefault(false)

    fun prepare(progress: (String, Int) -> Unit) {
        home.mkdirs()
        if (!rootfsReady()) {
            val archive = File(home, "rootfs.tar.gz"); archive.delete()
            copyAsset(ROOTFS_ASSET, archive) { p -> progress("Copying Ubuntu package…", 5 + p * 45 / 100) }
            progress("Checking Ubuntu package…", 52)
            verifySha256(archive, ROOTFS_SHA256)
            progress("Extracting Ubuntu filesystem…", 55)
            rootfs.deleteRecursively()
            TarGzExtractor.extract(archive, rootfs) { p -> progress("Extracting Ubuntu filesystem…", 55 + p * 30 / 100) }
            archive.delete(); configureRootfs()
        }
        if (!proot.exists() || !proot.canExecute()) {
            val archive = File(home, "proot.tar.gz"); archive.delete()
            progress("Installing PRoot runtime…", 87)
            download(PROOT_URL, archive) { p -> progress("Installing PRoot runtime…", 87 + p * 10 / 100) }
            TarGzExtractor.extractFirstNamed(archive, proot)
            archive.delete(); proot.setExecutable(true, false)
        }
        progress("Ready", 100)
    }

    private fun rootfsReady() = File(rootfs, "bin/bash").exists() && File(rootfs, "etc/os-release").exists()
    private fun copyAsset(name: String, target: File, progress: (Int) -> Unit) {
        val total = runCatching { context.assets.openFd(name).length }.getOrDefault(-1L)
        context.assets.open(name, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024); var done = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100)) }
            }
        }
    }
    private fun configureRootfs() {
        val resolv = File(rootfs, "etc/resolv.conf")
        runCatching { if (resolv.exists() || java.nio.file.Files.isSymbolicLink(resolv.toPath())) resolv.delete(); resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(rootfs, "tmp").mkdirs(); File(rootfs, "root").mkdirs()
    }
    private fun download(url: String, target: File, progress: (Int) -> Unit) {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; requestMethod = "GET" }
        try {
            connection.connect(); if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.buffered(64 * 1024).use { input -> FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100)) }
            } }
        } finally { connection.disconnect() }
    }
    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expected, ignoreCase = true)) { "Ubuntu archive checksum mismatch" }
    }
}

private class ShellTerminal(private val context: Context) {
    private var pty: NativePty? = null
    var isUbuntu: Boolean = false
        private set
    fun start(onOutput: (String) -> Unit, onExit: () -> Unit) {
        val base = File(context.filesDir, "ubuntu"); val rootfs = File(base, "rootfs"); val proot = File(base, "proot")
        val canRunUbuntu = File(rootfs, "bin/bash").exists() && proot.exists() && proot.canExecute()
        if (canRunUbuntu) {
            try {
                isUbuntu = true
                val args = listOf(proot.absolutePath, "-0", "-r", rootfs.absolutePath, "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys", "-b", "/sdcard:/mnt/shared", "/bin/bash", "--login")
                pty = NativePty().also { session -> session.start(args, rootfs.absolutePath, onOutput, onExit); session.resize(32, 120) }
                return
            } catch (_: Throwable) { isUbuntu = false }
        }
        isUbuntu = false
        val shPath = listOf("/system/bin/sh", "/bin/sh", "sh").firstOrNull { File(it).exists() } ?: "/system/bin/sh"
        pty = NativePty().also { session -> session.start(listOf(shPath), context.filesDir.absolutePath, onOutput, onExit); session.resize(32, 120) }
    }
    fun write(text: String) { pty?.write(text) }
    fun resize(rows: Int, columns: Int) { pty?.resize(rows, columns) }
    fun close() { pty?.close(); pty = null }
}

private object TarGzExtractor {
    private const val BLOCK = 512
    fun extract(archive: File, destination: File, progress: (Int) -> Unit) { destination.mkdirs(); GZIPInputStream(archive.inputStream().buffered(), 64 * 1024).use { input -> extractTar(input, destination, progress) } }
    fun extractFirstNamed(archive: File, target: File) {
        GZIPInputStream(archive.inputStream().buffered(), 64 * 1024).use { input ->
            val buffer = ByteArray(BLOCK)
            while (true) {
                if (!readFully(input, buffer)) break; if (buffer.all { it.toInt() == 0 }) break
                val name = tarString(buffer, 0, 100); val size = tarOctal(buffer, 124, 12); val type = buffer[156].toInt().toChar(); val skip = (size + BLOCK - 1) / BLOCK * BLOCK
                if ((type == '0' || type == '\u0000') && (File(name).name == "proot" || name.endsWith("/proot"))) { target.parentFile?.mkdirs(); target.outputStream().use { output -> copyExactly(input, output, size) }; target.setExecutable(true, false); return }
                skipFully(input, skip)
            }
        }
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
