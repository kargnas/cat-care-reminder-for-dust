package `as`.kargn.munji2

import `as`.kargn.munji2.domain.Band
import `as`.kargn.munji2.domain.Due
import `as`.kargn.munji2.domain.Event
import `as`.kargn.munji2.domain.EventType
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.domain.Regimen
import `as`.kargn.munji2.domain.Regimen.HOUR
import `as`.kargn.munji2.domain.Regimen.MIN
import `as`.kargn.munji2.domain.Settings
import `as`.kargn.munji2.domain.EditError
import `as`.kargn.munji2.domain.Origin
import `as`.kargn.munji2.domain.Violation
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RegimenTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val s = Settings()
    private fun t(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2025, 3, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private var nextId = 1L
    private fun ev(type: EventType, at: Long, amount: Double = 1.0, discarded: Boolean = false) = Event(nextId++, type, at, amount, discarded)

    private val anchors = listOf(ev(EventType.KREMEZIN, t(4, 3)), ev(EventType.GI, t(4, 5), 2.0))

    @Test fun kremezinChainIsLastPlus12h() {
        assertEquals(t(4, 15), Regimen.due(Item.KREMEZIN, anchors, t(4, 12), s, zone).dueAt)
    }

    @Test fun lateDoseShiftsChainInsteadOfCatchingUp() {
        val ev = anchors + ev(EventType.KREMEZIN, t(4, 17, 40))
        assertEquals(t(5, 5, 40), Regimen.due(Item.KREMEZIN, ev, t(4, 18), s, zone).dueAt)
    }

    @Test fun giAlternatesByLastAmountNotClock() {
        assertEquals(1.0, Regimen.due(Item.GI, anchors, t(4, 12), s, zone).amount, 0.0)
        val after = anchors + ev(EventType.GI, t(4, 17), 1.0)
        assertEquals(2.0, Regimen.due(Item.GI, after, t(4, 18), s, zone).amount, 0.0)
        assertEquals(t(5, 5), Regimen.due(Item.GI, after, t(4, 18), s, zone).dueAt)
    }

    @Test fun separationLocks30MinAndTargetsTwoHours() {
        val ev = anchors + ev(EventType.KREMEZIN, t(4, 16, 50))
        val gi = Regimen.due(Item.GI, ev, t(4, 17), s, zone)
        assertEquals(t(4, 17), gi.dueAt)
        assertEquals(t(4, 17, 20), gi.lockedUntil)
        assertEquals(t(4, 18, 50), gi.recommendedAt)
        // Reminder, widget and next-action pick all aim at the 2-hour mark.
        assertEquals(t(4, 18, 50), gi.effectiveAt)
    }

    @Test fun overdueByMoreThanOneHourFiresAtThirtyMinMark() {
        // GI was due 17:00; 크레메진 given 18:30 (GI 1.5 h overdue) → GI reminder at 19:00, not 20:30.
        val ev = anchors + ev(EventType.GI, t(4, 5), 2.0) + ev(EventType.KREMEZIN, t(4, 18, 30))
        val gi = Regimen.due(Item.GI, ev, t(4, 18, 40), s, zone)
        assertTrue(gi.overdueAtOther)
        assertEquals(t(4, 19), gi.effectiveAt)
    }

    @Test fun chainDueLaterThanTwoHourMarkWins() {
        val ev = anchors + ev(EventType.KREMEZIN, t(4, 14))
        assertEquals(t(4, 17), Regimen.due(Item.GI, ev, t(4, 14, 10), s, zone).effectiveAt)
    }

    @Test fun earlyGapNoteOnlyInsideTwoHours() {
        val k = ev(EventType.KREMEZIN, t(4, 15))
        val g = ev(EventType.GI, t(4, 15, 45), 1.0)
        val ev = anchors + k + g
        assertEquals(45L, Regimen.earlyGapMin(ev, g))
        assertEquals(null, Regimen.earlyGapMin(ev, k)) // 10 h after the 05:00 GI
    }

    @Test fun lockWorksInEitherOrder() {
        val k = Regimen.due(Item.KREMEZIN, anchors, t(4, 5, 10), s, zone)
        assertEquals(t(4, 5, 30), k.lockedUntil)
    }

    @Test fun ownerDayCutsAt0500() {
        assertEquals(LocalDate.of(2025, 3, 3), Regimen.ownerDay(t(4, 2), s, zone))
        assertEquals(LocalDate.of(2025, 3, 4), Regimen.ownerDay(t(4, 5), s, zone))
        val late = s.copy(dayCutMin = 7 * 60)
        assertEquals(LocalDate.of(2025, 3, 3), Regimen.ownerDay(t(4, 6), late, zone))
    }

    @Test fun fluidDueAtWakeAndMovesToNextDayAfterGiven() {
        assertEquals(t(4, 12), Regimen.fluidDue(anchors, t(4, 12), s, zone)) // never given: now
        val yesterday = anchors + ev(EventType.FLUID, t(3, 20), 100.0)
        assertEquals(t(4, 13), Regimen.fluidDue(yesterday, t(4, 12), s, zone))
        val given = yesterday + ev(EventType.FLUID, t(5, 2), 100.0) // 02:00 belongs to owner-day 3/4
        assertEquals(t(5, 13), Regimen.fluidDue(given, t(5, 3), s, zone))
    }

    @Test fun sleepWindowSilencesUntilThreeHoursOverdue() {
        val due = Due(Item.KREMEZIN, t(5, 6), 0, 1.0)
        assertTrue(Regimen.inSleep(t(5, 8), s, zone))
        assertFalse(Regimen.loud(due, t(5, 8), s, zone))
        assertTrue(Regimen.loud(due, t(5, 9, 1), s, zone))
        assertTrue(Regimen.loud(Due(Item.GI, t(5, 14), 0, 1.0), t(5, 14), s, zone))
    }

    @Test fun sleepWindowWrapsMidnight() {
        val night = s.copy(sleepStartMin = 23 * 60, sleepEndMin = 7 * 60)
        assertTrue(Regimen.inSleep(t(5, 2), night, zone))
        assertFalse(Regimen.inSleep(t(5, 8), night, zone))
    }

    @Test fun deferredReminderWakesAtWindowEnd() {
        val due = Due(Item.KREMEZIN, t(5, 8), 0, 1.0)
        // 12:30 inside window: next step is 13:00 hourly anyway; at 12:10 window end (13:00) beats 3h override (11:01)? override passed.
        assertEquals(t(5, 13), Regimen.nextFire(due, 0, t(5, 12, 10), s, zone))
        // 08:20 inside window: +30 step at 08:30 is first; ensures stepping still happens silently
        assertEquals(t(5, 8, 30), Regimen.nextFire(due, 0, t(5, 8, 20), s, zone))
    }

    @Test fun escalationSteps() {
        val b = t(5, 15)
        assertEquals(b, Regimen.nextStep(b, b - MIN))
        assertEquals(b + 15 * MIN, Regimen.nextStep(b, b))
        assertEquals(b + 30 * MIN, Regimen.nextStep(b, b + 15 * MIN))
        assertEquals(b + 60 * MIN, Regimen.nextStep(b, b + 40 * MIN))
        assertEquals(b + 2 * HOUR, Regimen.nextStep(b, b + 60 * MIN))
        assertEquals(b + 4 * HOUR, Regimen.nextStep(b, b + 3 * HOUR + 5 * MIN))
    }

    @Test fun snoozeMovesBase() {
        val due = Due(Item.GI, t(5, 15), 0, 1.0)
        assertEquals(t(5, 15, 45), Regimen.nextFire(due, t(5, 15, 45), t(5, 15, 15), s, zone))
    }

    @Test fun foodIntakeIsPutMinusLeft() {
        val ev = listOf(
            ev(EventType.FOOD_PUT, t(4, 14), 30.0),
            ev(EventType.FOOD_LEFT, t(4, 20), 10.0, discarded = false), // ate 20, 10 stays
            ev(EventType.FOOD_PUT, t(4, 21), 20.0), // bowl 30
            ev(EventType.FOOD_LEFT, t(5, 3), 5.0, discarded = true), // ate 25, owner-day 3/4
            ev(EventType.WET, t(4, 22), 40.0),
        )
        assertEquals(45.0, Regimen.dryIntakeByDay(ev, s, zone)[LocalDate.of(2025, 3, 4)]!!, 0.001)
        assertEquals(0.0, Regimen.bowl(ev), 0.0)
        assertEquals(40.0, Regimen.wetByDay(ev, s, zone)[LocalDate.of(2025, 3, 4)]!!, 0.0)
    }

    @Test fun stimulantGate() {
        val low = listOf(ev(EventType.FOOD_PUT, t(4, 14), 30.0), ev(EventType.FOOD_LEFT, t(5, 2), 6.0, true)) // 24 g on 3/4
        assertEquals(Regimen.Stim.GIVE, Regimen.stimulant(low, t(5, 6), s, zone))
        assertEquals(Regimen.Stim.NOT_MORNING, Regimen.stimulant(low, t(5, 14), s, zone))
        val given = low + ev(EventType.STIMULANT, t(5, 6))
        // Next day even if low again: every other day at most.
        val lowAgain = given + ev(EventType.FOOD_PUT, t(5, 14), 30.0) + ev(EventType.FOOD_LEFT, t(6, 2), 10.0, true)
        assertEquals(Regimen.Stim.RECENT, Regimen.stimulant(lowAgain, t(6, 6), s, zone))
        val enough = listOf(ev(EventType.FOOD_PUT, t(4, 14), 60.0), ev(EventType.FOOD_LEFT, t(5, 2), 5.0, true))
        assertEquals(Regimen.Stim.NOT_NEEDED, Regimen.stimulant(enough, t(5, 6), s, zone))
        assertEquals(Regimen.Stim.NOT_NEEDED, Regimen.stimulant(emptyList(), t(5, 6), s, zone))
    }

    @Test fun stimWindowIsMorningOnly() {
        assertTrue(Regimen.inStimWindow(t(5, 6), zone))
        assertFalse(Regimen.inStimWindow(t(5, 12), zone))
        assertFalse(Regimen.inStimWindow(t(5, 4), zone))
    }

    @Test fun nextPicksEarliestEffective() {
        val n = Regimen.next(anchors + ev(EventType.FLUID, t(3, 20), 100.0), t(4, 12), s, zone)
        assertEquals(Item.FLUID, n.item) // 13:00 fluids before 15:00 크레메진
    }

    @Test fun kremezinReferenceRangeEdges() {
        val i = Item.KREMEZIN
        assertEquals(Band.TOO_EARLY, Regimen.band(i, 8 * HOUR - 1))
        assertEquals(Band.CAUTION, Regimen.band(i, 8 * HOUR))
        assertEquals(Band.SAFE, Regimen.band(i, 10 * HOUR))
        assertEquals(Band.SAFE, Regimen.band(i, 14 * HOUR))
        assertEquals(Band.CAUTION, Regimen.band(i, 14 * HOUR + 1))
        assertEquals(Band.CAUTION, Regimen.band(i, 16 * HOUR))
        assertEquals(Band.TOO_LATE, Regimen.band(i, 16 * HOUR + 1))
    }

    @Test fun onlyKremezinHasAReferenceRange() {
        // The vet gave 위장약 and 수액 no interval, so no band, no early/late state, no two-tap.
        assertNull(Regimen.band(Item.GI, 3 * HOUR))
        assertNull(Regimen.band(Item.FLUID, 3 * HOUR))
        val g = Regimen.due(Item.GI, anchors, t(4, 7), s, zone)
        assertEquals(0L, g.earliestAt)
        assertEquals(0L, g.latestAt)
    }

    @Test fun fluidDoneTodayFlag() {
        assertFalse(Regimen.due(Item.FLUID, anchors, t(4, 12), s, zone).doneToday)
        val ev = anchors + ev(EventType.FLUID, t(4, 12), 100.0)
        assertTrue(Regimen.due(Item.FLUID, ev, t(4, 14), s, zone).doneToday)
        // Owner-day cut at 05:00: tomorrow 06:00 is a new day.
        assertFalse(Regimen.due(Item.FLUID, ev, t(5, 6), s, zone).doneToday)
    }

    @Test fun separationTiers() {
        assertEquals(Regimen.Sep.RED, Regimen.separation(t(4, 15), listOf(t(4, 14, 40))))
        assertEquals(Regimen.Sep.AMBER, Regimen.separation(t(4, 15), listOf(t(4, 16, 30))))
        assertEquals(Regimen.Sep.FREE, Regimen.separation(t(4, 15), listOf(t(4, 12, 59))))
    }

    @Test fun adjustmentRetargetsUntilDoseIsLogged() {
        val adj = ev(EventType.ADJUST_KREMEZIN, t(4, 15), t(4, 17).toDouble())
        val d = Regimen.due(Item.KREMEZIN, anchors + adj, t(4, 14), s, zone)
        assertEquals(t(4, 17), d.effectiveAt)
        assertEquals(t(4, 11), d.earliestAt)
        assertEquals(t(4, 19), d.latestAt)
        // Logging the dose moves the chain; the old adjustment no longer matches and is ignored.
        val after = anchors + adj + ev(EventType.KREMEZIN, t(4, 17, 5))
        val n = Regimen.due(Item.KREMEZIN, after, t(4, 18), s, zone)
        assertEquals(0L, n.adjustedAt)
        assertEquals(t(5, 5, 5), n.effectiveAt)
    }

    @Test fun nextPickUsesAdjustedTarget() {
        // Fluids pushed from 13:00 to 16:00 → 크레메진 15:00 becomes the next action.
        val adj = ev(EventType.ADJUST_FLUID, t(4, 13), t(4, 16).toDouble())
        assertEquals(Item.KREMEZIN, Regimen.next(anchors + ev(EventType.FLUID, t(3, 20), 100.0) + adj, t(4, 12), s, zone).item)
    }

    @Test fun overrideNotesAreDerivedFromTheLog() {
        // 크레메진 12 min after a 17:00 위장약 → 간격 위반 (12분).
        val g17 = ev(EventType.GI, t(4, 17), 1.0)
        val k = ev(EventType.KREMEZIN, t(4, 17, 12))
        val sep = Regimen.violations(anchors + g17 + k, k, s, zone)
        assertEquals(listOf(Violation(Violation.Kind.SEPARATION, 12 * MIN)), sep)
        // 크레메진 6 h after the last 크레메진 (before its reference range) → 조기 투약 (6시간 간격).
        val k9 = ev(EventType.KREMEZIN, t(4, 9))
        assertEquals(listOf(Violation(Violation.Kind.EARLY, 6 * HOUR)), Regimen.violations(anchors + k9, k9, s, zone))
        // 위장약 has no range: 3 h after → only 같은 날 2회차 (3시간 간격); 6 h after → nothing.
        val g8 = ev(EventType.GI, t(4, 8), 1.0)
        assertEquals(listOf(Violation(Violation.Kind.REPEAT_GAP, 3 * HOUR)), Regimen.violations(anchors + g8, g8, s, zone))
        val g11 = ev(EventType.GI, t(4, 11), 1.0)
        assertTrue(Regimen.violations(anchors + g11, g11, s, zone).isEmpty())
        // Second 수액 in one owner-day → 같은 날 2회차; next owner-day is clean.
        val f1 = ev(EventType.FLUID, t(4, 13), 100.0)
        val f2 = ev(EventType.FLUID, t(4, 20), 100.0)
        val f3 = ev(EventType.FLUID, t(5, 13), 100.0)
        assertEquals(Violation.Kind.REPEAT_DAY, Regimen.violations(listOf(f1, f2), f2, s, zone).single().kind)
        assertTrue(Regimen.violations(listOf(f1, f3), f3, s, zone).isEmpty())
        // 식욕촉진제 at 14:00 → 조건 밖 (오후); 09:00 is clean.
        val st = ev(EventType.STIMULANT, t(4, 14))
        assertEquals(listOf(Violation(Violation.Kind.OFF_WINDOW, 14)), Regimen.violations(listOf(st), st, s, zone))
        val ok = ev(EventType.STIMULANT, t(4, 9))
        assertTrue(Regimen.violations(listOf(ok), ok, s, zone).isEmpty())
    }

    @Test fun editingDoseTimeMovesTheChain() {
        val k = anchors[0]
        val edited = Regimen.edited(k, t(4, 4), 1.0, "")
        val ev = anchors.map { if (it.id == k.id) edited else it }
        assertEquals(t(4, 16), Regimen.due(Item.KREMEZIN, ev, t(4, 12), s, zone).dueAt)
        assertEquals(Origin.EDITED, edited.origin)
        assertEquals(k.id, edited.id)
    }

    @Test fun editingGiAmountFlipsAlternation() {
        val g = anchors[1]
        assertEquals(1.0, Regimen.due(Item.GI, anchors, t(4, 12), s, zone).amount, 0.0)
        val ev = anchors.map { if (it.id == g.id) Regimen.edited(g, g.at, 1.0, "") else it }
        assertEquals(2.0, Regimen.due(Item.GI, ev, t(4, 12), s, zone).amount, 0.0)
    }

    @Test fun manualAddIsTaggedAndStaysTaggedAfterEdit() {
        val m = Regimen.manual(EventType.FLUID, t(4, 12), 100.0)
        assertEquals(Origin.MANUAL, m.origin)
        assertEquals(Origin.MANUAL, Regimen.edited(m.copy(id = 9), t(4, 11), 100.0, "").origin)
        // Backfilled fluid counts for today: next fluid moves to tomorrow.
        val ev = anchors + m
        assertEquals(t(5, 13), Regimen.due(Item.FLUID, ev, t(4, 13), s, zone).dueAt)
    }

    @Test fun futureTimeIsRefused() {
        assertEquals(EditError.FUTURE, Regimen.checkEdit(anchors, Regimen.edited(anchors[0], t(4, 13), 1.0, ""), t(4, 12)))
        assertNull(Regimen.checkEdit(anchors, Regimen.edited(anchors[0], t(4, 12), 1.0, ""), t(4, 12)))
    }

    @Test fun leftoverCannotExceedWhatWasPutOut() {
        val put = ev(EventType.FOOD_PUT, t(4, 8), 30.0)
        val left = ev(EventType.FOOD_LEFT, t(4, 10), 10.0)
        val ev = listOf(put, left)
        assertEquals(EditError.LEFT_OVER_PUT, Regimen.checkEdit(ev, Regimen.edited(left, left.at, 40.0, ""), t(4, 12)))
        assertNull(Regimen.checkEdit(ev, Regimen.edited(left, left.at, 30.0, ""), t(4, 12)))
        // Shrinking the 담았어요 below the later 남은 양 breaks the pair too.
        assertEquals(EditError.LEFT_OVER_PUT, Regimen.checkEdit(ev, Regimen.edited(put, put.at, 5.0, ""), t(4, 12)))
        // Moving the 남은 양 before the 담았어요 leaves nothing in the bowl to weigh back.
        assertEquals(EditError.LEFT_OVER_PUT, Regimen.checkEdit(ev, Regimen.edited(left, t(4, 7), 10.0, ""), t(4, 12)))
        assertEquals(EditError.LEFT_OVER_PUT, Regimen.checkEdit(ev, Regimen.manual(EventType.FOOD_LEFT, t(4, 7), 1.0), t(4, 12)))
    }
}
