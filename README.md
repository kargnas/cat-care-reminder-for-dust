# cat-care-reminder-for-dust

An Android app that reminds you when to give a cat its medicine, fluids and food, and keeps a log of what was actually given. It was made for one cat, Dust (먼지), for long-term home care.

## Features

- **Home screen.** A 24-hour timeline of given and upcoming doses. The next thing to do is the big button below it. Other items, food, weight and the appetite stimulant are under that.
- **Schedules follow the last real dose.** The next dose is the last given time plus the interval. A late dose moves the whole chain later. A dose is never pulled earlier to catch up.
- **Alternating amounts.** A medicine given as 1 tablet, then 2, then 1, picks the next amount from the last amount given, not from the clock.
- **Gaps between medicines.** Two medicines can have a minimum gap and a recommended gap. Under the minimum, the button is greyed out. Between the minimum and the recommended gap, it is amber and shows the recommended time.
- **Warnings never block.** If a dose is early or too close to another medicine, the first tap shows the reason. A second tap within 5 seconds logs it anyway, and the log row records the warning.
- **Moving a dose.** The next dose of an item can be moved to another time. The sheet shows the gap from the previous dose and from the other medicine as you change it.
- **Everything can be fixed later.** Each row can be undone, edited (time, amount, note) or deleted. A dose given earlier and never logged can be added by hand. Edited and hand-added rows are tagged.
- **Food in grams.** Log what you put in the bowl and what was left. The app shows how much was eaten today against a daily target.
- **Appetite stimulant.** Always available as a button. After a day with low food intake, a banner suggests it the next morning. Outside the morning window it needs a second tap, and the row is tagged.
- **Weight.** Logged in kg. A banner appears when it drops below a set threshold.
- **Sleep window.** Reminders that come due while you sleep are posted silently. They turn loud when the window ends or when you unlock the phone. A dose more than 3 hours overdue rings anyway.
- **Day boundary.** The day starts at a set time (05:00 by default), so a dose at 02:00 counts toward the previous day.
- **Notifications with actions.** A due reminder has buttons to log it or to snooze 30 minutes. Unanswered reminders repeat at 15, 30 and 60 minutes, then every hour.
- **Home-screen widget** with the next item and its time.
- **Wide screens.** On a wide or unfolded screen, actions are on the left and the log is on the right.
- **Export** of the last 7 days as Markdown and the full log as CSV, through the share sheet.

## Stack

| Layer | Tech |
|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 (dynamic colour, system/light/dark) |
| Storage | Room, one `events` table, schemas in `app/schemas/` |
| Reminders | `AlarmManager.setExactAndAllowWhileIdle`, rescheduled on boot, time change and every log change |
| Widget | Jetpack Glance, refreshed by WorkManager |
| Logic | `domain/Regimen.kt`, plain Kotlin and `java.time`, 31 JUnit tests |

minSdk 31, targetSdk 35. The UI is in Korean.

## Build

```bash
git clone https://github.com/kargnas/cat-care-reminder-for-dust
cd cat-care-reminder-for-dust
./gradlew testDebugUnitTest assembleDebug   # JDK 17 + Android SDK 36
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The app starts with an empty log.

## Changing the routine

| What | Where |
|---|---|
| Intervals, gaps, amounts, food and weight thresholds | `app/src/main/java/as/kargn/munji2/domain/Regimen.kt` |
| Medicine names and all other text | `app/src/main/res/values/strings.xml` |
| Sleep window, day boundary, theme | Settings screen in the app |

## Disclaimer

This is a logbook and reminder tool. It does not give veterinary advice. Follow your vet.

## License

GPL-3.0. See [LICENSE](LICENSE).

Copyright (C) 2026 Sangrak Choi
