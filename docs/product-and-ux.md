# Product and UX

This document describes what Folirel is and is not, what it is required to do (requirement IDs `R01`-`R20`), and how its screens behave (screen IDs `S00`-`S40`). It describes the app as it is at version 0.4.1. Test scenarios that exercise these requirements are catalogued in [testing.md](testing.md). Internals are described in [architecture.md](architecture.md), [data-and-import.md](data-and-import.md) and [fetch-and-security.md](fetch-and-security.md); end-user instructions are in [user-guide.md](user-guide.md).

## 1. Purpose

Folirel is a local RSS/Atom reader for Android. It is built around one loop:

**skim subscribed news quickly, open the original article on its own site, and bookmark the articles worth keeping by hand.**

Everything runs on the device. There is no account, no sign-in, no server component and no cloud service. Subscriptions come from an OPML file (for example one exported from another feed reader) or from a feed URL typed in; articles are fetched directly from each site's own feed.

The first-run path is "import OPML" or "add a feed URL". There is no registration screen, no "connecting" screen and no placeholder for a sync service.

## 2. Scope

### 2.1 What the app does

- Imports subscriptions and categories from OPML, with a preview before anything is written, and exports them again.
- Manages subscriptions: add by URL, rename, assign to several categories, pause, unsubscribe and subscribe again.
- Fetches RSS 2.0, Atom 1.0 and RSS 1.0 (RDF) feeds directly, on demand and periodically.
- Shows the stored articles in three list layouts, with category tabs, a drawer, filters, sorting and local search.
- Opens an article's original page in a Custom Tab (or an external browser, by setting).
- Keeps read/unread and saved state as explicit, manual, persistent choices.

### 2.2 Non-goals

These are deliberate. Contributions that add them are out of scope.

- **No accounts and no sync services.** No OAuth, no remote read-state or saved-state synchronisation, no multi-account UI, no outbox of pending remote changes. The code keeps a boundary where another source could be added later (see R19), but nothing behind it is implemented.
- **No article reader.** No text extraction, no in-app HTML body view, no custom WebView browser, no offline copy of pages or full text. "Saved" is a bookmark, not a downloaded page.
- **No automatic read state.** Tapping an article, returning from the browser, scrolling, the passage of time, refreshing or preloading never marks anything as read. The only way to change read state is an explicit action. The open time is never used as a hidden read marker, and there is no "auto mark as read" setting, not even a disabled one.
- No notifications, text-to-speech, home-screen widgets, ads, AI features or analytics.
- No full-text (FTS) search, no cloud backup, no custom backup format. OPML export is the only export and it is not a full backup (read and saved state are not included).
- No site auto-discovery from a web page URL at add time, no feed generation for JavaScript-only sites, no scraping of article pages (for example for preview images), no bypassing of authentication or paywalls, no ad removal.
- No paging through a feed's archive. The app stores what each feed currently returns.
- No per-category display settings, no gesture customisation, no swipe-to-act rows or multi-select mode on the article list, no volume-key navigation.

### 2.3 Limits worth knowing

- OPML moves subscriptions only: not article text, not history, not read or saved state. Items that were never in the exported file (for example boards or other non-feed sources of the service the file came from) cannot be detected by the app and are not counted; the import screen carries a general note instead of a guess.
- After an import the app has only what each site's feed returns now. A feed whose publisher only exposes a handful of recent items will have only those.
- Lists, search and manual state work offline. Opening the original page usually needs a connection.
- Timing of periodic updates is approximate (the OS schedules them).

## 3. Requirements

| ID | Requirement | Done when |
|---|---|---|
| R01 | Local start | The app starts with no network, token or server and shows existing data. |
| R02 | OPML import | A system file picker, a preview, add/merge semantics, categories, and a breakdown of skipped and failed items. |
| R03 | OPML export | Currently subscribed feed URLs and categories are written through the system file-save dialog. |
| R04 | Subscription management | RSS/Atom URLs can be added; title and categories edited; feeds paused, unsubscribed and subscribed again. |
| R05 | Categories | One feed can be in several categories or none (uncategorized); categories can be created, renamed (including nested ones) and deleted. |
| R06 | Direct fetch | RSS 2.0, Atom 1.0 and RSS 1.0 are fetched, parsed and stored for the fields the app uses. |
| R07 | Refresh | Manual refresh of everything, one category or one feed; optional periodic and on-open refresh; progress and partial failure are shown. |
| R08 | Home | Drawer, category tabs and article list; the subscription scope and the read filter are separate controls. |
| R09 | Three list layouts | List, Compact list and Magazine, all showing title, time, source and saved state, including Japanese text. |
| R10 | Original article | Tap opens a Custom Tab, or an external browser by setting. No WebView, no reader. |
| R11 | Manual state | Read/unread, save/unsave and "mark all as read" are explicit and persisted. |
| R12 | Share | The Android share sheet receives one article's title and original URL. |
| R13 | Local search | Substring search over stored titles, summaries and source names, including Japanese. It does not query anything remote. |
| R14 | Settings | Theme, list layout, text size, images, browser, update frequency and network rules, retention, cache. |
| R15 | Position restore | Returning from an article, refreshing or rotating does not move the list or change its scope. |
| R16 | Persistence | Subscriptions, categories, read state and saved state survive app restarts and database schema changes. |
| R17 | Safety | External XML and URLs are validated, TLS stays enforced, data does not leak, permissions are minimal. |
| R18 | Maintainability | Reproducible build, automated tests, recorded errors, a dependency and licence list, a debug APK. |
| R19 | Extension boundary | Local IDs are separate from any external IDs, and the UI does not know how articles are fetched. |
| R20 | Accessibility | Main flows work with TalkBack and 200% text; touch targets are at least 48 dp; light and dark themes are legible. |

## 4. Defaults and platform

| Item | Value |
|---|---|
| Platform | Android 9 (API 28) and later. This is a minimum run condition, not a statement about the security of old OS versions. Target SDK 37. |
| UI language | English (default strings) and Japanese. Japanese text is a first-class case in layout, search and parsing. No user-visible string is hard-coded. |
| Theme | Follows the system; Light and Dark can be forced. |
| List layout | List. |
| Read filter / sort | All; newest first. Both can be changed and the choice is remembered. Order is deterministic for equal timestamps. |
| Tap on an article | Custom Tabs; an external browser if Custom Tabs is unavailable or chosen. Neither marks anything as read. |
| Refresh | One fetch after the first import. Update on open when the last attempt was at least 30 minutes ago (separate switch). Periodic update every 2 hours by default; options Off / 15 / 30 / 60 / 120 / 360 / 720 / 1440 minutes. |
| Automatic network use | Explained during first run. Default: unmetered networks only. A manual refresh uses the current connection; on a metered network it asks first. |
| HTTP (not HTTPS) | Off by default. `http://` feeds can be registered but are not fetched, and `http://` images are not loaded, until the user allows HTTP in Settings. |
| Thumbnail cache | Disk cache of 128 MiB by default (64 / 128 / 256 / 512 selectable, applied at next start), least-recently-used eviction. Offline availability of every image is not guaranteed. |
| Article retention | No automatic deletion by default. Settings can set "articles to keep on the device" (no limit, 1,000, 3,000, 5,000, 10,000 or 30,000); see S40. Database size and article count are shown, with a non-blocking warning above 100 MB. |
| Initial read state | Every fetched article is unread. Past read state is never guessed. |
| Backup | OS backup and device-transfer are disabled (subscription URLs may carry private tokens). |

"Unmetered" follows the system's metered flag used by WorkManager; it is not a strict Wi-Fi/SSID check. The same constraint applies to the update-on-open refresh, so opening the app cannot bypass it.

## 5. Screens and interaction rules

### S00 First run

One explanation screen with four short points: subscriptions are stored on the device; articles are fetched directly from each site; "Save" is a bookmark; OPML moves subscriptions only, not read or saved state. A switch (on by default) enables update every 2 hours on unmetered networks before any network use. The main button is "Import OPML", the secondary button is "Add feed URL"; skipping leads to an empty home screen. The app requests no notification permission and no broad storage permission.

Importing works offline; fetching is then reported as pending.

### S10 Home

```text
Toolbar:  [Menu]  <current scope>                    [Search] [More]
Tabs:     All | Uncategorized | Tech / AI | News | ...
Filter:   [All] [Unread] [Saved]                      [Newest first / Oldest first]
Notices:  drawn over the list, never inside the layout: progress, the result of an update that needs attention (a few seconds), offline mark
List:     articles of the current scope and filter
```

Layout rules:

- The toolbar is 56 dp high and respects system insets. There is no permanent large heading, no promo card and no bottom navigation bar. On a short screen the header never takes half the height.
- The filter row wraps instead of clipping at large text sizes.
- Nothing about an update takes room from the list. Notices are drawn over it (outside the list's own touch area), so an article never moves when one comes or goes, and pressing one is never taken for a touch of the list:
  - *Running*: a thin progress bar at the top edge and, above the back-to-top button, what is running (`n/m`, "first fetch in progress", another update running, checking HTTPS). It stays as long as the update runs.
  - *Finished*: an update the reader asked for (manual refresh, import) that ends with something to look at (failed, not reached, waiting, HTTP held, storage failure, interrupted, deferred because offline) leaves one notice, for about 5 seconds (longer if the system asks for more time for accessibility), with a close cross, "Details" (subscription management) and, when failed or not-reached feeds can be asked for again, "Retry unfinished". A fully successful update shows nothing. The end belongs to the update's request: rotating, switching tabs or coming back never shows it again or makes it last longer, and the timer of an older notice never removes a newer one. Automatic updates (startup, periodic) show no notice; their failures are left to the marks below.
  - *What stays*, from the stored state of the feeds (the notice is only a courtesy): a small badge on the menu button and the count on the "Subscriptions" entries (menu and drawer) for feeds that failed, are held for HTTP, or were left unfinished (not counted while an update is running, when every queued feed is unfinished); a warning icon in the toolbar while a feed's articles cannot be saved; a small cloud-off icon in the toolbar while offline. Each leads to subscription management, where the reason and the way to retry are.

The overflow menu contains the three list layouts (the current one is checked), the sort toggle, "Mark all as read", "Refresh", "Import OPML", "Add feed URL", "Subscriptions" and "Settings".

**Drawer.** "All articles", "Saved articles", "Uncategorized" (only if there are such feeds), then categories (nested, with a per-category expander that lists its feeds), then uncategorized feeds, "Subscriptions" and "Settings". A feed in several categories appears under each of them; its articles and their saved state are shared. The drawer is opened from the menu button only; a left-edge swipe does not open it, because horizontal swipes belong to the category pages.

**Tabs and pages.** Tabs are "All", "Uncategorized" (when present) and every category that has at least one subscribed feed in it or below it. Nested categories show their path, for example `Tech / AI`. The tab row and the pages are one control:

- Swiping the pages, tapping a tab and choosing a category in the drawer select the same page. The ends do not wrap around.
- The highlighted tab and its underline follow the finger while the page is dragged; the underline is centred under the tab title, at least 24 dp wide.
- Only the visible page (and its neighbour being pre-composed) holds a database subscription.
- Tapping the already-selected tab scrolls the visible list back to the top. A small up-arrow button appears at the bottom centre after scrolling more than six items down and does the same. (It sits at the bottom centre because the per-article buttons occupy the right edge.) It is one movement (section 6), and it also answers the new-article notice of that list. Neither action changes read state.
- When a single feed is selected from the drawer the tabs are hidden and the toolbar shows the feed name.

**Scope and filter.** Scope is one of: all, uncategorized, a category (including its descendant categories) or a single feed. The read filter is independent: All / Unread / Saved. Moving between categories or tabs keeps the current filter. "Saved articles" in the drawer selects the whole-collection scope with the Saved filter.

Unsubscribed feeds are excluded from normal lists and unread counts. Under the Saved filter their saved articles remain visible and are marked "Unsubscribed". Empty categories stay in subscription management and are hidden from the tabs.

### S11 List layouts

There are three layouts. The names are the user-facing names and the stored values (`LIST`, `COMPACT_LIST`, `MAGAZINE`).

| Aspect | List (default) | Compact list | Magazine |
|---|---|---|---|
| Shape | One column; headline and summary at the left, thumbnail at the right, meta and buttons on a row below | One row per article; thumbnail and buttons on the same row | A grid of cards of different sizes |
| Thumbnail | 96 dp square, fixed area; omitted when images are off | 56 dp square; omitted when images are off | Top of the card, 16:9 (capped at 200 dp); beside the text on a wide card |
| Headline | 16 sp, up to 3 lines | 15 sp, up to 2 lines | Up to 3 lines: 16 sp on a tile, 18 sp on a single-column or full-width card |
| Summary | 14 sp, up to 2 lines; omitted if empty | not shown | Tile: 13 sp, at least 4 lines (more when the article has no image); single-column or full-width card: 14 sp, 4 lines (5 beside the image of a horizontal card) |
| Source and time | 12 sp, one line, ellipsized | 12 sp, one line, ellipsized | 12 sp, ellipsized |
| Spacing | 12 dp outer, about 8 dp vertical; rules between rows | 12 dp outer, 6 dp vertical; rules between rows | 8 dp at the edges and between cards |
| Minimum row height | 72 dp for the top block | 56 dp | fixed text area per card |

The same buttons exist on every row: a save/unsave bookmark and a "more" menu, each with a 48 dp touch area that does not overlap the thumbnail. A card narrower than 230 dp shows only the "more" menu (save is inside it) and a small bookmark mark when the article is saved.

**Magazine arrangement.** Cards are placed left to right, then on the next row, in time order (never as a staggered masonry of unequal heights). The grid has six units per row.

- Each article asks for a size derived from a stable hash of its article ID, so the same article asks for the same size whatever the position, sort order or redraw. With three columns available the shares are small (one third) 40%, medium (one half) 25%, large (two thirds) 25% and full width 10%. With only two columns, halves (85%) and full-width cards; with one column, full width.
- Rows are filled with no gaps: a card that does not fit the rest of a row takes exactly the rest, and the last row is stretched. The result is an irregular, magazine-page rhythm such as 1/3+1/3+1/3, 1/2+1/2, 2/3+1/3, 1/3+2/3 or a full-width card. Row breaks can move when the sort order or the number of fetched articles changes; an individual article's preferred size does not.
- A full-width card puts the image at the left and the text at the right. A single column at 560 dp or wider does the same.
- All tiles of a page share one image height (the 16:9 height of the narrowest tile, at most 200 dp) and one text size, with a fixed text area (3 headline lines, 4 summary lines), so cards in one row line up. An article without an image uses that area for more summary; a row where no article has an image reserves no picture area.
- An alternative setting draws every card at the same size ("Same size for every card").
- Number of columns: automatic keeps each card at least 220 dp wide (maximum 3 columns); the user can choose up to 1, 2 or 3 columns, which may go down to 150 dp per card. Both minimums scale with the text size (the factor applies when the font scale is above 1), and columns are dropped if they would not fit. Text is never shrunk to fit. Settings shows the current number of columns and whether the choice was reduced.

**Text and fallbacks.** Manual text size is 0.9 / 1.0 / 1.15 / 1.3, and it multiplies the system font scale rather than adding to it. Row heights have minimums but no maximum that would clip text; at 200% text no text or control may be lost. A title that is missing is shown as "(Untitled)" and an unknown date as "Date unknown". A missing or broken thumbnail shows a neutral placeholder of the same size, so rows do not jump when an image finishes loading. When images are off the image area is removed completely and no image request is made.

Images come from what the feed itself provides (media extensions, enclosures, or an `<img>` in the entry content; tracking pixels and unsafe URLs are discarded). Article pages are never scraped for Open Graph images, and a feed with no images simply has none.

**Source icons.** A small icon precedes the source name. It comes from the feed's own declared icon/logo, else from the site's `<link rel="icon">` / `apple-touch-icon`, else `/favicon.ico`, else a coloured circle with the first letter. Each feed is looked up once (and again after long intervals or when the feed declares something new); icons are normalised to a small PNG. No third-party favicon service is used, no script is run, and a failed lookup never counts as a feed failure. The "Show thumbnails and source icons" setting turns both thumbnails and icons off: nothing is downloaded or shown (already saved icons stay hidden, not deleted).

### S12 Actions

| Action | Result |
|---|---|
| Tap a row | Opens the original URL. Read and saved state are unchanged. |
| Bookmark button | Saves or removes from saved. Does not open the article. |
| "More" menu button or long press | Mark as read / unread, save / remove from saved, share, open in external browser. |
| Pull to refresh | Refreshes the current scope. The read filter, sort and search only narrow what is shown, never what is fetched. |
| Toolbar overflow menu | List layout, sort order, mark all as read (current scope), refresh, import, add feed, subscriptions, settings. |
| Search | Searches stored articles within the current scope and filter. |

Read state is never automatic, so changing it by hand takes one menu step. Rows have no swipe gestures and there is no multi-select mode.

**Mark all as read** targets the unread articles stored on the device that match the current scope, filter and search, not just the page of articles currently loaded. The set of article IDs is fixed when the confirmation opens; articles that arrive afterwards are not included. The confirmation shows the scope name, the filter, the search text (if any) and the count; with zero matches the action is disabled.

Single actions report their result in a snackbar. A change that fails to write to the database is reported and is not shown as done: the displayed state follows the database, not an optimistic guess.

**Search** matches substrings of title, summary and source name. Whitespace separates terms; all terms must match (at most eight terms are used). `%`, `_` and `\` are searched as literal characters. A slow or out-of-date search response must never overwrite the result of a newer query.

### S20 Opening an article

A tap on a row opens the Custom Tab directly from the list; the app has no body screen of its own. If the user has chosen an external browser, an `ACTION_VIEW` intent is used instead. If Custom Tabs is unavailable the app falls back to `ACTION_VIEW`; if nothing can handle the link a message says so.

- Only `http` and `https` URLs are passed on, after validation (no `javascript:`, `file:`, `content:`, user-info, loopback or private-network literals). An unsafe link is not opened and the user is told why. `https` is never downgraded to `http`.
- An `http://` article asks for confirmation each time ("Open this time"). The dialog has an unchecked "Do not ask again before opening http:// articles" box; only choosing "Open" with the box ticked turns the question off (cancelling or tapping outside saves nothing). Settings has a switch to turn the question back on. This confirmation is separate from the setting that allows HTTP feed and image downloads, and the URL is never rewritten to https.
- Custom Tabs is browser-managed: the app does not promise text-only display, ad removal or a reader mode, does not control what the browser does with further links, and never reads the browser's cookies.
- An article without a usable original URL says "This article has no original URL" on tap; a long press opens a bottom sheet with the stored short plain-text summary only. This sheet is not a reader and is not extended into one.

### S30 Import and subscription management

**Import** is separate steps: choose a file, parse, preview, confirm, then fetch. Choosing a file never causes network access. The preview lists new feeds, already subscribed feeds, feeds in several categories, new categories, empty categories, `http://` feeds that will wait for the HTTP setting, feeds that will be subscribed again (a count and up to ten names), invalid URLs and unsupported entries. Confirming adds and merges only; existing user titles, read state and saved state are preserved. An OPML file that is malformed, contains a DOCTYPE or entity declaration, or exceeds limits is rejected without touching the database, with a reason shown. If every feed registers but some fail to fetch, the import result keeps the success count and the list of failures separate.

**Subscription management** is a searchable feed list plus category editing:

- Feed detail shows the registered URL, the current fetch URL, site URL, last attempt, last success, last error kind, and per-feed diagnostics (what was requested, the HTTP response, and how many entries the last document held, merged and added). It has a switch to stop fetching the feed, multi-select of categories, "Refresh this feed" and "Revalidate and refetch" (which sends no stored validators and asks caches to revalidate; articles and state are kept).
- The URL of a feed is not editable. To use a different URL, add it as a new feed; for a feed that has moved, see "find where this feed moved" below.
- **Unsubscribe** stops updates and removes the feed from normal lists; saved articles remain. Subscribing again with the same URL restores the original feed ID and state. A late fetch result never revives an unsubscribed feed.
- **Categories:** create, create a subcategory, rename, delete. Deleting a category deletes only the category and its descendants' memberships, after confirmation; feeds and articles stay, and feeds left without a category become uncategorized.
- **Bulk unsubscribe:** the list can be filtered (All, Failed, Never fetched, HTTP pending, Waiting to retry, Paused), selected (long press, menu, "select shown") and unsubscribed in one step after a confirmation that lists the feeds as they were at selection time (name, kind of problem, HTTP status, consecutive failures, last success, retry time). A feed that recovered in the meantime is not unsubscribed. "Undo" subscribes again exactly the feeds that were changed. Failures are classified (not found 404/410, access denied, rate limited, server error, certificate, network/timeout, unreadable, blocked, other, HTTP pending) and are never described as a site being "closed"; nothing is ever removed automatically.
- **Find where this feed moved:** reads the feed's own site page (the registered site URL, the origin of recent article links, or an address the user typed), takes only `<link rel="alternate">` RSS/Atom/RDF candidates, fetches and parses each to confirm it is really a feed, and shows facts about each (reachable, title, entry count, date range, articles in common, same name). Nothing is chosen automatically. When the user approves a candidate only the fetch URL changes; feed ID, categories, articles and read/saved state are kept, the registered URL stays as the identity for re-importing old OPML files, and the change is recorded. A candidate already belonging to another subscription is refused rather than merged. A "search the web" button hands "<feed name> RSS" to the system web search; the app has no search API of its own.
- **HTTPS check for `http://` feeds:** with the user's consent, each `http://` feed is fetched once over HTTPS with normal certificate checks; a feed is switched to HTTPS only if it returns the same RSS/Atom feed. Anything unconfirmed stays on HTTP. Feed, categories, articles and state are unchanged.

### S40 Settings

Sections: Display, Opening articles, Updates, Data, About.

- **Display:** theme (System / Light / Dark), list layout (List / Compact list / Magazine), Magazine arrangement (mixed sizes or the same size) and most columns, text size, and "Show thumbnails and source icons".
- **Opening articles:** browser (Custom Tabs or external), the confirmation switch for `http://` articles. A note states that opening an article never marks it read.
- **Updates:** automatic update interval, update when the app opens (at most every 30 minutes, same network rule), "unmetered networks only", "Allow unencrypted HTTP feeds and images" (with a confirmation explaining the risk; certificate checks do not change), and the HTTPS check.
- **Data:** articles to keep on the device, article count and database size (with a warning above 100 MB), image cache size, "Clear image cache", and OPML export.
- **About:** version, build identifier, a note that data stays on the device, and the open-source licence summary.

Rules:

- OPML export is labelled "subscriptions and categories only; read and saved state are not included", and warns that feed URLs may contain private tokens.
- "Articles to keep" is not a fetch limit: every article a feed returns is stored. After a confirmation that shows the numbers, it removes only the oldest articles that are both read and not saved, above the chosen number; unread and saved articles are never removed. Removed articles are remembered so that a feed that still lists them does not bring them back as new unread articles.
- "Clear image cache" deletes images only. It never touches subscriptions, categories, read state or saved state. There is no "reset database" or "delete history" action.

## 6. Position restore and new articles

The scroll position of each list is kept as the identity of an anchor article plus a pixel offset, keyed by account, scope, read filter, sort order and search text. The list layout is deliberately not part of the key, so changing the layout keeps the same article in view. Positions survive returning from the browser, rotation and process recreation (through saved instance state; at most 40 lists are remembered). If the anchor article no longer exists, the nearest remaining article is used. Switching to another category and back restores each category's own place.

**Back to the top** (the arrow button, tapping the selected tab, the new-article banner of a newest-first list) is one movement of the list, not a series of animations: an ease-in-out of about a quarter to two thirds of a second (the time grows with the distance, at most 650 ms; none at all when the system animations are switched off). A list far from its start skips once to about two and a half screens from it and moves the rest. Every frame moves the list by the share of the distance left that the curve asks for, and the distance left is measured again every frame, so the list does not stop and start again card by card, and it lands exactly on the first article. While it moves the list keeps the articles it was moving over; articles that arrive meanwhile (a refresh delivers feed after feed) are applied when it arrives, and for four seconds afterwards articles that arrive at the head are followed instantly, so the newest one is not left above the screen. A second request while it moves joins it. Any touch of the list (a finger put down, a drag, a long press, a tap on a card or its menu) or a wheel scroll ends the movement and the following at once and nothing starts them again; changing category or filter, or leaving the screen, ends them too. The buttons act on release, so their own press never cancels the movement they start.

**New articles** never scroll the list by themselves. Articles that arrive while the user is reading appear as a notice at the top of the list: "n new · to top" (newest first) or "n new · to their place" (oldest first), with a close cross. Only articles of feeds that have already been fetched before count (the first fetch is not "new"), and only those that belong to the list being shown. The notice is answered once:

| Action | Moves to | The notice | Read / saved state |
|---|---|---|---|
| Tap the notice, newest first | the start of the list (new articles are new to this device; they need not be the newest by date, so the text says "to top") | goes away for all the new articles this list shows | unchanged |
| Tap the notice, oldest first | the first new article in that order (the start of the list if it has gone) | goes away for all of them | unchanged |
| Cross | does not move | goes away for all of them | unchanged |
| Arrow button, tap on the selected tab | the start of the list | goes away for all the new articles this list shows | unchanged |

Being on screen does not count a new article down: there is no "n left to look at". An answered article is remembered (a bounded set of ids), so a request that finishes later and lists the same articles again, which happens when two requests share a feed, does not announce them again; articles that really arrive afterwards are announced as new. An article that is new in two lists (a feed in two categories) is answered with the list the reader answered; articles the list does not show are never answered. Acknowledging a notice is not reading: it never touches read or saved state, and opening an article never marks it read. Manual save and read changes apply immediately.

## 7. Required states

Each of the following must show a reason and a next step, never a blank screen: no subscriptions, no articles yet, no unread articles, no saved articles, no search results, offline, first fetch in progress, partial failure, HTTP pending, unsupported feed format, and low storage.

A network or parsing failure is never presented as an empty success. When not every feed has been processed (time budget, cancellation, constraints) the result says so ("not reached", "waiting", "interrupted") instead of reporting completion.

## 8. Density and accessibility rules

- Information density is a goal. Standard Material spacing is not adopted where it wastes space; list rows, cards and the toolbar use the compact values above.
- No decorative chrome: no oversized heading, hero card or bottom bar.
- Every touch target is at least 48 dp, and the thumbnail and the buttons never share a touch area.
- Layouts must remain usable at 200% system font size, with the app's own text size multiplying that, in portrait and landscape, in light and dark themes.
- Rows expose their actions (save, read/unread, share) as TalkBack custom actions in addition to the visible buttons. Icon buttons have content descriptions.
- Colours do not carry meaning alone: a saved article shows a bookmark icon, and filter state is a labelled chip.
- System insets are respected everywhere, including cut-outs and gesture areas.
