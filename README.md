# Barometric

A native Android app for pressure history and daily symptoms. Android 10+; no account, backend, ads, or paid API.

## Use

- **Today:** interactive three-source pressure graph in **kPa**, numbered vertical axes, seven tick positions on each axis at standard text size, and day / 2 days / week / month / 90-day views. Drag or tap to inspect values beside the source dots and the exact selection time on the horizontal axis. Campus station pressure uses the left axis; sea-level pressure uses the right. Location and pressure-history headings are hidden. Pull down at the top to refresh.
- **Daily statistics:** USU high, low, and difference appear first. Expand to show other sources; previous/next day navigation remains available.
- **Symptoms:** headache, nausea, dizziness, light sensitivity, and Sound each use a discrete 0–5 slider. Tapping or dragging chooses a whole number; changes save on release. The adjacent value can also save zero when an unlogged slider is already at its minimum. The compact form opens for an incomplete day, can be dismissed, and can be reopened through its Symptoms button. Daily steps share its header. Zero means none; unlogged is distinct.
- **Calendar:** each date shows the weighted average of entered symptoms and its corresponding green-to-red color. Missing and zero-weight symptoms are excluded from the average. Settings provides five integer weights from 0–10, each defaulting to 10. The score is the sum of severity × weight divided by the total weight of entered symptoms; equal weights count equally. If that total is zero, no score is shown. Changes recalculate all days and monthly summaries without changing recorded severities. Tap a date to inspect pressure and edit all five ratings. Future dates cannot be rated.
- **Settings:** location search, pressure units (kPa by default), daily symptom weights, optional daily Health Connect steps, JSON export, source information, and data deletion. Insights and the widget instruction panel are hidden.

## Home-screen widget

Long-press the Android home screen, choose **Widgets**, and add **Barometric**. Select **High, low, difference**, **Pressure graph**, or **Both**. Resize it as needed; tap **Edit** to change its display. Tapping the graph opens the app for interactive inspection. Widget graphs overlay all available sources; widget high/low/difference use one preferred source, named beneath the values. The widget follows the app's location and units and updates with hourly weather work and app refreshes. Android may delay updates while saving battery. Widget options are stored separately for each placement.

This uses Android's standard [configurable widget lifecycle](https://developer.android.com/develop/ui/views/appwidgets/configuration) and [widget update APIs](https://developer.android.com/develop/ui/views/appwidgets/advanced), without an additional widget library.

Hyrum, Utah is the default. The primary local station is the USU Campus Environmental Observatory in Logan, about 12.5 km from Hyrum. NOAA Logan-Cache Airport (KLGU) and Open-Meteo are independent alternatives. Campus is offered within 50 km; elsewhere the app uses available NOAA stations within 50 km and Open-Meteo estimates. Three providers are not guaranteed outside Cache Valley.

The app follows the phone's light/dark mode, including automatic schedules, startup backgrounds and system bars. Android 12+ uses the phone's dynamic colors; older phones use the app's light/dark palettes. Headache severity colors and graph source colors retain their meanings. Widgets also follow light/dark mode.

## Build

Open the folder in Android Studio and use a compatible **Java 17** runtime, Android SDK 36, and SDK build tools 35.0.0. Gradle is pinned by the included wrapper.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintDebug
# With an emulator or test device connected:
./gradlew :app:connectedDebugAndroidTest
```

The optimized installable APK is generated at `app/build/outputs/apk/release/app-release.apk`; the debuggable APK is at `app/build/outputs/apk/debug/app-debug.apk`. Both initial builds are signed with the local Android development key. Keep that key if you need future builds to update the same installation without uninstalling it. Use a dedicated private signing key before any store release.

On this workspace, build dependencies were installed under the ignored `.tools` folder. Use:

```sh
JAVA_HOME="$PWD/.tools/java17/Contents/Home" ./gradlew -g .tools/gradle-home :app:assembleDebug
```

`local.properties` points to the local SDK and is intentionally ignored. On another machine, Android Studio generates it; otherwise set `sdk.dir` to your Android SDK directory.

## Data and behavior

- Room stores locations and their effective dates, provider metadata, timestamped pressure readings, daily symptoms, and optional health totals. DataStore holds preferences. Database version 4 supports five integer symptom ratings through sequential migrations from versions 1, 2, and 3. The new sound sensitivity field starts unlogged; previous entries are unchanged. Headache ratings, weather history, locations, and health totals are retained. Older No responses become 0; older Yes responses become unlogged because presence cannot establish severity. There are no Yes/No symptom fields in the current model.
- Readings store **hPa** and an explicit pressure kind. Campus readings arrive in kPa with UTC epoch milliseconds and are converted to hPa/UTC seconds. They are **station pressure**, while NOAA and Open-Meteo graph sea-level pressure. Campus sensor specifications list 0.01 kPa resolution and ±0.03 kPa accuracy at 20°C; extra decimal places in downloaded readings do not imply greater accuracy. Legacy River Heights readings and metadata remain in exports, but are excluded from the active graph, widgets, and insights.
- High/low/range use available samples from a single provider. Historical days need readings in at least 75% of their actual hourly slots to qualify for pressure insights; DST days have 23 or 25 slots. Today's values are provisional. Larger sampling intervals may miss brief extremes.
- All source lines share the time axis. Campus station pressure and the other sources' sea-level pressure use separately labeled pressure scales with the same span: equal changes have equal visual height; crossing lines do not mean equal pressure. Raw selected values and daily statistics retain their true pressure basis. Widgets use the same scaling. Day, week, month (30 days), and 90-day views plot cached individual readings. Tapping or dragging inspects nearby readings with their actual observation times. Missing intervals break each source's line. No future observations are fabricated.
- The first refresh requests 90 days where available. The public campus chart supplies only the latest seven days of three-minute readings; older detailed history accumulates as the app downloads and keeps observations. Its four-year daily averages cannot supply daily highs/lows and are not used. Switching ranges, navigating dates, and opening historical days fetch available data for the requested interval. Hourly WorkManager jobs refresh recent data. Requests upsert cached readings without deleting history after a failure. Android may defer background work to save battery.
- After the initial load, foreground refreshes update the recent week and the selected graph interval. Overlapping intervals are merged into one download; viewing a distant historical date does not download every intervening day. Graph inspection uses binary searches, and line paths are cached until readings or chart geometry change. Unchanged source metadata is not rewritten; background refreshes update widgets once.
- Widget summary values prefer complete nearby station days, then NOAA, then modeled data. An incomplete source is used when no complete alternative exists; source readings are never averaged together. Credits, source precision and status remain available in Settings.
- NOAA's live API exposes up to 30 days and caps individual responses at 400 records. History requests use bounded end-date windows and continue past capped responses so month views retain all available reports. Open-Meteo archives provide older modeled history, with the recent model feed covering the archive delay. Older NOAA gaps remain gaps.
- USU data is read from its currently public campus chart JSON endpoint without an API key. It is not a versioned public API and may change. Its parser is isolated and failures leave existing readings intact.
- Location changes do not rewrite the location/timezone on existing daily entries. Retrospective entries use recorded location periods; Today displays the selected weather location.
- Steps are an opt-in, read-only Health Connect integration. Enable sharing from Fitbit/Google Health or another writer app first. The app uses Health Connect's aggregation and priority rules rather than summing competing trackers. No direct Fitbit OAuth integration is required.
- Initially import the permitted recent 30-day window, then sync forward and revisit the last week for corrections. Each step total covers the location’s midnight-to-midnight calendar day, up to the current time for today. Missing records stay null. Sleep is no longer requested or synchronized; older imported sleep totals remain stored and exportable. Permissions are checked on each foreground sync; no background health permission is requested.
- Cloud backup and device transfer are disabled. Weather requests contain the selected weather coordinates, never headache ratings or health records.

## JSON export

Settings opens Android's file picker to save `barometric-YYYY-MM-DD.json`. No broad storage permission is required. Schema version 4 includes export time, unit definitions, symptom encoding, settings (including the five symptom weights), locations, location periods, sources and status, typed readings, per-source daily high/low/range, pressure kind and coverage, and daily entries including symptoms and optional health totals/origins. `rating` retains headache severity; `nausea`, `dizziness`, `lightSensitivity`, and `soundSensitivity` are nullable integer ratings from 0 to 5. Timestamps are Unix seconds in UTC; dates and timezones are retained. Missing values are null. The export includes all local history, not just the visible graph. Import/restore is outside this version.

## Installing updates

Install the new APK **over the existing app**. Keep the application ID and signing key; later builds use higher version codes and Room migrations. Do not uninstall first: Android removes local app data on uninstall. Export JSON before uninstalling or changing phones. Import/restore remains outside this version.

## Validation

Unit tests cover unit/timezone conversion, source isolation, high-minus-low, DST coverage, null/malformed provider fields, duplicate downloads, persisted zero versus missing ratings, historical location preservation, future-date rejection, export round trips, tied ranks, and insufficient/constant correlation data. Emulator tests cover daily ratings, past-date entry, navigation, export access, and vertical scrolling over the graph.

Fitbit end-to-end transfer requires a device/account writing steps to Health Connect; an emulator alone cannot prove that transfer. No weather data is fabricated for screenshots or app behavior.

## Sources and attribution

- [USU Campus Environmental Observatory](https://qanr.usu.edu/weather/graphical-data/barometric-pressure), [sensor specifications](https://qanr.usu.edu/weather/general-info/campus-observatory-specifications)
- [NOAA Aviation Weather Center API](https://aviationweather.gov/data/api/)
- [Open-Meteo](https://open-meteo.com/) weather estimates, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); ECMWF/ERA5 datasets as described in [Open-Meteo documentation](https://open-meteo.com/en/docs/historical-weather-api).
- [GeoNames](https://www.geonames.org/) locations via [Open-Meteo geocoding](https://open-meteo.com/en/docs/geocoding-api).

Use Open-Meteo's free endpoint only within its [noncommercial terms and rate limits](https://open-meteo.com/en/terms). No keys are embedded in this app.
