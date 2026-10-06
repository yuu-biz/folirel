# Architecture

Technology choices, package layout, data flow and the one extension boundary of Folirel. Data and import rules are in
[data-and-import.md](data-and-import.md); fetching, refresh scheduling and security are in
[fetch-and-security.md](fetch-and-security.md). Product overview: [../README.md](../README.md).

Section numbers are stable and are cited from source code comments (for example "architecture.md section 4" for the backend
contract).

## 1. Stack

Native Android, Kotlin, Jetpack Compose, one Gradle module (`:app`) with packages separating responsibilities. The
minimum SDK is 28 and the code targets the current SDK; all dependency versions are fixed in
`gradle/libs.versions.toml` (no dynamic or snapshot versions).

| Area | Choice | Notes |
|---|---|---|
| UI | Jetpack Compose, Material 3 | Density-tuned lists, drawer, category tabs in a pager, accessibility text scaling |
| State | ViewModel, `StateFlow`, coroutines | UI does no I/O; state follows the lifecycle |
| Database | Room 2.8 with KSP | Exported schemas, explicit additive migrations, transactions |
| Preferences | DataStore (Preferences) | Kept separate from reading state in Room |
| HTTP | OkHttp 5 | One client factory and network guard for everything (see fetch-and-security.md section 6); conditional GET |
| Images | Coil 3 with the OkHttp fetcher | Uses the same guarded client family and size limits |
| XML | Platform `XmlPullParser` (`android.util.Xml`) behind `SafeXmlReader` | RSS 2.0, Atom 1.0, RSS 1.0, OPML; namespace aware; hardened (section 6 of fetch-and-security.md) |
| HTML to text | jsoup | Parses markup to a tree for text and link extraction; nothing is rendered or executed |
| JSON | kotlinx.serialization | Category paths only |
| Periodic work | WorkManager | Network constraints, OS scheduling |
| Browser | AndroidX Browser (Custom Tabs), `ACTION_VIEW` fallback | The app does not manage cookies and has no WebView |
| Dependency injection | Hand-written `AppContainer` | No DI framework |
| Tests | JUnit 4, Robolectric, coroutine test, OkHttp MockWebServer, Room and WorkManager testing, Compose UI tests, UI Automator | JVM tests for logic, instrumented tests for Room migrations, UI and upgrades |

## 2. Package layout

```text
app/src/main/java/io/github/yuubiz/folirel/
  app/                    Application, AppContainer, MainActivity (navigation), ShareIntent (Intent to share request), DocumentIo (SAF)
  model/                  Backend-independent models: scopes, list models, normalized feed, errors, subscription tree
  data/db/                Room entities, DAOs, migrations
  data/preferences/       DataStore settings
  data/repository/        FeedStore, SubscriptionRepository, ArticleRepository, FeedIconRepository, RetentionManager
  backend/                BackendSynchronizer contract, refresh scopes, results
  backend/local/          LocalBackend, FeedFetcher, SmallResourceFetcher, HtmlLinks, HttpsUpgradeChecker
  backend/local/parser/   FeedParser, SafeXmlReader, XmlCharset, FeedDates, HtmlText, EntryKeys
  backend/local/opml/     OpmlParser, OpmlWriter
  backend/local/icon/     FeedIconResolver, IconDecoder
  backend/local/discovery/ FeedProbe (page and feed reading shared by both searches; one read per address, optional same-origin root fallback), FeedDiscovery (in-site search for a moved feed)
  network/                NetworkGuard, redirect handling, HttpClients, AddressPolicy, UrlNormalizer, SharedUrlExtractor, NetworkStatus
  sync/                   RefreshCoordinator, RefreshWorker/Scheduler, AutoRefreshPolicy, HTTPS-migration,
                          icon and discovery coordinators, ShareDiscoveryService (Share to Subscribe: what a shared address offers)
  ui/home/                Article lists, drawer, tabs, search
  ui/subscriptions/       OPML import, subscription management, categories, feed detail
  ui/share/               Share to Subscribe: the request state machine (ShareSubscriptionController) and its dialog
  ui/settings/            Settings: top page and its pages (appearance, default layout, opening, refresh and network, storage, about)
  ui/common/              Controls shared by Settings and the home screen: layout picker, choice, selection dialog row, switch row, slider row
  ui/onboarding/          First-run screen
  ui/theme/               Colour sets (Palettes; CustomPalette generates the Custom set from one seed colour), bar colours and the theme; light/dark and the colour set are independent
  browser/                BrowserLauncher
```

The code is not split into many feature or core modules. Splitting is justified only when test volume, build time or
a real dependency boundary requires it.

## 3. Data flow

For reading: UI, then ViewModel, then Repository, then Room, then a `Flow` back to the UI. Room is the only source
for the article lists; feed responses are never streamed straight to a screen, and online and offline use the same
list logic. List queries are built from constant SQL fragments, and every user-supplied value (ids, search terms) is
a bind argument.

For updating: ViewModel or Worker, then `RefreshCoordinator`, then `BackendSynchronizer` (`LocalBackend`), then
`FeedFetcher` over the guarded OkHttp client, then `FeedParser`, then `FeedStore`, which applies one feed per
transaction. The parser produces `NormalizedFeed` (plain Kotlin data, no database types). `FeedStore` is the only
writer of fetched content.

Opening an article goes from a UI event to `BrowserLauncher` and is independent of refresh and storage.

Navigation is a small string-route back stack held in `MainActivity` (routes survive process death). The home screen is
a pager over the selectable scopes (*All*, *Uncategorized* when present, then the categories that contain feeds), in
the same order as the tabs; only composed pages observe the database. Three list layouts exist: **List** (one column
with summary and thumbnail), **Compact list** (one dense row per article) and **Magazine** (a grid of cards
whose sizes are chosen from a hash of the article id, packed into gap-free rows; an "even" arrangement is an option;
sixths up to three columns, one unit per column on four and five: `MagazinePlan.gridSpans`). Masonry and Bento use up to five
columns as well (`BentoPlan` has patterns for four and five columns).
The list position is remembered per list key (account, scope, filter, sort order, search), up to 40 keys, and the
layout is deliberately not part of the key.

**Share to Subscribe (0.9.2).** `MainActivity` (`singleTask`) turns an `ACTION_SEND` `text/plain` intent into a request and nothing more (`ShareIntent`, on `onCreate` only without saved state, and on `onNewIntent`); it hands the text to `SubscriptionsViewModel.share`, a `ShareSubscriptionController`. The controller extracts the addresses (`SharedUrlExtractor`), asks `ShareDiscoveryService` what the chosen one offers and keeps the state (`ShareState`: Idle, ChoosingUrl, Discovering, Candidates, Failed, Subscribing, Done). Only the newest request may write the state (a generation counter, like `DiscoveryController`), a request waits until first-run setup is done, and registering goes through `SubscriptionsViewModel.addFeed` (the single add-feed path, so one message, one HTTP rule and one refresh). `ShareDiscoveryService` and the moved-feed search both sit on `FeedProbe`, which owns the page read, the `rel=alternate` links, the feed fetch and parse and the limit of 8 candidates. The dialog (`ShareSubscribeOverlay`) is drawn above the navigation stack and is not a route.


The order of the pages is one function, `TabOrder.resolve` (pinned categories first, each group by the saved rank, the
unplaced after the placed in path order); `Structure` carries it (`tabOrdered`, `tabCategories`) together with the per-category
preferences read from `category_preferences`, so the tabs, the pager, the picker, the drawer (its category list and shortcuts), the
persistent pane and the organizer cannot disagree. The drawer and the pane are two frames around one composable,
`HomeNavigationContent`; `NavigationLayout.resolve(width, fontScale, CategoryNavigation)` decides between them from the
window width. The layout of a page is `CategoryDisplay.resolve(pageScope, global, preferences)`, resolved for each page
separately. `HomeScreen` keeps three things apart: the pager's drawn position (`currentPage + currentPageOffsetFraction`),
the chosen category id (`HomeViewModel.selection`) and the settled page (`SettleGuard` decides what a settle means after a
newer choice). `ScopeTabs` is a plain horizontal scroll container whose scroll is set directly by `TabFollower` for each
pager position (the geometry is in `TabFollow`, free of Compose), with the titles measured in the content of the row and keyed
by scope id. The pager re-syncs to the chosen id when the pages are reordered. A selected scope that is not a page (a single
feed, a category without subscribed feeds) is shown without the pager.

## 4. Backend boundary

One backend, `LocalBackend`, is implemented. A refresh is addressed to an account and a scope rather than to
individual feeds, so a different kind of backend (one that does not poll feeds one by one) can fit behind the same
contract.

```kotlin
interface BackendSynchronizer {
    val backendType: String
    val capabilities: BackendCapabilities
    suspend fun refresh(
        accountId: String,
        scope: RefreshScope,
        reason: RefreshReason,
        force: Boolean = false,
        onProgress: (RefreshProgress) -> Unit,
    ): RefreshReport
}
```

- `BackendCapabilities` has two flags: `subscriptionEditing` (subscriptions are edited in the local database) and
  `remoteStateSync` (reading state is synchronized with a remote service). For `LocalBackend` they are `true` and
  `false`. The UI asks repositories about capabilities instead of branching on a backend name.
- `RefreshScope` is one of all feeds, uncategorized, a category (with descendants), an explicit list of feeds, or
  *pending* (feeds whose last request did not finish or whose last attempt failed).
- `RefreshReason` is `MANUAL`, `IMPORT`, `STARTUP` or `PERIODIC`; the last two are automatic and obey the network
  constraint and failure backoff. `force` means revalidate without stored validators.
- The result is a `RefreshReport` with per-feed `FeedResult` values; it never claims completeness while any target
  feed was deferred or not attempted (details in fetch-and-security.md section 5).
- A backend does not assemble Room transactions itself; it hands normalized results to `FeedStore`.
- Test doubles live in the test source sets only; release builds contain no demo-data switch.
- `ArchitectureBoundaryTest` enforces that the UI package does not import OkHttp, the network guard, the fetcher, the
  backend implementation or DAOs, and that exactly one `BackendSynchronizer` implementation exists with no provider
  scaffolding in the code base.

### Preparation that exists

- All rows carry an `accountId`; local UUIDs, `entryKey` values and (nullable) remote ids are kept distinct.
- The UI is decoupled from HTTP endpoints; it talks to view models, repositories and the `RefreshCoordinator`.
- Every change to reading state (read, saved) goes through `ArticleRepository`.
- The database schema is exported and migrated explicitly.

### Not built

Sign-in screens, token storage, remote cursors, an outbox for pending remote changes, conflict resolution, switching
between several accounts, dynamic plugins and any client for a third-party sync service do not exist. Local article ids
are never assumed to equal remote ids. Adding a remote backend would require its own design of authentication, initial
sync, change upload, state mapping and migration; the current boundary does not promise that arbitrary providers can be
attached without changes.

## 5. Implementation notes

**Threading.** Network, XML parsing, Room access and image decoding never run on the main thread: fetching runs on
the I/O dispatcher, parsing on the default dispatcher, and the database through suspend functions and Flows.

**Search.** The search field updates immediately and the query is applied after a 250 ms debounce; the observed query
is replaced when the selection changes, so results of an old query cannot overwrite a newer one. Matching is a `LIKE`
substring match over title, summary text and feed name, which works for Japanese. The query is NFC-normalized and
split on whitespace into at most 8 terms, all of which must match; `%`, `_` and `\` are escaped (`ESCAPE '\'`) and
terms are bind arguments, never concatenated into SQL. Full-text search is not used, because an English-oriented
tokenizer would not make Japanese search work.

**Lists.** Lazy lists (`LazyColumn` for List and Compact list, `LazyVerticalGrid` for Magazine) with stable item keys.
Search, filters and "mark all as read" operate on the full result set, not on the loaded page. There is no paging of
remote articles.

**Testability.** Time, id generation, connectivity and network policy are injectable (`Clock`, `IdGenerator`,
`NetworkStatus`, `AddressPolicy`, `Dns`) so that tests are deterministic; interfaces are added only where such a seam is
needed.
