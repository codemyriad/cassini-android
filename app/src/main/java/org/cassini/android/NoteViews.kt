package org.cassini.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared native surfaces for the notes library and capture screen. */
internal class NoteViews(val context: Context) {
    val accent = Color.rgb(133, 213, 190)
    val record = Color.rgb(255, 103, 57)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(size: Float, color: Int = DeckViews.paper, mono: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", Typeface.NORMAL)
    }
    fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun button(label: Int, color: Int = DeckViews.surface, foreground: Int = DeckViews.paper) = Button(context).apply {
        setText(label); isAllCaps = false; textSize = 16f
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(DeckViews.muted, foreground)))
        minimumHeight = dp(52); minHeight = dp(52); minWidth = 0; minimumWidth = 0
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x25FFFFFF), shape(color, 28), null)
    }
    fun input(hint: Int) = EditText(context).apply {
        setHint(hint); setSingleLine(); textSize = 16f; setTextColor(DeckViews.paper); setHintTextColor(DeckViews.muted)
        setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(52)
        background = shape(DeckViews.surface, 14)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }
    fun add(parent: LinearLayout, view: View, width: Int = -1, height: Int = -2, weight: Float = 0f, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(top) })
    }
}
