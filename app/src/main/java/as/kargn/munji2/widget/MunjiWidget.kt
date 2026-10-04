package `as`.kargn.munji2.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `as`.kargn.munji2.MainActivity
import `as`.kargn.munji2.R
import `as`.kargn.munji2.alarm.Reminders
import `as`.kargn.munji2.data.Store
import `as`.kargn.munji2.domain.Fmt
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.domain.Regimen
import java.time.ZoneId

private data class WidgetText(val item: Item, val title: String, val status: String, val other: String, val locked: Boolean)

class MunjiWidget : GlanceAppWidget() {
    // 2×2 on the cover screen and 4×2 when stretched.
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent { GlanceTheme { Content(context, data) } }
    }

    private suspend fun load(ctx: Context): WidgetText {
        val store = Store.get(ctx)
        val events = store.all()
        val now = System.currentTimeMillis()
        val z = ZoneId.systemDefault()
        val dues = Regimen.allDues(events, now, store.settings.value, z).sortedBy { it.effectiveAt }
        val d = dues.first()
        val o = dues[1]
        val locked = Fmt.blocked(d, now)
        // Overdue shows "늦음 2시간 · 크레메진" first so lateness reads before the name.
        val title = if (!locked && now - d.dueAt >= Regimen.MIN)
            ctx.getString(R.string.widget_late, Fmt.dur(ctx, now - d.dueAt), Fmt.dose(ctx, d))
        else ctx.getString(R.string.item_at, Fmt.dose(ctx, d), Fmt.hm(d.effectiveAt))
        val status = if (locked || Fmt.early(d, now) || d.item == Item.FLUID) Fmt.status(ctx, d, now) else ctx.getString(R.string.notif_due_text, Fmt.hm(d.dueAt))
        return WidgetText(d.item, title, status, ctx.getString(R.string.widget_other, Fmt.dose(ctx, o), Fmt.hm(o.effectiveAt)), locked)
    }

    @Composable
    private fun Content(ctx: Context, w: WidgetText) {
        val wide = LocalSize.current.width >= 250.dp
        // No two-tap on the widget: a locked item opens the app focused on it, where the second tap lives.
        val give = if (w.locked) actionStartActivity<MainActivity>(actionParametersOf(FOCUS to w.item.name))
        else actionRunCallback<GiveCallback>(actionParametersOf(ITEM to w.item.name))
        val open = actionStartActivity<MainActivity>()
        val bg = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp)
        val titleStyle = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val small = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
        val label = ctx.getString(if (w.locked) R.string.widget_open else R.string.action_given)
        if (wide) {
            Row(bg, verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().clickable(open)) {
                    Text(w.title, style = titleStyle, maxLines = 2)
                    Text(w.status, style = small, maxLines = 1)
                    Spacer(GlanceModifier.height(6.dp))
                    Text(w.other, style = small, maxLines = 1)
                }
                Spacer(GlanceModifier.width(8.dp))
                Button(label, onClick = give, modifier = GlanceModifier.height(56.dp))
            }
        } else {
            Column(bg) {
                Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(open)) {
                    Text(w.title, style = titleStyle, maxLines = 2)
                    Text(w.status, style = small, maxLines = 1)
                    Text(w.other, style = small, maxLines = 1)
                }
                Button(label, onClick = give, modifier = GlanceModifier.fillMaxWidth())
            }
        }
    }

    companion object {
        val ITEM = ActionParameters.Key<String>("item")
        /** Activity extra (Glance passes action parameters as extras keyed by name). */
        val FOCUS = ActionParameters.Key<String>(MainActivity.EXTRA_FOCUS)
        suspend fun refresh(ctx: Context) = MunjiWidget().updateAll(ctx)
    }
}

class GiveCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val item = parameters[MunjiWidget.ITEM]?.let { Item.valueOf(it) } ?: return
        Reminders.give(context.applicationContext, item, confirm = true)
    }
}

class MunjiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MunjiWidget()
}

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Reminders.changed(applicationContext)
        return Result.success()
    }
}
