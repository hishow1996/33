package com.hishow.terminal33

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class TerminalScreenModel {
    private val lock = Any()
    private val state = VtState()
    private var version = 0
    private var selectionStart: CellPosition? = null
    private var selectionEnd: CellPosition? = null

    fun feed(chunk: String) = synchronized(lock) { state.feed(chunk); clearSelectionLocked(); version++ }
    fun resize(columns: Int, rows: Int) = synchronized(lock) {
        val c = state.columns; val r = state.rows
        state.resize(columns, rows)
        if (c != state.columns || r != state.rows) { clearSelectionLocked(); version++ }
    }
    fun scrollBy(delta: Int) = synchronized(lock) {
        val before = state.scrollOffset
        state.scrollBy(delta)
        if (before != state.scrollOffset) version++
    }
    fun scrollToBottom() = synchronized(lock) {
        val before = state.scrollOffset
        state.scrollToBottom()
        if (before != state.scrollOffset) version++
    }
    fun clear() = synchronized(lock) { state.feed("\u001b[2J\u001b[H"); clearSelectionLocked(); version++ }

    fun beginSelection(position: CellPosition) = synchronized(lock) {
        selectionStart = position.clamp(state.columns, state.rows)
        selectionEnd = selectionStart
        version++
    }

    fun updateSelection(position: CellPosition) = synchronized(lock) {
        if (selectionStart != null) {
            selectionEnd = position.clamp(state.columns, state.rows)
            version++
        }
    }

    fun clearSelection() = synchronized(lock) { if (selectionStart != null || selectionEnd != null) { clearSelectionLocked(); version++ } }

    fun hasSelection(): Boolean = synchronized(lock) = selectionStart != null && selectionEnd != null && selectionStart != selectionEnd

    fun selectedText(): String = synchronized(lock) {
        val start = selectionStart ?: return@synchronized ""
        val end = selectionEnd ?: return@synchronized ""
        if (start == end) return@synchronized ""
        val a = start.clamp(state.columns, state.rows)
        val b = end.clamp(state.columns, state.rows)
        val first = minOf(a, b)
        val last = maxOf(a, b)
        val rows = state.snapshot()
        buildString {
            for (y in first.row..last.row) {
                val from = if (y == first.row) first.column else 0
                val to = if (y == last.row) last.column else state.columns - 1
                if (y in rows.indices) {
                    for (x in from..to.coerceAtLeast(from)) if (x in rows[y].indices) append(rows[y][x].ch)
                }
                if (y != last.row) append('\n')
            }
        }.trimEnd()
    }

    fun copyText(): String = synchronized(lock) {
        val selected = selectedTextLocked()
        if (selected.isNotEmpty()) selected else state.snapshot().joinToString("\n") { row -> row.joinToString("") { it.ch.toString() }.trimEnd() }.trimEnd()
    }

    fun snapshot() = synchronized(lock) {
        TerminalFrame(state.snapshot(), state.columns, state.rows, state.cursorX, state.cursorY, state.cursorVisible, state.scrollOffset, version, selectionStart, selectionEnd)
    }

    private fun selectedTextLocked(): String {
        val start = selectionStart ?: return ""
        val end = selectionEnd ?: return ""
        if (start == end) return ""
        val a = start.clamp(state.columns, state.rows); val b = end.clamp(state.columns, state.rows)
        val first = minOf(a, b); val last = maxOf(a, b); val rows = state.snapshot()
        return buildString {
            for (y in first.row..last.row) {
                val from = if (y == first.row) first.column else 0
                val to = if (y == last.row) last.column else state.columns - 1
                if (y in rows.indices) for (x in from..to.coerceAtLeast(from)) if (x in rows[y].indices) append(rows[y][x].ch)
                if (y != last.row) append('\n')
            }
        }.trimEnd()
    }

    private fun clearSelectionLocked() { selectionStart = null; selectionEnd = null }
}

data class CellPosition(val row: Int, val column: Int) : Comparable<CellPosition> {
    override fun compareTo(other: CellPosition): Int = compareValuesBy(this, other, { it.row }, { it.column })
    fun clamp(columns: Int, rows: Int) = CellPosition(row.coerceIn(0, (rows - 1).coerceAtLeast(0)), column.coerceIn(0, (columns - 1).coerceAtLeast(0)))
}

data class TerminalFrame(
    val cells: Array<Array<VtState.Cell>>,
    val columns: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val scrollOffset: Int,
    val version: Int,
    val selectionStart: CellPosition? = null,
    val selectionEnd: CellPosition? = null
)

@Composable
fun TerminalCanvas(model: TerminalScreenModel, modifier: Modifier = Modifier, fontSizeSp: Int = 13, onSizeChanged: (rows: Int, columns: Int) -> Unit = { _, _ -> }) {
    val density = LocalDensity.current
    val textSize = with(density) { fontSizeSp.sp.toPx() }
    var version by remember(model) { mutableIntStateOf(-1) }
    var selecting by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        while (true) { version = model.snapshot().version; delay(50) }
    }
    val current = model.snapshot()
    val sizingModifier = modifier.fillMaxSize()
        .pointerInput(model, textSize) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; this.textSize = textSize }
                    val metrics = paint.fontMetrics
                    val cellWidth = paint.measureText("M").coerceAtLeast(1f)
                    val cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(textSize * 1.2f)
                    model.beginSelection(CellPosition((offset.y / cellHeight).toInt(), (offset.x / cellWidth).toInt()))
                    selecting = true
                },
                onDrag = { change, _ ->
                    if (selecting) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; this.textSize = textSize }
                        val metrics = paint.fontMetrics
                        val cellWidth = paint.measureText("M").coerceAtLeast(1f)
                        val cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(textSize * 1.2f)
                        model.updateSelection(CellPosition((change.position.y / cellHeight).toInt(), (change.position.x / cellWidth).toInt()))
                        change.consume()
                    }
                },
                onDragEnd = { selecting = false },
                onDragCancel = { selecting = false }
            )
        }
        .pointerInput(model, selecting) {
            detectVerticalDragGestures { _, dragAmount ->
                if (!selecting) {
                    val lines = (dragAmount / 22f).toInt().let { if (it == 0) if (dragAmount > 0) 1 else -1 else it }
                    model.scrollBy(lines)
                }
            }
        }
        .onSizeChanged { size ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; this.textSize = textSize }
            val metrics = paint.fontMetrics
            val cellWidth = paint.measureText("M").coerceAtLeast(1f)
            val cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(textSize * 1.2f)
            onSizeChanged((size.height / cellHeight).toInt().coerceAtLeast(1), (size.width / cellWidth).toInt().coerceAtLeast(1))
        }
    @Suppress("UNUSED_VARIABLE") val observedVersion = version
    Canvas(sizingModifier.background(Color(0xFF050608))) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; this.textSize = textSize }
        val metrics = paint.fontMetrics
        val cellWidth = paint.measureText("M").coerceAtLeast(1f)
        val cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(textSize * 1.2f)
        val baseline = -metrics.ascent
        val visibleColumns = (size.width / cellWidth).toInt().coerceAtLeast(1)
        val visibleRows = (size.height / cellHeight).toInt().coerceAtLeast(1)
        val rows = minOf(current.rows, visibleRows); val columns = minOf(current.columns, visibleColumns)
        val selectionA = current.selectionStart?.let { current.selectionEnd?.let { end -> minOf(it, end) } }
        val selectionB = current.selectionStart?.let { current.selectionEnd?.let { end -> maxOf(it, end) } }
        for (y in 0 until rows) for (x in 0 until columns) {
            val cell = current.cells[y][x]
            val baseFg = ansiColor(cell.fg, false); val baseBg = ansiColor(cell.bg, true)
            val fg = if (cell.reverse) baseBg else baseFg; val bg = if (cell.reverse) baseFg else baseBg
            val left = x * cellWidth; val top = y * cellHeight
            if (bg != null) drawRect(bg, Offset(left, top), Size(cellWidth, cellHeight))
            val selected = selectionA != null && selectionB != null && CellPosition(y, x) >= selectionA && CellPosition(y, x) <= selectionB
            if (selected) drawRect(Color(0xFF365B78), Offset(left, top), Size(cellWidth, cellHeight))
            if (cell.ch != ' ') {
                paint.color = (if (selected) Color.White else fg ?: Color(0xFFE4E7EB)).toArgb(); paint.isFakeBoldText = cell.bold
                drawContext.canvas.nativeCanvas.drawText(cell.ch.toString(), left, top + baseline, paint)
                if (cell.underline) {
                    val lineY = top + baseline + 1.5f
                    drawContext.canvas.nativeCanvas.drawRect(left, lineY, left + cellWidth, lineY + 1f, paint)
                }
            }
        }
        if (current.scrollOffset == 0 && current.cursorVisible && current.cursorY in 0 until rows && current.cursorX in 0 until columns) {
            val left = current.cursorX * cellWidth; val top = current.cursorY * cellHeight
            drawRect(Color(0x99FFFFFF), Offset(left, top), Size(cellWidth, cellHeight))
        }
    }
}

private fun ansiColor(value: Int, background: Boolean): Color? {
    if (value == -1) return if (background) null else Color(0xFFE4E7EB)
    if (value < 0 && (value and 0x80000000.toInt()) != 0) return Color(value and 0x00FFFFFF)
    val palette = arrayOf(0xFF1B1D21, 0xFFE05252, 0xFF65C466, 0xFFE6C15A, 0xFF5B9CF6, 0xFFCF75D6, 0xFF4FC1C9, 0xFFD6D9DE, 0xFF5D626B, 0xFFFF6B6B, 0xFF7BE07B, 0xFFFFD66B, 0xFF73B7FF, 0xFFEA8FF0, 0xFF63E1E5, 0xFFFFFFFF)
    return if (value < 16) Color(palette[value]) else Color(xterm256(value - 16))
}

private fun xterm256(index: Int): Int {
    if (index < 216) {
        val r = index / 36; val g = (index / 6) % 6; val b = index % 6
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        return android.graphics.Color.rgb(level(r), level(g), level(b))
    }
    val gray = 8 + (index - 216) * 10
    return android.graphics.Color.rgb(gray, gray, gray)
}
