package `as`.kargn.munji2

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import `as`.kargn.munji2.alarm.Reminders
import `as`.kargn.munji2.alarm.async
import `as`.kargn.munji2.data.Store
import `as`.kargn.munji2.widget.WidgetWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannels(this)
        // USER_PRESENT cannot be declared in the manifest; this only works while the process lives,
        // MainActivity.onResume covers the rest.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                async { Reminders.onUserPresent(context.applicationContext) }
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        // WorkManager's 15-min floor is a safety net only; exact boundaries come from AlarmManager.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).build(),
        )
        CoroutineScope(Dispatchers.IO).launch {
            Reminders.changed(this@App)
        }
    }
}
