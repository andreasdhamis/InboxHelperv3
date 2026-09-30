package gr.ipexpert.inboxhelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Status
import gr.ipexpert.inboxhelper.engine.SlaState

val Critical = Color(0xFF9E2A10)
val Blue = Color(0xFF27518F)
val BlueSoft = Color(0xFFDDE7F5)

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
        modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun PriorityPill(level: String, source: String = "") {
    val (bg, fg) = when (level) {
        Level.CRITICAL -> Critical to Color.White
        Level.HIGH -> Urgent to Color.White
        Level.MEDIUM -> Amber to AmberText
        Level.LOW -> LowBg to Muted
        else -> Color(0xFFEDE8DE) to Muted
    }
    val suffix = when (source) { "manual" -> " · you"; "learned" -> " · learned"; "pending" -> " · pending"; else -> "" }
    Pill(Level.label(level).uppercase() + suffix, bg, fg)
}

@Composable
fun StatusPill(status: String) {
    val (bg, fg) = when (status) {
        Status.UNANSWERED -> Color(0xFFF7E1D3) to UrgentText
        Status.PARTIAL -> Amber to AmberText
        Status.WAITING_CUSTOMER, Status.WAITING_THIRD -> BlueSoft to Blue
        Status.ANSWERED, Status.COMPLETED -> TealSoft to Teal
        else -> LowBg to Muted
    }
    Pill(Status.label(status), bg, fg)
}

@Composable
fun SlaPill(state: SlaState) {
    if (state == SlaState.NONE) return
    val (bg, fg) = when (state) {
        SlaState.BREACHED -> Critical to Color.White
        SlaState.AT_RISK -> Urgent to Color.White
        SlaState.APPROACHING -> Amber to AmberText
        else -> TealSoft to Teal
    }
    Pill(state.label, bg, fg)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 0.8.sp, modifier = modifier)
}

@Composable
fun Panel(modifier: Modifier = Modifier, bg: Color = Paper, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(bg, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Horizontal 0–100 bar with its value, used for importance / urgency / confidence. */
@Composable
fun ScoreBar(label: String, value: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            Text("$value/100", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE6E0D4), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0, 100) / 100f).height(6.dp).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Ink) {
    Row {
        Text(key, fontSize = 13.sp, color = Muted, modifier = Modifier.width(118.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ChannelBadge(channel: String, appName: String) {
    val color = when (channel) { "sms" -> Blue; "mail" -> Color(0xFF8A3B12); else -> Color(0xFF1B5E3B) }
    Box(Modifier.size(40.dp).background(color, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(appName.take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

/** Simple horizontal bar chart row (label, bar, value). */
@Composable
fun BarRow(label: String, value: Int, max: Int, color: Color = Teal) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(110.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(14.dp)) {
            val f = if (max <= 0) 0f else value.toFloat() / max
            Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(14.dp).background(color, RoundedCornerShape(4.dp)))
        }
        Text(value.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
    }
}
