package org.cassini.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*

/** Native tape-deck surfaces: readable paper text, amber controls, quiet mono labels. */
class DeckViews(private val context: Context) {
    companion object {
        val ink = Color.rgb(16, 18, 15)
        val surface = Color.rgb(27, 30, 24)
        val paper = Color.rgb(232, 229, 217)
        val muted = Color.rgb(169, 176, 159)
        val line = Color.rgb(64, 70, 57)
        val amber = Color.rgb(244, 197, 106)
        val warning = Color.rgb(255, 161, 142)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(size: Float, color: Int = paper, mono: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
    }
    private fun label(resource: Int) = text(11f, muted, true).apply { setText(resource); letterSpacing = .08f }
    private fun background(color: Int, border: Int = line, radius: Int = 8) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), border)
    }
    private fun button(resource: Int, primary: Boolean = false) = Button(context).apply {
        setText(resource); isAllCaps = false; textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) ink else paper)))
        minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),
            background(if (primary) amber else surface, if (primary) amber else line), null)
    }
    private fun LinearLayout.add(view: View, width: Int = LinearLayout.LayoutParams.MATCH_PARENT, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                                 weight: Float = 0f, marginTop: Int = 0) {
        addView(view, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(marginTop) })
    }

    val root = column().apply {
        setBackgroundColor(ink)
        // Opening a note should start with the page focused, rather than its search keyboard.
        isFocusableInTouchMode = true
    }
    val library = button(R.string.back_to_notes).apply { id = R.id.notes_button; textSize = 12f }
    val menu = button(R.string.settings).apply { id = R.id.settings_button; textSize = 12f }
    val model = text(12f, amber, true).apply { id = R.id.model_status }
    val filename = text(18f).apply { id = R.id.recording_name; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }
    val caption = text(12f, muted)
    val open = button(R.string.open_audio).apply { id = R.id.open_button }
    val transcribe = button(R.string.transcribe, true).apply { id = R.id.transcribe_button }
    val download = button(R.string.download_model).apply { id = R.id.download_button }
    val status = text(12f, muted).apply { id = R.id.operation_status; setPadding(0, dp(8), 0, dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        id = R.id.operation_progress; max = 100; visibility = View.GONE; contentDescription = context.getString(R.string.busy_description)
    }
    val export = button(R.string.export).apply { id = R.id.export_button; textSize = 12f; contentDescription = context.getString(R.string.export_description) }
    val documentInfo = button(R.string.document_info).apply { id = R.id.document_info_button; textSize = 12f }
    val variant = button(R.string.choose_transcript).apply { id = R.id.variant_button; textSize = 12f }
    val trust = text(12f, muted).apply { id = R.id.document_trust }
    val details = text(12f, muted, true).apply { id = R.id.transcript_details }
    val search = EditText(context).apply {
        id = R.id.search_input; setHint(R.string.search_hint); setSingleLine(true); textSize = 15f
        setTextColor(paper); setHintTextColor(muted); setPadding(dp(12), dp(6), dp(12), dp(6))
        minHeight = dp(48); background = background(surface)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    val clearSearch = button(R.string.clear_search).apply {
        id = R.id.clear_search_button; text = "×"; textSize = 22f; contentDescription = context.getString(R.string.clear_search)
    }
    val matches = text(12f, muted).apply { id = R.id.search_matches }
    val draft = text(12f, amber).apply { id = R.id.transcript_draft; setText(R.string.transcript_draft); visibility = View.GONE }
    val voice = label(R.string.unidentified_voice)
    val transcript = text(18f).apply {
        id = R.id.transcript_text; setLineSpacing(dp(7).toFloat(), 1.1f)
        setPadding(0, dp(12), 0, dp(24)); highlightColor = amber
    }
    val emptyTitle = text(25f).apply { gravity = Gravity.CENTER }
    val emptyHint = text(14f, muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f) }
    val empty = column().apply { setPadding(dp(12), dp(24), dp(12), dp(32)); gravity = Gravity.CENTER }
    val searchRow = row()
    val scroll = ScrollView(context).apply { id = R.id.content_scroll; isFillViewport = true; isVerticalScrollBarEnabled = false }
    val position = text(12f, paper, true).apply { id = R.id.playback_position }
    val seek = SeekBar(context).apply {
        id = R.id.playback_seek; contentDescription = context.getString(R.string.seek_description)
        minimumHeight = dp(48); setPadding(dp(8), 0, dp(8), 0)
    }
    val play = button(R.string.play, true).apply { id = R.id.play_button }
    val back = button(R.string.back_ten).apply {
        id = R.id.back_button; setText(R.string.back_ten_label); contentDescription = context.getString(R.string.back_ten)
    }
    val forward = button(R.string.forward_ten).apply {
        id = R.id.forward_button; setText(R.string.forward_ten_label); contentDescription = context.getString(R.string.forward_ten)
    }

    init {
        val heading = row().apply { setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val brand = column().apply {
            add(text(25f).apply { setText(R.string.brand); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            add(label(R.string.prototype_badge).apply { setTextColor(amber) }, marginTop = 2)
        }
        heading.add(library, LinearLayout.LayoutParams.WRAP_CONTENT)
        heading.add(Space(context), dp(12), dp(1))
        heading.add(brand, 0, weight = 1f)
        heading.add(menu, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.add(heading)

        val content = column().apply { setPadding(dp(20), dp(6), dp(20), dp(12)) }
        val modelRow = row().apply { setPadding(0, dp(6), 0, dp(12)) }
        modelRow.add(model, 0, weight = 1f)
        modelRow.add(label(R.string.on_device).apply { setTextColor(muted) }, LinearLayout.LayoutParams.WRAP_CONTENT)
        content.add(modelRow)
        val fileCard = column().apply { background = background(surface); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        fileCard.add(filename)
        fileCard.add(caption, marginTop = 5)
        fileCard.add(trust, marginTop = 5)
        content.add(fileCard)
        val actions = row()
        actions.add(open, 0, weight = 1f)
        actions.add(Space(context), dp(8), dp(1))
        actions.add(transcribe, 0, weight = 1f)
        content.add(actions, marginTop = 10)
        content.add(download, marginTop = 8)
        content.add(status)
        content.add(progress, height = dp(3))
        val transcriptHeading = row().apply { setPadding(0, dp(16), 0, dp(8)) }
        transcriptHeading.add(label(R.string.transcript), 0, weight = 1f)
        transcriptHeading.add(documentInfo, LinearLayout.LayoutParams.WRAP_CONTENT)
        transcriptHeading.add(Space(context), dp(6), dp(1))
        transcriptHeading.add(export, LinearLayout.LayoutParams.WRAP_CONTENT)
        content.add(transcriptHeading)
        content.add(draft)
        content.add(details)
        content.add(variant, marginTop = 8)
        searchRow.add(search, 0, weight = 1f)
        searchRow.add(Space(context), dp(6), dp(1))
        searchRow.add(clearSearch, dp(48))
        content.add(searchRow, marginTop = 12)
        content.add(matches, marginTop = 5)
        content.add(voice, marginTop = 18)
        content.add(transcript)
        empty.add(CassetteView(context), dp(220), dp(92))
        empty.add(emptyTitle, marginTop = 20)
        empty.add(emptyHint, marginTop = 10)
        content.add(empty)
        scroll.addView(content)
        root.add(scroll, height = 0, weight = 1f)

        val playback = column().apply {
            background = background(surface, line, 0); setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        val clockRow = row()
        clockRow.add(label(R.string.playback), 0, weight = 1f)
        clockRow.add(position, LinearLayout.LayoutParams.WRAP_CONTENT)
        playback.add(clockRow)
        playback.add(seek, height = dp(48))
        val controls = row()
        controls.add(back, dp(72))
        controls.add(Space(context), dp(8), dp(1))
        controls.add(play, 0, weight = 1f)
        controls.add(Space(context), dp(8), dp(1))
        controls.add(forward, dp(72))
        playback.add(controls)
        root.add(playback)
    }

    private class CassetteView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val scale = width / 220f
            canvas.save(); canvas.scale(scale, height / 92f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = line
            canvas.drawRoundRect(1f, 1f, 219f, 91f, 8f, 8f, paint)
            canvas.drawRoundRect(22f, 15f, 198f, 66f, 25f, 25f, paint)
            for (x in listOf(50f, 170f)) {
                paint.color = amber; canvas.drawCircle(x, 40f, 19f, paint)
                paint.color = line; canvas.drawCircle(x, 40f, 8f, paint)
                repeat(6) { i ->
                    canvas.save(); canvas.rotate(i * 60f, x, 40f)
                    canvas.drawLine(x, 25f, x, 29f, paint); canvas.restore()
                }
            }
            paint.color = line; canvas.drawLine(74f, 40f, 146f, 40f, paint)
            canvas.drawLine(62f, 78f, 158f, 78f, paint)
            canvas.restore()
        }
    }
}
