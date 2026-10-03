# Testing

This document explains how to run FeedDeck's tests, how they are layered, what the synthetic fixtures are, and which scenarios the tests cover. Test names, class comments and source comments cite scenario IDs (`T15`, `N12`, `V3-R03`, ...); this page is where those IDs are defined. Requirement IDs (`R01`-`R20`) and screen IDs (`S00`-`S40`) are defined in [product-and-ux.md](product-and-ux.md).

## 1. Running the tests

Prerequisites (JDK, Android SDK platform and build-tools versions, `local.properties`) are in [building.md](building.md).

### JVM tests, lint and debug APK

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `.\gradlew.bat`. `tools/verify.sh` and `tools/verify.ps1` wrap the same command; pass `--device-tests` (shell) or `-DeviceTests` (PowerShell) to run the device tests after it. CI (`.github/workflows/android.yml`) runs this command and the helper-script tests below on every push and pull request.

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

These tests need an emulator or a device running Android 9 (API 28) or later. They use seeded data only and make no network requests. Things to know:

- Use a test device or emulator, never a device that holds your own subscriptions. The tests clear the app's database (`clearAllTables`) and rewrite its settings, and Gradle uninstalls the app after the run, which also removes its data and per-app language setting.
- Keep the screen awake while the tests run (`adb shell svc power stayon true`).
- An API 28 emulator may need software rendering (`-gpu swiftshader_indirect`) for Compose tests to start reliably.
- Do not truncate a running `gradlew` with a pipe. If adb log files stay locked afterwards, run `gradlew --stop`, delete `app/build/outputs/androidTest-results` and rerun.
- If you could not run the device tests, say so in your pull request rather than assuming they pass.

### Helper-script tests

```sh
python3 -m unittest tools.test_compare_app_state tools.test_layout_upgrade_check
```

On Windows use `python -X utf8 -m unittest ...`. Run from the repository root. These tests need no device (a fake adb stands in for the real one).

### Development helper scripts

The scripts in `tools/` are development aids; they are not used by the app. The ones that talk to a device expect `adb` at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (Windows) and should be run with `python -X utf8`.

| Script | Purpose |
|---|---|
| `tools/verify.sh`, `tools/verify.ps1` | Run the Gradle verification command (and optionally the device tests). |
| `tools/adb_ui.py` | A tiny adb and uiautomator driver for manual emulator checks: `dump`, `texts`, `tap TEXT`, `tapxy X Y`, `type TEXT` (ASCII only), `key KEYCODE`, `shot PATH`, `wait TEXT [SECONDS]`, `swipe X1 Y1 X2 Y2 [MS]`, with an optional `-s SERIAL`. |
| `tools/compare_app_state.py` | Upgrade check. Compares two pulled copies of the app's Room database and DataStore and exits with code 1 when something that must not change did. `--mode install` (right after an overwrite install, before any fetch): nothing may differ in subscriptions, categories, articles, reading state or settings. `--mode after-refresh` (after one ordinary refresh): new articles are allowed and existing articles may change only the columns a refresh rewrites; their ID, key, feed and read/saved state may not change. Usage: `python -X utf8 tools/compare_app_state.py --mode install BEFORE_PREFIX AFTER_PREFIX`. |
| `tools/layout_upgrade_check.py` | For each list-layout value that version 0.3.1 could store, installs an old APK, writes the old settings file, records the screen, installs the new APK over it without uninstalling, records again, and fails unless the new setting holds the expected new name, the old key is untouched and the two screens show the same articles in the same places. Usage: `python -X utf8 tools/layout_upgrade_check.py [-s SERIAL] OLD_APK NEW_APK [OUT_DIR]`. Test devices only; it replaces the app and rewrites its settings file. Any adb failure stops it with exit code 2. |
| `tools/make_icon_fixtures.py` | Regenerates the synthetic images in `fixtures/icons/`. |
| `tools/fixtures/datastore-settings-0.3.0.preferences_pb` | A DataStore settings file as an older version wrote it, for upgrade checks. |

## 2. Test layers

| Layer | Where | What it uses | What it is for |
|---|---|---|---|
| JVM | `app/src/test` | JUnit, Robolectric (plain `Application`, no WorkManager side effects), in-memory Room, MockWebServer bound to `127.0.0.1`, a settable clock | Parsers, URL rules, address policy, repositories, refresh coordination, the full fetch pipeline against a local server |
| Static guards | `app/src/test` | Source scanning | Architecture boundaries (`T48`), no code path that sets read state automatically (`T29`), the HTTP-open confirmation setting is not read by network code (`U07`) |
| Device | `app/src/androidTest` | Compose UI tests on a real Android runtime, the production `AppContainer`, seeded data | UI flows, list layouts, pager, Room migrations, icon decoding, settings upgrade, performance measurement |
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
| `NewArticleBannerTest` | The new-articles banner on the real list composable in all three layouts, both sort orders, neighbour pages, arrivals at the head (`V31-T02`, `V3-R03`) |
| `MigrationTest` | Room v1 to v2 and v2 to v3 migrations validated against exported schemas, data kept, chained 1 to 3, an unknown newer version fails instead of wiping data (`T38`, `N25`) |
| `ListLayoutUpgradeTest` | A real DataStore file in the pre-0.3.2 format is migrated and the layout that is then drawn is the one the user had |
| `IconDecoderTest` | Source-icon decoding on a real Android runtime: PNG, ICO with PNG entry, ICO with BMP entry, wide logo, 512 px image, non-images (`U08`) |
| `PerformanceTest` | Synthetic 300 feeds / 5,000 articles: warm start to a visible list and local search timings (`T47`) |

`TestData` seeds the app through its own repositories; there is no demo mode in the app.

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
| T07 | R03, R05 | OPML round trip. Multiple memberships, Japanese text, XML special characters, paused feeds and HTTP-pending feeds are exported, and re-importing into an empty database gives the same URL set and direct memberships. Read and saved state are not exported. | Room + file I/O | `CategoryAndExportTest` |
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
| T35 | R15, R07 | New arrivals. A fetch while reading mid-list is announced by the banner and the position holds until it is tapped. | UI + HTTP | - (exercised by `V31-T02`) |
| T36 | R13 | Japanese search. Japanese substrings, Latin, whitespace, and `%`, `_` as literal text; the scope and filter are respected; an old search response never overwrites a newer query. | Room + UI | `ArticleQueryTest` |
| T37 | R14, R09 | Settings and image traffic. List layout, text size and theme are restored. With images off there are no image requests; clearing the cache removes images only. Public articles trigger no Open Graph fetches in the background. | UI + HTTP | `HomeFlowTest` |
| T38 | R16, R18 | Database persistence and migration. Subscriptions, categories, articles and state survive being killed mid-operation and a schema change. There is no destructive fallback. The migration test infrastructure exists even when no earlier schema had been released. | Room + device | `MigrationTest`, `DataKeepingTest`, `DatabaseSmokeTest` |
| T39 | R01, R16 | Offline. Stored lists, search, saved and read state work. Web pages are not claimed to be saved. Uncached images show the placeholder. | Device | - |
| T40 | R17 | XML hardening. DOCTYPE, external DTD, entities and XInclude are rejected with no external access or local file read. UTF-16 and other encodings cannot slip past an ASCII string check. | JVM + HTTP | `OpmlParserTest`, `FeedParserFixtureTest` |
| T41 | R17 | URL and DNS. Unsafe schemes, user-info, loopback, private, IPv6 ULA, link-local and dangerous redirects are rejected. The DNS result that was validated is the one connected to (no split). Applies to images too. | HTTP + fake DNS | `NetworkSecurityTest` |
| T42 | R17 | HTTP and TLS. With the HTTP setting off, no request is sent for first fetch, redirect or image. With it on, TLS and hostname checks still apply and an invalid certificate fails. No test-only trust exists in release. | HTTP + release inspection | `NetworkSecurityTest` |
| T43 | R17 | Size and image limits. Compressed responses, deeply nested XML, huge attributes, 2,001 entries, huge images and broken images stop within limits with no UI failure, OOM or endless retry. | JVM + HTTP | `NetworkSecurityTest` |
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
| N07 | Arrivals only in another category, under the Saved filter or under a search do not produce an unrelated banner. | - |
| N08 | In both newest-first and oldest-first order the banner moves to the new articles, and nothing scrolls before the user acts. | `NewArticleBannerTest` |
| N09 | The three layouts (List, Compact list, Magazine) and the selection persist across restart and keep the same article near the top. | `PagerAndMagazineTest` |
| N10 | Magazine cards with image, without image, failed image, images off, long Japanese titles, 200% text and dark theme. | - |
| N11 | Atom XHTML `A<b>B</b>C` becomes `ABC`; in-content images and `xml:base` resolve safely; tracking pixels and dangerous URLs are excluded. | `XhtmlContentTest` |
| N12 | Stopping a worker-only refresh cancels its queued and running fetches; feeds already finished stay applied. | `FetchCoverageTest` |
| N13 | A fetch shared by the worker and a manual request survives the worker's stop and serves the manual request. | `FetchCoverageTest` |
| N14 | A 20-entry feed reports 20; duplicates are counted as merged; new and updated counts are right in the diagnostics. | `FetchCoverageTest` |
| N15 | A 304 keeps the previous document's entry counts and does not confuse "0 new" with the feed's count. | `FetchCoverageTest` |
| N16 | 2,001 entries, oversized body, deep XML and broken XML are told apart, and old articles and state stay. | `FetchCoverageTest` |
| N17 | Time budget exhaustion, HTTP not allowed, network constraint and backoff are visible. | `FetchCoverageTest` |
| N18 | The old hours-based setting migrates to the same duration in minutes (2 hours never becomes 2 minutes); 15 and 30 minutes and Off can be scheduled and cancelled. | `DataKeepingTest`, `UpdateConstraintsTest` |
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
| U05 | Pre-composing a neighbour page does not consume new-article markers. | `NewArticleBannerTest` |
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
| V3-R03 | The new-articles banner also clears when the marker is added after the article is already on screen. | `NewArticleSeenTest`, `NewArticleBannerTest` |
| V3-R04 | A cancelled or replaced moved-feed discovery never writes its outcome into the state of the newer one; an adopted candidate is tied to the fetch URL it was found for. | `DiscoveryControllerTest`, `FeedDiscoveryTest` |
| V3-R05 | What a page is follows from its root element, not from a leading `<?xml` (XHTML pages are pages; feeds with a BOM or comments before the root are feeds). | `HtmlRootElementTest`, `FeedDiscoveryTest` |
| V31-R01 | After an address change, the refresh coordinator does not coalesce a new request into an older in-flight request for the old address (normal, forced and All refreshes); repeated taps with an unchanged address still fetch once. | `RefreshAfterAddressChangeTest` |
| V31-R02 | A request that did not fetch a feed because its address changed reports it as replaced (history), not as an unfinished or "not fetched" feed. | `RefreshAfterAddressChangeTest` |
| V31-C01 | The upgrade checker scripts really fail on the differences they exist to catch (self-tests of `tools/compare_app_state.py` and `tools/layout_upgrade_check.py`). | `tools/test_compare_app_state.py`, `tools/test_layout_upgrade_check.py` |
| V31-C02 | A replaced feed is not counted as unfinished work, nor offered for retry. | `ReplacedFeedStatusTest` |
| V31-T01 | Upgrading by overwrite install (without uninstalling) keeps rows, reading state and settings unchanged, and a following ordinary refresh creates no duplicates. | `tools/compare_app_state.py` (manual device procedure) |
| V31-T02 | The new-articles banner behaves correctly on the real list composable in all layouts and both orders. | `NewArticleBannerTest` |
| V31-T03 | Tabs, underline, pages and the saved selection agree after drags, quick taps and distant tabs at large text; back to top works in every layout and does not change other categories' positions or any state. | `PagerAndMagazineTest` |

### 4.6 Design topics cited in source comments

Source comments name the design topic a piece of code belongs to. They are not test IDs.

| Tag | Topic |
|---|---|
| SYNC-02 | A manual category or feed refresh acts on its own target; filter, sort and search are display conditions only. |
| SYNC-03 | Per-feed forced refetch: stored validators are not sent and caches are asked to revalidate. |
| SYNC-04 | Tracing a cause from request to storage to display: per-feed request states (queued, running, updated, not modified, failed, deferred, cancelled, inactive). |
| DATA-01 | Fetch diagnostics per feed: the last request, the last real HTTP exchange and the last applied document, kept apart. |
| CFG-02 | Retention ("articles to keep") and image cache settings; deletion only after consent, unread and saved articles protected. |
| REV-01 | Each category keeps its own scroll position. |
| REV-02 | The new-articles banner: its state, scope and where it moves to. |
| REV-03 | Atom XHTML text order and in-content images. |
| REV-05 | Pager loading design: only visible pages hold a database subscription. |
| UX-01 | Switching categories by horizontal swipe. |
| UX-GRID | The multi-column Magazine layout and its card rules. |
| UX-ICON | Source icons. |
| SUB-BULK | Bulk clean-up of failing subscriptions. |
| SUB-DISCOVERY | Finding where a moved feed went, on the feed's own site. |

Scenario IDs `V3-R01`-`V3-R05` and `V31-R01`/`V31-R02` also appear in source comments next to the code that implements the rule they describe.
