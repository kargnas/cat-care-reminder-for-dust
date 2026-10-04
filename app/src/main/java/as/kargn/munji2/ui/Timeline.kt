package `as`.kargn.munji2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import `as`.kargn.munji2.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `as`.kargn.munji2.domain.Due
import `as`.kargn.munji2.domain.Event
import `as`.kargn.munji2.domain.EventType
import `as`.kargn.munji2.domain.Fmt
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.domain.Regimen
import `as`.kargn.munji2.domain.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 24 h strip centred on now: −12 h … +12 h. Filled dots = given, hollow = upcoming due,
 * shaded band = 30-min lock, faint band = sleep window.
 */
@Composable
fun Timeline(events: List<Event>, dues: List<Due>, now: Long, s: Settings, modifier: Modifier = Modifier) {
  Column(modifier) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val colorOf = { item: Item -> when (item) { Item.KREMEZIN -> cs.primary; Item.GI -> cs.tertiary; Item.FLUID -> cs.secondary } }
    val label = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)
    val zone = ZoneId.systemDefault()
    val adjTag = stringResource(R.string.adjust_tag)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val start = now - 12 * Regimen.HOUR
        val span = 24 * Regimen.HOUR
        fun x(t: Long) = ((t - start).toFloat() / span) * size.width
        val mid = size.height * 0.5f

        // Sleep window bands for every occurrence in range.
        var day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
        repeat(3) {
            val a = day.atStartOfDay(zone).plusMinutes(s.sleepStartMin.toLong()).toInstant().toEpochMilli()
            var b = day.atStartOfDay(zone).plusMinutes(s.sleepEndMin.toLong()).toInstant().toEpochMilli()
            if (b <= a) b += 24 * Regimen.HOUR
            drawRect(cs.surfaceVariant.copy(alpha = 0.6f), Offset(x(a), 0f), Size(x(b) - x(a), size.height))
            day = day.plusDays(1)
        }

        drawLine(cs.outline, Offset(0f, mid), Offset(size.width, mid), 2f)
        // Hour ticks; label every 3 h.
        var h = Instant.ofEpochMilli(start).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        while (h.toInstant().toEpochMilli() < start + span) {
            val px = x(h.toInstant().toEpochMilli())
            val major = h.hour % 3 == 0
            drawLine(cs.outline, Offset(px, mid - if (major) 8f else 4f), Offset(px, mid + if (major) 8f else 4f), 1.5f)
            if (major) {
                val r = measurer.measure("%02d".format(h.hour), label)
                drawText(r, topLeft = Offset(px - r.size.width / 2f, size.height - r.size.height))
            }
            h = h.plusHours(1)
        }

        // One thin row per item under the axis: overdue span as a red stripe; for 크레메진 also its
        // reference range (green = around 12 h, amber = reference edges, thin grey ticks = range ends).
        dues.forEachIndexed { i, d ->
            val y = mid + 14f + i * 7f
            fun band(a: Long, b: Long, c: androidx.compose.ui.graphics.Color) =
                drawRect(c, Offset(x(a), y), Size(x(b) - x(a), 5f))
            if (now > d.dueAt) band(d.dueAt, now, cs.error.copy(alpha = 0.7f))
            val sp = Regimen.spacing(d.item)
            if (d.earliestAt == 0L || sp == null) return@forEachIndexed
            val last = d.earliestAt - sp.min
            band(last + sp.min, last + sp.safeLo, AMBER.copy(alpha = 0.6f))
            band(last + sp.safeLo, last + sp.safeHi, GREEN.copy(alpha = 0.6f))
            band(last + sp.safeHi, last + sp.max, AMBER.copy(alpha = 0.6f))
            for (edge in listOf(last + sp.min, last + sp.max)) drawRect(cs.outline, Offset(x(edge) - 0.75f, y - 3f), Size(1.5f, 11f))
        }

        // Separation bands: red = 30-min hard lock, amber = until the recommended 2 h mark.
        for (d in dues) if (d.recommendedAt > start) {
            val a = d.lockedUntil - Regimen.SEPARATION
            drawRect(AMBER.copy(alpha = 0.25f), Offset(x(d.lockedUntil), mid - 12f), Size(x(d.recommendedAt) - x(d.lockedUntil), 24f))
            drawRect(cs.error.copy(alpha = 0.3f), Offset(x(a), mid - 16f), Size(x(d.lockedUntil) - x(a), 32f))
        }

        // Given events.
        for (e in events) if (e.at in start..now && !e.type.name.startsWith("ADJUST")) {
            val item = Item.entries.firstOrNull { it.type == e.type }
            val c = item?.let(colorOf) ?: cs.outline
            drawCircle(c, if (item != null) 9f else 5f, Offset(x(e.at), mid))
            if (item != null) {
                val r = measurer.measure(Fmt.hm(e.at), label)
                val above = e.type == EventType.KREMEZIN
                drawText(r, topLeft = Offset(x(e.at) - r.size.width / 2f, if (above) mid - 22f - r.size.height else mid + 38f))
            }
        }

        // Upcoming dues (hollow). An adjusted dose keeps a thin line back to its chain due.
        for (d in dues) {
            val t = d.effectiveAt
            if (d.adjustedAt > 0) drawLine(colorOf(d.item), Offset(x(d.dueAt), mid), Offset(x(t), mid), 3f)
            if (t !in start..start + span) continue
            drawCircle(colorOf(d.item), 10f, Offset(x(t), mid), style = Stroke(4f))
            val text = if (d.adjustedAt > 0) "${Fmt.hm(t)} $adjTag" else Fmt.hm(t)
            val r = measurer.measure(text, label.copy(color = colorOf(d.item)))
            drawText(r, topLeft = Offset(x(t) - r.size.width / 2f, mid - 22f - r.size.height))
        }

        drawLine(cs.onSurface, Offset(x(now), 4f), Offset(x(now), size.height - 18f), 3f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Legend(GREEN, stringResource(R.string.legend_safe))
        Legend(AMBER, stringResource(R.string.legend_caution))
    }
  }
}

@Composable
private fun Legend(c: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Safe-band green; Material 3 has no success role.
val GREEN = androidx.compose.ui.graphics.Color(0xFF43A047)
