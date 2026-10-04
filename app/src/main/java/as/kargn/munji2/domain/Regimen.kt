package `as`.kargn.munji2.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * ADJUST_* rows are schedule adjustments, not doses: `at` = the chain due they replace,
 * `amount` = adjusted target (epoch ms). They live in the log so they are undoable and exported.
 */
enum class EventType { KREMEZIN, GI, FLUID, FOOD_PUT, FOOD_LEFT, WET, WEIGHT, STIMULANT, ADJUST_KREMEZIN, ADJUST_GI, ADJUST_FLUID }

/** Reminder-bearing items. Food/weight are logged but never alarm. */
enum class Item(val type: EventType, val adjustType: EventType) {
    KREMEZIN(EventType.KREMEZIN, EventType.ADJUST_KREMEZIN),
    GI(EventType.GI, EventType.ADJUST_GI),
    FLUID(EventType.FLUID, EventType.ADJUST_FLUID),
}

/** Spacing from the last dose of the same item. */
enum class Band { SAFE, CAUTION, TOO_EARLY, TOO_LATE }

/**
 * Guardrails for 12 h meds and daily fluids. Spacing rules, not pharmacology. Edges: safe [safeLo, safeHi] inclusive, caution [min, safeLo)
 * and (safeHi, max], too early < min, too late > max.
 */
data class Spacing(val min: Long, val safeLo: Long, val safeHi: Long, val max: Long)

/**
 * amount: pills for meds, mL for fluids, grams for food, kg for weight.
 * discarded: only meaningful for FOOD_LEFT (true = bowl emptied, session closed).
 * note: free text from the edit sheet, or the overridden warning on an ADJUST_* row.
 */
data class Event(
    val id: Long,
    val type: EventType,
    val at: Long,
    val amount: Double,
    val discarded: Boolean = false,
    val note: String = "",
    val origin: Origin = Origin.LIVE,
)

/** How a row got its time: tapped at the moment (LIVE), backfilled (MANUAL, `직접 입력`), or changed later (EDITED, `수정됨`). */
enum class Origin { LIVE, MANUAL, EDITED }

/**
 * A guardrail the owner overrode with the second tap, derived from the log so an edit or undo keeps it true.
 * value: ms since the other medicine (SEPARATION), ms since the same item (EARLY 크레메진 before its reference
 * range, REPEAT_GAP 위장약 < 6 h, REPEAT_DAY second 수액 in one owner-day), local hour (OFF_WINDOW).
 */
data class Violation(val kind: Kind, val value: Long) {
    enum class Kind { SEPARATION, EARLY, REPEAT_GAP, REPEAT_DAY, OFF_WINDOW }
}

enum class EditError { FUTURE, LEFT_OVER_PUT }

data class Settings(
    val dayCutMin: Int = 5 * 60,
    val sleepStartMin: Int = 5 * 60,
    val sleepEndMin: Int = 13 * 60,
)

data class Due(
    val item: Item,
    /** Chain due: last actual dose + interval. */
    val dueAt: Long,
    /** Hard minimum: other medicine + 30 min; 0 when the other medicine was never given. */
    val lockedUntil: Long,
    val amount: Double,
    /** Vet's recommended gap: other medicine + 2 h; 0 when the other medicine was never given. */
    val recommendedAt: Long = 0L,
    /** Already > 1 h overdue when the other medicine was given: aim at the 30-min mark, not 2 h. */
    val overdueAtOther: Boolean = false,
    /** Adjusted target for this dose only; 0 when not adjusted. */
    val adjustedAt: Long = 0L,
    /** 크레메진 reference-range edges (last dose + min / + max); 0 for other items or with no previous dose. */
    val earliestAt: Long = 0L,
    val latestAt: Long = 0L,
    /** 수액 only: a dose is already logged in the current owner-day. */
    val doneToday: Boolean = false,
) {
    /**
     * Target time for reminders, widget and the "next action" pick: the later of the (adjusted or
     * chain) due and the 2-hour mark (or the 30-min mark for a dose that was already overdue).
     */
    val effectiveAt: Long get() = maxOf(if (adjustedAt > 0) adjustedAt else dueAt, if (overdueAtOther) lockedUntil else recommendedAt)
}

object Regimen {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DOSE_INTERVAL = 12 * HOUR
    // Vet's hard minimum between 크레메진 and 위장약 (recommended ~2 h, but 30 min is the line).
    const val SEPARATION = 30 * MIN
    const val RECOMMENDED_GAP = 2 * HOUR
    // Not a vet rule: the regimen only says 12 h apart with no minimum. This is an app-side reference range around 12 h, labelled as such in the UI; a dose
    // before its lower edge needs a second tap. 위장약 ("아침 1알, 저녁 2알") and 수액 ("하루 한 번") have none.
    val KREMEZIN_REFERENCE = Spacing(8 * HOUR, 10 * HOUR, 14 * HOUR, 16 * HOUR)
    // A 위장약 this soon after the previous one is labelled `같은 날 2회차`; a note, never a warning.
    const val GI_REPEAT_NOTE = 6 * HOUR

    fun spacing(item: Item): Spacing? = if (item == Item.KREMEZIN) KREMEZIN_REFERENCE else null

    /** Position of a gap in the item's reference range; null for items without one. */
    fun band(item: Item, gap: Long): Band? {
        val sp = spacing(item) ?: return null
        return when {
            gap < sp.min -> Band.TOO_EARLY
            gap > sp.max -> Band.TOO_LATE
            gap in sp.safeLo..sp.safeHi -> Band.SAFE
            else -> Band.CAUTION
        }
    }

    enum class Sep { FREE, AMBER, RED }

    /** Cross-item separation for a planned time against the other medicine's last dose and next target. */
    fun separation(at: Long, otherTimes: List<Long>): Sep {
        val gap = otherTimes.minOfOrNull { kotlin.math.abs(at - it) } ?: return Sep.FREE
        return when {
            gap < SEPARATION -> Sep.RED
            gap < RECOMMENDED_GAP -> Sep.AMBER
            else -> Sep.FREE
        }
    }

    fun lastOf(events: List<Event>, item: Item) = last(events, item.type)
    // An overdue dose is not held for the full 2 h once it is this late.
    const val OVERDUE_HOLD_LIMIT = 1 * HOUR
    const val FLUID_ML = 100.0
    const val FOOD_TARGET_G = 53.0
    const val LOW_INTAKE_G = 26.0
    const val WEIGHT_GOAL_KG = 3.6
    const val WEIGHT_CALL_KG = 3.4
    // A dose this late sounds even inside the sleep window.
    const val SLEEP_OVERRIDE = 3 * HOUR
    // Appetite stimulant only in the morning; afternoon dosing causes insomnia/agitation.
    val STIM_FROM: LocalTime = LocalTime.of(5, 0)
    val STIM_UNTIL: LocalTime = LocalTime.of(12, 0)

    fun ownerDay(at: Long, s: Settings, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(at).atZone(zone).minusMinutes(s.dayCutMin.toLong()).toLocalDate()

    fun dayStart(day: LocalDate, s: Settings, zone: ZoneId): Long =
        day.atStartOfDay(zone).plusMinutes(s.dayCutMin.toLong()).toInstant().toEpochMilli()

    private fun last(events: List<Event>, type: EventType) = events.filter { it.type == type }.maxByOrNull { it.at }

    /** Last 2알 → next 1알 and vice versa; decided by the last amount, not the clock. */
    fun nextGiAmount(events: List<Event>): Double = if (last(events, EventType.GI)?.amount == 2.0) 1.0 else 2.0

    fun due(item: Item, events: List<Event>, now: Long, s: Settings, zone: ZoneId): Due {
        val d = chainDue(item, events, now, s, zone)
        val l = last(events, item.type)
        val sp = spacing(item)
        // An adjustment only counts for the chain due it was made against; logging the dose moves the
        // chain and so clears it without extra state.
        val adj = events.filter { it.type == item.adjustType && it.at == d.dueAt }.maxByOrNull { it.id }
        return d.copy(
            adjustedAt = adj?.amount?.toLong() ?: 0L,
            earliestAt = if (l != null && sp != null) l.at + sp.min else 0L,
            latestAt = if (l != null && sp != null) l.at + sp.max else 0L,
            doneToday = item == Item.FLUID && events.any { it.type == EventType.FLUID && ownerDay(it.at, s, zone) == ownerDay(now, s, zone) },
        )
    }

    private fun chainDue(item: Item, events: List<Event>, now: Long, s: Settings, zone: ZoneId): Due = when (item) {
        Item.KREMEZIN -> {
            val l = last(events, EventType.KREMEZIN)
            // A late dose shifts the chain; never pull earlier to catch up.
            separated(Due(item, l?.let { it.at + DOSE_INTERVAL } ?: now, 0L, 1.0), last(events, EventType.GI))
        }
        Item.GI -> {
            val l = last(events, EventType.GI)
            separated(Due(item, l?.let { it.at + DOSE_INTERVAL } ?: now, 0L, nextGiAmount(events)), last(events, EventType.KREMEZIN))
        }
        Item.FLUID -> Due(item, fluidDue(events, now, s, zone), 0L, FLUID_ML)
    }

    private fun separated(d: Due, other: Event?): Due = if (other == null) d else d.copy(
        lockedUntil = other.at + SEPARATION,
        recommendedAt = other.at + RECOMMENDED_GAP,
        overdueAtOther = other.at - d.dueAt > OVERDUE_HOLD_LIMIT,
    )

    /**
     * Minutes between a 크레메진/위장약 dose and the previous other medicine when that gap was under
     * the recommended 2 h; null otherwise. Derived from the log, so undo/redo keeps it consistent.
     */
    fun earlyGapMin(events: List<Event>, e: Event): Long? {
        val other = when (e.type) { EventType.KREMEZIN -> EventType.GI; EventType.GI -> EventType.KREMEZIN; else -> return null }
        val prev = events.filter { it.type == other && it.at <= e.at }.maxByOrNull { it.at } ?: return null
        val gap = e.at - prev.at
        return if (gap < RECOMMENDED_GAP) gap / MIN else null
    }

    /**
     * Labels for a logged dose: other medicine < 30 min before (the vet's only hard line), 크레메진 before
     * its reference range, a second 위장약/수액 soon or on the same owner-day, stimulant outside 05:00–12:00.
     * The log records what happened; this only labels it.
     */
    fun violations(events: List<Event>, e: Event, s: Settings, zone: ZoneId): List<Violation> {
        val out = mutableListOf<Violation>()
        val other = when (e.type) { EventType.KREMEZIN -> EventType.GI; EventType.GI -> EventType.KREMEZIN; else -> null }
        if (other != null) {
            events.filter { it.type == other && it.at <= e.at && it.id != e.id }.maxByOrNull { it.at }
                ?.let { if (e.at - it.at < SEPARATION) out += Violation(Violation.Kind.SEPARATION, e.at - it.at) }
        }
        val prev = events.filter { it.type == e.type && it.at < e.at && it.id != e.id }.maxByOrNull { it.at }
        if (prev != null) when (e.type) {
            EventType.KREMEZIN -> if (e.at - prev.at < KREMEZIN_REFERENCE.min) out += Violation(Violation.Kind.EARLY, e.at - prev.at)
            EventType.GI -> if (e.at - prev.at < GI_REPEAT_NOTE) out += Violation(Violation.Kind.REPEAT_GAP, e.at - prev.at)
            EventType.FLUID -> if (ownerDay(prev.at, s, zone) == ownerDay(e.at, s, zone)) out += Violation(Violation.Kind.REPEAT_DAY, e.at - prev.at)
            else -> {}
        }
        if (e.type == EventType.STIMULANT) {
            val t = Instant.ofEpochMilli(e.at).atZone(zone).toLocalTime()
            if (t.isBefore(STIM_FROM) || !t.isBefore(STIM_UNTIL)) out += Violation(Violation.Kind.OFF_WINDOW, t.hour.toLong())
        }
        return out
    }

    /**
     * Edits and backfills accept any past time. Future is refused, and a 남은 양 can never exceed what
     * was in the bowl before it (checked for the edited row, or the next 남은 양 after an edited 담았어요).
     */
    fun checkEdit(events: List<Event>, e: Event, now: Long): EditError? {
        if (e.at > now) return EditError.FUTURE
        val list = (events.filter { it.id != e.id || e.id == 0L } + e).sortedBy { it.at }
        val target = when (e.type) {
            EventType.FOOD_LEFT -> e
            EventType.FOOD_PUT -> list.firstOrNull { it.type == EventType.FOOD_LEFT && it.at >= e.at } ?: return null
            else -> return null
        }
        var bowl = 0.0
        for (x in list) {
            if (x === target) return if (x.amount > bowl) EditError.LEFT_OVER_PUT else null
            when (x.type) {
                EventType.FOOD_PUT -> bowl += x.amount
                EventType.FOOD_LEFT -> bowl = if (x.discarded) 0.0 else x.amount
                else -> {}
            }
        }
        return null
    }

    /** A changed row keeps its id; a backfilled row stays `직접 입력` even after later edits. */
    fun edited(old: Event, at: Long, amount: Double, note: String): Event =
        old.copy(at = at, amount = amount, note = note, origin = if (old.origin == Origin.MANUAL) Origin.MANUAL else Origin.EDITED)

    /** Backfill for "gave it an hour ago and forgot"; id 0 until Room assigns one. */
    fun manual(type: EventType, at: Long, amount: Double, note: String = ""): Event =
        Event(0L, type, at, amount, note = note, origin = Origin.MANUAL)

    /** Once per owner-day; the reminder sits at the end of the sleep window (the owner's "morning"). Never given: due now. */
    fun fluidDue(events: List<Event>, now: Long, s: Settings, zone: ZoneId): Long {
        if (events.none { it.type == EventType.FLUID }) return now
        var day = ownerDay(now, s, zone)
        val givenToday = events.any { it.type == EventType.FLUID && ownerDay(it.at, s, zone) == day }
        if (givenToday) day = day.plusDays(1)
        val start = Instant.ofEpochMilli(dayStart(day, s, zone)).atZone(zone)
        var t: ZonedDateTime = start.toLocalDate().atStartOfDay(zone).plusMinutes(s.sleepEndMin.toLong())
        if (t.isBefore(start)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    fun allDues(events: List<Event>, now: Long, s: Settings, zone: ZoneId) =
        Item.entries.map { due(it, events, now, s, zone) }

    /** The single next action: the earliest effective due. */
    fun next(events: List<Event>, now: Long, s: Settings, zone: ZoneId): Due =
        allDues(events, now, s, zone).minBy { it.effectiveAt }

    fun inSleep(at: Long, s: Settings, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        val m = t.hour * 60 + t.minute
        return if (s.sleepStartMin <= s.sleepEndMin) m in s.sleepStartMin until s.sleepEndMin
        else m >= s.sleepStartMin || m < s.sleepEndMin
    }

    /** Next end of the sleep window strictly after [at]. */
    fun sleepEndAfter(at: Long, s: Settings, zone: ZoneId): Long {
        val z = Instant.ofEpochMilli(at).atZone(zone)
        var t = z.toLocalDate().atStartOfDay(zone).plusMinutes(s.sleepEndMin.toLong())
        if (!t.isAfter(z)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    /** Reminder may make sound now? Inside the sleep window only when overdue by > 3 h. */
    fun loud(due: Due, now: Long, s: Settings, zone: ZoneId): Boolean =
        !inSleep(now, s, zone) || now - due.effectiveAt > SLEEP_OVERRIDE

    /** Escalation: +0, +15, +30, +60 min, then hourly, relative to [base]. */
    fun nextStep(base: Long, now: Long): Long {
        if (now < base) return base
        for (m in longArrayOf(15, 30, 60)) if (base + m * MIN > now) return base + m * MIN
        val hours = (now - base) / HOUR + 1
        return base + hours * HOUR
    }

    /**
     * Next alarm for an item. Besides the escalation step, wake at the sleep-window end and at the
     * 3 h override so a silently posted reminder turns loud without waiting for the next hourly step.
     */
    fun nextFire(due: Due, snoozeUntil: Long, now: Long, s: Settings, zone: ZoneId): Long {
        val base = maxOf(due.effectiveAt, snoozeUntil)
        val candidates = mutableListOf(nextStep(base, now))
        if (now >= due.effectiveAt) {
            if (inSleep(now, s, zone)) candidates += sleepEndAfter(now, s, zone)
            val override = due.effectiveAt + SLEEP_OVERRIDE + MIN
            if (override > now) candidates += override
        }
        return candidates.filter { it > now }.min()
    }

    /**
     * Dry intake per owner-day, attributed to the moment the leftover is weighed:
     * eaten = (in bowl) − (left). 버림 empties the bowl; 그대로 둠 keeps the leftover in it.
     */
    fun dryIntakeByDay(events: List<Event>, s: Settings, zone: ZoneId): Map<LocalDate, Double> {
        var bowl = 0.0
        val out = mutableMapOf<LocalDate, Double>()
        for (e in events.sortedBy { it.at }) when (e.type) {
            EventType.FOOD_PUT -> bowl += e.amount
            EventType.FOOD_LEFT -> {
                val d = ownerDay(e.at, s, zone)
                out[d] = (out[d] ?: 0.0) + (bowl - e.amount).coerceAtLeast(0.0)
                bowl = if (e.discarded) 0.0 else e.amount
            }
            else -> {}
        }
        return out
    }

    /** Grams currently in the bowl that have not been weighed back yet (open session). */
    fun bowl(events: List<Event>): Double {
        var bowl = 0.0
        for (e in events.sortedBy { it.at }) when (e.type) {
            EventType.FOOD_PUT -> bowl += e.amount
            EventType.FOOD_LEFT -> bowl = if (e.discarded) 0.0 else e.amount
            else -> {}
        }
        return bowl
    }

    fun wetByDay(events: List<Event>, s: Settings, zone: ZoneId): Map<LocalDate, Double> =
        events.filter { it.type == EventType.WET }.groupBy { ownerDay(it.at, s, zone) }.mapValues { (_, v) -> v.sumOf { it.amount } }

    enum class Stim { GIVE, NOT_MORNING, RECENT, NOT_NEEDED }

    /**
     * 식욕촉진제 gate: yesterday's (owner-day) dry intake was measured and ≤ 26 g, no stimulant
     * yesterday or today (at most every other day), and it is 05:00–12:00 now.
     */
    fun stimulant(events: List<Event>, now: Long, s: Settings, zone: ZoneId): Stim {
        val today = ownerDay(now, s, zone)
        val yesterday = today.minusDays(1)
        val measured = events.any { it.type == EventType.FOOD_LEFT && ownerDay(it.at, s, zone) == yesterday }
        val intake = dryIntakeByDay(events, s, zone)[yesterday] ?: 0.0
        if (!measured || intake > LOW_INTAKE_G) return Stim.NOT_NEEDED
        val recent = events.any { it.type == EventType.STIMULANT && ownerDay(it.at, s, zone) >= yesterday }
        if (recent) return Stim.RECENT
        val t = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
        return if (!t.isBefore(STIM_FROM) && t.isBefore(STIM_UNTIL)) Stim.GIVE else Stim.NOT_MORNING
    }

    fun inStimWindow(at: Long, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        return !t.isBefore(STIM_FROM) && t.isBefore(STIM_UNTIL)
    }
}
