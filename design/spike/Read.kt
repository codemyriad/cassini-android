package org.cassini.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val C = CassiniColors()

data class Turn(val name: String, val ink: Color, val dot: Color, val time: String, val words: List<String>, val named: Boolean, val auto: Boolean = false, val active: Int = -1)

@Composable
fun TurnHeader(t: Turn) {
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(t.dot))
        Text(t.name, style = sans(13, 600), color = t.ink)
        Text(t.time, style = mono(13).copy(lineHeight = sans(13).lineHeight), color = C.text2)
        if (t.auto) Text("auto", Modifier.border(1.dp, C.line2, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp), style = sans(11, 500), color = C.text2)
    }
}

@Composable
fun TurnText(t: Turn) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = buildAnnotatedString {
        var start = -1; var end = -1
        t.words.forEachIndexed { i, w ->
            if (i > 0) append(' ')
            if (i == t.active) { start = length; withStyle(SpanStyle(color = Color.White)) { append(w) }; end = length } else append(w)
        }
        if (start >= 0) addStringAnnotation("active", "", start, end)
    }
    val range = text.getStringAnnotations("active", 0, text.length).firstOrNull()
    Text(
        text,
        Modifier.padding(top = 2.dp).drawBehind {
            val l = layout ?: return@drawBehind
            if (range != null) {
                val a = l.getBoundingBox(range.start); val b = l.getBoundingBox(range.end - 1)
                val pad = 3.dp.toPx()
                drawRoundRect(C.blue, Offset(a.left - pad, a.top), Size(b.right - a.left + 2 * pad, a.height), CornerRadius(4.dp.toPx()))
            }
        },
        style = sans(17, lh = 1.6f), color = if (t.named) C.ink else C.text3, onTextLayout = { layout = it },
    )
}

@Composable
fun ReadSpike() {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(MoreIcons.Back, "Back", tint = C.ink) }
            Text("Call with supplier", Modifier.weight(1f), style = sans(18, 600), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(MoreIcons.Search21, "Search", tint = C.ink) }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(MoreIcons.More, "More", tint = C.ink) }
        }
        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(C.tealTint).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(MoreIcons.Check, null, tint = C.tealInk); Text("Verified", style = sans(13, 600), color = C.tealInk)
            }
            Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Version 2 of 2 · speakers merged", style = sans(13), color = C.ink); Icon(MoreIcons.ChevronDown14, null, tint = C.ink)
            }
            Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) { Text("3 speakers", style = sans(13), color = C.text2) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().background(C.surface).drawBehind { drawLine(C.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(start = 20.dp, end = 20.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val turns = listOf(
                Turn("Giulia", C.blueInk, C.blue, "11:32", "Ok, then let's confirm the quantities first. We said four hundred units for the first batch, right?".split(' '), true),
                Turn("Paolo Ferri", C.tealInk, C.teal, "11:41", "Sì, quattrocento. Il problema è la seconda consegna: quindi la consegna slitta a giovedì, but the price stays as agreed in the March order.".split(' '), true, auto = true, active = 8),
                Turn("Speaker 3", C.amberInk, C.amber, "12:20", "Can you send that in writing? I need it for the purchase order before Friday.".split(' '), false),
            )
            turns.forEachIndexed { i, t -> Column(if (i == 0) Modifier.padding(top = 10.dp) else Modifier) { TurnHeader(t); TurnText(t) } }
        }
        Column(
            Modifier.fillMaxWidth().background(C.surface).drawBehind { drawLine(C.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line).border(1.dp, C.line2, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth(704f / 1430f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.blue))
                Box(Modifier.fillMaxWidth(704f / 1430f), contentAlignment = Alignment.CenterEnd) { Box(Modifier.offset(x = 8.dp).size(16.dp).clip(CircleShape).background(C.blue)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("11:44", style = mono(12), color = C.text2); Text("23:50", style = mono(12), color = C.text2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(MoreIcons.Back10, "Back 10 seconds", tint = C.ink); Text("10", Modifier.offset(y = 2.dp), style = sans(10, 700).copy(lineHeight = 12.sp), color = C.ink) }
                Box(Modifier.size(64.dp).clip(CircleShape).background(C.ink), contentAlignment = Alignment.Center) { Icon(MoreIcons.Pause, "Pause", tint = Color.White) }
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(MoreIcons.Fwd10, "Forward 10 seconds", tint = C.ink); Text("10", Modifier.offset(y = 2.dp), style = sans(10, 700).copy(lineHeight = 12.sp), color = C.ink) }
            }
        }
    }
}
