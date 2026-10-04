package `as`.kargn.munji2.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `as`.kargn.munji2.MainActivity
import `as`.kargn.munji2.R
import `as`.kargn.munji2.data.Store
import `as`.kargn.munji2.domain.Due
import `as`.kargn.munji2.domain.Fmt
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.domain.Regimen
import `as`.kargn.munji2.widget.MunjiWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

object Reminders {
    const val CH_LOUD = "dose_loud"
    const val CH_QUIET = "dose_quiet"
    const val CH_LOGGED = "dose_logged"
    const val ACTION_FIRE = "as.kargn.munji2.FIRE"
    const val ACTION_WIDGET = "as.kargn.munji2.WIDGET"
    const val ACTION_GIVE = "as.kargn.munji2.GIVE"
    const val ACTION_SNOOZE = "as.kargn.munji2.SNOOZE"
    const val ACTION_UNDO = "as.kargn.munji2.UNDO"
    const val EXTRA_ITEM = "item"
    const val EXTRA_EVENT = "event"
    private const val SNOOZE_MS = 30 * Regimen.MIN
    // The "기록됨 · 되돌리기" notification disappears on its own after 5 min.
    private const val LOGGED_TIMEOUT_MS = 5 * Regimen.MIN

    private fun dueId(item: Item) = 100 + item.ordinal
    private fun loggedId(item: Item) = 200 + item.ordinal

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val loud = NotificationChannel(CH_LOUD, ctx.getString(R.string.ch_loud), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_loud_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
        val quiet = NotificationChannel(CH_QUIET, ctx.getString(R.string.ch_quiet), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.ch_quiet_desc)
            setSound(null, null)
            enableVibration(false)
        }
        val logged = NotificationChannel(CH_LOGGED, ctx.getString(R.string.ch_logged), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.ch_logged_desc)
            setSound(null, null)
        }
        nm.createNotificationChannels(listOf(loud, quiet, logged))
    }

    private fun zone() = ZoneId.systemDefault()

    private fun pi(ctx: Context, action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Recompute every item's next alarm from the event log; called after any change. */
    suspend fun reschedule(ctx: Context) {
        val store = Store.get(ctx)
        val events = store.all()
        val s = store.settings.value
        val now = System.currentTimeMillis()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val dues = Regimen.allDues(events, now, s, zone())
        for (d in dues) {
            val at = Regimen.nextFire(d, store.snoozeUntil(d.item), now, s, zone())
            val p = pi(ctx, ACTION_FIRE, d.item.ordinal) { putExtra(EXTRA_ITEM, d.item.name) }
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
        // Widget text changes at due/lock boundaries and every hour of lateness; refresh then (non-wakeup).
        val changes = dues.flatMap { d ->
            listOf(d.dueAt, d.lockedUntil, d.effectiveAt) + if (now > d.dueAt) listOf(d.dueAt + ((now - d.dueAt) / Regimen.HOUR + 1) * Regimen.HOUR) else emptyList()
        }.filter { it > now }
        changes.minOrNull()?.let { am.setWindow(AlarmManager.RTC, it, Regimen.MIN, pi(ctx, ACTION_WIDGET, 50)) }
    }

    suspend fun fire(ctx: Context, item: Item, forceLoud: Boolean = false) {
        val store = Store.get(ctx)
        val events = store.all()
        val s = store.settings.value
        val now = System.currentTimeMillis()
        val d = Regimen.due(item, events, now, s, zone())
        val snooze = store.snoozeUntil(item)
        // Stale alarm (dose already given, or snoozed further out): just reschedule.
        if (!forceLoud && (now < d.effectiveAt - Regimen.MIN || now < snooze - Regimen.MIN)) {
            reschedule(ctx); return
        }
        val loud = forceLoud || Regimen.loud(d, now, s, zone())
        post(ctx, d, now, loud)
        store.setSilent(item, !loud)
        reschedule(ctx)
        MunjiWidget.refresh(ctx)
    }

    /** First unlock (or app resume) after a silent post re-posts it with sound. */
    suspend fun onUserPresent(ctx: Context) {
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        val events = store.all()
        for (item in Item.entries) {
            if (!store.isSilent(item)) continue
            val d = Regimen.due(item, events, now, store.settings.value, zone())
            if (now >= d.effectiveAt) post(ctx, d, now, loud = true)
            store.setSilent(item, false)
        }
    }

    private fun post(ctx: Context, d: Due, now: Long, loud: Boolean) {
        val title = ctx.getString(R.string.notif_due_title, Fmt.dose(ctx, d))
        val text = if (now - d.dueAt >= Regimen.MIN) ctx.getString(R.string.notif_late_text, Fmt.hm(d.dueAt), Fmt.dur(ctx, now - d.dueAt))
        else ctx.getString(R.string.notif_due_text, Fmt.hm(d.dueAt))
        val n = NotificationCompat.Builder(ctx, if (loud) CH_LOUD else CH_QUIET)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setSilent(!loud)
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(activityPi(ctx))
            .addAction(0, ctx.getString(R.string.action_given), pi(ctx, ACTION_GIVE, 10 + d.item.ordinal) { putExtra(EXTRA_ITEM, d.item.name) })
            .addAction(0, ctx.getString(R.string.action_snooze), pi(ctx, ACTION_SNOOZE, 20 + d.item.ordinal) { putExtra(EXTRA_ITEM, d.item.name) })
            .build()
        notify(ctx, dueId(d.item), n)
    }

    private fun activityPi(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }

    /**
     * Logs a dose at the tap time, always. Guardrails are warnings the caller confirmed (second tap in
     * the app, or the notification tap itself); the crossed rule shows up as a derived note in the log.
     */
    suspend fun give(ctx: Context, item: Item, confirm: Boolean): Long {
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        val d = Regimen.due(item, store.all(), now, store.settings.value, zone())
        val id = store.add(item.type, now, d.amount)
        store.setSnooze(item, 0L)
        store.setSilent(item, false)
        NotificationManagerCompat.from(ctx).cancel(dueId(item))
        if (confirm) {
            val n = NotificationCompat.Builder(ctx, CH_LOGGED)
                .setSmallIcon(android.R.drawable.checkbox_on_background)
                .setContentTitle(ctx.getString(R.string.notif_logged, Fmt.dose(ctx, d), Fmt.hm(now)))
                .setSilent(true)
                .setTimeoutAfter(LOGGED_TIMEOUT_MS)
                .setContentIntent(activityPi(ctx))
                .addAction(0, ctx.getString(R.string.action_undo), pi(ctx, ACTION_UNDO, 30 + item.ordinal) {
                    putExtra(EXTRA_ITEM, item.name); putExtra(EXTRA_EVENT, id)
                })
                .build()
            notify(ctx, loggedId(item), n)
        }
        changed(ctx)
        return id
    }

    suspend fun snooze(ctx: Context, item: Item) {
        Store.get(ctx).setSnooze(item, System.currentTimeMillis() + SNOOZE_MS)
        NotificationManagerCompat.from(ctx).cancel(dueId(item))
        reschedule(ctx)
    }

    suspend fun undo(ctx: Context, id: Long, item: Item?) {
        Store.get(ctx).undo(id)
        item?.let { NotificationManagerCompat.from(ctx).cancel(loggedId(it)) }
        changed(ctx)
    }

    /** Every log change: alarms and widget follow the event log. */
    suspend fun changed(ctx: Context) {
        reschedule(ctx)
        MunjiWidget.refresh(ctx)
    }

    /** Debug-only path: post the next due as a loud reminder right now. */
    suspend fun debugFire(ctx: Context) {
        val store = Store.get(ctx)
        val d = Regimen.next(store.all(), System.currentTimeMillis(), store.settings.value, zone())
        fire(ctx, d.item, forceLoud = true)
    }
}

fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val p = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { p.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val item = intent.getStringExtra(Reminders.EXTRA_ITEM)?.let { Item.valueOf(it) }
        async {
            when (intent.action) {
                Reminders.ACTION_FIRE -> item?.let { Reminders.fire(ctx, it) }
                Reminders.ACTION_WIDGET -> Reminders.changed(ctx)
                Reminders.ACTION_GIVE -> item?.let { Reminders.give(ctx, it, confirm = true) }
                Reminders.ACTION_SNOOZE -> item?.let { Reminders.snooze(ctx, it) }
                Reminders.ACTION_UNDO -> Reminders.undo(ctx, intent.getLongExtra(Reminders.EXTRA_EVENT, -1), item)
            }
        }
    }
}

class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> async { Reminders.changed(context.applicationContext) }
        }
    }
}
