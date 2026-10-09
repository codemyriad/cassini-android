package org.cassini.spike

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

private val C = CassiniColors()

enum class Tone { Info, Ok, Warn, Outline }

@Composable
fun StatusChip(text: String, tone: Tone) {
    val (bg, fg) = when (tone) {
        Tone.Info -> C.blueTint to C.blueInk
        Tone.Ok -> C.tealTint to C.tealInk
        Tone.Warn -> C.amberTint to C.amberInk
        Tone.Outline -> Color.Transparent to C.ink
    }
    val m = Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
        .then(if (tone == Tone.Outline) Modifier.border(1.dp, C.line2, RoundedCornerShape(10.dp)) else Modifier)
        .padding(horizontal = 8.dp, vertical = 2.dp)
    Text(text, m.then(if (tone == Tone.Outline) Modifier.padding(0.dp) else Modifier), style = sans(13, 500), color = fg)
}

@Composable
fun NoteRow(title: String, dur: String, time: String, chip: Pair<String, Tone>, snippet: String? = null, extra: String? = null, active: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) C.surface else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = sans(16, 600), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dur, style = mono(13).copy(lineHeight = sans(16).lineHeight), color = C.text2)
        }
        if (snippet != null) Text(snippet, style = sans(14, lh = 1.4f), color = C.text3)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, style = sans(13), color = C.text2)
            StatusChip(chip.first, chip.second)
            if (extra != null) Text(extra, style = sans(13), color = C.text2)
        }
    }
}

@Composable
fun GroupHeader(text: String, top: Int) {
    Text(text.uppercase(), Modifier.padding(start = 20.dp, end = 20.dp, top = top.dp, bottom = 6.dp), style = sans(13, 600, ls = 0.06f), color = C.text2)
}

@Composable
fun LibrarySpike() {
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CassiniIcons.logo(28, 1.6f), null, tint = C.ink)
                Spacer(Modifier.width(10.dp))
                Text("Notes", Modifier.weight(1f), style = sans(24, 600, ls = -0.01f), color = C.ink)
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(CassiniIcons.Trash, "Trash", tint = C.ink) }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(CassiniIcons.Settings, "Settings", tint = C.ink) }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(C.surface)
                        .border(1.dp, C.line, RoundedCornerShape(26.dp)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(CassiniIcons.Search, null, tint = C.text2)
                    Text("Search titles and what was said", style = sans(16), color = C.text4)
                }
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(CassiniIcons.Shield, null, tint = C.teal)
                    Text("Transcribed on this phone. Audio never leaves it.", style = sans(13), color = C.text2)
                }
            }
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.blueTint)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Transcribing Weekly product sync", Modifier.weight(1f).alignByBaseline(), style = sans(14, 600), color = C.ink)
                    Text("62%", Modifier.alignByBaseline(), style = mono(13), color = C.ink)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.blueTrack)) {
                    Box(Modifier.fillMaxWidth(0.62f).height(4.dp).background(C.blue))
                }
                Text("About 6–9 min left · 1 more in queue", style = sans(13), color = C.text3)
            }
            Column(Modifier.padding(top = 6.dp)) {
                GroupHeader("Today", 14)
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NoteRow("Weekly product sync", "48:13", "10:02", "Transcribing" to Tone.Info, active = true)
                    NoteRow("Interview — Marta", "1:12:05", "08:40", "Queued" to Tone.Outline)
                }
                GroupHeader("Yesterday", 16)
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NoteRow("Call with supplier", "23:50", "15:10", "Transcribed" to Tone.Ok,
                        snippet = "…quindi la consegna slitta a giovedì, but the price stays as agreed in the March order…", extra = "· 3 speakers")
                    NoteRow("Board review prep", "34:12", "11:00", "Paused at 12:40" to Tone.Warn, extra = "· Resume")
                    NoteRow("Voice note 8 Oct, 08:14", "02:41", "08:14", "Audio only" to Tone.Outline)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to C.bg.copy(alpha = 0f), 0.4f to C.bg))
                .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(C.surface).border(BorderStroke(1.dp, C.line2), RoundedCornerShape(26.dp))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CassiniIcons.Import, null, tint = C.ink)
                Text("Import audio", style = sans(15, 600), color = C.ink)
            }
            Row(
                Modifier.height(64.dp)
                    .dropShadow(RoundedCornerShape(32.dp), Shadow(radius = 18.dp, color = C.red, alpha = 0.28f, offset = DpOffset(0.dp, 6.dp)))
                    .clip(RoundedCornerShape(32.dp)).background(C.red).padding(start = 22.dp, end = 26.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
                Text("Record", style = sans(17, 600), color = Color.White)
            }
        }
    }
}
