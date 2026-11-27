package com.amcilraith.inboxhub.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
internal val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
internal val shortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
internal val longFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")

/** "5m", "3h", "Tue", "Nov 2": short enough for a list row. */
fun ago(t: Instant, zone: ZoneId): String {
    val d = Duration.between(t, Instant.now())
    val day = t.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        day == today -> timeFmt.format(t.atZone(zone))
        day == today.minusDays(1) -> "Yesterday"
        d.toDays() < 7 -> t.atZone(zone).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        else -> shortFmt.format(t.atZone(zone))
    }
}

/** Section a list row belongs to: Today, Yesterday, This week, Earlier. */
fun dayBucket(t: Instant, zone: ZoneId): String {
    val day = t.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        !day.isBefore(today) -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "This week"
        else -> "Earlier"
    }
}

@Composable
fun Dot(color: Long, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)))
}

private val AVATAR_TONES = listOf(0xFF5B5BD6, 0xFF0F8FB3, 0xFFC27C0E, 0xFF2F855A, 0xFFB83280, 0xFF8E4EC6, 0xFFD6409F, 0xFF12A594, 0xFFE5484D, 0xFF3E63DD)

fun initials(name: String): String {
    val words = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** A round initials badge, tinted per sender so the same person always gets the same colour. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val tone = Color(AVATAR_TONES[abs(name.lowercase().hashCode()) % AVATAR_TONES.size])
    val dark = MaterialTheme.colorScheme.background.luminanceApprox() < 0.5f
    val bg = if (dark) lerp(tone, Color.Black, 0.45f) else lerp(tone, Color.White, 0.82f)
    val fg = if (dark) lerp(tone, Color.White, 0.55f) else lerp(tone, Color.Black, 0.25f)
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initials(name), color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp, maxLines = 1)
    }
}

internal fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ------------------------------------------------------------------ text

private val URL = Regex("""(?i)\b(https?://|www\.)[^\s<>"'()\[\]]+[^\s<>"'()\[\].,;:!?]""")
private val EMAIL = Regex("""(?i)\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b""")

/** "https://shop.example/bf?utm_…" shown as "shop.example/bf…": tracking links stay tappable but short. */
fun shortUrl(url: String): String {
    val noScheme = url.replaceFirst(Regex("(?i)^https?://"), "").removePrefix("www.")
    val host = noScheme.substringBefore('/')
    val path = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val firstSeg = path.split('/').firstOrNull { it.isNotEmpty() }
    val shown = if (firstSeg == null) host else "$host/$firstSeg"
    return if (shown.length + 1 < noScheme.length) "$shown…" else shown
}

/** Email body text with links and addresses tappable, and long URLs shortened. */
fun linkified(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.None, fontWeight = FontWeight.Medium))
    val matches = (URL.findAll(text).map { it to true } + EMAIL.findAll(text).map { it to false })
        .sortedBy { it.first.range.first }
    var i = 0
    for ((m, isUrl) in matches) {
        if (m.range.first < i) continue
        append(text.substring(i, m.range.first))
        val target = if (isUrl) (if (m.value.startsWith("www.", true)) "https://" + m.value else m.value) else "mailto:" + m.value
        withLink(LinkAnnotation.Url(target, style)) { append(if (isUrl && m.value.length > 40) shortUrl(m.value) else m.value) }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

private val BOLD = Regex("""\*\*(.+?)\*\*|__(.+?)__""")

/** Superhuman AI answers in light Markdown: headings, bullets, bold. Rendered, not stripped. */
fun markdown(md: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val lines = md.replace("\r\n", "\n").trim().lines()
    lines.forEachIndexed { n, raw ->
        val t = raw.trimStart()
        val indent = raw.length - t.length
        var line = t
        var heading = false
        when {
            t.startsWith("#") -> { line = t.trimStart('#').trim(); heading = true }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> line = (if (indent >= 2) "    ◦ " else "•  ") + t.substring(2)
        }
        val start = length
        var i = 0
        for (m in BOLD.findAll(line)) {
            append(linkified(line.substring(i, m.range.first), linkColor))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            i = m.range.last + 1
        }
        append(linkified(line.substring(i).replace("`", ""), linkColor))
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp), start, length)
        if (n < lines.lastIndex) append("\n")
    }
}

// ------------------------------------------------------------- loading

/** Placeholder rows while the very first inbox loads, instead of a blank screen. */
@Composable
fun SkeletonRows(count: Int = 7) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val c = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.alpha(a)) {
        repeat(count) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth(0.42f + (i % 3) * 0.1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c))
                    Box(Modifier.fillMaxWidth(0.8f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(c))
                    Box(Modifier.fillMaxWidth(0.62f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(c))
                }
            }
        }
    }
}

/** Three dots that rise in turn while Superhuman AI is working. */
@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(16.dp)) {
        repeat(3) { i ->
            val p by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160)),
                label = "dot$i",
            )
            val lift = remember(p) { if (p < 0.35f) kotlin.math.sin(p / 0.35f * Math.PI).toFloat() else 0f }
            Box(Modifier.padding(bottom = (lift * 5).dp).size(7.dp).clip(CircleShape).background(color.copy(alpha = 0.45f + 0.55f * lift)))
        }
    }
}
