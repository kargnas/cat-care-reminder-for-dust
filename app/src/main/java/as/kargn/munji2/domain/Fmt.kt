package `as`.kargn.munji2.domain

import android.content.Context
import `as`.kargn.munji2.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared labels for notification, widget and app so all three say the same thing. */
object Fmt {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun hm(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(HM)

    fun name(ctx: Context, item: Item): String = ctx.getString(
        when (item) {
            Item.KREMEZIN -> R.string.item_kremezin
            Item.GI -> R.string.item_gi
            Item.FLUID -> R.string.item_fluid
        }
    )

    fun dose(ctx: Context, item: Item, amount: Double): String =
        if (item == Item.FLUID) ctx.getString(R.string.dose_ml, name(ctx, item), amount.toInt())
        else ctx.getString(R.string.dose_pills, name(ctx, item), amount.toInt())

    fun dose(ctx: Context, d: Due) = dose(ctx, d.item, d.amount)

    /** Rounded down to minutes; hours shown as raw numbers ("2시간 10분"). */
    fun dur(ctx: Context, ms: Long): String {
        val m = (ms / Regimen.MIN).coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0L -> ctx.getString(R.string.dur_m, r.toInt())
            r == 0L -> ctx.getString(R.string.dur_h, h.toInt())
            else -> ctx.getString(R.string.dur_hm, h.toInt(), r.toInt())
        }
    }

    /** Same-item minimum spacing or the 30-min cross-item lock not reached yet: giving needs a second tap. */
    fun blocked(d: Due, now: Long) = d.earliestAt > now || d.lockedUntil > now

    /** First-tap warning, e.g. "30분 안 지남 (12분)" or "12시간 전 (6시간 간격)"; counts time since the dose that set it. */
    fun warn(ctx: Context, d: Due, now: Long): String = if (d.lockedUntil > now)
        ctx.getString(R.string.warn_sep, dur(ctx, now - (d.lockedUntil - Regimen.SEPARATION)))
    else ctx.getString(R.string.warn_before_12h, dur(ctx, now - (d.earliestAt - Regimen.KREMEZIN_REFERENCE.min)))

    /** Open but before the vet's recommended 2 h gap (amber state). */
    fun early(d: Due, now: Long) = d.lockedUntil <= now && now < d.recommendedAt && d.recommendedAt > d.dueAt

    /**
     * Two-tier separation: "아직 못 줘요 · 최소 30분" while locked, "지금 가능 · 권장은 HH:MM"
     * between 30 min and 2 h; otherwise "예정 15:00 · 2시간 남음" / "· 늦음" against the target time.
     */
    fun status(ctx: Context, d: Due, now: Long): String = when {
        d.earliestAt > now -> ctx.getString(R.string.status_before_12h, hm(d.dueAt))
        // 수액 is "하루 한 번": done/not-yet for the owner-day, with the reminder time when pending.
        d.item == Item.FLUID -> if (d.doneToday) ctx.getString(R.string.status_fluid_done) else ctx.getString(R.string.status_fluid_todo, timing(ctx, d, now))
        d.lockedUntil > now -> ctx.getString(R.string.status_locked, dur(ctx, d.lockedUntil - now + Regimen.MIN - 1), hm(d.lockedUntil))
        early(d, now) -> ctx.getString(R.string.status_early, hm(d.recommendedAt))
        else -> timing(ctx, d, now)
    }

    private fun timing(ctx: Context, d: Due, now: Long): String = when {
        now < d.effectiveAt - Regimen.MIN -> ctx.getString(R.string.status_due_at, hm(d.effectiveAt), dur(ctx, d.effectiveAt - now))
        now < d.effectiveAt + Regimen.MIN -> ctx.getString(R.string.status_now, hm(d.effectiveAt))
        else -> ctx.getString(R.string.status_late, hm(d.dueAt), dur(ctx, now - d.dueAt))
    }

    /**
     * Log/export label plus derived notes: overridden guardrails (`간격 위반`, `조기 투약`, `조건 밖`), the
     * softer `권장 간격 전`/`참고 범위 밖`, `같은 날 2회차`, the stored note, and the `직접 입력`/`수정됨` tag.
     */
    fun eventLabel(ctx: Context, e: Event, events: List<Event>, s: Settings): String {
        val notes = mutableListOf<String>()
        val v = Regimen.violations(events, e, s, ZoneId.systemDefault())
        for (x in v) notes += when (x.kind) {
            Violation.Kind.SEPARATION -> ctx.getString(R.string.note_violation_sep, dur(ctx, x.value))
            Violation.Kind.EARLY -> ctx.getString(R.string.note_violation_early, dur(ctx, x.value))
            Violation.Kind.REPEAT_GAP -> ctx.getString(R.string.note_repeat_gap, dur(ctx, x.value))
            Violation.Kind.REPEAT_DAY -> ctx.getString(R.string.note_repeat_day)
            Violation.Kind.OFF_WINDOW -> ctx.getString(R.string.note_off_window, ctx.getString(if (x.value >= 12) R.string.off_window_pm else R.string.off_window_night))
        }
        // The softer notes only add information when the hard one for the same rule is absent.
        if (v.none { it.kind == Violation.Kind.SEPARATION }) Regimen.earlyGapMin(events, e)?.let { notes += ctx.getString(R.string.note_early, it.toInt()) }
        if (v.none { it.kind == Violation.Kind.EARLY }) Item.entries.firstOrNull { it.type == e.type }?.let { item ->
            val prev = events.filter { it.type == e.type && it.at < e.at }.maxByOrNull { it.at }
            val b = prev?.let { Regimen.band(item, e.at - it.at) }
            if (prev != null && b != null && b != Band.SAFE)
                notes += ctx.getString(R.string.note_spacing, dur(ctx, e.at - prev.at))
        }
        if (e.note.isNotBlank()) notes += e.note
        when (e.origin) {
            Origin.MANUAL -> notes += ctx.getString(R.string.tag_manual)
            Origin.EDITED -> notes += ctx.getString(R.string.tag_edited)
            Origin.LIVE -> {}
        }
        return (listOf(eventLabel(ctx, e)) + notes).joinToString(" · ")
    }

    fun eventLabel(ctx: Context, e: Event): String = when (e.type) {
        EventType.KREMEZIN -> dose(ctx, Item.KREMEZIN, e.amount)
        EventType.GI -> dose(ctx, Item.GI, e.amount)
        EventType.FLUID -> dose(ctx, Item.FLUID, e.amount)
        EventType.FOOD_PUT -> "${ctx.getString(R.string.type_food_put)} ${ctx.getString(R.string.amount_g, e.amount.toInt())}"
        EventType.FOOD_LEFT -> "${ctx.getString(R.string.type_food_left)} " +
            ctx.getString(if (e.discarded) R.string.left_discarded else R.string.left_kept, e.amount.toInt())
        EventType.WET -> "${ctx.getString(R.string.type_wet)} ${ctx.getString(R.string.amount_g, e.amount.toInt())}"
        EventType.WEIGHT -> "${ctx.getString(R.string.type_weight)} ${ctx.getString(R.string.amount_kg, e.amount)}"
        EventType.STIMULANT -> ctx.getString(R.string.type_stimulant)
        EventType.ADJUST_KREMEZIN, EventType.ADJUST_GI, EventType.ADJUST_FLUID -> {
            val item = Item.entries.first { it.adjustType == e.type }
            ctx.getString(R.string.adjust_row, hm(e.amount.toLong()), name(ctx, item))
        }
    }
}
