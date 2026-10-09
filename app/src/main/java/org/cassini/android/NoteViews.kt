package org.cassini.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared native surfaces: the components every screen is built from, in code. Colours come from [Palette]. */
internal class NoteViews(val context: Context, private val dark: Boolean = false) {
    enum class Style { PRIMARY, RECORD, DARK, OUTLINE, TONAL, TEXT, DANGER }

    private val foregroundText get() = if (dark) Palette.nightText else Palette.text
    private val mutedText get() = if (dark) Palette.nightMuted else Palette.muted

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(size: Float, color: Int = foregroundText, mono: Boolean = false, medium: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(color)
        typeface = when {
            mono -> Typeface.MONOSPACE
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }
    fun title(size: Float, color: Int = foregroundText) = text(size, color, medium = true).apply { letterSpacing = -.01f }
    /** Small uppercase heading above a group. */
    fun sectionLabel(resource: Int) = text(12f, mutedText, medium = true).apply {
        setText(resource); isAllCaps = true; letterSpacing = .06f
    }
    fun shape(color: Int, radius: Int = 16, stroke: Int? = null, dashed: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) { if (dashed) setStroke(dp(1), stroke, dp(4).toFloat(), dp(3).toFloat()) else setStroke(dp(1), stroke) }
    }
    private fun ripple(content: Drawable?, mask: Drawable? = null) =
        RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x1F14171A), content, mask)
    fun icon(resource: Int, tint: Int = foregroundText, size: Int = 22): Drawable =
        context.getDrawable(resource)!!.mutate().apply { setTint(tint); setBounds(0, 0, dp(size), dp(size)) }

    fun button(label: Int, style: Style = Style.OUTLINE, icon: Int? = null, height: Int = 52) = Button(context).apply {
        setText(label); configure(this, style, icon, height)
    }
    /** A pill button. Disabled controls keep their shape and fade. */
    fun configure(button: Button, style: Style, icon: Int? = null, height: Int = 52) = button.apply {
        isAllCaps = false; textSize = 15f; stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val (fill, stroke, foreground) = when (style) {
            Style.PRIMARY -> Triple(Palette.accent, null, Palette.surface)
            Style.RECORD -> Triple(Palette.record, null, Palette.surface)
            Style.DARK -> if (dark) Triple(Palette.nightText, null, Palette.night) else Triple(Palette.text, null, Palette.surface)
            Style.OUTLINE -> if (dark) Triple(Palette.night, Palette.nightLine, Palette.nightText) else Triple(Palette.surface, Palette.lineStrong, Palette.text)
            Style.TONAL -> Triple(Palette.accentSoft, null, Palette.accentDeep)
            Style.TEXT -> Triple(0, null, if (dark) Palette.nightText else Palette.accentDeep)
            Style.DANGER -> Triple(0, null, Palette.error)
        }
        setTextColor(foreground)
        minHeight = dp(height); minimumHeight = dp(height); minWidth = 0; minimumWidth = 0
        setPadding(dp(20), 0, dp(20), 0)
        background = ripple(shape(fill, height / 2, stroke), shape(Palette.text, height / 2))
        if (icon != null) {
            setCompoundDrawablesRelative(icon(icon, foreground, 20), null, null, null)
            compoundDrawablePadding = dp(8)
            setPadding(dp(18), 0, dp(22), 0)
        }
    }
    /** An icon-only control, 48 dp, with a spoken label. It stays a Button so tests and accessibility treat it as one. */
    fun iconButton(icon: Int, description: Int, tint: Int = foregroundText) = Button(context).apply {
        contentDescription = context.getString(description); text = ""
        minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48); minimumWidth = dp(48)
        setPadding(dp(13), 0, 0, 0); stateListAnimator = null
        setCompoundDrawablesRelative(icon(icon, tint), null, null, null)
        background = ripple(null, shape(Palette.text, 24))
    }
    /** A rounded status label: state, trust or version. */
    fun chip(fill: Int, foreground: Int, stroke: Int? = null) = text(13f, foreground, medium = true).apply {
        background = shape(fill, 12, stroke); setPadding(dp(10), dp(3), dp(10), dp(3))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    fun input(hint: Int) = EditText(context).apply {
        setHint(hint); setSingleLine(); textSize = 16f; setTextColor(foregroundText); setHintTextColor(mutedText)
        setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(52)
        background = shape(if (dark) Palette.night else Palette.surface, 26, if (dark) Palette.nightLine else Palette.line)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }
    /** A single hairline under a field: the stroke's other three sides are pushed outside the bounds. */
    fun underline(color: Int): Drawable {
        val edge = dp(2)
        return android.graphics.drawable.InsetDrawable(GradientDrawable().apply { setColor(0); setStroke(dp(1), color) }, -edge, -edge, -edge, 0)
    }
    /** A white rounded card with a hairline. */
    fun card(radius: Int = 20) = column().apply { background = shape(Palette.surface, radius, Palette.line) }
    /** Makes a container a single tap target, with a ripple clipped to its shape. */
    fun clickable(view: View, radius: Int = 16) = view.apply {
        isClickable = true; isFocusable = true
        foreground = ripple(null, shape(Palette.text, radius))
    }
    fun add(parent: LinearLayout, view: View, width: Int = -1, height: Int = -2, weight: Float = 0f, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(top) })
    }
    fun space(parent: LinearLayout, width: Int = 1, height: Int = 1) =
        parent.addView(Space(context), LinearLayout.LayoutParams(dp(width), dp(height)))
}
