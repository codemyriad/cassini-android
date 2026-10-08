package org.cassini.android

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The parts of the speaker naming dialog, built in code like the rest of the note screen. */
object SpeakerDialog {
    const val MAX_NAME = 80
    fun nameInput(context: Context, name: String) = EditText(context).apply {
        id = R.id.speaker_name_input; setHint(R.string.speaker_name_hint); setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        filters = arrayOf(InputFilter.LengthFilter(MAX_NAME))
        setText(name); setSelection(text.length)
    }
    /** Saved people, best match first; tapping one fills the name and picks that voice. */
    fun people(context: Context, names: List<Pair<String, () -> Unit>>) = LinearLayout(context).apply {
        id = R.id.speaker_people_list; orientation = LinearLayout.VERTICAL
        val pad = (10 * context.resources.displayMetrics.density).toInt()
        names.forEach { (name, pick) -> addView(TextView(context).apply { text = name; setPadding(0, pad, 0, pad); setOnClickListener { pick() } }) }
    }
    fun remember(context: Context, possible: Boolean) = CheckBox(context).apply {
        id = R.id.speaker_remember; setText(if (possible) R.string.speaker_remember else R.string.speaker_too_short)
        isChecked = possible; isEnabled = possible
    }
    fun column(context: Context, vararg views: View): View = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; views.forEach { addView(it) } }
    fun frame(context: Context, content: View): View = FrameLayout(context).apply {
        val side = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(side, side / 2, side, 0); addView(content)
    }
}
