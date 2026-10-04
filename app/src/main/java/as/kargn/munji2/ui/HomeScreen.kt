package `as`.kargn.munji2.ui

import android.app.AlarmManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import `as`.kargn.munji2.R
import `as`.kargn.munji2.alarm.Reminders
import `as`.kargn.munji2.data.Store
import `as`.kargn.munji2.domain.Band
import `as`.kargn.munji2.domain.Due
import `as`.kargn.munji2.domain.EditError
import `as`.kargn.munji2.domain.Event
import `as`.kargn.munji2.domain.EventType
import `as`.kargn.munji2.domain.Fmt
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.domain.Regimen
import `as`.kargn.munji2.domain.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Sheet { NONE, LOG, SETTINGS, FOOD_PUT, FOOD_LEFT, WET, WEIGHT, ADJUST, EDIT }

// A warned give stays armed this long waiting for the second tap, then reverts.
private const val ARM_MS = 5_000L
private const val STIM_KEY = "STIM"

// Content is capped so the unfolded 2448 px screen never gets stretched controls.
private val MAX_PANE = 560.dp
// Material 3 has no warning role; a fixed amber marks "allowed but before the recommended 2 h".
val AMBER = Color(0xFFFFB300)
val ON_AMBER = Color(0xFF231A00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: Store, focus: MutableStateFlow<Item?>, twoPane: Boolean, requestNotifications: () -> Unit, openExactAlarmSettings: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val events by store.events.collectAsState(initial = null)
    val settings by store.settings.collectAsState()
    val theme by store.theme.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var adjustItem by remember { mutableStateOf(Item.KREMEZIN) }
    // Row being edited; null with Sheet.EDIT means 직접 추가.
    var editing by remember { mutableStateOf<Event?>(null) }
    // Item name (or STIM_KEY) waiting for the confirming second tap.
    var armed by remember { mutableStateOf<String?>(null) }
    val focused by focus.collectAsState()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    // Countdown and lateness are shown to the minute; a 15 s tick keeps them honest.
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LaunchedEffect(armed) { if (armed != null) { delay(ARM_MS); armed = null } }

    val undoMsg = stringResource(R.string.undone)
    val redoLabel = stringResource(R.string.action_redo)
    val editedMsg = stringResource(R.string.edited_snack)
    val undoLabel = stringResource(R.string.action_undo)
    // 5 s window, not the 4 s/10 s presets. True when the action was tapped.
    suspend fun snack5(msg: String, action: String): Boolean = coroutineScope {
        val job = launch { delay(5_000); snack.currentSnackbarData?.dismiss() }
        val r = snack.showSnackbar(msg, action, duration = SnackbarDuration.Indefinite)
        job.cancel()
        r == SnackbarResult.ActionPerformed
    }
    val undo: (Event) -> Unit = { e ->
        scope.launch {
            Reminders.undo(ctx, e.id, null)
            if (snack5(undoMsg, redoLabel)) { store.restore(e.id); Reminders.changed(ctx) }
        }
    }
    val log: (EventType, Double, Boolean) -> Unit = { type, amount, discarded ->
        scope.launch { store.add(type, System.currentTimeMillis(), amount, discarded); Reminders.changed(ctx) }
    }
    // Same recompute path as a live tap: every due, alternation, separation and alarm derive from the log.
    val saveEdit: (Event?, Event) -> Unit = { old, new ->
        scope.launch {
            if (old == null) { store.add(new); Reminders.changed(ctx); return@launch }
            store.update(new)
            Reminders.changed(ctx)
            // Prior values live only in this coroutine, for the snackbar's 되돌리기.
            if (snack5(editedMsg, undoLabel)) { store.update(old); Reminders.changed(ctx) }
        }
    }
    val give: (Item) -> Unit = { item ->
        scope.launch { Reminders.give(ctx, item, confirm = false) }
        if (focused == item) focus.value = null
    }
    // Guardrails warn, never block: a warned give needs a second tap within ARM_MS.
    val tap: (Due) -> Unit = { d ->
        if (Fmt.blocked(d, System.currentTimeMillis()) && armed != d.item.name) armed = d.item.name
        else { armed = null; give(d.item) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (!twoPane) TextButton({ sheet = Sheet.LOG }) { Text(stringResource(R.string.menu_log)) }
                    TextButton({ events?.let { Export.share(ctx, it, settings) } }) { Text(stringResource(R.string.menu_export)) }
                    TextButton({ sheet = Sheet.SETTINGS }) { Text(stringResource(R.string.menu_settings)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val ev = events
        if (ev == null) {
            // Initializing: Room has not emitted yet.
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            return@Scaffold
        }
        val zone = ZoneId.systemDefault()
        // A widget tap on a locked item puts that item on the big button.
        val dues = Regimen.allDues(ev, now, settings, zone).sortedWith(compareBy({ it.item != focused }, { it.effectiveAt }))
        val edit: (Event) -> Unit = { editing = it; sheet = Sheet.EDIT }
        val main: @Composable (Modifier) -> Unit = { m ->
            Column(m.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Warnings(ev, now, settings, requestNotifications, openExactAlarmSettings, stimArmed = armed == STIM_KEY) { morning ->
                    if (morning || armed == STIM_KEY) { armed = null; log(EventType.STIMULANT, 1.0, false) } else armed = STIM_KEY
                }
                Timeline(ev, dues, now, settings)
                PrimaryAction(dues.first(), now, armed == dues.first().item.name, tap)
                Others(dues.drop(1), now, armed, tap, { sheet = it }, stimArmed = armed == STIM_KEY) {
                    if (Regimen.inStimWindow(System.currentTimeMillis(), zone) || armed == STIM_KEY) { armed = null; log(EventType.STIMULANT, 1.0, false) } else armed = STIM_KEY
                }
                AdjustEntry { adjustItem = it; sheet = Sheet.ADJUST }
                Summary(ev, now, settings)
                if (!twoPane) {
                    HorizontalDivider()
                    ev.take(6).forEach { LogRow(it, ev, settings, undo, edit) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        if (twoPane) {
            Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.Center) {
                main(Modifier.weight(1f).widthIn(max = MAX_PANE))
                LogList(ev, settings, undo, edit, { editing = null; sheet = Sheet.EDIT }, Modifier.weight(1f).widthIn(max = MAX_PANE))
            }
        } else {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
                main(Modifier.widthIn(max = MAX_PANE).fillMaxWidth())
            }
        }

        when (sheet) {
            Sheet.NONE -> {}
            Sheet.LOG -> ModalBottomSheet({ sheet = Sheet.NONE }) {
                LogList(ev, settings, undo, edit, { editing = null; sheet = Sheet.EDIT }, Modifier.heightIn(max = 640.dp))
            }
            Sheet.EDIT -> ModalBottomSheet({ sheet = Sheet.NONE }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                val old = editing
                EditSheet(
                    old, ev, now,
                    onSave = { saveEdit(old, it); sheet = Sheet.NONE },
                    onDelete = { undo(it); sheet = Sheet.NONE },
                )
            }
            Sheet.SETTINGS -> ModalBottomSheet({ sheet = Sheet.NONE }) {
                SettingsSheet(settings, theme, onSettings = { store.saveSettings(it); scope.launch { Reminders.changed(ctx) } }, onTheme = store::saveTheme)
            }
            Sheet.ADJUST -> ModalBottomSheet({ sheet = Sheet.NONE }) {
                AdjustSheet(
                    adjustItem, ev, now, settings,
                    onSave = { chain, target, note -> scope.launch { store.add(adjustItem.adjustType, chain, target.toDouble(), note = note); Reminders.changed(ctx) }; sheet = Sheet.NONE },
                    onCancel = { e -> undo(e); sheet = Sheet.NONE },
                )
            }
            Sheet.FOOD_PUT, Sheet.WET, Sheet.WEIGHT, Sheet.FOOD_LEFT -> ModalBottomSheet({ sheet = Sheet.NONE }) {
                AmountSheet(sheet) { amount, discarded ->
                    val type = when (sheet) {
                        Sheet.FOOD_PUT -> EventType.FOOD_PUT
                        Sheet.WET -> EventType.WET
                        Sheet.WEIGHT -> EventType.WEIGHT
                        else -> EventType.FOOD_LEFT
                    }
                    log(type, amount, discarded)
                    sheet = Sheet.NONE
                }
            }
        }
    }
}

@Composable
private fun Warnings(ev: List<Event>, now: Long, s: Settings, requestNotifications: () -> Unit, openExact: () -> Unit, stimArmed: Boolean, onStim: (morning: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val z = ZoneId.systemDefault()
    if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
        Banner(stringResource(R.string.warn_notif), stringResource(R.string.warn_notif_btn), requestNotifications)
    }
    if (!ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        Banner(stringResource(R.string.warn_exact), stringResource(R.string.warn_exact_btn), openExact)
    }
    ev.firstOrNull { it.type == EventType.WEIGHT }?.let {
        if (it.amount <= Regimen.WEIGHT_CALL_KG) Banner(stringResource(R.string.warn_weight, it.amount), null, {})
    }
    val yesterday = Regimen.ownerDay(now, s, z).minusDays(1)
    val intake = (Regimen.dryIntakeByDay(ev, s, z)[yesterday] ?: 0.0).toInt()
    when (Regimen.stimulant(ev, now, s, z)) {
        Regimen.Stim.GIVE -> Banner(stringResource(R.string.warn_stim, intake), stringResource(R.string.give_stim), { onStim(true) })
        // Outside the morning window the give is a two-tap override, logged as 조건 밖.
        Regimen.Stim.NOT_MORNING -> Banner(
            stringResource(R.string.warn_stim_late, intake),
            if (stimArmed) stringResource(R.string.tap_again, stringResource(R.string.warn_not_morning)) else stringResource(R.string.give_stim),
            { onStim(false) }, armed = stimArmed,
        )
        else -> {}
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit, armed: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                if (armed) Button(onAction, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(action) }
                else TextButton(onAction) { Text(action) }
            }
        }
    }
}

/**
 * Button colours: armed (waiting for the second tap) = red, warned = muted like the old disabled look,
 * inside the recommended 2 h = amber, otherwise the default.
 */
@Composable
private fun giveColors(d: Due, now: Long, armed: Boolean, tonal: Boolean) = when {
    armed -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    Fmt.blocked(d, now) -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Fmt.early(d, now) -> ButtonDefaults.buttonColors(containerColor = AMBER, contentColor = ON_AMBER)
    tonal -> ButtonDefaults.filledTonalButtonColors()
    else -> ButtonDefaults.buttonColors()
}

@Composable
private fun PrimaryAction(d: Due, now: Long, armed: Boolean, tap: (Due) -> Unit) {
    val ctx = LocalContext.current
    val early = Fmt.early(d, now)
    val other = Fmt.name(ctx, if (d.item == Item.KREMEZIN) Item.GI else Item.KREMEZIN)
    Button(
        onClick = { tap(d) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        contentPadding = PaddingValues(16.dp),
        colors = giveColors(d, now, armed, tonal = false),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.action_give_label, Fmt.dose(ctx, d)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (armed) stringResource(R.string.tap_again, Fmt.warn(ctx, d, now)) else Fmt.status(ctx, d, now), style = MaterialTheme.typography.bodyMedium)
            if (d.lockedUntil > now) Text(stringResource(R.string.lock_reason, other), style = MaterialTheme.typography.bodySmall)
            if (early) Text(stringResource(R.string.early_reason, other), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Others(rest: List<Due>, now: Long, armed: String?, tap: (Due) -> Unit, open: (Sheet) -> Unit, stimArmed: Boolean, onStim: () -> Unit) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rest.forEach { d ->
            val isArmed = armed == d.item.name
            FilledTonalButton(
                { tap(d) }, Modifier.weight(1f).heightIn(min = 64.dp), contentPadding = PaddingValues(8.dp),
                colors = giveColors(d, now, isArmed, tonal = true),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.dose(ctx, d), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isArmed) stringResource(R.string.tap_again, Fmt.warn(ctx, d, now)) else Fmt.status(ctx, d, now),
                        style = MaterialTheme.typography.labelSmall, maxLines = 3,
                    )
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ open(Sheet.FOOD_PUT) }) { Text(stringResource(R.string.btn_food_put)) }
        OutlinedButton({ open(Sheet.FOOD_LEFT) }) { Text(stringResource(R.string.btn_food_left)) }
        OutlinedButton({ open(Sheet.WET) }) { Text(stringResource(R.string.btn_wet)) }
        OutlinedButton({ open(Sheet.WEIGHT) }) { Text(stringResource(R.string.btn_weight)) }
    }
    // Always reachable. Own line, so the longer armed label grows to the right instead of wrapping away from
    // the finger. Outside the morning window it is a two-tap override, labelled 조건 밖 in the log.
    if (stimArmed) Button(onStim, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
        Text(stringResource(R.string.tap_again, stringResource(R.string.warn_not_morning)))
    } else OutlinedButton(onStim) { Text(stringResource(R.string.btn_stim)) }
}

@Composable
private fun Summary(ev: List<Event>, now: Long, s: Settings) {
    val z = ZoneId.systemDefault()
    val today = Regimen.ownerDay(now, s, z)
    val dry = (Regimen.dryIntakeByDay(ev, s, z)[today] ?: 0.0).toInt()
    val wet = (Regimen.wetByDay(ev, s, z)[today] ?: 0.0).toInt()
    val bowl = Regimen.bowl(ev).toInt()
    val weight = ev.firstOrNull { it.type == EventType.WEIGHT }
    val style = MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.sum_food, dry, Regimen.FOOD_TARGET_G.toInt(), wet), style = style)
        if (bowl > 0) Text(stringResource(R.string.sum_bowl, bowl), style = style)
        Text(
            if (weight != null) stringResource(R.string.sum_weight, weight.amount, Regimen.WEIGHT_GOAL_KG)
            else stringResource(R.string.sum_weight_none, Regimen.WEIGHT_GOAL_KG),
            style = style,
        )
    }
}

@Composable
private fun LogRow(e: Event, all: List<Event>, s: Settings, undo: (Event) -> Unit, edit: (Event) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { edit(e) }, verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.hm(e.at), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
        Text(Fmt.eventLabel(ctx, e, all, s), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton({ undo(e) }) { Text(stringResource(R.string.action_undo)) }
    }
}

@Composable
private fun LogList(ev: List<Event>, s: Settings, undo: (Event) -> Unit, edit: (Event) -> Unit, add: () -> Unit, modifier: Modifier = Modifier) {
    val z = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN) }
    val dry = Regimen.dryIntakeByDay(ev, s, z)
    val wet = Regimen.wetByDay(ev, s, z)
    val byDay = ev.groupBy { Regimen.ownerDay(it.at, s, z) }
    LazyColumn(modifier.padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(add) { Text(stringResource(R.string.add_entry)) }
            }
        }
        if (ev.isEmpty()) item { Text(stringResource(R.string.log_empty)) }
        byDay.forEach { (day, list) ->
            item {
                Text(
                    stringResource(R.string.day_header, day.format(fmt), (dry[day] ?: 0.0).toInt(), (wet[day] ?: 0.0).toInt()),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(list, key = { it.id }) { LogRow(it, ev, s, undo, edit) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AmountSheet(kind: Sheet, onDone: (Double, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var askLeft by remember { mutableStateOf(false) }
    val value = text.replace(',', '.').toDoubleOrNull()
    val title = when (kind) {
        Sheet.FOOD_PUT -> R.string.sheet_food_put
        Sheet.FOOD_LEFT -> R.string.sheet_food_left
        Sheet.WET -> R.string.sheet_wet
        else -> R.string.sheet_weight
    }
    // Quick picks cover the usual portions so most logs are one tap.
    val presets = when (kind) {
        Sheet.FOOD_PUT -> listOf("10", "15", "20", "30")
        Sheet.FOOD_LEFT -> listOf("0", "5", "10")
        Sheet.WET -> listOf("20", "40", "80")
        else -> emptyList()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (askLeft && value != null) {
            Text(stringResource(R.string.left_question, value.toInt()), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ onDone(value, true) }, Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.btn_discard)) }
                FilledTonalButton({ onDone(value, false) }, Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.btn_keep)) }
            }
            return@Column
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(if (kind == Sheet.WEIGHT) R.string.input_kg else R.string.input_g)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (presets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p -> OutlinedButton({ text = p }) { Text(p) } }
        }
        Button(
            { if (kind == Sheet.FOOD_LEFT) askLeft = true else onDone(value!!, false) },
            Modifier.fillMaxWidth().height(56.dp), enabled = value != null && value >= 0,
        ) { Text(stringResource(R.string.btn_record)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(s: Settings, theme: Int, onSettings: (Settings) -> Unit, onTheme: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        Stepper(stringResource(R.string.set_sleep_start), s.sleepStartMin) { onSettings(s.copy(sleepStartMin = it)) }
        Stepper(stringResource(R.string.set_sleep_end), s.sleepEndMin) { onSettings(s.copy(sleepEndMin = it)) }
        Text(stringResource(R.string.set_sleep_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Stepper(stringResource(R.string.set_day_cut), s.dayCutMin) { onSettings(s.copy(dayCutMin = it)) }
        Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                SegmentedButton(theme == i, { onTheme(i) }, SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(stringResource(l)) }
            }
        }
    }
}

/** 30-minute steps, wrapping around midnight. */
@Composable
private fun Stepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton({ onChange((minutes - 30 + 1440) % 1440) }) { Text(stringResource(R.string.minus)) }
        Text("%02d:%02d".format(minutes / 60, minutes % 60), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ onChange((minutes + 30) % 1440) }) { Text(stringResource(R.string.plus)) }
    }
}

@Composable
private fun AdjustEntry(open: (Item) -> Unit) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.adjust_entry), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Item.entries.forEach { item -> TextButton({ open(item) }) { Text(Fmt.name(ctx, item)) } }
    }
}

/**
 * Moves only the next dose. Live line shows the gap from the last same-item dose (크레메진: against its
 * reference range, green/amber/red)
 * and the separation from the other medicine. Red still saves, as `경고 무시하고 저장`, and the warning is
 * stored as the adjustment row's note so the export shows it.
 */
@Composable
private fun AdjustSheet(item: Item, ev: List<Event>, now: Long, s: Settings, onSave: (Long, Long, String) -> Unit, onCancel: (Event) -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val d = Regimen.due(item, ev, now, s, zone)
    val existing = ev.filter { it.type == item.adjustType && it.at == d.dueAt }.maxByOrNull { it.id }
    var target by remember { mutableLongStateOf(if (d.adjustedAt > 0) d.adjustedAt else d.dueAt) }
    val last = Regimen.lastOf(ev, item)
    // Only 크레메진 has a (reference) range; 위장약/수액 show the plain gap.
    val band = last?.let { Regimen.band(item, target - it.at) }
    val otherItem = when (item) { Item.KREMEZIN -> Item.GI; Item.GI -> Item.KREMEZIN; Item.FLUID -> null }
    val otherTimes = otherItem?.let { o -> listOfNotNull(Regimen.lastOf(ev, o)?.at, Regimen.due(o, ev, now, s, zone).effectiveAt) } ?: emptyList()
    val sep = Regimen.separation(target, otherTimes)
    val green = Color(0xFF2E7D32)
    val redColor = MaterialTheme.colorScheme.error
    // Earlier than the reference range mirrors the two-tap on the give button; later is only amber.
    val redBand = band == Band.TOO_EARLY
    val red = redBand || sep == Regimen.Sep.RED
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.adjust_title, Fmt.name(ctx, item)), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.adjust_chain, Fmt.hm(d.dueAt)), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.adjust_target, Fmt.hm(target)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.adjust_minus_1h to -60, R.string.adjust_minus_30 to -30, R.string.adjust_plus_30 to 30, R.string.adjust_plus_1h to 60).forEach { (l, m) ->
                OutlinedButton({ target += m * Regimen.MIN }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(l)) }
            }
        }
        val gapLine = last?.let {
            val verdict = when (band) {
                Band.SAFE -> stringResource(R.string.adjust_safe)
                Band.CAUTION -> stringResource(R.string.adjust_caution)
                Band.TOO_EARLY -> stringResource(R.string.adjust_ref_early)
                Band.TOO_LATE -> stringResource(R.string.adjust_ref_late)
                null -> null
            }
            val gap = stringResource(R.string.adjust_gap, Fmt.name(ctx, item), Fmt.hm(it.at), Fmt.hm(target), Fmt.dur(ctx, target - it.at))
            if (verdict == null) gap else "$gap · $verdict"
        }
        if (gapLine != null) Text(
            gapLine,
            color = when (band) { Band.SAFE -> green; Band.CAUTION, Band.TOO_LATE -> AMBER; Band.TOO_EARLY -> redColor; null -> MaterialTheme.colorScheme.onSurface },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
        )
        val sepLine = if (otherItem != null && sep != Regimen.Sep.FREE) {
            val near = otherTimes.minBy { kotlin.math.abs(target - it) }
            stringResource(if (sep == Regimen.Sep.RED) R.string.adjust_sep_red else R.string.adjust_sep_amber, Fmt.name(ctx, otherItem), Fmt.hm(near))
        } else null
        if (sepLine != null) Text(
            sepLine,
            color = if (sep == Regimen.Sep.RED) redColor else AMBER, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
        )
        // Only the red lines are overridden warnings worth keeping on the row.
        val note = listOfNotNull(gapLine?.takeIf { redBand }, sepLine?.takeIf { sep == Regimen.Sep.RED }).joinToString(" · ")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                { onSave(d.dueAt, target, note) }, Modifier.weight(1f).height(56.dp), enabled = target != d.dueAt,
                colors = if (red) ButtonDefaults.buttonColors(containerColor = redColor, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(stringResource(if (red) R.string.adjust_save_override else R.string.adjust_save)) }
            if (existing != null) OutlinedButton({ onCancel(existing) }, Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.adjust_cancel)) }
        }
    }
}

private val ADDABLE = listOf(
    EventType.KREMEZIN, EventType.GI, EventType.FLUID, EventType.FOOD_PUT, EventType.FOOD_LEFT, EventType.WET, EventType.WEIGHT, EventType.STIMULANT,
)

private fun unitOf(type: EventType): Int? = when (type) {
    EventType.KREMEZIN, EventType.GI -> R.string.input_pills
    EventType.FLUID -> R.string.input_ml
    EventType.FOOD_PUT, EventType.FOOD_LEFT, EventType.WET -> R.string.input_g
    EventType.WEIGHT -> R.string.input_kg
    else -> null
}

private fun typeName(ctx: android.content.Context, type: EventType): String = ctx.getString(
    when (type) {
        EventType.KREMEZIN -> R.string.item_kremezin
        EventType.GI -> R.string.item_gi
        EventType.FLUID -> R.string.item_fluid
        EventType.FOOD_PUT -> R.string.type_food_put
        EventType.FOOD_LEFT -> R.string.type_food_left
        EventType.WET -> R.string.type_wet
        EventType.WEIGHT -> R.string.type_weight
        EventType.STIMULANT -> R.string.type_stimulant
        EventType.ADJUST_KREMEZIN, EventType.ADJUST_GI, EventType.ADJUST_FLUID -> R.string.adjust_entry
    }
)

private fun amountText(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/**
 * Edit a row ([initial] != null) or backfill one (`직접 추가`). Any past time is allowed; the future and
 * a 남은 양 above what was in the bowl are refused inline. ADJUST_* rows only take a note: their time is
 * the chain due they replace, not something the owner did.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditSheet(initial: Event?, ev: List<Event>, now: Long, onSave: (Event) -> Unit, onDelete: (Event) -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(initial?.at ?: now).atZone(zone)
    var type by remember { mutableStateOf(initial?.type ?: EventType.KREMEZIN) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    val time = androidx.compose.material3.rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    // Backfill defaults to what the schedule would give now (위장약 alternation, 수액 100 mL).
    fun defaultAmount(t: EventType): String = when (t) {
        EventType.KREMEZIN -> "1"
        EventType.GI -> amountText(Regimen.nextGiAmount(ev))
        EventType.FLUID -> amountText(Regimen.FLUID_ML)
        EventType.STIMULANT -> "1"
        else -> ""
    }
    var amount by remember { mutableStateOf(initial?.let { amountText(it.amount) } ?: defaultAmount(type)) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    val isAdjust = initial != null && Item.entries.any { it.adjustType == initial.type }
    val unit = unitOf(type)
    val value = if (unit == null) initial?.amount ?: 1.0 else amount.replace(',', '.').toDoubleOrNull()
    val at = if (isAdjust) initial!!.at else date.atTime(time.hour, time.minute).atZone(zone).toInstant().toEpochMilli()
    val candidate = value?.let { v ->
        if (initial == null) Regimen.manual(type, at, v, note.trim()) else Regimen.edited(initial, at, if (isAdjust) initial.amount else v, note.trim())
    }
    val err = candidate?.let { Regimen.checkEdit(ev, it, System.currentTimeMillis()) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (initial == null) stringResource(R.string.add_title) else stringResource(R.string.edit_title, Fmt.eventLabel(ctx, initial)),
            style = MaterialTheme.typography.titleLarge,
        )
        if (initial == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ADDABLE.forEach { t ->
                androidx.compose.material3.FilterChip(type == t, { type = t; amount = defaultAmount(t) }, { Text(typeName(ctx, t)) })
            }
        }
        if (!isAdjust) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ date = date.minusDays(1) }) { Text(stringResource(R.string.edit_date_prev)) }
                Text(date.format(dayFmt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                OutlinedButton({ date = date.plusDays(1) }) { Text(stringResource(R.string.edit_date_next)) }
            }
            androidx.compose.material3.TimeInput(time)
            if (unit != null) OutlinedTextField(
                amount, { amount = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(unit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.edit_note)) })
        when (err) {
            EditError.FUTURE -> Text(stringResource(R.string.edit_future), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            EditError.LEFT_OVER_PUT -> Text(stringResource(R.string.edit_left_over), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            null -> {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ onSave(candidate!!) }, Modifier.weight(1f).height(56.dp), enabled = candidate != null && err == null && (value ?: -1.0) >= 0) {
                Text(stringResource(R.string.edit_save))
            }
            if (initial != null) OutlinedButton({ onDelete(initial) }, Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.edit_delete)) }
        }
    }
}
