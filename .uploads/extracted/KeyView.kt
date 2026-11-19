/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.sp
import splitties.dimensions.dp

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val theme: Theme,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private var slideDeleteSession: SlideDeleteSession? = null
    private var selectDeleteSession: SelectDeleteSession? = null

    private class SlideDeleteSession(
        val composing: Boolean,
    ) {
        val deletedTextBuffer = ArrayDeque<String>()
    }

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    companion object {
        private val RIME_BACKSPACE = RimeKeyMapping.keyCodeToVal(KeyEvent.KEYCODE_DEL)
        private val RIME_DELETE = RimeKeyMapping.keyCodeToVal(KeyEvent.KEYCODE_FORWARD_DEL)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    private val labelFontMetrics = Paint.FontMetrics()
    private var labelMetricsSize = Float.NaN
    private val symbolFontMetrics = Paint.FontMetrics()
    private var symbolMetricsSize = Float.NaN
    private var cachedSymbolText: String? = null
    private var cachedSymbolLines: List<String> = emptyList()
    private var iconColorFilter: PorterDuffColorFilter? = null
    private var iconColorFilterColor = 0

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        isSelectDelete = key.click?.isSelectDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK)
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK)
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            val composing = RimeDaemon.getFirstSessionOrNull()?.run { statusCached.isComposing } == true
            slideStepScale = if (isSlideCursor && composing) 2f else 1f
            slideDeleteSession = if (isSlideDelete) SlideDeleteSession(composing) else null
            selectDeleteSession = if (isSelectDelete) {
                SelectDeleteSession(composing).also { initializeSelectDelete(it) }
            } else {
                null
            }
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(theme.resolveAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }

                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            when {
                isSlideCursor -> when {
                    delta > 0 -> keyboardActionListener.onAction(theme.resolveAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(theme.resolveAction("Left"))
                }

                isSelectDelete -> onSelectDeleteSlide(delta)

                isSlideDelete -> onSlideDelete(delta)
            }
        }

        onLongClick = {
            if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            slideDeleteSession = null
            selectDeleteSession?.let { cancelSelectDelete(it) }
            selectDeleteSession = null
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    private fun initializeSelectDelete(session: SelectDeleteSession) {
        if (session.composing) {
            service.postRimeJob {
                session.initialize(getContext().caretPos)
            }
        } else {
            val extracted = service.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
            if (extracted != null) {
                session.initialize(extracted.startOffset + extracted.selectionEnd)
            }
        }
    }

    private fun onSlideDelete(delta: Int) {
        val session = slideDeleteSession ?: return
        if (session.composing) {
            if (delta == 0) return
            service.postRimeJob {
                var remaining = if (delta < 0) -delta.toLong() else delta.toLong()
                while (remaining > 0) {
                    if (delta < 0) {
                        val context = getContext()
                        if (context.caretPos <= 0 || context.caretPos > context.input.length) break
                        val deleted = context.input[context.caretPos - 1].toString()
                        if (!processKey(RIME_BACKSPACE, 0u, propagateUnhandled = false)) break
                        session.deletedTextBuffer.addFirst(deleted)
                    } else {
                        val deleted = session.deletedTextBuffer.firstOrNull() ?: break
                        if (!processKey(deleted[0].code, 0u, propagateUnhandled = false)) break
                        session.deletedTextBuffer.removeFirst()
                    }
                    remaining--
                }
            }
            return
        }

        val ic = service.currentInputConnection ?: return
        when {
            delta < 0 -> {
                val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                if (beforeText.isNotEmpty()) {
                    session.deletedTextBuffer.addFirst(beforeText.toString())
                    ic.deleteSurroundingText(1, 0)
                }
            }

            delta > 0 -> {
                if (session.deletedTextBuffer.isNotEmpty()) {
                    ic.commitText(session.deletedTextBuffer.removeFirst(), 1)
                }
            }
        }
    }

    private fun onSelectDeleteSlide(delta: Int) {
        val session = selectDeleteSession ?: return
        if (delta == 0) {
            session.finished = true
            finishSelectDelete(session)
            return
        }
        if (session.cancelled || session.finished) return
        session.started = true

        if (session.composing) {
            service.postRimeJob {
                if (session.cancelled) return@postRimeJob
                val previous = session.current
                val target = session.move(delta) ?: return@postRimeJob
                if (target != previous) moveCursorPos(target)
            }
        } else {
            val target = session.move(delta) ?: return
            service.currentInputConnection?.setSelection(target, session.anchor)
        }
    }

    private fun finishSelectDelete(session: SelectDeleteSession) {
        if (session.composing) {
            service.postRimeJob {
                if (session.cancelled) return@postRimeJob
                var remaining = session.anchor - session.current
                while (remaining > 0) {
                    val context = getContext()
                    if (context.caretPos != session.current || session.current >= context.input.length) break
                    if (!processKey(RIME_DELETE, 0u, propagateUnhandled = false)) break
                    remaining--
                }
            }
        } else if (session.started) {
            val ic = service.currentInputConnection ?: return
            if (session.current < session.anchor) {
                ic.commitText("", 1)
            } else {
                ic.setSelection(session.anchor, session.anchor)
            }
        }
    }

    private fun cancelSelectDelete(session: SelectDeleteSession) {
        if (session.finished || !session.started) return
        session.cancelled = true
        if (session.composing) {
            service.postRimeJob {
                if (session.anchor >= 0) moveCursorPos(session.anchor)
            }
        } else if (session.started) {
            service.currentInputConnection?.setSelection(session.anchor, session.anchor)
        }
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        if (action.isModifierKey) {
            keyboard.clickModifierKey(
                action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK),
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        val label = key.getLabel().let {
            if (it == "enter_labels") keyboardView.labelEnter else it
        }

        if (label.isNotEmpty()) {
            drawLabel(canvas, label)
        }

        val symbol = key.symbolLabel
        if (symbol.isNotEmpty()) {
            drawSymbol(canvas, symbol)
        }

        val hint = key.hint
        if (hint.isNotEmpty()) {
            drawSymbol(canvas, hint, isTop = false)
        }
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        val bg = k.getBackgroundDrawable() ?: return

        if (bg is GradientDrawable) {
            k.roundCorner.takeIf { it > 0f }?.let { bg.cornerRadius = dp(it) }
            k.keyBorder.takeIf { it > 0 }?.let { bg.setStroke(dp(it), k.getBorderColor()) }
        }

        bg.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        bg.draw(canvas)
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1 && !label.isIconFont) keyboardView.keyLongTextSize else keyboardView.keyTextSize)

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.key
                clearShadowLayer()
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            if (labelMetricsSize != textSize) {
                textPaint.getFontMetrics(labelFontMetrics)
                labelMetricsSize = textSize
            }
            val adjustmentY = -(labelFontMetrics.ascent + labelFontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX), centerY + adjustmentY + sp(key.keyTextOffsetY), textPaint)
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val icon = if (cachedIconName == iconName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, iconName.toIconName()).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = iconName
            }
        }

        if (iconColorFilterColor != color || iconColorFilter == null) {
            iconColorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            iconColorFilterColor = color
        }
        icon.colorFilter = iconColorFilter

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY)
            false -> height - paddingBottom - size + sp(offsetY)
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY)
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val textColor = key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.symbol
            }

            if (cachedSymbolText != text) {
                cachedSymbolLines = text.split("\n")
                cachedSymbolText = text
            }
            val lines = cachedSymbolLines
            if (symbolMetricsSize != textSize) {
                symbolPaint.getFontMetrics(symbolFontMetrics)
                symbolMetricsSize = textSize
            }
            val fontMetrics = symbolFontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }
}
