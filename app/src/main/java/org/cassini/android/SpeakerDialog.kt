package org.cassini.android

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout

/** The parts of the speaker naming dialog, built in code like the rest of the note screen. */
object SpeakerDialog {
    const val MAX_NAME = 80
    fun nameInput(context: Context, name: String) = EditText(context).apply {
        id = R.id.speaker_name_input; setHint(R.string.speaker_name_hint); setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        filters = arrayOf(InputFilter.LengthFilter(MAX_NAME))
        setText(name); setSelection(text.length)
    }
    fun frame(context: Context, content: View): View = FrameLayout(context).apply {
        val side = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(side, side / 2, side, 0); addView(content)
    }
}
