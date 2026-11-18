package io.translatetube.local.ui.screens

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.util.TypedValue

internal data class NativeAddressBarAppearance(
    val textColor: Int,
    val hintColor: Int,
    val backgroundColor: Int,
    val outlineColor: Int,
    val cornerRadiusPx: Float,
    val strokeWidthPx: Int,
)

private data class NativeAddressBarTextMetrics(
    val textSizeSp: Float,
    val paddingLeftPx: Int,
    val paddingTopPx: Int,
    val paddingRightPx: Int,
    val paddingBottomPx: Int,
)

/**
 * Native single-line URL editor shared by the home screen and web video screen.
 *
 * Android owns text selection handles and touch hit-testing. Compose only synchronizes
 * explicit external URL revisions; it never writes back on ordinary recompositions, which
 * keeps native cursor placement and horizontal scrolling stable while editing long URLs.
 */
internal class NativeAddressBarEditText(context: Context) : android.widget.EditText(context) {
    var onAddressTextEdited: (() -> Unit)? = null
    var onAddressFocusChanged: ((Boolean) -> Unit)? = null
    var onAddressAction: (() -> Unit)? = null

    private var applyingExternalText = false
    private var appliedExternalRevision = Long.MIN_VALUE
    private var appliedAppearance: NativeAddressBarAppearance? = null
    private var appliedTextMetrics: NativeAddressBarTextMetrics? = null
    private var selectionViewportUpdatePending = false
    private var lastSelectionStart = -1
    private var lastSelectionEnd = -1
    private var pendingSelectionEndpoint = 0

    init {
        setSingleLine(true)
        setHorizontallyScrolling(true)
        maxLines = 1
        isFocusable = true
        isFocusableInTouchMode = true
        setSelectAllOnFocus(false)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        gravity = Gravity.CENTER_VERTICAL or Gravity.START

        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingExternalText) onAddressTextEdited?.invoke()
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (isGo) {
                onAddressAction?.invoke()
                true
            } else {
                false
            }
        }
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        onAddressFocusChanged?.invoke(focused)
        if (focused) {
            // Match both address bars: select the existing URL once when focus enters.
            // Subsequent taps are handled by the native editor and place the caret normally.
            post {
                if (!hasFocus()) return@post
                val length = text?.length ?: 0
                setSelection(0, length)
                bringPointIntoView(length)
            }
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Determine which end moved. This keeps that end visible while either selection
        // handle is dragged toward an edge, without implementing a competing touch gesture.
        pendingSelectionEndpoint = when {
            selStart != lastSelectionStart && selEnd == lastSelectionEnd -> selStart
            selEnd != lastSelectionEnd -> selEnd
            else -> selEnd
        }.coerceAtLeast(0)
        lastSelectionStart = selStart
        lastSelectionEnd = selEnd
        scheduleSelectionEndpointIntoView()
    }

    fun syncExternalText(value: String, revision: Long) {
        if (appliedExternalRevision == revision) return
        applyingExternalText = true
        try {
            if (text.toString() != value) setText(value)
            // Explicit navigation/reset updates place the caret at the URL end.
            setSelection(value.length)
            appliedExternalRevision = revision
        } finally {
            applyingExternalText = false
        }
        if (hasFocus()) {
            post {
                if (hasFocus()) bringPointIntoView(text?.length ?: 0)
            }
        }
    }

    fun applyAppearance(appearance: NativeAddressBarAppearance) {
        if (appliedAppearance == appearance) return
        setTextColor(appearance.textColor)
        setHintTextColor(appearance.hintColor)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = appearance.cornerRadiusPx
            setColor(appearance.backgroundColor)
            setStroke(appearance.strokeWidthPx, appearance.outlineColor)
        }
        appliedAppearance = appearance
    }

    fun applyTextMetrics(
        textSizeSp: Float,
        paddingLeftPx: Int,
        paddingTopPx: Int = 0,
        paddingRightPx: Int = 0,
        paddingBottomPx: Int = 0,
    ) {
        val metrics = NativeAddressBarTextMetrics(
            textSizeSp = textSizeSp,
            paddingLeftPx = paddingLeftPx,
            paddingTopPx = paddingTopPx,
            paddingRightPx = paddingRightPx,
            paddingBottomPx = paddingBottomPx,
        )
        if (appliedTextMetrics == metrics) return
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setPadding(paddingLeftPx, paddingTopPx, paddingRightPx, paddingBottomPx)
        appliedTextMetrics = metrics
    }

    private fun scheduleSelectionEndpointIntoView() {
        if (selectionViewportUpdatePending) return
        selectionViewportUpdatePending = true
        post {
            selectionViewportUpdatePending = false
            if (!hasFocus()) return@post
            val length = text?.length ?: 0
            bringPointIntoView(pendingSelectionEndpoint.coerceIn(0, length))
        }
    }
}
