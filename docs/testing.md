# Testing

This document explains how to run Folirel's tests, how they are layered, what the synthetic fixtures are, and which scenarios the tests cover. Test names, class comments and source comments cite scenario IDs (`T15`, `N12`, `V3-R03`, ...); this page is where those IDs are defined. Requirement IDs (`R01`-`R20`) and screen IDs (`S00`-`S40`) are defined in [product-and-ux.md](product-and-ux.md).

## 1. Running the tests

Prerequisites (JDK, Android SDK platform and build-tools versions, `local.properties`) are in [building.md](building.md).

### JVM tests, lint and debug APK

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `.\gradlew.bat`. `tools/verify.sh` and `tools/verify.ps1` wrap the same command; pass `--device-tests` (shell) or `-DeviceTests` (PowerShell) to run the device tests after it. CI (`.github/workflows/android.yml`) runs this command and the helper-script tests below for a release only (a pushed `v*` tag or a manual start; see [building.md](building.md)), so run them locally before pushing.

Reports and results are written to:

| Output | Location |
|---|---|
| Unit test report (HTML) | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Unit test results (XML) | `app/build/test-results/testDebugUnitTest/` |
| Lint report | `app/build/reports/lint-results-debug.html` |
| Debug APK | `app/build/outputs/apk/debug/` |
| Device test report | `app/build/reports/androidTests/connected/` |

Lint is configured to abort the build on errors.

### Device and emulator tests

```sh
./gradlew :app:connectedDebugAndroidTest
```

These tests need an emulator or a device running Android 9 (API 28) or later. They use seeded data and, for the home flow tests, a local server inside the test process; they make no requests to the internet. Things to know:

- Use a test device or emulator, never a device that holds your own subscriptions. The tests clear the app's database (`clearAllTables`) and rewrite its settings, and Gradle uninstalls the app after the run, which also removes its data and per-app language setting.
- Keep the screen awake while the tests run (`adb shell svc power stayon true`).
- An API 28 emulator may need software rendering (`-gpu swiftshader_indirect`) for Compose tests to start reliably.
- Do not truncate a running `gradlew` with a pipe. If adb log files stay locked afterwards, run `gradlew --stop`, delete `app/build/outputs/androidTest-results` and rerun.
- If you could not run the device tests, say so in your pull request rather than assuming they pass.

### Helper-script tests

```sh
python3 -m unittest tools.test_compare_app_state
```

On Windows use `python -X utf8 -m unittest ...`. Run from the repository root. These tests need no device (a fake adb stands in for the real one).

### Development helper scripts

The scripts in `tools/` are development aids; they are not used by the app. The ones that talk to a device expect `adb` at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (Windows) and should be run with `python -X utf8`.

| Script | Purpose |
|---|---|
| `tools/verify.sh`, `tools/verify.ps1` | Run the Gradle verification command (and optionally the device tests). |
| `tools/adb_ui.py` | A tiny adb and uiautomator driver for manual emulator checks: `dump`, `texts`, `tap TEXT`, `tapxy X Y`, `type TEXT` (ASCII only), `key KEYCODE`, `shot PATH`, `wait TEXT [SECONDS]`, `swipe X1 Y1 X2 Y2 [MS]`, with an optional `-s SERIAL`. |
| `tools/compare_app_state.py` | Upgrade check. Compares two pulled copies of the app's Room database and DataStore and exits with code 1 when something that must not change did. `--mode install` (right after an overwrite install, before any fetch): nothing may differ in subscriptions, categories, articles, reading state or settings; the `category_preferences` table that schema 4 adds is expected and must be empty. `--mode after-refresh` (after one ordinary refresh): new articles are allowed and existing articles may change only the columns a refresh rewrites; their ID, key, feed and read/saved state may not change. Usage: `python -X utf8 tools/compare_app_state.py --mode install BEFORE_PREFIX AFTER_PREFIX`. |
| `tools/p1_restore_check.py` | Restart and process-restore check (`P1-T29`) on a test emulator: installs the APK and the test APK, stores an order, pins and display overrides through `P1RestoreSetup`, restarts the app and reads the screen back with uiautomator, then selects a category and types a search text, presses Home, kills the process (`am kill`) and starts the app again; it checks that Android restored the screen state, that the order, pins and overrides are intact, and (from the settings file's time and size, the decoded settings and the database rows) that nothing was written while the screen was rebuilt. Usage in the script's header; the exit code is 1 when a check fails. |
| `tools/make_icon_fixtures.py` | Regenerates the synthetic images in `fixtures/icons/`. |
| `P2SettingsTest`, `P2IntegrationTest` (instrumented) | Phase 2: the Settings pages, colour sets drawn in light and dark (read from the rendered preview), photos not tinted, Dynamic with and without Android 12 (each test skips on the other side), rapid palette and mode changes, switch rows, the segmented choices, the text-size slider (drag preview, store on release, accessibility action, reset, semantics), selection dialogs, the retention confirmation, the layout picker and which options each layout shows, global and per-category layout integration, an appearance change that moves nothing. `PalettesTest`, `PaletteSettingTest` (JVM): Classic pinned to the 0.6.0 values, contrast of every colour pair, the stored palette and text-size grid. `SettingsWriteQueueTest` (JVM): a failed write is reported once, nothing is stored or shown as saved, and later writes still happen in order. `P2SettingsTest#theSystemModeFollowsTheSystemNightSetting` (Android 10+): with System selected the colours follow `cmd uimode night yes/no`, and a forced mode ignores it. `tools/p2_palette_restore_check.py` (manual, `P2StateSetup` with `-e p2evidence true`): a chosen colour set and mode survive `am kill` and the restored Appearance page shows them (chosen rows, colours read from the screenshot, settings file unchanged). `P2ShotTest` is an evidence helper (skipped without `-e p2evidence true`). |
| `tools/fixtures/datastore-settings-0.3.0.preferences_pb` | A DataStore settings file as an older version wrote it, for upgrade checks. |

## 2. Test layers

| Layer | Where | What it uses | What it is for |
|---|---|---|---|
| JVM | `app/src/test` | JUnit, Robolectric (plain `Application`, no WorkManager side effects), in-memory Room, MockWebServer bound to `127.0.0.1`, a settable clock | Parsers, URL rules, address policy, repositories, refresh coordination, the full fetch pipeline against a local server |
| Static guards | `app/src/test` | Source scanning | Architecture boundaries (`T48`), no code path that sets read state automatically (`T29`), the HTTP-open confirmation setting is not read by network code (`U07`) |
| Device | `app/src/androidTest` | Compose UI tests on a real Android runtime, the production `AppContainer`, seeded data, and for the home flows a MockWebServer on the device with the real fetch pipeline (loopback is allowed in the test source set only; production always uses the public-only address policy) | UI flows, list layouts, pager, Room migrations, icon decoding, settings upgrade, performance measurement |
| Helper-script tests | `tools/test_*.py` | `unittest` with a fake adb | The upgrade-check scripts must really fail when they should |
| Manual | emulator or phone | `tools/adb_ui.py`, screenshots | TalkBack, 200% text, landscape, split screen, real feeds |

Rules that apply to every layer:

- Test the real stack. The JVM fetch tests go through the real HTTP client, parser, storage and Room; repositories are not replaced by mocks to make a test easy. The network tests use the same client factory the app uses.
- Fixture expectations are subset assertions over the normalised result. Database UUIDs and receive times are injected by the test, never asserted as fixed values.
- URL mapping to a local server is test-only and must not exist in release code. HTTP behaviour that fixtures cannot express (status codes, redirects, DNS, compression limits, concurrency, 5,000 articles) is generated in the tests.
- A synthetic fixture is test data; it never replaces the parser or the test.
- Do not weaken a check, add a skip, or change a fixture's expected values to make a test pass; if a requirement changes, change the requirement and the test together.
- Database schema changes need an explicit migration, an exported schema in `app/schemas/` and a migration test. Destructive fallback is not allowed.

### Device test classes

| Class | Covers |
|---|---|
| `HomeFlowTest` | First launch without data (`T01`), opening/scrolling/returning never marks read (`T29`), Custom Tabs path and URL-less article (`T32`), position after rotation and recreation (`T34`), layouts and images off (`T31`, `T37`) |
| `PagerAndMagazineTest` | Category pager and tabs (`N01`, `N03`, `N24`), layouts List / Compact list / Magazine persisted (`N09`), tabs following the drag, underline position, back to top (arrow and tab re-tap) in every layout, quick successive taps, distant tabs at large text (`V31-T03`) |
| `UxNextTest` | Position kept across layout changes (`U03`), category round trip in Magazine (`U04`), HTTP confirmation and "do not ask again" (`U06`), bulk unsubscribe and undo (`U10`, `U11`) |
| `CustomColorUiTest` | The Custom colour row and picker: Cancel, Apply, invalid code, touching the area, Reset (needs a device; compiled only in 0.9.3). |
| `ShareOverlayUiTest` | The Share to Subscribe dialog: Subscribe / Cancel / Feed details / Subscribe again and a problem message (needs a device; compiled only in 0.9.2). |
| `NewArticleBannerTest` | The new-article banner on the real list composable in all three layouts and both orders: one tap answers it for every new article of the list (it goes to the top, or to the first new article when oldest first), the cross only closes it, the arrow and the second tap on the tab answer it, only the list shown is answered, articles arriving after the tap are announced as new, arrivals at the head keep the reading place (`UXR-12` to `UXR-18`) |
| `HomeBannerFlowTest`, `HomeNoticeFlowTest` | The real thing end to end (`HomeFlowTestBase`): feeds served by a local MockWebServer, fetched by the real `LocalBackend`, saved by the real `FeedStore` into the app's Room database, driven by a real `RefreshCoordinator`, seen through the real `HomeViewModel` and drawn by the real `HomeScreen` (pager, tabs, list, banner, notices). Only the address policy differs from the app (loopback allowed in the test source set). Banner: one tap goes to the top and ends the notice, the cross, a report that lists answered articles again does not bring them back (`UXR-12`, `UXR-14`, `UXR-17`, `UXR-19`). Notices: partial failure notice and its end, no second notice after the screen is built again, a newer notice survives the older one's timer, the warning badge and offline mark stay, "Retry" and "Details", feeds the update did not reach, the notice and the banner together (`UXR-07` to `UXR-11`, `UXR-21`) |
| `BackToTopListTest` | End positions of back to top on the real list with 3,000 uneven rows in all three layouts: after arrivals, with arrivals during the movement, with many batches (`V4-R01`) |
| `BackToTopInterruptTest` | A touch ends the movement for good and arrivals afterwards do not start it again, repeated requests (the tick, and real presses with down and up on the arrow, which go through the list's own pointer input) are one movement, a press on a card still ends it, a replaced list is not moved by the request made for the one before (`UXR-04` to `UXR-06`) |
| `HomePagerMoveTest` | On the real home screen (pager, tabs, view model, Room): choosing another category during the movement back to the top, or during the four seconds of following, ends both for the page left even while the pager still shows it; the articles it held are released; arrivals do not make that page jump; coming back keeps the place and the new-article notice of the view model is untouched (`UXR-06`) |
| `UxrScrollTraceTest` | The way back to the top as a movement, frame by frame on the test clock with pictures loading for real: no stop on the way, no start over, no step away from the top, at most one skip, no late snap, in List, Compact list and Magazine (2 and 3 columns, with pictures), near and far, with batches arriving meanwhile (`UXR-01` to `UXR-03`) |
| `UxrRealtimeTest` | The same in real time: the arrow is pressed through the accessibility tree and the first visible article is timed on every change. Meant to be screen-recorded (see "Position traces and recordings") |
| `BackToTopTest` | Back to the top (arrow and tab re-tap) through the whole app with Room: articles arriving at the head during and right after the way up end on the newest article in all three layouts, and stop following as soon as the user touches the list: drag, finger put down without a drag, long press, article menu (`V4-R01`) |
| `MigrationTest` | Room v1 to v2, v2 to v3 and v3 to v4 migrations validated against exported schemas, data kept (v3 to v4 on a populated database: subscriptions in two categories, read and saved states, icons), chained 1 to 3 and 1 to 4, an unknown newer version fails instead of wiping data (`T38`, `N25`, `P1-T30`) |
| `P1NavTest` | The real app with 30 categories (short, long and equal names) and real pointer input: the tab row and the underline at 25/50/75 % of an unfinished drag, a drag that comes back, the row scrolled by hand alone and the follow resuming without a jump, the ends of the row, quick choices of far categories (`P1-T01` to `P1-T06`). Per-frame positions go to logcat tag `P1NAV` |
| `P1DenseTest`, `P1MoveTest` | The real `HomeScreen` with the real coordinator: the two-row header and 48 dp touch areas, the display panel and the state line, mark-all coverage, a notice that outlives the panel, 320/411/733 dp at 100 % and 200 % text, choosing through the picker during the movement back to the top (`P1-T08`, `P1-T09` to `P1-T12`) |
| `P1OrgTest` | The real app: the picker (local search by name and parent path, same category = close only), pin and unpin, drag with the handle and a cancelled drag, up/down and standard order, a pin that changes the index of the category on screen (it keeps its identity and its place), an emptied or deleted category (`P1-T14` to `P1-T21`) |
| `P1LayoutTest` | Per-category layout: three pages with three layouts while dragged, one feed in two categories, a global change reaching only what inherits, the same article kept in view across layout changes and a recreate, the details sheet and reset, a stored column choice that does not fit (`P1-T24` to `P1-T28`) |
| `P1OrgRaceTest` | The organizer when a write fails (a trigger in the app's own database aborts every write to `category_preferences`, so the failure comes out of SQLite through the repository and view model: the error is shown, the stored order is unchanged and the screen goes back to it; with writes working again the same drag is stored) and when the reader presses faster than the screen updates (several presses issued on the UI thread with no frame between: three "Move up", two rows moved, pins with a move, a drag right after a move; all must be applied, none undone) (`P1-T16`) |
| `MdLayoutTest` | Magazine density (`MD-T01` to `MD-T08`) on the real article list: low cards for short headlines without summary, a "read more" summary not shown while real text is, a row as tall as its tallest card and no taller, no blank areas without pictures, card heights and the reading place unchanged while pictures load or fail, 2.3:1 tile pictures and the small wide-card picture, no overlap and 48 dp buttons at 130 % and 200 % text |
| `TileLayoutTest` | The Masonry and Bento layouts on the real list: two lanes of different heights on a phone, tiles of the planned sizes that fill the width and never overlap, no movement when pictures arrive, text above the source row and 48 dp menus, one column at 200 % text, back to the top in both, the article kept in view when switching between Bento, Masonry and List, and new articles at the head leaving every Bento tile on screen in place (Masonry: the article being read stays in view) |
| `P1ScaleTest` | Empty install, one category, 30 categories with 300 feeds and 5,000 articles (only the pages on screen are composed), the organizer at 200 % text (`P1-T32`, `P1-T33`) |
| `P1ShotTest`, `P1SeedTest`, `P1RestoreSetup`, `MdDensityShotTest`, `TileShotTest` | Evidence helpers that assert nothing and are skipped in an ordinary run (`-e p1evidence true` runs them). `P1ShotTest` writes screenshots of the home screen for a before/after comparison (`-e shotLabel before|after`, pictures in `/sdcard/Android/media/<package>/p1shots/`). `MdDensityShotTest` shows the same Magazine articles, pictures, width and text size and logs the measured row heights and how many cards and headlines are on screen (`MDDENSITY`), with a screenshot per case, so it can be run before and after a change of the card geometry (`-e mdLabel before|after`). `P1RestoreSetup` stores the order, pins and overrides that `tools/p1_restore_check.py` restarts and restores (`P1-T29`). `P1SeedTest` puts data into an installed previous release for the overwrite-install check and, in one method, fetches five public feeds (the only test code that uses the internet); run its methods one at a time with `-e class ...P1SeedTest#method` through `am instrument` so that Gradle does not uninstall the app afterwards |
| `IconDecoderTest` | Source-icon decoding on a real Android runtime: PNG, ICO with PNG entry, ICO with BMP entry, wide logo, 512 px image, non-images (`U08`) |
| `PerformanceTest` | Synthetic 300 feeds / 5,000 articles: warm start to a visible list and local search timings (`T47`) |
| `ArticleListCostProbe` (JVM) | Home list query cost on 30,000 articles, one query against 1,000-row chunks. A measurement, not a check: excluded from `testDebugUnitTest` unless you pass `-Pfolirel.probe` |

`TestData` seeds the app through its own repositories; there is no demo mode in the app.

### Position traces and recordings

"The list ended on the first article" does not show that the movement was smooth. Two kinds of device test look at the movement itself; both write to logcat and neither is a frame-rate measurement of a phone (an emulator draws slowly, and a debug build is not optimised).

- **`UxrScrollTraceTest`** drives the real list with the test's own frame clock (one sample per 16 ms frame; pictures are real PNG files that load between the frames). `ScrollTrace` computes the movement of every frame from the cards that are on screen in two frames (the median shift) and reports `duration`, `stalls` (a frame without movement before the run-out ends), `restarts` (the movement came almost to rest and took off again after cruising), `reversals`, `teleports` (frames where no card is shared with the last frame: the one skip is allowed) and `finalSnap` (an instant correction at the end). Run `adb shell am instrument -w -e class io.github.yuubiz.folirel.UxrScrollTraceTest io.github.yuubiz.folirel.test/androidx.test.runner.AndroidJUnitRunner` after installing the app and the test APK (`./gradlew :app:installDebug :app:installDebugAndroidTest`), then read `adb logcat -d -s UXR_TRACE:I`: the first line of each run is the summary, the following lines are `label,frame,axis,shiftPx,px|est,first cards (id:top/height)`.
- **`UxrRealtimeTest`** uses real time (no compose test rule, whose frame clock only runs while the test pumps it). `adb logcat -d -s UXR_REALTIME:I` has a summary per run (`updates`, `duration`, the gap between changes of the first visible article as p50/p95/max, `reversals`, `endIndex`) and the trace lines `label,msSinceStart,articleId,offsetPx`. To record the screen at the same time:

  ```sh
  adb shell screenrecord --size 540x1080 --bit-rate 3000000 --time-limit 170 /sdcard/run.mp4 &
  adb shell am instrument -w -e class io.github.yuubiz.folirel.UxrRealtimeTest io.github.yuubiz.folirel.test/androidx.test.runner.AndroidJUnitRunner
  adb shell pkill -INT screenrecord; adb pull /sdcard/run.mp4
  ```

  Keep the screen on (`adb shell svc power stayon true`). Compare a recording of the previous version with one of the new version from the same emulator.

Comparisons against a previous commit are done in a second work tree (`git worktree add --detach ../folirel-head <commit>`) with the same test files copied in (the old `ListHost` method is `markNewSeen`, the new one `acknowledgeNew`); remove the work tree afterwards.

### Home refinement scenarios (UXR-01 to UXR-21)

| ID | Scenario | Test classes |
|---|---|---|
| UXR-01 | Back to top from near and far with no update running: one movement, no restart card by card | `UxrScrollTraceTest`, `UxrRealtimeTest` |
| UXR-02 | 3,000 rows of uneven height, pictures loading: no repeated stop in the trace or the recording (List, Compact list, Magazine with 2 and 3 columns) | `UxrScrollTraceTest`, `UxrRealtimeTest` |
| UXR-03 | Batches arriving during and right after the movement: it ends on the newest article without chasing | `UxrScrollTraceTest`, `BackToTopListTest`, `BackToTopTest` |
| UXR-04 | A press, drag, long press or menu during or after the movement: the user wins and nothing resumes it | `BackToTopInterruptTest`, `BackToTopTest` |
| UXR-05 | Arrow (real presses) and tab pressed repeatedly: one movement, no restart, correct end | `BackToTopInterruptTest`, `PagerAndMagazineTest` |
| UXR-06 | Category chosen during the movement or the following (real pager and tabs), filter changed, or the screen left: the old request does not move the other list | `HomePagerMoveTest`, `BackToTopInterruptTest` |
| UXR-07 | A finished update that needs attention leaves one notice that goes after its time; the diagnostics stay | `RefreshNoticeTest`, `HomeNoticeFlowTest` |
| UXR-08 | Closing the notice, switching tabs, building the screen again: it does not come back and does not last longer | `RefreshNoticeBoardTest`, `HomeNoticeFlowTest` |
| UXR-09 | An older notice's timer never removes a newer notice or progress | `RefreshNoticeBoardTest`, `HomeNoticeFlowTest` |
| UXR-10 | A feed that moved address: its present state decides (a current problem is shown, a recovery clears it) | `RefreshNoticeTest` (the coordinator side: `ReplacedFeedStatusTest`) |
| UXR-11 | Offline and HTTP held: a small mark and a badge stay when the notice is gone; "Subscriptions" leads to the reason | `HomeNoticeFlowTest`, `FeedAttentionTest` |
| UXR-12 | 120 new articles, newest first: one tap goes to the top and the notice for all 120 is gone | `NewArticleBannerTest` (three layouts), `HomeBannerFlowTest` |
| UXR-13 | Some of the new articles are already on screen: no counting down, one answer for all | `NewArticleBannerTest`, `NewArticleNoticesTest` |
| UXR-14 | The cross: no move, no read/saved change, no article lost | `NewArticleBannerTest`, `HomeBannerFlowTest` |
| UXR-15 | Oldest first: one move to the first new article of that order, the order kept | `NewArticleBannerTest` |
| UXR-16 | Only the new articles of the list shown are answered | `NewArticleBannerTest` |
| UXR-17 | New articles arriving after the tap, and a late report listing the answered ones again | `NewArticleNoticesTest`, `NewArticleBannerTest`, `HomeBannerFlowTest` |
| UXR-18 | New articles that are not at the head: counted, nothing dropped, the text says "to top" | `NewArticleBannerTest` |
| UXR-19 | Real coordinator, Room, view model and pager from the update to the answer | `HomeBannerFlowTest`, `HomeNoticeFlowTest` |
| UXR-20 | Overwrite install over data (subscriptions, categories, read, saved, settings, article IDs) | manual with `tools/compare_app_state.py` (emulator only) |
| UXR-21 | Notice and banner together with going to the top and the notice ending: nothing moves the articles | `HomeNoticeFlowTest` |

### Performance targets

On a 5,000-article, 300-feed database the initial targets are a warm start with the list visible within 2 seconds and local search results within 500 ms of committing the query. They are targets for a stated device, not guarantees. `PerformanceTest` prints p50 and p95; when reporting numbers include the device, API level, build type and number of runs, keep debug and release measurements apart, and do not mix network-dependent fetch times into UI response times. Scrolling should show no sustained large stalls while images load and no ANR.

## 3. Synthetic fixtures

`fixtures/` holds fictional feeds, OPML files, expected-result files and icons. All content is made up; URLs use reserved example hosts. The directory is mounted as unit-test resources and as device-test assets by `app/build.gradle.kts`; the files are read as raw bytes and never copied or re-encoded. Details of each file are in [fixtures/README.md](../fixtures/README.md).

The fixtures do not cover all scenarios. HTTP codes, redirects, DNS, size limits, database races and large data sets are generated by the tests themselves.

`app/schemas/` holds the exported Room schemas (`1.json`, `2.json`, `3.json`); it is also mounted as device-test assets for `MigrationTestHelper`. The current database schema version is 3.

## 4. Scenario catalogue

Column "Test classes" lists the classes whose comments cite that ID. A dash means no test cites the ID directly: the scenario is verified by hand, or is exercised through other IDs.

### 4.1 Core scenarios (T01-T48)

| ID | Req. | Scenario and expectation | Layer | Test classes |
|---|---|---|---|---|
| T01 | R01, R16 | First local start. Starts with no network or token; after skipping onboarding the empty state leads to add actions; with a stored database the list is shown. | UI / device | `HomeFlowTest` |
| T02 | R02, R05 | OPML hierarchy. `subscriptions.opml` previews and commits as 4 feeds, 4 categories, 4 direct memberships and 1 uncategorized feed (6 outlines collapse to 4 feeds). A parent category's scope includes its descendants. | JVM + Room | `OpmlParserTest`, `ImportRepositoryTest` |
| T03 | R02, R16 | Duplicate and repeated import. Importing the same OPML twice keeps one feed per URL; user title, read and saved state are kept; only a new category membership can be added. | Room | `ImportRepositoryTest` |
| T04 | R02, R17 | Atomic rejection. `malformed.opml`, DOCTYPE and limit overruns are rejected, never changing the database even partway; an exception during commit rolls back. | JVM + Room | `OpmlParserTest`, `ImportRepositoryTest` |
| T05 | R02, R17 | Mixed candidates. `warnings.opml` yields HTTPS 2 and HTTP 1 as candidates, and reports 1 unsafe URL and 1 unknown leaf as excluded. HTTP feeds are registered only (not fetched) under default settings. Linked URLs are not fetched at import time. | JVM + HTTP | `OpmlParserTest`, `ImportRepositoryTest` |
| T06 | R02, R04 | URL identity. Host, scheme, default port and fragment normalise as listed in `url-identity.json`. Query order, repeated parameters, path case, trailing slash and http versus https are not merged. | JVM | `UrlNormalizerTest` |
| T07 | R03, R05 | OPML round trip. Multiple memberships, Japanese text, XML special characters, paused feeds and HTTP-pending feeds are exported, and re-importing into an empty database gives the same URL set and direct memberships. Read and saved state are not exported. Also: 1,200 feeds in three folder levels come back complete and re-import adds nothing, empty and broken files change nothing, a feed a permanent redirect moved from http:// to https:// is exported with https://, unsubscribed feeds are not exported. | Room + file I/O | `CategoryAndExportTest`, `OpmlPortabilityTest` |
| T08 | R02, R03 | File cancellation. Cancelling a read or save, a revoked URI permission and a write failure never show success and never change the database or an existing file unintentionally. | Device | - |
| T09 | R04, R16 | Add, unsubscribe, resubscribe. A duplicate URL is merged. Unsubscribing is a soft delete; unsubscribing during a refresh does not bring the feed back; resubscribing the same URL reuses the original ID and state. | Room + HTTP | `CategoryAndExportTest`, `ImportRepositoryTest` |
| T10 | R05 | Category editing. Renaming a parent and its descendants keeps IDs and memberships; a normalised-path conflict rolls back everything; deleting a category keeps feeds and articles and moves orphaned feeds to uncategorized. | Room | `CategoryAndExportTest` |
| T11 | R06 | RSS 2.0. `rss2-basic.xml`, fetched through the network path, stores the expected ID, title, original URL, summary and image. A `guid` with `isPermaLink="false"` is not used as the article URL. | JVM + HTTP | `FetchPipelineTest`, `FeedParserFixtureTest` |
| T12 | R06 | Atom 1.0. `atom-basic.xml`: namespaces, `published`/`updated`, author inheritance, `rel` omitted or `alternate`, `type="html"`. | JVM | `FeedParserFixtureTest` |
| T13 | R06 | RSS 1.0 / RDF. `rss1-basic.xml`: `rdf:about` and Dublin Core fields; channel and items are not confused. | JVM | `FeedParserFixtureTest` |
| T14 | R06, R17 | Namespaces and relative URLs. Changing prefixes gives the same result. `xml:base` is inherited down the hierarchy; without it, URLs resolve against the final response URL. An unsafe base is not followed. | JVM + HTTP | `FeedParserFixtureTest` |
| T15 | R06 | Character encodings. `rss2-shiftjis.xml` and UTF-8, BOM, XML declaration and HTTP charset combinations decode Japanese correctly according to the precedence rules (Shift_JIS labels are decoded as windows-31j). | JVM + HTTP | `FeedParserFixtureTest`, `ParserUnitsTest` |
| T16 | R06, R16 | IDs and updates. The same `guid` with changed content keeps the article UUID, first-seen time, sort time, read and saved state. The same `guid` in a different feed is a different article. | Room | `FetchPipelineTest` |
| T17 | R06 | Entries without an ID. `rss2-idless.xml` yields the defined fallback digest; the ID does not depend on receive time or item order. | JVM | `ParserUnitsTest`, `FeedParserFixtureTest` |
| T18 | R06, R09 | Dates and missing fields. Invalid dates, missing titles and missing links do not crash and the article is kept. A publication time more than 24 hours in the future uses the first receive time as its sort time. | JVM + UI | `ParserUnitsTest`, `FetchPipelineTest` |
| T19 | R06, R09, R17 | HTML and image candidates. `script`/`style` content is not in the plain-text summary and HTML is never executed. Image priority is media, then enclosure, then `<img>`; 1 px and unsafe images are dropped. | JVM + UI | `ParserUnitsTest`, `FeedParserFixtureTest` |
| T20 | R07 | Conditional GET. 200 then 304 creates no duplicates. Validators are updated only after the database write succeeded. A first-time 304 is retried once without conditions. | HTTP + Room | `RefreshBehaviorTest` |
| T21 | R07, R17 | Redirects. 301/308 versus 302/307 are distinguished in whether the target is kept; the registered subscription URL never changes. Old validators are not sent to a changed URL. A loop or more than 6 hops fails. | HTTP | `RefreshBehaviorTest` |
| T22 | R07 | Status codes and waiting. 401, 403, 404, 410, 429 and 5xx are distinguished. `Retry-After` as seconds, as a date and malformed; timeouts and backoff are tested with a fixed clock. | HTTP | `RefreshBehaviorTest` |
| T23 | R07, R16 | Partial failure and cancellation. A failing feed keeps its existing articles and the others are saved. A 6-minute budget, cancellation or limit overrun reports the unfinished count and is never shown as success. | HTTP + Room | `RefreshBehaviorTest`, `FetchPipelineTest` |
| T24 | R07 | Concurrency. Manual, startup and worker refreshes do not double-fetch; at most 4 feeds in total and 2 per host at once. Stop, unsubscribe and manual state changes racing with a refresh never restore old data. | HTTP + Room | `RefreshBehaviorTest` |
| T25 | R07, R14 | Update constraints. Periodic off, unmetered-only, the 30-minute startup rule and failure waiting; startup never bypasses the network rule; offline does not time out every feed one after another. | Worker + device | `UpdateConstraintsTest` |
| T26 | R11, R16 | Manual state. Read/unread and saved change independently and survive restart and content updates. A database failure is reported and the display follows the database. | Room + UI | `ArticleQueryTest` |
| T27 | R11 | Mark all as read. All matching articles on the device for the current scope, filter and search are fixed at confirmation time; not limited to the first page; later arrivals are not included. | Room + UI | `ArticleQueryTest` |
| T28 | R04, R11 | Unsubscribed but saved. Saved articles of an unsubscribed feed show under the Saved filter of the whole collection, and the state is identical after resubscribing. Clearing the image cache changes neither saved nor read state. | Room + UI | `ArticleQueryTest` |
| T29 | R11 | No automatic read. `isRead` is unchanged before and after opening, returning, scrolling, days passing, refreshing and list prefetch. An article disappears from the Unread filter only through an explicit action. | UI + fixed clock | `NoAutomaticReadTest`, `HomeFlowTest` |
| T30 | R08, R05 | Scope and filter. Drawer and tabs; all / uncategorized / category with descendants / single feed; no duplicates from the category join; the filter is kept when the scope changes. | UI + Room | `ArticleQueryTest` |
| T31 | R09 | List layouts. Long Japanese text, images present / absent / failed, and missing time in List, Compact list and Magazine; rows do not jump when images load late. | UI | `HomeFlowTest` |
| T32 | R10 | Original site. Custom Tabs, then `ACTION_VIEW` when Custom Tabs is unavailable, then the "no app" message; unsafe schemes rejected; HTTP confirmation; the short-summary path for articles without a URL; no WebView. | UI + device | `BrowserLauncherTest`, `HomeFlowTest` |
| T33 | R12 | Share. Exactly one article's title and original URL; no image cache path, database content, other articles or query diagnostics attached. | UI | `BrowserLauncherTest` |
| T34 | R15 | Position restore. From the middle of a list: round trip to the site, rotation and process recreation restore the anchor, scope, filter, sort, query; the list does not jump to the top. | Device | `HomeFlowTest` |
| T35 | R15, R07 | New arrivals. A fetch while reading mid-list is announced by the banner and the position holds until the reader answers it (one tap, the cross, the arrow or the tab). | UI + HTTP | `HomeBannerFlowTest`, `NewArticleBannerTest` |
| T36 | R13 | Japanese search. Japanese substrings, Latin, whitespace, and `%`, `_` as literal text; the scope and filter are respected; an old search response never overwrites a newer query. | Room + UI | `ArticleQueryTest` |
| T37 | R14, R09 | Settings and image traffic. List layout, text size and theme are restored. With images off there are no image requests; clearing the cache removes images only. Public articles trigger no Open Graph fetches in the background. | UI + HTTP | `HomeFlowTest` |
| T38 | R16, R18 | Database persistence and migration. Subscriptions, categories, articles and state survive being killed mid-operation and a schema change. There is no destructive fallback. The migration test infrastructure exists even when no earlier schema had been released. | Room + device | `MigrationTest`, `DataKeepingTest`, `DatabaseSmokeTest` |
| T39 | R01, R16 | Offline. Stored lists, search, saved and read state work. Web pages are not claimed to be saved. Uncached images show the placeholder. | Device | - |
| T40 | R17 | XML hardening. DOCTYPE, external DTD, entities and XInclude are rejected with no external access or local file read. UTF-16 and other encodings cannot slip past an ASCII string check. | JVM + HTTP | `OpmlParserTest`, `FeedParserFixtureTest` |
| T41 | R17 | URL and DNS. Unsafe schemes, user-info, loopback, private, IPv6 ULA, link-local and dangerous redirects are rejected. The DNS result that was validated is the one connected to (no split). Applies to images too. | HTTP + fake DNS | `NetworkSecurityTest` |
| T42 | R17 | HTTP and TLS. With the HTTP setting off, no request is sent for first fetch, redirect or image. With it on, TLS and hostname checks still apply and an invalid certificate fails. No test-only trust exists in release. | HTTP + release inspection | `NetworkSecurityTest` |
| T43 | R17 | Size and image limits. Compressed responses (an image is counted after gzip is removed), deeply nested XML, huge attributes, 2,001 entries, huge images and broken images stop within limits with no UI failure, OOM or endless retry. | JVM + HTTP | `NetworkSecurityTest` |
| T44 | R17, R18 | Permissions and data protection. The merged manifest and the APK are inspected: no extra permissions or SDKs; no secrets, real OPML files or private URLs in the repository, logs or build artifacts; the backup policy is as intended. | Static + device | - |
| T45 | R20, R09 | Accessibility. Japanese text, the main TalkBack paths, 200% text, 48 dp targets, light and dark, landscape. | Device (manual) | - |
| T46 | R18 | Build and distribution. Pinned compatible versions, a Gradle wrapper, JVM tests, lint and assemble succeed, the debug APK exists, upgrades keep the same signature, dependency licences are listed. | CI + device | - (CI workflow) |
| T47 | R06, R18 | Real feeds and performance. Measurements for a synthetic 5,000 articles / 300 feeds, and a small smoke test of public RSS 2.0 and Atom feeds, are recorded separately with device and API. | Device / live network | `PerformanceTest` |
| T48 | R19 | Backend boundary. The UI does not reference HTTP or external IDs directly, the local backend and the repositories can be replaced in tests, and there is no empty provider implementation, OAuth or outbox code. | Design + unit | `ArchitectureBoundaryTest` |

### 4.2 Category pager, new articles, layouts, data keeping (N01-N25)

| ID | Scenario and expectation | Test classes |
|---|---|---|
| N01 | Category A at item 30, then B at item 10, then A/B round trips by tab and by swipe: each position is restored. | `PagerAndMagazineTest` |
| N02 | After switching categories and recreating the process, each list's anchor is restored. | - |
| N03 | Swipe, tab and drawer select the same category; the end pages do not wrap around. | `PagerAndMagazineTest` |
| N04 | Vertical scroll, pull to refresh, article tap and the Android back action do not interfere with horizontal paging. | - |
| N05 | Deleting, adding or reordering categories does not carry the position or selection of one page into another. | - |
| N06 | After a category switch, new-article detection looks at the current list's own state. | `NewArticleSeenTest` (see `V3-R03`) |
| N07 | Arrivals only in another category, under the Saved filter or under a search do not produce an unrelated banner, and answering the banner answers only the articles of the list it was shown on (`UXR-16`). | `NewArticleBannerTest` |
| N08 | In newest-first order the banner goes to the start of the list, in oldest-first order to the first new article of that order, and nothing scrolls before the user acts. | `NewArticleBannerTest`, `HomeBannerFlowTest` |
| N09 | The three layouts (List, Compact list, Magazine) and the selection persist across restart and keep the same article near the top. | `PagerAndMagazineTest` |
| N10 | Magazine cards with image, without image, failed image, images off, long Japanese titles, 200% text and dark theme. | - |
| N11 | Atom XHTML `A<b>B</b>C` becomes `ABC`; in-content images and `xml:base` resolve safely; tracking pixels and dangerous URLs are excluded. | `XhtmlContentTest` |
| N12 | Stopping a worker-only refresh cancels its queued and running fetches; feeds already finished stay applied. | `FetchCoverageTest` |
| N13 | A fetch shared by the worker and a manual request survives the worker's stop and serves the manual request. | `FetchCoverageTest` |
| N14 | A 20-entry feed reports 20; duplicates are counted as merged; new and updated counts are right in the diagnostics. | `FetchCoverageTest` |
| N15 | A 304 keeps the previous document's entry counts and does not confuse "0 new" with the feed's count. | `FetchCoverageTest` |
| N16 | 2,001 entries, oversized body, deep XML and broken XML are told apart, and old articles and state stay. | `FetchCoverageTest` |
| N17 | Time budget exhaustion, HTTP not allowed, network constraint and backoff are visible. | `FetchCoverageTest` |
| N18 | The refresh interval is stored in minutes; 15 and 30 minutes and Off can be scheduled and cancelled. | `DataKeepingTest`, `UpdateConstraintsTest` |
| N19 | Real periodic worker execution and cancellation on a dedicated emulator (registration alone is not execution). | - |
| N20 | Lowering the retention limit protects unread and saved articles and explains why the count stays above the limit. | `DataKeepingTest` |
| N21 | Read articles removed by cleanup are not added back as new unread articles when the feed redelivers them. | `DataKeepingTest` |
| N22 | Changing or clearing the image cache does not change subscriptions, read state or saved state. | - |
| N23 | Many categories and 30,000 articles: pager load range, memory and responsiveness are measured. | - |
| N24 | Flicking, pre-composing neighbour pages, viewing an article, refreshing and cleanup never mark anything read. | `PagerAndMagazineTest`, `NoAutomaticReadTest` |
| N25 | Database migration and a same-signature overwrite install keep the user's existing data (v1 to v2 validated against the exported schemas). | `DataKeepingTest`, `MigrationTest` |

### 4.3 Refresh scenarios (S01-S16)

These `S` IDs are refresh scenarios. They are not the screen IDs `S00`-`S40` of [product-and-ux.md](product-and-ux.md); a source comment that says "docs/02 S10" or "S30" means the screen.

| ID | Scenario and expectation | Test classes |
|---|---|---|
| S01 | Categories A and B with a child category and multi-membership feeds: refreshing A contacts exactly the feeds of A and its children, each once, and never feeds only in B. | `FetchCoverageTest` |
| S02 | A single feed whose newest article is 30 days old is still fetched by a manual refresh. Search, Unread/Saved filter and sort order never narrow the target. | `FetchCoverageTest`, `DataKeepingTest` |
| S03 | While All is refreshing and feed F is queued, a manual refresh of F runs at the next free slot and returns F's own result without waiting for all other categories. | `ScopedRefreshTest` |
| S04 | A feed excluded from an automatic All by its ordinary failure backoff is retried by a narrow manual refresh. A valid `Retry-After` is still respected. | `ScopedRefreshTest` |
| S05 | When All's time budget expires right after a manual request for F, F is still fetched for that request and is not left unstarted. | `ScopedRefreshTest` |
| S06 | A feed that already finished inside the running All is fetched again for a later manual request; the past completion is not reused. | `ScopedRefreshTest` |
| S07 | One feed with many entries does not crowd out the others; every feed's current document is applied in full. | `FetchCoverageTest` |
| S08 | A budget cut leaves feeds pending; the next run, also after a restart, starts with the unfinished feeds. | `FetchCoverageTest` |
| S09 | Dates never cut a document: old, recent and undated entries are all stored. | `FetchCoverageTest` |
| S10 | A feed returning 3 entries stores 3, reports 3 and is requested once (no paging loop). | `FetchCoverageTest` |
| S11 | A 304 keeps the previous document's counts; a forced refetch sends no validators and applies the new body. | `FetchCoverageTest` |
| S12 | A forced refetch requested during a running conditional fetch causes exactly one follow-up unconditional fetch, even when pressed repeatedly. | `FetchCoverageTest` |
| S13 | Forced refetch still respects a valid `Retry-After` (429/503), the HTTP opt-in and the size limit. | `FetchCoverageTest` |
| S14 | From response to category list: new IDs, raw count, unique count, insert/update, display, with duplicate GUIDs, no date and two categories. | `FetchCoverageTest` |
| S15 | Queued, running and cancelled requests are told apart, as are failure and 304; the last-success time does not advance when nothing started. | `FetchCoverageTest` |
| S16 | A manual category or feed refresh from the UI through real HTTP to the database, including alongside an All refresh, causes no automatic read and no loss of saved state. | - |

### 4.4 Second-generation UX scenarios (U01-U14, F01-F03)

| ID | Scenario and expectation | Test classes |
|---|---|---|
| U01 | The automatic number of Magazine columns changes with narrow, medium and wide widths and with text size; a wide screen is not stuck at one huge column. | `CardGridLayoutTest` |
| U02 | Choosing 1, 2 or 3 columns, 200% text and split screens keep headlines and actions usable (a choice that does not fit is reduced and shown). | `CardGridLayoutTest` |
| U03 | After column, rotation or layout changes the same article stays near the top and nothing is marked read. | `UxNextTest`, `HomeFlowTest` |
| U04 | Category round trips in the Magazine layout keep each category's position; new arrivals and the oldest-first banner jump work. | `UxNextTest` |
| U05 | Pre-composing a neighbour page does not answer the new-article notice (only the arrow, the tab, the banner and the cross do; being on screen does not). | `NewArticleBannerTest` |
| U06 | Skipping the HTTP-open confirmation persists across restart, is not saved when cancelled, and can be turned back on in settings. | `HttpOpenSettingTest`, `UxNextTest` |
| U07 | Skipping the confirmation changes neither the HTTP feed/image allowance nor TLS verification. | `HttpOpenSettingTest` |
| U08 | Source icons: declared icon, relative URLs, PNG/ICO, no icon and failure fallback. | `FeedIconTest`, `IconDecoderTest` |
| U09 | Showing many articles of one feed does not cause icon or page lookups per article. | `FeedIconTest` |
| U10 | Bulk unsubscribe stops only the selected feeds and keeps saved state, read state and categories. | `BulkUnsubscribeTest`, `UxNextTest` |
| U11 | Bulk unsubscribe survives cancel, undo, a fetch completing during the operation and changes to the target list. | `BulkUnsubscribeTest`, `UxNextTest` |
| U12 | 404/410, 401/403, 429, 5xx, temporary network failure and HTTP-pending are shown separately. | `BulkUnsubscribeTest` |
| U13 | Moved-feed discovery does not adopt HTML pages, unrelated sites, multiple feeds or redirect loops by mistake. | `FeedDiscoveryTest` |
| U14 | The registered URL does not change before a candidate is approved; after approval the ID and state are kept and old OPML files do not duplicate the feed. | `FeedDiscoveryTest` |
| F01 | A pruned `http://` key redelivered over HTTPS does not reappear as a new unread article. | `ReviewFixesTest` |
| F02 | OPML preview counts and the committed result agree for HTTP and HTTPS versions of the same feed. | `ReviewFixesTest` |
| F03 | A `Retry-After` of 48 hours or more is not shortened to 24 hours and is respected for manual, automatic and forced refresh. | `ReviewFixesTest`, `RefreshBehaviorTest` |

### 4.5 Consistency scenarios (V3-R01 to V3-R05, V31-*)

| ID | Scenario and expectation | Test classes |
|---|---|---|
| V3-R01 | A fetch result is applied only if it belongs to the fetch URL its request started with. When the fetch URL changes (approved move, HTTPS migration) while a fetch runs, the new address gets a fresh job; late results of the old address, whether success, 429, a permanent redirect or 304, are discarded; A to B to A applies the response of the current address. | `StaleFetchResponseTest` |
| V3-R02 | One definition of "same article" (`EntryKeyAliases`) serves discovery's shared-article count and storage after a move: only `id:`/`url:` prefix, `http`/`https` and a leading `www.` differ, URL-shaped keys only, never across feeds, and only for feeds with a recorded move or HTTPS migration. | `MovedFeedIdentityTest`, `EntryKeyAliasesTest` |
| V3-R03 | Superseded by H03 (`UXR-13`): the banner is no longer counted down by what is on screen, so a marker added after the article is already on screen changes nothing; it is answered by the reader. | `NewArticleBannerTest`, `NewArticleNoticesTest` |
| V3-R04 | A cancelled or replaced moved-feed discovery never writes its outcome into the state of the newer one; an adopted candidate is tied to the fetch URL it was found for. | `DiscoveryControllerTest`, `FeedDiscoveryTest` |
| V3-R05 | What a page is follows from its root element, not from a leading `<?xml` (XHTML pages are pages; feeds with a BOM or comments before the root are feeds). | `HtmlRootElementTest`, `FeedDiscoveryTest` |
| V31-R01 | After an address change, the refresh coordinator does not coalesce a new request into an older in-flight request for the old address (normal, forced and All refreshes); repeated taps with an unchanged address still fetch once. | `RefreshAfterAddressChangeTest` |
| V31-R02 | A request that did not fetch a feed because its address changed reports it as replaced (history), not as an unfinished or "not fetched" feed. | `RefreshAfterAddressChangeTest` |
| V31-C02 | A replaced feed is not counted as unfinished work, nor offered for retry. | `ReplacedFeedStatusTest` |
| V31-T01 | Upgrading by overwrite install (without uninstalling) keeps rows, reading state and settings unchanged, and a following ordinary refresh creates no duplicates. | `tools/compare_app_state.py` (manual device procedure) |
| V31-T02 | The new-articles banner behaves correctly on the real list composable in all layouts and both orders. | `NewArticleBannerTest` |
| V4-R01 | "Back to the top" ends on the first article even when articles arrive at the head meanwhile or right after (a refresh), and the list does not follow arrivals once the user has touched it. | `BackToTopTest`, `BackToTopListTest`, `BackToTopInterruptTest` |
| V31-T03 | Tabs, underline, pages and the saved selection agree after drags, quick taps and distant tabs at large text; back to top works in every layout and does not change other categories' positions or any state. | `PagerAndMagazineTest` |

### 4.6 Phase 1 scenarios: navigation, density, organizing, layout (P1-T01 to P1-T34)

| ID | Rule | Test |
|---|---|---|
| P1-T01 | With 30 categories the titles and the underline are at the same continuous place at 25, 50 and 75 % of an unfinished drag. | `P1NavTest`, `TabFollowTest` |
| P1-T02 | The underline is centred under short, long and equal-named titles; tab touch areas are 48 dp. | `P1NavTest`, `PagerAndMagazineTest`, `P1DenseTest` |
| P1-T03 | A drag that comes back leaves the selection and the saved settings as they were, and the row and underline come back. | `P1NavTest` |
| P1-T04 | The first and last tab and a row that fits: no empty space added, no needless movement. | `P1NavTest`, `TabFollowTest` |
| P1-T05 | Scrolling the row alone selects nothing and does not fight the next pager drag. | `P1NavTest`, `TabFollowTest` |
| P1-T06 | Far choices in quick succession end on the last one and select nothing in between. | `P1NavTest`, `TabFollowTest` (`SettleGuard`) |
| P1-T07 | Pressing the selected tab again or the arrow (real down and up) is one movement to the top; a press on an article ends it. | `BackToTopInterruptTest`, `BackToTopTest` |
| P1-T08 | Choosing another category through a tab, the picker or the drawer ends the old page's movement and following. | `HomePagerMoveTest` (tab), `P1MoveTest` (picker, drawer) |
| P1-T09 | The header is the toolbar and the tabs (about 104 dp); the touch areas are 48 dp. | `P1DenseTest` |
| P1-T10 | At 320, 411 and 733 dp and 100 % and 200 % text the display panel, the picker and the search are reachable without overlap. | `P1DenseTest`, `P1ScaleTest` |
| P1-T11 | Range and order chosen in the panel reach the list, the state line and the mark-all coverage. | `P1DenseTest` |
| P1-T12 | A panel or a search does not restart, extend or bring back a notice, and does not move the list; an update that finishes with new articles while the panel is open (or a search is active) is announced afterwards, by the list it belongs to. | `P1DenseTest`, `HomeNoticeFlowTest` |
| P1-T13 | Offline, held HTTP and storage failure keep a small way to the details. | `HomeNoticeFlowTest` |
| P1-T14 | The picker searches names and parent paths locally; the same category only closes it; its search is separate from the article search. | `P1OrgTest`, `P1MoveTest` |
| P1-T15 | Pin and unpin: one tab, in front; unpinning restores the place; kept across a restart. | `P1OrgTest`, `CategoryPreferencesTest` |
| P1-T16 | The handle moves a row within its group; a cancelled drag changes nothing; tabs follow the stored order; a failed write shows the error and puts the stored order back; fast presses before the last write is stored are all applied. | `P1OrgTest`, `P1OrgRaceTest`, `CategoryPreferencesTest` |
| P1-T17 | Up/down in the menu and the standard order (which keeps pins). | `P1OrgTest`, `P1ScaleTest`, `CategoryPreferencesTest` |
| P1-T18 | Reordering changes no category id, path, membership, article or state. | `CategoryPreferencesTest` |
| P1-T19 | A pin or move that changes the index of the shown category does not change the selection or the list place. | `P1OrgTest` |
| P1-T20 | A reorder and a new category, a rename and a re-import: the saved order stays, new ones go to the end. | `CategoryPreferencesTest`, `TabOrderTest` |
| P1-T21 | No feed left, resubscribe, delete: settings kept or removed at the right boundary; an emptied category stays on screen; delete returns to All. | `CategoryPreferencesTest`, `P1OrgTest` |
| P1-T22 | Equal child names, names with `/`, depth: the tab order is independent of the tree. | `TabOrderTest` |
| P1-T23 | Resolution of inherited, explicit and automatic (0) settings for All, Uncategorized, a feed, a parent and a child. | `TabOrderTest` |
| P1-T24 | Three categories with three layouts, also while dragged between two pages. | `P1LayoutTest` |
| P1-T25 | One feed in two categories: A's change leaves B, the articles and their state alone. | `P1LayoutTest` |
| P1-T26 | A global change reaches only what inherits; resetting a category keeps its pin and order. | `P1LayoutTest`, `CategoryPreferencesTest` |
| P1-T27 | The same article stays in view across layout changes and a recreate. | `P1LayoutTest`, `UxNextTest` |
| P1-T28 | A column choice that does not fit is reduced on screen and kept as stored; images off stay off. | `P1LayoutTest`, `CardGridLayoutTest` |
| P1-T29 | After a restart and after the process was killed and the screen state restored, the selected id, the order, the pins and the overrides (and what inherits) are kept and nothing is written while the screen is rebuilt. | `P1NavTest` (saved scope never replaced), `tools/p1_restore_check.py` (manual device procedure) |
| P1-T30 | A populated schema 3 database migrates to schema 4 with every row kept and every category at the defaults. | `MigrationTest` |
| P1-T31 | Installing the new APK over the previous one (without uninstalling) keeps rows, reading state and settings; the new table is empty. | `tools/compare_app_state.py` (manual device procedure) |
| P1-T32 | Empty install, one category, 30 categories with 300 feeds and 5,000 articles: no crash, only the pages on screen composed. | `P1ScaleTest` |
| P1-T33 | At 200 % text the new controls (pin, menu, up/down) are operable and 48 dp. | `P1ScaleTest`, `P1DenseTest` |
| P1-T34 | The 0.4.2 scenarios (back to top, one answer to the notice, the cross does not move, an old page stops, the arrow pressed repeatedly joins one movement) still hold. | `BackToTopTest`, `BackToTopInterruptTest`, `HomePagerMoveTest`, `NewArticleBannerTest`, `HomeBannerFlowTest`, `HomeNoticeFlowTest` |

### 4.7 Magazine density (MD-T01 to MD-T12)

| ID | Rule | Test |
|---|---|---|
| MD-T01 | Two columns with pictures, short headlines and empty summaries: no reserved text area. | `MdLayoutTest` |
| MD-T02 | A summary that is only a "read more" label is not shown; ordinary text and text containing the words are; the data is unchanged. | `CardSummaryTest`, `MdLayoutTest` |
| MD-T03 | A row is as tall as its tallest card; the next row is not stretched. | `MdLayoutTest` |
| MD-T04 | Long headlines and two-line summaries keep the standard text size and end with an ellipsis. | `MdLayoutTest` (200 %), `MdDensityShotTest` (long data) |
| MD-T05 | No pictures, pictures off, a picture on one side: no empty text area; pictures off make no picture request. | `MdLayoutTest`, `HomeFlowTest` (`T37`) |
| MD-T06 | Pictures loading, failing or cached do not change card heights or the reading place. | `MdLayoutTest` |
| MD-T07 | Mixed and even, 1/2/3 columns, narrow and wide: stored settings and order unchanged, tile pictures 2.3:1, a small wide-card picture. | `MdLayoutTest`, `CardGridLayoutTest`, `P1LayoutTest` |
| MD-T08 | 200 % text and long Japanese text: the column correction applies, no overlap, 48 dp buttons. | `MdLayoutTest`, `CardGridLayoutTest` |
| MD-T09 | Category round trips, layout changes and coming back keep the article in view. | `P1LayoutTest`, `UxNextTest`, `PagerAndMagazineTest` |
| MD-T10 | Arrivals, back to top, repeated presses, another tab, closing the notice: the 0.4.x/0.5.x behaviour is unchanged. | `NewArticleBannerTest`, `BackToTopTest`, `BackToTopInterruptTest`, `BackToTopListTest`, `UxrScrollTraceTest`, `HomePagerMoveTest`, `P1MoveTest` |
| MD-T11 | Installing over the previous release keeps order, pins, overrides, read/saved state and settings. | `tools/p1_restore_check.py` (manual device procedure) |
| MD-T12 | Same articles, pictures, width, text size and position before and after: measured row heights and cards on screen. | `MdDensityShotTest` (evidence helper) |

### 4.8 Design topics cited in source comments

Source comments name the design topic a piece of code belongs to. They are not test IDs.

| Tag | Topic |
|---|---|
| SYNC-02 | A manual category or feed refresh acts on its own target; filter, sort and search are display conditions only. |
| SYNC-03 | Per-feed forced refetch: stored validators are not sent and caches are asked to revalidate. |
| SYNC-04 | Tracing a cause from request to storage to display: per-feed request states (queued, running, updated, not modified, failed, deferred, cancelled, inactive). |
| DATA-01 | Fetch diagnostics per feed: the last request, the last real HTTP exchange and the last applied document, kept apart. |
| CFG-02 | Retention ("articles to keep") and image cache settings; deletion only after consent, unread and saved articles protected. |
| REV-01 | Each category keeps its own scroll position. |
| REV-02 | The new-articles banner: its state, scope, answer and where it moves to (see product-and-ux section 6). |
| REV-03 | Atom XHTML text order and in-content images. |
| REV-05 | Pager loading design: only visible pages hold a database subscription. |
| UX-01 | Switching categories by horizontal swipe. |
| UX-GRID | The multi-column Magazine layout and its card rules. |
| UX-ICON | Source icons. |
| SUB-BULK | Bulk clean-up of failing subscriptions. |
| SUB-DISCOVERY | Finding where a moved feed went, on the feed's own site. |

Scenario IDs `V3-R01`-`V3-R05` and `V31-R01`/`V31-R02` also appear in source comments next to the code that implements the rule they describe.

### 4.9 Phase 2.5 scenarios: adaptive layout and navigation consistency (RSP-T01 to RSP-T40)

Window widths are made with `wm size` / `wm density` (density 320) from inside the test, so no foldable is needed; the expectations are written out in the tests, not computed by the rules they check.

| ID | Rule | Test |
|---|---|---|
| RSP-T01 | After a reorder (a child before its parent), tabs, picker and drawer show the same category ids in the same order. | `ResponsiveNavTest` |
| RSP-T02 | A pin or unpin shows at once in tabs and pane without a restart. | `ResponsiveNavTest`, `CategoryPreferencesTest` |
| RSP-T03 | Nested categories: the drawer names a category by its own name with its parent path under it; no indent. | `ResponsiveNavTest` |
| RSP-T04 | A drawer category's feeds stay under that category. | `ResponsiveNavTest` |
| RSP-T05 | The pane uses the same order. | `ResponsiveNavTest` |
| RSP-T06 | A choice in the pane ends the old page's back-to-top movement (the tab, picker and drawer paths: P1-T08). | `ResponsiveNavTest`, `P1MoveTest` |
| RSP-T07 | A 0.7.2 store (columns 0..3, no navigation key) reads as it was; navigation is Auto and nothing is written by reading. | `ResponsiveSettingsTest` |
| RSP-T08 | The global columns 4 and 5 are stored; 6 and negative values read as automatic; the setting is offered in Settings. | `ResponsiveSettingsTest`, `ResponsiveNavTest` |
| RSP-T09 | A category override keeps 0..5; 6 is refused; an unknown stored value reads as "use the default". | `CategoryPreferencesTest`, `ResponsiveNavTest` |
| RSP-T10 | A chosen 5 is reduced on a narrow window and the stored choice is not changed. | `CardGridLayoutTest`, `ResponsiveNavTest` |
| RSP-T11 | Widening restores the chosen 5. | `CardGridLayoutTest`, `ResponsiveNavTest` |
| RSP-T12 | 200 % text takes columns away. | `CardGridLayoutTest`, `ResponsiveNavTest` |
| RSP-T13 | One to three Magazine columns give exactly the sizes of 0.7.2 (a copy of that planner); the Bento 1..3 blocks equal the 0.7.2 output. | `WideColumnsTest`, `TileLayoutsTest` |
| RSP-T14, T15 | Magazine Even on 4 and 5 columns: equal cells, no leftover. | `WideColumnsTest`, `ResponsiveNavTest` |
| RSP-T16, T17 | Magazine Mixed on 4 and 5 columns: deterministic, gap-free rows, featured cards mixed in, no long run of small cards. | `WideColumnsTest`, `ResponsiveNavTest` |
| RSP-T18 | The article in view stays through 3 -> 4 -> 5 and 5 -> 2 columns. | `ResponsiveNavTest` |
| RSP-T19 | Masonry 4 and 5 lanes fill the width. | `ResponsiveNavTest`, `TileLayoutsTest` |
| RSP-T20, T21 | Bento 4 and 5 columns: every block covers every cell, also at the end of the list and when packed from the end. | `TileLayoutsTest`, `ResponsiveNavTest` |
| RSP-T22 | A new article at the newest end changes only the block there (the bound follows the patterns' size). | `TileLayoutsTest` |
| RSP-T23 | A picture arriving does not change a card's height (the shape is fixed by the id; unchanged from 0.7.2). | `MdLayoutTest`, `TileLayoutTest` |
| RSP-T24..T28 | Auto, Pane when possible, Drawer only and the compact fallback, by width and text size. | `NavigationLayoutTest`, `ResponsiveNavTest` |
| RSP-T29 | The menu button is absent only while the pane is shown. | `ResponsiveNavTest` |
| RSP-T30 | The tabs and the pager work beside the pane. | `ResponsiveNavTest` |
| RSP-T31..T35 | 411, 600, 733, 839/840 and 1200 dp. | `ResponsiveNavTest` |
| RSP-T36, T37 | Resize keeps the category, filter, sort, search and the article in view; nothing becomes read or saved. | `ResponsiveNavTest` |
| RSP-T38 | 200 % text: fewer columns, the pane's entries stay 48 dp tall and usable. | `ResponsiveNavTest` |
| RSP-T39 | The pane choice and the columns survive a restart of the store and a recreate of the screen. | `ResponsiveSettingsTest`, `ResponsiveNavTest` |
| RSP-T40 | A 0.7.2 install with data keeps its categories, order, pins, overrides, theme, read and saved states after the 0.8.0 APK is installed over it. | `tools/p2_palette_restore_check.py` (procedure in the report) |

### 4.10 Phase 3A scenarios: source actions, period, hidden keywords (P3A-T01 to P3A-T35)

JVM tests run the real Room query, the real settings store and the real `HomeViewModel` (Robolectric, in-memory database,
a movable clock). `DailyReadingUiTest` drives the menu, the panel and the sheet on the real screen and needs a device.

| ID | Scenario | Where |
|---|---|---|
| P3A-T01, T02 | Article menu: "Show articles from this source" selects the single-feed scope; "Source details" opens the feed detail of that feed. | `HomeViewModelFiltersTest` (scope, return, no state change), `DailyReadingUiTest` (menu items) |
| P3A-T03 | Read and saved do not change by showing a source; the article is not opened. | `HomeViewModelFiltersTest`, `DailyReadingFiltersTest`, `DailyReadingUiTest` |
| P3A-T04 | Reachable in all five layouts: one menu (`ArticleItem.moreButton`) is shared by every layout. | Code path shared; `DailyReadingUiTest` runs it in the default layout only |
| P3A-T05 | Back from the single feed returns to the scope it was chosen from; another scope forgets it. | `HomeViewModelFiltersTest`, `DailyReadingUiTest` |
| P3A-T06..T10 | All, 24 h, 3 d, 7 d, 30 d: the boundary is inclusive at the cutoff and excludes one minute before it. | `DailyReadingFiltersTest` |
| P3A-T11..T14 | Period with unread, saved, search and both orders. | `DailyReadingFiltersTest` |
| P3A-T15, T16 | The period survives process recreation (saved state) and a restart (stored setting). | `HomeViewModelFiltersTest`, `PeriodAndKeywordSettingTest` |
| P3A-T17 | The list key (scroll anchor key) contains the period, not the cutoff. | `HomeViewModelFiltersTest` |
| P3A-T18 | An open list renews its cutoff on foreground and on the tick. | `HomeViewModelFiltersTest` |
| P3A-T19..T23 | Title, summary, Japanese normalization, ASCII case, `%` `_` `\` literal. | `DailyReadingFiltersTest` |
| P3A-T24, T25 | Blank, duplicate, 32 words, 64 characters. | `HiddenKeywordsTest`, `PeriodAndKeywordSettingTest` |
| P3A-T26, T27 | Switching off or deleting brings the articles back. | `DailyReadingFiltersTest`, `HomeViewModelFiltersTest`, `PeriodAndKeywordSettingTest` |
| P3A-T28, T29 | Hiding changes neither read nor saved nor the stored articles. | `DailyReadingFiltersTest` |
| P3A-T30 | Keyword with period and search. | `DailyReadingFiltersTest` |
| P3A-T31 | The mark-all set equals the unread rows of the visible list, for every filter combination, and it leaves hidden and out-of-period articles unread. | `DailyReadingFiltersTest`, `HomeViewModelFiltersTest` |
| P3A-T32, T33 | The banner counts announced articles the list shows (`newArticlesIn`): a hidden arrival makes no banner and stays pending; a looser filter counts it. | `DailyReadingFiltersTest` (the function; the banner composable itself is not run in JVM tests) |
| P3A-T34 | A store written by 0.8.0 reads as All, no keywords, off, and reading writes nothing. | `PeriodAndKeywordSettingTest` |
| P3A-T35 | Keyword, search and cutoff are bind arguments: never in the SQL text, same SQL for any text, argument count. | `DailyReadingFiltersTest` |

### 4.11 Navigation and filter separation (0.9.1, P3A-F01 to P3A-F04)

| ID | Scenario | Where |
|---|---|---|
| P3A-F01 | The drawer and the pane (one `HomeNavigationContent`) have no "Saved articles" entry; Saved is only the filter of the display panel. | Removed parameter and string (does not compile otherwise); `DailyReadingUiTest` (drawer has no such text) |
| P3A-F02 | Changing the scope (drawer, pane, tabs, picker, a source, Back from a source) keeps the read filter, period, search, order and the hidden keywords; "All articles" resets none of them. | `HomeViewModelFiltersTest`, `DailyReadingUiTest` |
| P3A-F03 | Changing a filter does not move the scope. | `HomeViewModelFiltersTest` |
| P3A-F04 | A single feed opened from the Saved filter keeps the Saved filter. | `HomeViewModelFiltersTest` |

### 4.12 Share to Subscribe (0.9.2, P3B-T01 to P3B-T36)

JVM tests run the real network stack (OkHttp client with the app's address policy, HTTP opt-in and TLS verification), `FeedProbe`, the parser, Room and a real `SubscriptionsViewModel` against a local MockWebServer; `MainActivityShareTest` drives the real `MainActivity` under Robolectric. `ShareOverlayUiTest` shows the dialog on a device and needs one.

| ID | Scenario | Where |
|---|---|---|
| P3B-T01..T03 | An address alone, text before and after it, Japanese text (punctuation, brackets, full-width space, text touching the address). | `SharedUrlExtractorTest` |
| P3B-T04 | Several addresses are all returned, in order; the flow waits for the user's choice and fetches nothing meanwhile; an address that was not offered cannot be chosen. | `SharedUrlExtractorTest`, `ShareSubscriptionControllerTest` |
| P3B-T05..T07 | Duplicates removed after normalizing; trailing punctuation and unmatched closing brackets dropped (Wikipedia-style brackets kept); only http/https taken (`javascript:`, `file:`, `content:`, `data:`, `ftp:`, `view-source:` and user info refused); at most 8. | `SharedUrlExtractorTest` |
| P3B-T08 | Text above 64 Ki characters is refused unread; pathological inputs are handled in bounded time. | `SharedUrlExtractorTest`, `ShareSubscriptionControllerTest` |
| P3B-T09, T10 | A direct RSS / Atom address is a candidate with title, site and entry count; nothing is stored. | `ShareToSubscribeTest` |
| P3B-T11, T12 | A page with one declared feed; a page with several (only verified feeds, in page order; at most 8 looked at). | `ShareToSubscribeTest` |
| P3B-T13, T14 | A page without feed links is "no feed"; an HTML page at a declared or entered feed address is never a candidate. | `ShareToSubscribeTest` |
| P3B-T15 | A redirect inside a candidate gives the final address; a redirect of the shared address is followed and links resolve against the final page. | `ShareToSubscribeTest` |
| P3B-T16 | Untrusted certificate (nothing past the handshake), unreachable host, error status, unsafe (non-public) address, malformed address: each a different problem. | `ShareToSubscribeTest` |
| P3B-T17 | `http://` while HTTP is off: refused, the server receives nothing; allowed once the setting is on. | `ShareToSubscribeTest` |
| P3B-T18 | Cancel and a newer request invalidate an older run, including one that ignores cancellation; a late result brings nothing back. | `ShareSubscriptionControllerTest` |
| P3B-T19 | Before Subscribe: no feed row, no refresh. | `ShareToSubscribeTest`, `ShareSubscriptionControllerTest` |
| P3B-T20 | Subscribe registers once (a second tap does nothing), through `SubscriptionsViewModel.addFeed`, and ends as Done. | `ShareToSubscribeTest`, `ShareSubscriptionControllerTest` |
| P3B-T21 | An already subscribed feed (also when known under the declared address of a redirect) is shown as such; no registration, no second row. | `ShareToSubscribeTest`, `ShareSubscriptionControllerTest` |
| P3B-T22 | An unsubscribed feed is not subscribed by seeing it; Subscribe again keeps the same row and its categories. | `ShareToSubscribeTest`, `ShareSubscriptionControllerTest` |
| P3B-T23 | HTTP pending: Share verifies a feed before offering it, so with HTTP off no `http://` feed is offered or registered (the existing add-feed "pending" rule applies to the path itself). | `ShareToSubscribeTest` |
| P3B-T24 | After subscribing the add path's one refresh fetches the new feed exactly once. | `ShareToSubscribeTest` |
| P3B-T25..T27 | Cold start, warm start (`onNewIntent`) and recreation on the real activity: one request each, none replayed. | `MainActivityShareTest`, `ShareIntentTest` |
| P3B-T28 | Cancel and Back close the dialog and change nothing (dialog on a device). | `ShareSubscriptionControllerTest` (state), `ShareOverlayUiTest` (not run) |
| P3B-T29 | Scope, filters, search and scroll under the dialog: the dialog is not a route and writes none of them. | By construction (no navigation or home state is touched; reviewed); on a device not run |
| P3B-T30 | Sharing the same text later is a new request. | `ShareSubscriptionControllerTest`, `MainActivityShareTest` |
| P3B-T31 | A share before first-run setup waits and is handled after it; setup is not skipped. | `ShareSubscriptionControllerTest`, `MainActivityShareTest` |
| P3B-T32 | Malformed intent (no text, text that is not text, another type, another action) does not crash. | `ShareIntentTest`, `MainActivityShareTest` |
| P3B-T33 | A launcher start, and a launcher tap on a running instance, are not shares; the manifest keeps the launcher entry and adds no permission. | `ShareIntentTest`, `MainActivityShareTest` |
| P3B-T34 | "Add feed URL" unchanged: the dialog calls the same `addFeed` with the same message, HTTP rule and one refresh (the only change: a failing account lookup is now reported as an error message instead of escaping the coroutine). | `ShareToSubscribeTest` (the share path through the same function); no dialog test on a device |
| P3B-T35 | The moved-feed search is unchanged on top of `FeedProbe`. | `FeedDiscoveryTest` (unchanged), `ShareToSubscribeTest.theMovedFeedSearchStillSharesTheSameCore` |
| P3B-T36 | A cancelled share leaves feeds and settings as they were. | `ShareToSubscribeTest`, `ShareSubscriptionControllerTest` |

### 4.13 0.9.3 polish: root fallback, one fetch, no fixed waits, Custom colour set (P3C-T01 to P3C-T30)

The Share rows run the real network stack against a local MockWebServer and count the requests it received (`ShareToSubscribeTest`). The Custom rows are pure JVM tests of the generated schemes and of the settings store; the picker on screen is `CustomColorUiTest` (needs a device).

| ID | Scenario | Where |
|---|---|---|
| P3C-T01 | An article with no feed: the feed of the site root is found; requests are article 1, root 1, feed 1 and nothing else. | `ShareToSubscribeTest.anArticleWithoutAFeedFindsTheFeedOfTheSiteRootExactlyOnce` |
| P3C-T02 | An article with a valid feed: the root is not requested (also for a direct feed). | `aValidFeedOfTheSharedPageMeansTheRootIsNotRead`, `aDirectFeedNeverReadsTheRoot` |
| P3C-T03 | Every feed link of the article fails (404, HTML): the root fallback still runs. | `feedLinksOfTheSharedPageThatAreAllBrokenStillAllowTheRootFallback` |
| P3C-T04 | The shared URL is the root: no second root request; with a query it is not the root and the plain root is read once. | `aSharedRootThatHasNoFeedIsReadOnceNotTwice` |
| P3C-T05 | A redirect to another origin: the root of the final page's origin is read, not that of the first. | `theRootOfAFinalPageAfterARedirectIsTheRootOfTheFinalOrigin` |
| P3C-T06 | Policy kept: HTTP off sends nothing; a root that ends on another origin gives nothing; no guessed address or deeper page is requested. TLS, address-policy and redirect rules are the shared client's (`ShareToSubscribeTest` TLS/BLOCKED rows, `NetworkSecurityTest`). | `theRootFallbackKeepsTheHttpRule`, `aRootThatLeadsToAnotherOriginGivesNothing`, `aRootThatHasNoFeedEndsTheSearchAndNothingDeeperIsTried` |
| P3C-T07 | The root has no feed: the search ends as "no feed". | `aRootThatHasNoFeedEndsTheSearchAndNothingDeeperIsTried` |
| P3C-T08 | Two ways to one final address are one candidate; an address the page already failed on is not asked again. | `aFeedAddressedByThePageAndByTheRootIsOneCandidateAndFetchedOnce` |
| P3C-T09 | A feed from the root carries the site-root provenance and the dialog says so. | `ShareToSubscribeTest` (provenance), `ShareOverlayUiTest.aFeedFoundOnTheSiteRootSaysSo` (not run) |
| P3C-T10, T11 | Direct RSS and direct Atom: exactly one request. | `directRssAndAtomAreFetchedOnceEach`, `directRssIsACandidateWithItsFacts` |
| P3C-T12 | Redirect to a feed: one chain, no second check; a direct feed larger than the page limit is read in full. | `aRedirectToAFeedIsOneChainAndNoSecondCheck`, `aDirectFeedLargerThanAPageIsStillReadInFull` |
| P3C-T13 | HTML with declared feeds: 1 request plus 1 per candidate. | `anHtmlPageWithDeclaredFeedsIsOneRequestPlusOnePerCandidate` |
| P3C-T14 | No fixed sleep or delay in the Share tests. | `NoFixedWaitsInShareTestsTest` |
| P3C-T15 | The Share tests repeated (8 runs of 73 tests, cleaned between runs) all pass. | Run by hand (not a test); see ACCEPTANCE_RESULTS |
| P3C-T16, T17 | `CUSTOM` and the seed persist across a restart; a store from before 0.9.3 reads as before and nothing is written by reading; alpha is ignored. | `CustomPaletteSettingTest` |
| P3C-T18, T19 | Light and dark schemes are generated from one seed, are complete (every family differs between seeds), deterministic, and share the seed's hue. | `CustomPaletteTest` |
| P3C-T20..T22 | Black, white, bright yellow, saturated red, dark navy and a sweep of 144 hue/strength seeds (plus greys, pure primaries): every text, icon, accent and outline pair reaches its contrast in light and dark. | `CustomPaletteTest`, `PalettesTest` (Custom at the default seed in the existing checks) |
| P3C-T23 | Cancel applies nothing. | `CustomColorUiTest` (not run); the draft stores nothing by itself (`CustomColorDraftTest`) |
| P3C-T24 | Apply hands out the colour once and writes seed and set in one step. | `CustomColorUiTest` (not run), `CustomPaletteSettingTest.applyWritesTheSeedAndTheSetInOneStep` |
| P3C-T25 | An invalid code is never applicable and never changes the preview. | `CustomColorDraftTest`, `CustomPaletteTest` (parser), `CustomColorUiTest` (not run) |
| P3C-T26 | Reset to default returns the seed only. | `CustomPaletteSettingTest.anotherSetKeepsTheSeedAndResetReturnsToTheDefault`, `CustomColorUiTest` (not run) |
| P3C-T27 | The seed survives a restart (store); the open picker's draft survives rotation (saved state). | `CustomPaletteSettingTest`; the saver is exercised on a device only (not run) |
| P3C-T28, T29 | Leaf, Sand, Violet keep every colour of 0.9.2 (written out); Classic is pinned exactly; the existing sets do not depend on the seed. | `ExistingPalettesUnchangedTest`, `PalettesTest.classicIsExactlyTheSetOf060` |
| P3C-T30 | Custom draws tinted bars (every set but Classic does). | `ExistingPalettesUnchangedTest.customUsesTintedBarsAndClassicDoesNot` |

### 4.14 Full backup and restore (0.10.0, BR-T01 to BR-T47)

The specification numbers these T01–T47; they are BR-T01–BR-T47 here (P3C-* is taken by 0.9.3). All rows are JVM tests in
`app/src/test/.../data/backup/` over in-memory Room, a file DataStore and the real repositories (`BackupFixture.kt`:
`BackupDevice` is one install, `RichState` builds a used install through the app's own write paths, `LogicalState`
dumps every backed-up table and setting without install-specific ids). Failures are injected through the service's
step hook, a DataStore that fails writes, and an `Error` that stands for the process being killed. A device run
(file pickers, the restore screen, a real process restart) is not automated; the release-signing walk-through is
manual ([backup-format.md](backup-format.md) section 7).

| ID | Scenario | Where |
|---|---|---|
| BR-T01 | An empty install backs up and restores; the seven entries in order. | `BackupRoundTripTest.t01_emptyInstall` |
| BR-T02..T11, T30, T32..T36 | A used install (nested categories, a feed in two categories, order, pin, per-category layout with 0 and 5 columns, HTTPS migration, URL move, unsubscribed and paused feeds, articles with Japanese and JSON special characters, read and saved, a saved article of an unsubscribed feed, pruned entries, every setting away from its default, Custom seed and five light and five dark colours, hidden keywords, period) restores into a fresh install identically. | `t43_richStateRestoresIdenticallyIntoAFreshInstall` |
| BR-T08 | Pruned entries travel and still stop a removed article from returning unread; past 90 days they are not restored. | `t08_prunedEntries` |
| BR-T12 | No validators, failures, waits, pending requests, icons, diagnostics or install bookkeeping in the archive. | `t12_bookkeepingIsNotBackedUp` |
| BR-T13 | Backups taken while feeds, articles, read state, categories and settings change are consistent snapshots. | `t13_snapshotIsConsistentUnderConcurrentChanges`, `backupDoesNotBreakAConcurrentWriter` |
| BR-T14, T46 | OPML, text, empty file, other ZIPs are not backups; a backup given to the OPML parser is refused without a crash. | `BackupArchiveSecurityTest.t14_wrongFiles`, `t46_backupIsNotAnOpml` |
| BR-T15 | Broken JSON in every entry, trailing data, wrong types, a cut-off ZIP. | `t15_malformedJson` |
| BR-T16 | A missing entry or required field. | `t16_missingEntryOrField` |
| BR-T17 | A newer (or zero) format version, also with sections this version cannot read. | `t17_futureFormat` |
| BR-T18 | Duplicate entries (names patched into the ZIP). | `t18_duplicateEntry` |
| BR-T19 | Traversal, absolute, backslash, drive, directory, unknown and out-of-order entries. | `t19_pathsAndUnexpectedEntries` |
| BR-T20 | File, entry, total and small-entry limits; a 64 MB ZIP bomb by ratio; stored entries. | `t20_sizeLimitsAndZipBomb` |
| BR-T21 | Duplicate feed id, subscription URL, category id and path, article id and key, state, pruned entry, membership. | `t21_duplicateIds` |
| BR-T22 | References to missing feeds and articles, a category without its parent, an article without state, wrong counts, another account kind. | `t22_brokenReferences` |
| BR-T23 | `file:`, `javascript:`, `content:`, `ftp:`, credentials, non-URLs; ids outside the id alphabet; an HTTPS migration that is not http → https. | `t23_invalidUrls` |
| BR-T24 | Unknown enum names, columns, text size, interval, limits, Custom colours, keywords, scope, times, lengths, category names. | `t24_invalidValues` |
| BR-T25 | Every refusal leaves data and settings exactly as they were. | `assertRefused` in every row above |
| BR-T26 | Checking and restoring an archive whose feeds point at a local server sends it no request; the backup code has no network imports. | `t26_noNetworkWhileCheckingOrRestoring`, `t26_backupCodeHasNoNetworkPath` |
| BR-T27 | The preview's counts equal the source install's; it shows what is on the device now. | `BackupRoundTripTest.t27_previewCountsAreExact` |
| BR-T28, T29 | Inspecting and cancelling change nothing and delete the private copy. | `t28_t29_previewAndCancelChangeNothing` |
| BR-T31 | Restoring over a used install leaves nothing of it; its account and last refresh attempt stay. | `t31_replacesANonEmptyInstall` |
| BR-T32 | After a restore an old OPML matches the restored feeds and does not move a feed back to HTTP. | `t32_identityHistoryWorksAfterRestore` |
| BR-T37 | Fetch bookkeeping starts fresh (only "fetched before" kept); addresses and history kept. | `t37_fetchBookkeepingStartsFresh` |
| BR-T38 | Refresh is held and running refreshes are cancelled during a restore; a restore starts no refresh; periodic work switched on by a restore waits one interval. | `RestoreAftermathTest.t38_refreshIsHeldDuringARestore`, `t38_restoreStartsNoRefresh`, `periodicWorkAfterARestoreWaitsOneInterval` |
| BR-T39 | A last selection naming nothing restored opens All; one that exists is restored. | `BackupRoundTripTest.t39_missingLastScopeOpensAll` |
| BR-T40 | The settings write fails: nothing changed. | `RestoreRollbackTest.t40_settingsWriteFails` |
| BR-T41 | The database replace fails before commit: data and settings unchanged; if the settings cannot be put back at once, the next start does it. | `t41_databaseReplaceFails`, `t41_databaseFailsAndSettingsCannotBeRevertedYet` |
| (process death) | Killed before the settings, between settings and database, inside the transaction, after the commit: the next start leaves a consistent install. Busy background work stops a restore before any change. | `processDeath*`, `busyBackgroundWorkStopsTheRestoreBeforeAnyChange`, `backgroundWorkIsPausedAroundTheRestore` |
| BR-T42 | After a restore a manual refresh works: known articles keep their state, the new one is announced, no old validators are sent. | `RestoreAftermathTest.t42_manualRefreshAfterRestore` |
| (§8) | The home list switches to the restored selection, leaves search and starts at the top. | `RestoreAftermathTest.homeListFollowsTheRestore` |
| BR-T43 | Rich state → backup → fresh install → restore → identical logical state. | `t43_richStateRestoresIdenticallyIntoAFreshInstall` |
| BR-T44 | Backup → restore → backup: the same archive apart from the creation time. | `t44_backupOfARestoreEqualsTheOriginalBackup` |
| BR-T45 | OPML import and export unchanged. | The existing OPML tests (`OpmlParserTest`, `ImportRepositoryTest`, `OpmlPortabilityTest`, `CategoryAndExportTest`) |
| BR-T47 | Debug backup → uninstall → differently signed install → restore → compare. | Manual, at the release candidate ([backup-format.md](backup-format.md) section 7) |
| (§17) | 1,000 feeds and 30,000 articles with long Japanese summaries and JSON special characters round-trip exactly. | `largeInstallRoundTrips` |
| (own data) | A category name stored untrimmed and not in NFC, and a very long feed name, are restored as they are (the reader's own backup is not refused by today's input rules). | `olderStoredNamesAreRestoredAsTheyAre` |
| (later field) | An archive without `restoreLastScope` (written by 0.10.0) is read with the switch off; a non-boolean value is refused. | `BackupArchiveSecurityTest.settingAddedLaterIsOptional`, `t24_invalidValues` |

### 4.15 Start tab and cache sizes (0.10.1)

| ID | Scenario | Test |
|---|---|---|
| ST-T01 | A start opens All by default, also for a store of 0.10.0 or earlier that names a category; the read filter is restored. With "open the last category at start" on, the last tab opens. A screen restored after process death keeps its tab either way. | `HomeViewModelFiltersTest.aStartOpensAllUnlessTheLastTabIsKept`, `PeriodAndKeywordSettingTest` |
| ST-T02 | A thumbnail cache opened with a smaller size than it holds is trimmed, least recently used first, without waiting for a new image; one within its size is left alone. | `ImageCacheTrimTest` |
| ST-T03 | The article limit removes only read, unsaved articles (unchanged); unread and saved articles can keep the count above it. | `DataKeepingTest`, `ReviewFixesTest` (unchanged) |
