package io.translatetube.local.ui.screens

import android.content.Context

internal data class NativeAddressBarAppearance(
    val textColor: Int,
    val hintColor: Int,
    val backgroundColor: Int,
    val outlineColor: Int,
    val cornerRadiusPx: Float,
    val strokeWidthPx: Int,
)

/**
 * Native single-line URL editor shared by the home screen and web video screen.
 *
 * Android owns cursor placement, selection handles and horizontal auto-scrolling. Compose
 * only synchronizes explicit external URL revisions; it never writes text back for ordinary
 * recompositions, so dragging a selection handle cannot reset the cursor or scroll position.
 */
internal class NativeAddressBarEditText(context: Context) : android.widget.EditText(context) {
    var onAddressTextEdited: (() -> Unit)? = null
    private var applyingExternalText = false
    private var appliedExternalRevision = Long.MIN_VALUE
    private var appliedAppearance: NativeAddressBarAppearance? = null

    init {
        setSingleLine(true)
        setHorizontallyScrolling(true)
        maxLines = 1
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO

        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingExternalText) onAddressTextEdited?.invoke()
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
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
}
