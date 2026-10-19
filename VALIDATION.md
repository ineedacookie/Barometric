# Validation — 2026-10-10

## 0.4.2 Sound rating

- Added a fifth discrete 0–5 slider labeled **Sound** to the shared Today/calendar form. It saves through the existing symptom repository; calendar averages include it when logged and exclude it when missing.
- Database version 4 adds nullable `soundSensitivity` through migration 3→4. The existing migration chain remains configured, with no destructive fallback. Existing fields are not rewritten; new Sound values start unlogged.
- JSON schema version 4 includes `soundSensitivity` and its 0–5 encoding. Updated the existing test expectations for the fifth symptom and export schema; automated tests were not run for this update.
- App version 0.4.2 uses version code 8 for installation over the previous release.
- Release build succeeded and APK v2 signature verified with the same certificate as 0.4.1. Deliverable: `artifacts/Barometric-0.4.2.apk`, 3,571,660 bytes. SHA-256: `ce1045f27df133455b6fac4b21d5c32b3d3a1dec4674a70f21a0763b537d9b10`. Emulator checks were not run for this update.

## 0.4.1 efficiency checks

- Deliverable: `artifacts/Barometric-0.4.1.apk`, 3,571,656 bytes, version code 7. SHA-256: `d6f3dc0a05e1caf2f24a8039119468a0b84aa8ed230f4c7fceee7d3bb83f9abb`.
- Audited foreground/background downloads, graph inspection/rendering, database writes, and steps syncing before building the release. First load still requests 90 days. Later foreground refreshes request the recent week plus the visible range, merging overlap into one interval and keeping distant historical intervals separate. Removed the second, overlapping foreground download and duplicate widget update from background work.
- Graph selection now uses binary search on cached sorted timestamps. Regression tests compare against a full scan over 100,000 readings and require at most 21 timestamp reads per selection. Graph line paths are cached across inspection gestures, rebuilding for changed data or geometry. Removed an unnecessary source metadata write and nested weighted widget measurements.
- Cleaned previous generated app build outputs with `:app:clean`. Final `:app:testDebugUnitTest :app:lintDebug` passed before the release build: **27 tests, 0 failures; lint 0 errors, 46 advisory warnings, 0 performance issues**. Tests include real SQLite migrations, persistence, pressure calculations, refresh interval planning, and graph lookup edge cases.
- The first lint run hit a Compose detector initialization error in the reused Gradle process. A fresh Gradle process completed successfully; no lint checks were disabled.
- Removed all six older distributed APKs (0.1.0 through 0.4.0). Kept preview images and the signing key. Version code increased to 7; database schema remains version 3 with its existing migrations.
- Fresh `:app:assembleRelease` succeeded; APK v2 signature verified. Certificate SHA-256 matches the previous release: `7877bf1c624dd5daf588e70d6c3ca386a480c32591363ff2ce9dc1bb329bd1a5`.
- Installed over the emulator's existing app with `adb install -r`, without uninstalling. Launch returned success. No physical-device battery or frame-rate benchmark was performed.
- Automatic approval review rejected a requested copy of the emulator's private database into the workspace because it could include personal health data. No private database copy was made for this update; migration and persistence checks use test fixtures.
- These are code and regression checks; battery consumption and frame rates on a physical phone were not measured.

## 0.4.0 update

- Deliverable: `artifacts/Barometric-0.4.0.apk`, approximately 3.4 MB, version code 6.
- SHA-256: `8db98e272c3b536235cecf7026b629f4836a8fa155df4330e27c82b182250880`.
- All four symptoms use 0–5 sliders. Missing values remain distinct from 0. The form opens for an incomplete day, can be dismissed, and can be reopened. Calendar colors and numbers use the average of entered symptoms only. Daily step totals share the form header.
- kPa is the new default; display values are converted from stored hPa by dividing by 10. Added the two-day graph view, numeric vertical axes, additional ticks on both axes, labels beside selected dots, and exact time on the horizontal axis. Removed the below-graph reading breakdown, main location/pressure-history headings, Insights navigation item, and Settings widget instruction panel. Daily statistics show USU first, with an expansion control for other sources. Added standard Material pull-to-refresh.
- Health Connect requests and aggregates steps only, using each location's midnight-to-midnight calendar day. Existing imported sleep totals remain stored and exported.
- `:app:testDebugUnitTest :app:assembleRelease` succeeded: **23 unit tests, 0 failures**. New checks open real SQLite schemas from database versions 1 and 2 through Room's migration chain to version 3, verify retained entries/locations/periods/sources/readings, and reopen the migrated database. Also checked symptom averages and kPa conversion.
- Version 2 No responses migrate to 0. Yes responses become unlogged because they do not establish severity, as requested; no Yes/No symptom fields remain. Existing headache ratings and health totals retain their values.
- APK v2 signature verification passed. Its signer certificate matches 0.3.0: SHA-256 `7877bf1c624dd5daf588e70d6c3ca386a480c32591363ff2ce9dc1bb329bd1a5`.
- Installed over emulator version **0.2.0 without uninstalling**. Database migrated 1→3; all original entry fields were unchanged. All 16,203 original reading identities, three original source identities, the location and location period remained. Refresh added campus observations.
- Inspected the real-data graph and four-slider form. Manually checked two-day selection, horizontal graph dragging, and form dismissal/reopening. Existing instrumented tests were updated for sliders and hidden Insights; they were not run for this update.
- Pulling down at the top advanced the campus source refresh timestamp. Confirmed the installed package declares `READ_STEPS` and no `READ_SLEEP` permission. Stopped the emulator after these checks.
- Android removes local app data on uninstall. Update by installing over the existing app, with the same signing key. No destructive database fallback is configured.

## 0.3.0 update

- Deliverable: `artifacts/Barometric-0.3.0.apk`, approximately 3.4 MB, signed with the existing development key.
- SHA-256: `84f12adae4645c2354b55b127b04bb21cfb6f1d1c238d3e099747952fcda1330`.
- Replaced active River Heights downloads with the public USU campus chart feed. Live source research returned 3,219 consecutive three-minute readings with the latest observation about five minutes old at the time of the check. This confirms feed availability, not independent sensor calibration.
- Campus station pressure remains typed separately from NOAA/Open-Meteo sea-level pressure. App and widget graphs use labeled separate scales with the same span; actual values remain unchanged. The public campus feed has seven days of detailed history. Cached readings accumulate; older daily averages are not substituted for highs/lows. River Heights history remains in full exports.
- Added optional Yes/No logging for nausea, dizziness, and light sensitivity alongside the existing 0–5 headache rating. Today and calendar details save changes immediately; calendar letters identify reported symptoms. Null is distinct from No.
- Added Room migration 1→2, preserving previous columns and adding nullable symptom fields. JSON schema 2 includes symptoms, their encoding, and summary pressure kinds. The existing export schema assertion was updated; tests were not run.
- `:app:assembleRelease` succeeded and APK v2 signature verification passed. No emulator, migration runtime, or automated tests were run for this update.

## 0.2.2 update

- Deliverable: `artifacts/Barometric-0.2.2.apk`, approximately 3.4 MB, signed with the existing development key.
- SHA-256: `d01dade9963553e97552446a6a1fc874f6a32244b026918a855dbfd8a7d9cbd8`.
- Compose follows the system's light/dark mode and uses dynamic phone colors on Android 12+. Added a native night theme so startup backgrounds and system bars also match. Widget colors use system tones on Android 12+ and refresh when the running app receives configuration changes.
- `:app:assembleRelease` succeeded and APK signature verification passed. Tests were not run for this update.

## 0.2.1 update

- Deliverable: `artifacts/Barometric-0.2.1.apk`, approximately 3.4 MB, signed with the existing development key.
- SHA-256: `180ac6aed41656faa8e1e5023349f6809b0b46173dc79c74730fb4f12c3568be`.
- Centered the front-screen **Headache** heading and 0–5 buttons. Removed the footer's Clear button and status text; the selected value remains highlighted. Buttons continue to replace the day's saved rating when another value is tapped.
- `:app:assembleRelease` succeeded and APK signature verification passed. The existing rating UI test was updated for replacement behavior; tests were not run for this update.

## 0.2.0 update

- Deliverable: `artifacts/Barometric-0.2.0.apk`, approximately 3.4 MB, development-signed with the same key for an in-place update.
- SHA-256: `d4161bac009baae7e3b75a35116a717c45ead206e23f0dcaa08f1def293b643e`.
- `:app:assembleRelease` succeeded; APK v2 signature verified and installed over 0.1.0 without deleting the saved zero rating.
- Visually previewed the graph-first screen with three source colors and the fixed rating footer, the month graph, and the real-data Android home-screen widget in Both mode.
- Range requests load individual readings for all available providers, upgrading USU history to 10-minute data. NOAA requests use bounded end dates and pagination around its 400-record cap. A live end-date query returned the requested historical week.
- Credits and provider details stay in Settings. Current-pressure headline removed.
- Existing emulator assertions were updated for the new footer and graph labels. Automated tests were not rerun for this update; results below apply to 0.1.0.

## 0.1.0 deliverable

- `artifacts/Barometric-0.1.0.apk`: optimized, development-signed Android 10+ APK, approximately 3.3 MB.
- SHA-256: `97d7e1a87ec4b277d96285330e04da3cce9bbfa6956b3fbfc5004cde0798c95d`.
- APK v2 signature verified; final release installed and launched on Android 15 / API 35.

## Automated results

Command: `:app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest :app:assembleRelease`.

- 19 unit tests passed: pressure, providers, correlations, storage, export, dates and DST.
- 4 emulator tests passed: rating/clearing, historical editing, navigation, and vertical chart scrolling.
- Lint: 0 errors; 19 advisory warnings, primarily newer dependency versions and Android KTX suggestions.
- Release build successful.

## Manual checks

- Real USU, NOAA and Open-Meteo data loaded for Hyrum; sources remained separate.
- Found USU metric dashboard rounding to whole kPa. Switched to its finer imperial feed, normalized to hPa, and labeled its 0.1 inHg precision.
- Saved rating 0, restarted the release process, and confirmed it remained saved.
- Disabled emulator connectivity, restarted, and confirmed cached weather remained available.
- Saved JSON using Android's document picker. Final export contained 9,045 typed readings, 197 source/day summaries, three sources, location/timezone/settings, and the saved zero rating. Verified unique reading identities and every high-minus-low calculation.
- Health Connect steps request showed only the steps permission. Denial returned safely with syncing off.
- Visually inspected large text at 200% with dark theme; restored default emulator settings afterward.
- Screenshots in `artifacts/` use real weather and explicitly entered test ratings.

## Remaining device verification

Real Fitbit-to-Health Connect steps/sleep transfer requires a connected account and device and has not been verified. Screen-reader actions are implemented; a full human TalkBack session has not been performed. Public weather services may change or provide incomplete readings; cached data and source status remain visible.
