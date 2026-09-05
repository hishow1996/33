package com.hishow.terminal33

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Thread-safe bridge between the PTY reader and the Compose screen renderer. */
class TerminalScreenModel {
    private val lock = Any()
    private val state = VtState()
    private var version = 0

    fun feed(chunk: String) = synchronized(lock) {
        state.feed(chunk)
        version++
    }

    fun resize(columns: Int, rows: Int) = synchronized(lock) {
        val beforeC = state.columns
        val beforeR = state.rows
        state.resize(columns, rows)
        if (beforeC != state.columns || beforeR != state.rows) version++
    }

    fun clear() = synchronized(lock) {
        state.feed("\u001b[2J\u001b[H")
        version++
    }

    fun copyText(): String = synchronized(lock) {
        state.snapshot().joinToString("\n") { row ->
            row.concatToString().trimEnd()
        }.trimEnd()
    }

    fun snapshot(): TerminalFrame = synchronized(lock) {
        TerminalFrame(
            state.snapshot(), state.columns, state.rows,
            state.cursorX, state.cursorY, state.cursorVisible, version
        )
    }
}

data class TerminalFrame(
    val cells: Array<Array<VtState.Cell>>,
    val columns: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val version: Int
)

@Composable
fun TerminalCanvas(
    model: TerminalScreenModel,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 13,
    onSizeChanged: (rows: Int, columns: Int) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current
    val textSize = with(density) { fontSizeSp.sp.toPx() }
    var version by remember(model) { mutableIntStateOf(-1) }

    LaunchedEffect(model) {
        while (true) {
            version = model.snapshot().version
            delay(50)
        }
    }

    val current = model.snapshot()
    // Keep the state read observable so Compose redraws on PTY updates.
    if (version < -1) Unit

    Canvas(modifier.fillMaxSize().background(Color(0xFF050608))) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = textSize
        }
        val metrics = paint.fontMetrics
        val cellWidth = paint.measureText("M").coerceAtLeast(1f)
        val cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(textSize * 1.2f)
        val baseline = -metrics.ascent
        val visibleColumns = (size.width / cellWidth).toInt().coerceAtLeast(1)
        val visibleRows = (size.height / cellHeight).toInt().coerceAtLeast(1)
        onSizeChanged(visibleRows, visibleColumns)

        val rows = minOf(current.rows, visibleRows)
        val columns = minOf(current.columns, visibleColumns)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val cell = current.cells[y][x]
                val baseFg = ansiColor(cell.fg, false)
                val baseBg = ansiColor(cell.bg, true)
                val fg = if (cell.reverse) baseBg else baseFg
                val bg = if (cell.reverse) baseFg else baseBg
                val left = x * cellWidth
                val top = y * cellHeight
                if (bg != null) drawRect(bg, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellWidth, cellHeight))
                if (cell.ch != ' ') {
                    paint.color = (fg ?: Color(0xFFE4E7EB)).toArgb()
                    paint.isFakeBoldText = cell.bold
                    drawContext.canvas.nativeCanvas.drawText(cell.ch.toString(), left, top + baseline, paint)
                    if (cell.underline) {
                        val lineY = top + baseline + 1.5f
                        drawContext.canvas.nativeCanvas.drawRect(left, lineY, left + cellWidth, lineY + 1f, paint)
                    }
                }
            }
        }

        if (current.cursorVisible && current.cursorY in 0 until rows && current.cursorX in 0 until columns) {
            val left = current.cursorX * cellWidth
            val top = current.cursorY * cellHeight
            drawRect(Color(0x99FFFFFF), androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellWidth, cellHeight))
        }
    }
}

private fun ansiColor(value: Int, background: Boolean): Color? {
    if (value < 0) return if (background) null else Color(0xFFE4E7EB)
    val palette = arrayOf(
        0xFF1B1D21, 0xFFE05252, 0xFF65C466, 0xFFE6C15A,
        0xFF5B9CF6, 0xFFCF75D6, 0xFF4FC1C9, 0xFFD6D9DE,
        0xFF5D626B, 0xFFFF6B6B, 0xFF7BE07B, 0xFFFFD66B,
        0xFF73B7FF, 0xFFEA8FF0, 0xFF63E1E5, 0xFFFFFFFF
    )
    return if (value < 16) Color(palette[value]) else Color(xterm256(value - 16))
}

private fun xterm256(index: Int): Int {
    if (index < 216) {
        val r = index / 36
        val g = (index / 6) % 6
        val b = index % 6
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        return android.graphics.Color.rgb(level(r), level(g), level(b))
    }
    val gray = 8 + (index - 216) * 10
    return android.graphics.Color.rgb(gray, gray, gray)
}
