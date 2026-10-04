package `as`.kargn.munji2.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import `as`.kargn.munji2.R
import `as`.kargn.munji2.domain.Event
import `as`.kargn.munji2.domain.Fmt
import `as`.kargn.munji2.domain.Regimen
import `as`.kargn.munji2.domain.Settings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 7 owner-days of Markdown for the vet plus a raw CSV, handed to the share sheet together. */
object Export {
    private val DAY = DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN)
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun share(ctx: Context, events: List<Event>, s: Settings) {
        val z = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = Regimen.ownerDay(now, s, z)
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val dry = Regimen.dryIntakeByDay(events, s, z)
        val wet = Regimen.wetByDay(events, s, z)
        val md = buildString {
            appendLine(ctx.getString(R.string.export_title, days.first().format(DAY), days.last().format(DAY)))
            appendLine()
            for (d in days) {
                appendLine()
                appendLine(ctx.getString(R.string.export_day, d.format(DAY)))
                appendLine(ctx.getString(R.string.export_food, (dry[d] ?: 0.0).toInt(), Regimen.FOOD_TARGET_G.toInt(), (wet[d] ?: 0.0).toInt()))
                val dayEvents = events.filter { Regimen.ownerDay(it.at, s, z) == d }.sortedBy { it.at }
                if (dayEvents.isEmpty()) appendLine(ctx.getString(R.string.export_none))
                for (e in dayEvents) appendLine(ctx.getString(R.string.export_line, Fmt.hm(e.at), Fmt.eventLabel(ctx, e, events, s)))
            }
        }
        val csv = buildString {
            appendLine(ctx.getString(R.string.csv_header))
            for (e in events.sortedBy { it.at }) {
                appendLine("${e.id},${e.type.name},${Instant.ofEpochMilli(e.at).atZone(z).format(ISO)},${e.amount},${e.discarded},${e.origin.name},\"${e.note.replace("\"", "\"\"")}\"")
            }
        }
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val stamp = today.toString()
        val mdFile = File(dir, "munji-log-$stamp.md").apply { writeText(md) }
        val csvFile = File(dir, "munji-events-$stamp.csv").apply { writeText(csv) }
        val auth = "${ctx.packageName}.files"
        val uris = arrayListOf(FileProvider.getUriForFile(ctx, auth, mdFile), FileProvider.getUriForFile(ctx, auth, csvFile))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.export_subject, days.first().format(DAY), days.last().format(DAY)))
            // Some targets (messengers, notes) ignore attachments; include the Markdown as text too.
            putExtra(Intent.EXTRA_TEXT, md)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
