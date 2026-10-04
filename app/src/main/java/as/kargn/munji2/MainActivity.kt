package `as`.kargn.munji2

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import `as`.kargn.munji2.alarm.Reminders
import `as`.kargn.munji2.data.Store
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.ui.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Set by the widget when its item is locked: the app shows that item as the big button.
    private val focus = MutableStateFlow<Item?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDebug(intent)
        handleFocus(intent)
        val store = Store.get(this)
        setContent {
            val theme by store.theme.collectAsState()
            val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                val wide = calculateWindowSizeClass(this).widthSizeClass != WindowWidthSizeClass.Compact
                HomeScreen(
                    store = store,
                    focus = focus,
                    twoPane = wide,
                    requestNotifications = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) },
                    openExactAlarmSettings = { startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebug(intent)
        handleFocus(intent)
    }

    override fun onResume() {
        super.onResume()
        // Opening the app counts as "the owner picked the phone up": re-post silent reminders loudly.
        lifecycleScope.launch {
            Reminders.onUserPresent(applicationContext)
            Reminders.changed(applicationContext)
        }
    }

    private fun handleFocus(intent: Intent?) {
        intent?.getStringExtra(EXTRA_FOCUS)?.let { name -> focus.value = Item.entries.firstOrNull { it.name == name } }
        intent?.removeExtra(EXTRA_FOCUS)
    }

    /** `am start ... --ez debug_fire true` posts the next reminder now; ignored in release builds. */
    private fun handleDebug(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.getBooleanExtra("debug_fire", false) != true) return
        intent.removeExtra("debug_fire")
        lifecycleScope.launch { Reminders.debugFire(applicationContext) }
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
    }
}
