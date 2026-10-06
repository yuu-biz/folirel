# Product and UX

This document describes what Folirel is and is not, what it is required to do (requirement IDs `R01`-`R20`), and how its screens behave (screen IDs `S00`-`S40`). It describes the app as it is at version 0.6.0. Test scenarios that exercise these requirements are catalogued in [testing.md](testing.md). Internals are described in [architecture.md](architecture.md), [data-and-import.md](data-and-import.md) and [fetch-and-security.md](fetch-and-security.md); end-user instructions are in [user-guide.md](user-guide.md).

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
| R14 | Settings | Light/dark and colour set, list layout, text size, images, browser, update frequency and network rules, retention, cache. |
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
| Theme | Light or dark follows the system; Light and Dark can be forced. The colour set is a separate setting: Classic (the blue of every earlier version, the default), Leaf, Sand, Violet, and Dynamic (Android 12 and later). |
| List layout | List. |
| Read filter / sort | All; newest first. Both can be changed and the choice is remembered. Order is deterministic for equal timestamps. |
| Tap on an article | Custom Tabs; an external browser if Custom Tabs is unavailable or chosen. Neither marks anything as read. |
| Refresh | One fetch after the first import. Update on open when the last attempt was at least 30 minutes ago (separate switch). Periodic update every 2 hours by default; options Off / 15 / 30 / 60 / 120 / 360 / 720 / 1440 minutes. |
| Automatic network use | Explained during first run. Default: unmetered networks only. A manual refresh uses the current connection; on a metered network it asks first, with a "do not ask again" box (saved only with "Update now"; Settings can turn the question back on). |
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
Toolbar:  [Menu]  <category name> v                  [Display] [Search] [More]
          <range> . <order>   (small line under the name, e.g. "Unread . Newest first")
Tabs:     All | Uncategorized | pinned categories | Tech / AI | News | ...
Notices:  drawn over the list, never inside the layout: progress, the result of an update that needs attention (a few seconds), offline mark
List:     articles of the current scope and filter
```

Layout rules:

- The header is two rows: the toolbar (56 dp, or 80 dp when the text is above 130 %) and the tab row (48 dp), about 104 dp at normal text size, not counting the system bars. There is no row of filter chips, no permanent large heading, no promo card and no bottom navigation bar. On a short screen the header never takes half the height. Nothing is shrunk to reach these numbers: touch targets stay 48 dp, and at large text or a narrow window the header grows instead.
- The toolbar title is the name of the category on screen with a small down arrow. Pressing it opens the category picker (S10b). Under the name a small line says what the list shows (range and order, "Unread . Oldest first"), so the state is never guessed from an icon. The button to its right opens the display panel (S10a).
- In a window narrower than 360 dp the search icon moves into the overflow menu; the title, the display button and the menu stay on the bar.
- Nothing about an update takes room from the list. Notices are drawn over it (outside the list's own touch area), so an article never moves when one comes or goes, and pressing one is never taken for a touch of the list:
  - *Running*: a thin progress bar at the top edge and, above the back-to-top button, what is running (`n/m`, "first fetch in progress", another update running, checking HTTPS). It stays as long as the update runs.
  - *Finished*: an update the reader asked for (manual refresh, import) that ends with something to look at (failed, not reached, waiting, HTTP held, storage failure, interrupted, deferred because offline) leaves one notice, for about 5 seconds (longer if the system asks for more time for accessibility), with a close cross, "Details" (subscription management) and, when failed or not-reached feeds can be asked for again, "Retry unfinished". A fully successful update shows nothing. The end belongs to the update's request: rotating, switching tabs or coming back never shows it again or makes it last longer, and the timer of an older notice never removes a newer one. Automatic updates (startup, periodic) show no notice; their failures are left to the marks below.
  - *What stays*, from the stored state of the feeds (the notice is only a courtesy): a small badge on the menu button and the count on the "Subscriptions" entries (menu and drawer) for feeds that failed, are held for HTTP, or were left unfinished (not counted while an update is running, when every queued feed is unfinished); a warning icon in the toolbar while a feed's articles cannot be saved; a small cloud-off icon in the toolbar while offline. Each leads to subscription management, where the reason and the way to retry are.

The overflow menu contains "Mark all as read", "Refresh", "Organize categories and tabs", "Import OPML", "Add feed URL", "Subscriptions" and "Settings" (and "Search" in a narrow window). The layouts and the sort order are in the display panel only, so no setting is written from two places.

**Drawer.** "All articles", "Saved articles", "Uncategorized" (only if there are such feeds), the pinned categories as shortcuts (only if any are pinned; the same categories are also in the tree below), then categories (each with a per-category expander that lists its feeds), then uncategorized feeds, "Subscriptions" and "Settings". The categories are in the order of the tabs (the one order that tabs, pager, picker, organizer, drawer and pane share; the drawer has no sort of its own), so a change of order or pin shows in all of them at once. Because a child can then stand before its parent, a category is named by its own name with its parent path in small text under it (as in the picker), not by an indent; its feeds open under that category. A feed in several categories appears under each of them; its articles and their saved state are shared. The drawer is opened from the menu button only; a left-edge swipe does not open it, because horizontal swipes belong to the category pages.

**Tabs and pages.** Tabs are "All", "Uncategorized" (when present), the pinned categories and every other category that has at least one subscribed feed in it or below it, in the order of section S10c (path order until the reader changes it). Nested categories show their path, for example `Tech / AI`. The tab row and the pages are one control:

- Swiping the pages, tapping a tab and choosing a category in the drawer select the same page. The ends do not wrap around.
- The tab row follows the pager continuously from the first pixel of a drag (not when the page settles): the titles scroll so that the point between the two neighbouring titles, at the pager's fractional position, is in the middle of the row, and the underline (centred under the title, as wide as it, at least 24 dp) moves with them in the same frame. A row whose tabs all fit is not moved, and the ends stop at the ends of what can be scrolled (no empty space is added to centre the first or last tab). Dragging back moves the row back; there is no second animation on landing. A long jump (a tab far away, the picker) lands directly instead of sweeping every category in between.
- The row can be scrolled by hand to look for a tab. That selects nothing and is never pulled back; when the pager moves next, the row resumes from where it is and the displacement fades over the first page of movement, so nothing jumps.
- Three things are kept apart: the position the pager is drawn at (what the row follows), the category the reader chose (an id, set the moment a tab, the drawer or the picker is used) and the page the pager settled on (what a swipe commits). A page that settles a moment after a newer choice does not undo that choice; the last explicit choice wins. Tabs are keyed by category id, so a reorder or pin changes which index a category has, never which category is selected, and the list keeps its place.
- A tab pressed again counts as "again" only when its page is settled and on screen; while the pager is still moving toward it, the press is not a new request.
- Only the visible page (and its neighbour being pre-composed) holds a database subscription.
- Tapping the already-selected tab scrolls the visible list back to the top. A small up-arrow button appears at the bottom centre after scrolling more than six items down and does the same. (It sits at the bottom centre because the per-article buttons occupy the right edge.) It is one movement (section 6), and it also answers the new-article notice of that list. Neither action changes read state.
- When a single feed is selected from the drawer the tabs are hidden and the toolbar shows the feed name.

**Scope and filter.** Scope is one of: all, uncategorized, a category (including its descendant categories) or a single feed. The read filter is independent: All / Unread / Saved. Moving between categories or tabs keeps the current filter. "Saved articles" in the drawer selects the whole-collection scope with the Saved filter.

Unsubscribed feeds are excluded from normal lists and unread counts. Under the Saved filter their saved articles remain visible and are marked "Unsubscribed". Empty categories stay in subscription management, in the picker, in the drawer and in the organizer, and have no tab. Choosing an empty category shows it on its own (without tabs, like a single feed) with its empty message; if the category on screen loses its last feed it stays on screen the same way and is never silently replaced by another one. Its order, pin and layout settings are kept while the category exists, so they return when a feed is subscribed again. Deleting a category deletes its settings and returns to "All".

### S10a Display panel

A bottom sheet opened by one button of the toolbar. It is the one place for what the list shows:

1. **Articles**: All / Unread / Saved.
2. **Order**: Newest first / Oldest first.
3. **Layout**: List / Compact list / Magazine. For a category the choice is that category's own, with "Use the default (…)" as the first row and a button for the arrangement and the columns (S11); for All, Uncategorized and a single feed it writes the global default, and says so.

A choice applies at once. Opening and closing the panel changes no setting, no position and no notice. Segmented buttons are used for the short choices when the labels fit; a narrow window, text above 115 % or long labels (the layouts) use 48 dp selectable rows. The range and the order are one shared state, not kept per category. Search is unchanged (the toolbar field, 250 ms debounce, current scope, cleared when closed); the picker has its own search text.

### S10b Category picker

A bottom sheet opened by the toolbar title. Rows: All, Uncategorized (when present), a "Pinned" group, then the other categories, in the tab order, each with its parent path in small text so two children of one name can be told apart; the one on screen is marked. A search field filters the names and parent paths on the device (no network) and reports how many entries match (not a count of articles or feeds). Choosing a row closes the sheet and ends in the same selection a tab or the drawer uses; choosing the category already on screen only closes it (no scroll to the top, no notice answered). A pin button on each row pins or unpins; searching and pinning change no article, no read state and no fetch. "Organize categories and tabs" is at the bottom.

### S10c Organizing the tabs

"Organize categories and tabs" (the picker, the toolbar menu) opens the category screen on its "Tab order" view; the "Hierarchy" view beside it is the existing rename / add / delete list. The tab order is flat and independent of the category tree: paths, parents and which feed is in which category are never changed by it.

- **Order of the tabs**: All and Uncategorized first and fixed; then the pinned categories; then the others. Each group is in the reader's saved order; a category without a saved place follows the saved ones in path order, so a new category lands at the end and nothing already placed moves. Before any choice, the order is the path order (the update to 0.5.0 changes no order). Pinning moves a category to the front group; it is one tab, never two. Unpinning gives it back its saved place, which pinning does not discard. Renaming, refreshing and re-importing OPML keep the order (it follows the category id).
- **Row**: name and parent path, the number of feeds (or "no subscribed feed, so no tab"), a drag handle, a pin button and a menu (move up, move down, display settings). Dragging starts at the handle only and moves a row within its group; a long press on a page never reorders anything. The order being dragged is kept on the screen and saved once, when the finger is lifted, as "before this neighbour" applied to the order that is stored at that moment; cancelling, or a write that fails (an error message is shown), puts the stored order back on the screen. A stored order that arrives while a row is being dragged (the result of an earlier press) waits until the finger is lifted. Up and down, and the drop, are computed from the stored order inside the database transaction, so presses made faster than the screen updates (three "Move up" in a row, two different rows moved one after the other) are all applied, one after the other, and none is undone by an older result. The menu items and the accessibility actions "Move up" / "Move down" are the way without dragging.
- **Back to the standard order** forgets the saved places only; pins and the display settings of each category stay (the screen says so).
- A pin, a move or a reset changes only these settings: no category id, path, membership, article, read or saved state.

### S11 List layouts

There are five layouts. The names are the user-facing names and the stored values (`LIST`, `COMPACT_LIST`, `MAGAZINE`, `MASONRY`, `BENTO`); Masonry and Bento are described after the table.

| Aspect | List (default) | Compact list | Magazine |
|---|---|---|---|
| Shape | One column; headline and summary at the left, thumbnail at the right, meta and buttons on a row below | One row per article; thumbnail and buttons on the same row | A grid of cards of different sizes |
| Thumbnail | 96 dp square, fixed area; omitted when images are off | 56 dp square; omitted when images are off | Top of a tile, 2.3:1 (capped at 160 dp); top of a card of its own, 16:9 (capped at 200 dp); a small picture at the left of a wide card |
| Headline | 16 sp, up to 3 lines | 15 sp, up to 2 lines | Up to 3 lines: 16 sp on a tile, 18 sp on a single-column or full-width card |
| Summary | 14 sp, up to 2 lines; omitted if empty | not shown | Tile: 13 sp, up to 2 lines; card of its own: 14 sp, up to 2 lines. A limit, not a reservation; omitted when empty or only a "read more" label |
| Source and time | 12 sp, one line, ellipsized | 12 sp, one line, ellipsized | 12 sp, ellipsized |
| Spacing | 12 dp outer, about 8 dp vertical; rules between rows | 12 dp outer, 6 dp vertical; rules between rows | 8 dp at the edges and between cards |
| Minimum row height | 72 dp for the top block | 56 dp | none reserved: a card is as tall as its content, a row of tiles as tall as its tallest card |

The same buttons exist on every row: a save/unsave bookmark and a "more" menu, each with a 48 dp touch area that does not overlap the thumbnail. A card narrower than 230 dp shows only the "more" menu (save is inside it) and a small bookmark mark when the article is saved.

**Magazine arrangement.** Cards are placed left to right, then on the next row, in time order (never as a staggered masonry of unequal heights). The grid has six units per row.

- Each article asks for a size derived from a stable hash of its article ID, so the same article asks for the same size whatever the position, sort order or redraw. With three columns available the shares are small (one third) 40%, medium (one half) 25%, large (two thirds) 25% and full width 10%. With only two columns, halves (85%) and full-width cards; with one column, full width. Four and five columns (wide windows) count the row in quarters and fifths, one grid unit per column, so that an Even row is really four or five equal cards and a Mixed row has no gap; the shares are 1 / 2 / 3 / 4 units 38 / 30 / 22 / 10 % on four columns and 1 / 2 / 3 / 4 / 5 units 30 / 28 / 22 / 12 / 8 % on five, a card that does not fit the rest of its row takes the rest, and the last row is stretched. One to three columns keep the sixths and exactly the sizes of 0.7.2 (a copy of that planner is a test).
- Rows are filled with no gaps: a card that does not fit the rest of a row takes exactly the rest, and the last row is stretched. The result is an irregular, magazine-page rhythm such as 1/3+1/3+1/3, 1/2+1/2, 2/3+1/3, 1/3+2/3 or a full-width card. Row breaks can move when the sort order or the number of fetched articles changes; an individual article's preferred size does not.
- A full-width card of a mixed page puts a small picture at the left (about a quarter of the card's width, 88 to 140 dp) with the headline, the summary and the source row beside it; the picture never makes the card taller than its text. A single column at 560 dp or wider does the same.
- All tiles of a page share one picture height (the 2.3:1 height of the narrowest tile, at most 160 dp) and one text size. Nothing is reserved for text an article does not have: a card is its picture, its headline (up to 3 lines), its summary only if there is one (up to 2 lines), and the source row. The cards of one row end together at the height of the tallest card of that row (measured from their real text with the same styles, widths and text size they are drawn with), so a long article does not stretch another row and a row of short articles is low; free space in a shorter card sits above its source row. A card without a picture beside cards with one uses the free height for more summary lines instead of a blank area; a row where no article has a picture reserves no picture area.
- A summary that is only a link label ("続きを読む", "Read more", with the brackets, arrows or ellipses feeds add) or only decoration is not shown and takes no room; a sentence that merely contains such words is shown. This is display only: the stored summary and search are unchanged.
- The picture area is fixed from the start (whether the picture is still loading, failed or comes from the cache); only an article without a picture address has no picture area. The source row keeps 48 dp touch targets.
- An alternative setting draws every card at the same size ("Same size for every card").
- Number of columns: automatic keeps each card at least 220 dp wide (maximum 5 columns); the user can choose up to 1, 2, 3, 4 or 5 columns, which may go down to 150 dp per card. The width is the one the list is really given (the window width, minus the navigation pane when there is one), never a device name. Both minimums scale with the text size (the factor applies when the font scale is above 1), and columns are dropped if they would not fit. Text is never shrunk to fit. Settings shows the current number of columns and whether the choice was reduced.

**Per-category layout.** A category can override three things: the layout (List / Compact list / Magazine / Masonry / Bento), the Magazine arrangement (mixed / even) and the most columns (automatic, 1, 2, 3, 4, 5); each can also stay "use the default". The default is the global setting (Settings); "automatic columns" is a choice of its own, not the same as inheriting. Only a category has settings of its own: All, Uncategorized and a single feed use the global ones; a parent category uses its own and a child never inherits from its parent; a feed that is in two categories is shown with the settings of the category that is open (nothing is written to the feed). Each page of the pager resolves its own layout, so a page dragged in beside another keeps its own. The arrangement applies only in the Magazine layout and the columns only in Magazine, Masonry and Bento: the sheet shows only what the layout has (a note says why the arrangement is absent for Masonry and Bento) and the hidden values are kept for later. The sheet uses the same layout picker and names as Settings > Layout and navigation. A column count that does not fit the window or the text size is reduced exactly as for the global setting, and the stored choice is not changed. "Reset the display of this category" sends these three back to the default and touches nothing else (not the pin, the order, the subscriptions or any read state). Changing a layout moves no article, marks nothing and fetches nothing; the same article stays in view (the list position is remembered per list, never per layout). Images switched off in Settings stay off in every layout.

**Masonry** (`MASONRY`, "段違い"). Lanes of cards of different heights; each card goes under the shortest lane, so the page has no gaps but no rows either. A card is a picture, the headline (up to 3 lines), the summary if it says something (up to 3 lines) and the source row. The picture's shape (portrait 4:5, square, 4:3 or 16:9, at most 260 dp high) is chosen from the article's id, so its area is known before the picture arrives and nothing moves when it loads (pictures are cropped to that shape). Automatic columns allow cards down to 150 dp, so a phone shows two lanes and a 1200 dp window five; a choice of 1 to 5 columns and the text-size correction work as for Magazine. When new articles arrive at the head, the article being read stays in view, but the lanes under the new cards are dealt again (each card goes to the shortest lane counted from the first article), so cards around it can change lanes; Magazine rows behave the same way.

**Bento** (`BENTO`, "タイル"). Tiles of four shapes on a grid of equal cells: small (one cell), wide (two cells side by side), tall (two cells one above the other) and, on three columns, big (two by two). Articles are packed in order into blocks that fill every column without a gap (on two columns: a tall tile beside two small ones, a wide tile, two small tiles, or a wide tile over two small ones; on three columns similar blocks plus a big tile beside two small ones; one column: one tile per article); packing starts at the end of the list that does not grow (the oldest article when the newest are first) and the pattern of a block is chosen from the id of the article it starts with, so new arrivals change only the block at the newest end and every tile under it keeps its shape and place. A cell is as tall as a small tile's picture (2.3:1), two headline lines and the source row, at the current text size; a small tile shows its picture and headline, a wide tile its picture at the left with headline and summary, a tall or big tile a large picture with headline and as much summary as fits. Line limits come from the tile's height, so no line is cut. Columns follow the same rule as Masonry. A lone last article on three columns takes the whole width. Four and five columns have patterns of their own (a big tile with small ones, a tall tile with small and wide ones, a row of small tiles, a wide tile with small ones, and on five columns three-wide tiles), each of which fills every column, so no column stays empty on the right; a block still starts at its top left tile and ends at its bottom right, and the last few articles take a shorter pattern or one full-width tile. The 1 to 3 column patterns are those of 0.7.2 (their blocks are pinned by a test).

Both are card layouts like Magazine: the same buttons and 48 dp touch targets, the same "read more" rule for summaries, the same picture placeholders, and the most-columns setting (global or per category) applies to them; the Magazine arrangement (mixed / even) does not. Positions are kept by article, so switching between any two layouts keeps the same article near the top.

**Wide windows (Phase 2.5, 0.8.0).** The window's real width decides, never a device name (no foldable-specific branch); a fold, an unfold, a rotation or a split-screen resize is only a new width. The selected category, the filter and sort, the search text and the article at the top of the list live in the view model and are kept through it (the activity is recreated by the system on such a change); read and saved states are never touched.
- *Category navigation* (Settings > Layout and navigation, stored as `category_navigation`, default **Auto**, also for an install that never had the key): **Auto** shows the persistent pane on an expanded window (>= 840 dp) and the drawer below that; **Pane when possible** shows the pane from 600 dp as long as the article area keeps its readable width (360 dp at normal text size, more with larger text up to 1.5 times); **Drawer only** is always the modal drawer. A compact window (< 600 dp) is always the drawer, whatever was chosen. The decision is `NavigationLayout.resolve` (pure).
- The pane is the drawer's content (the same `HomeNavigationContent`: All, Saved, pinned shortcuts, categories in tab order with their feeds, Subscriptions, Settings, the same selected states, no sort or data of its own), 240 dp wide at normal text and up to 300 dp with larger text, on the left. While it is shown the menu button is absent and the drawer is not composed; the **tabs and the horizontal pager stay** (swipe and tab selection work as before) and a choice in the pane is the same selection as a tab (it ends the old page's back-to-top movement and following at once). Whether the tabs are redundant beside the pane is left for a later decision.
- Columns follow the width left beside the pane. With the pane the article area of a 1200 dp window is 960 dp: four automatic Magazine columns where Drawer only shows five. A chosen four or five that does not fit (a phone, a split screen, 200 % text) is reduced and comes back when the window is wide again; the stored choice is never changed by a resize.
- Folding postures (a hinge reported by the window manager) are not used: width alone is enough for the layouts here, and a hinge was not available to test. Lists and Compact lists stay one column on wide windows; no maximum content width is applied.

**Text and fallbacks.** Manual text size is 90% to 130% in steps of 5% (the slider in Settings; 0.9 / 1.0 / 1.15 / 1.3 of earlier versions are kept), and it multiplies the system font scale rather than adding to it. Row heights have minimums but no maximum that would clip text; at 200% text no text or control may be lost. A title that is missing is shown as "(Untitled)" and an unknown date as "Date unknown". A missing or broken thumbnail shows a neutral placeholder of the same size, so rows do not jump when an image finishes loading. When images are off the image area is removed completely and no image request is made.

Images come from what the feed itself provides (media extensions, enclosures, or an `<img>` in the entry content; tracking pixels and unsafe URLs are discarded). Article pages are never scraped for Open Graph images, and a feed with no images simply has none.

**Source icons.** A small icon precedes the source name. It comes from the feed's own declared icon/logo, else from the site's `<link rel="icon">` / `apple-touch-icon`, else `/favicon.ico`, else a coloured circle with the first letter. Each feed is looked up once (and again after long intervals or when the feed declares something new); icons are normalised to a small PNG. No third-party favicon service is used, no script is run, and a failed lookup never counts as a feed failure. The "Show thumbnails and source icons" setting turns both thumbnails and icons off: nothing is downloaded or shown (already saved icons stay hidden, not deleted).

### S12 Actions

| Action | Result |
|---|---|
| Tap a row | Opens the original URL. Read and saved state are unchanged. |
| Bookmark button | Saves or removes from saved. Does not open the article. |
| "More" menu button or long press | Mark as read / unread, save / remove from saved, share, open in external browser. |
| Pull to refresh | Refreshes the current scope. The read filter, sort and search only narrow what is shown, never what is fetched. |
| Toolbar title | Opens the category picker. |
| Display button | Opens the display panel: range, order, layout. |
| Toolbar overflow menu | Mark all as read (current scope), refresh, organize categories and tabs, import, add feed, subscriptions, settings (and search in a narrow window). |
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

The top page is short: six rows, each with the values it holds now (for example "Dark · Leaf · 115%", "Magazine · Mixed · Automatic", "Every 2 hours · Unmetered only", "No article limit · 128 MB images"). A row opens its page; there is no deeper level, and the system back goes page → top → home. A page is a plain list of settings; no page spreads a long list of choices over the screen. The pages are routes of the app's back stack (`settings`, `settings/appearance`, `settings/layout`, `settings/open`, `settings/refresh`, `settings/storage`, `settings/about`), so a page survives a screen recreation or process death.

- **Appearance:** a preview, light or dark (System / Light / Dark, one segmented row), the colour set, the text size.
  - *Colour set.* Classic, Leaf, Sand, Violet, Dynamic, shown as swatches (four colours of the set in the current light or dark mode) with the name. The chosen one has a thicker border, a check mark and the "selected" state in its semantics, so colour alone never says which is chosen. Each fixed set is a light and a dark `ColorScheme` defined together (primary, secondary and tertiary with containers, surface and its variants, outline, error and the surface containers, tinted towards the set's hue); `PalettesTest` checks the contrast of every text/background pair (AA, 7:1 for body text on the surface, 3:1 for outlines). Classic is exactly the set 0.6.0 drew, so updating changes nothing visible, and "no palette stored" means Classic. Dynamic uses `dynamicLightColorScheme` / `dynamicDarkColorScheme` and exists from Android 12; before that its swatch is disabled with "Android 12 or later" in text and in its state description, a stored Dynamic is drawn as Classic and shown selected as Classic (with a note that the choice is kept), and nothing crashes. Photos, thumbnails and icons are never tinted by a colour set.
  - *Text size.* A slider from 90% to 130% in steps of 5%, with the value shown, "Reset to 100%", and the range and the value as its semantics. The four sizes older versions offered (90, 100, 115, 130) are on the grid and are kept as stored; a value that is not on the grid reads as 100%. While the thumb is dragged only the preview changes; the size is stored when the finger is lifted, and at once for a change that is not a drag (a tap on the track, the keyboard, an accessibility action). It still multiplies the system font scale.
  - *Preview.* Fixed sample text and a fixed, deliberately un-themed picture (no real article, no network image): a tab with its accent, a card on its surface, a title, the source line with the unread and saved marks, and a summary. It follows light/dark, the colour set and, while dragging, the text size.
- **Layout and navigation:** *Category navigation* first (Auto / Pane when possible / Drawer only; see "Wide windows"), then the layout picker (below), then only what that layout has: the Magazine arrangement (Mixed / Even) for Magazine; the most columns (Automatic, 1, 2, 3, 4, 5, chosen in a dialog, with what this screen shows right now) for Magazine, Masonry and Bento; a list has neither and says so. Masonry and Bento show a note that the Magazine arrangement does not apply to them and no Mixed/Even control. The choices of a hidden option are kept for when the layout is chosen again. "Show thumbnails and source icons" is here too. This page is the value every category inherits.
- **Opening articles:** browser (Custom Tabs or external, one segmented row), a note, and the confirmation switch for `http://` articles. A note states that opening an article never marks it read.
- **Refresh & network:** automatic update interval (dialog), update when the app opens (at most every 30 minutes, same network rule), "unmetered networks only", "Confirm before updating on a metered network", "Allow unencrypted HTTP feeds and images" (with a confirmation explaining the risk; certificate checks do not change), and the HTTPS check.
- **Storage & data:** articles to keep on the device (dialog; a smaller limit is previewed and confirmed exactly as before), article count and database size (with a warning above 100 MB), image cache size (dialog; applied at the next app start), "Clear image cache", and OPML export.
- **About:** the small horizontal Folirel logo (36 dp high, left-aligned, above the version), version, build identifier, a note that data stays on the device, and the open-source licence summary. The logo is the brand picture and is never tinted or taken from the colour set: it keeps its own greens in Classic, Leaf, Sand, Violet and Dynamic alike. Only the variant changes: the light logo on a light surface, the dark logo (white lettering) on a dark one, chosen from the surface the app draws (the app's own light/dark choice, so a dark app on a light system gets the dark logo). It sits in the scrolling page, so large text pushes nothing out of reach.

**Brand.** The Folirel logo and icon are fixed identity, separate from the user's preferences: the launcher icon and the logo do not change with the colour set, the light/dark choice or Dynamic colour. The SVG files in `artwork/folirel/` are the source of truth (colours, variants and the Android layers are described there). The launcher icon is an adaptive icon (normal and round, the same symbol) with a dedicated monochrome layer for Android 13's themed icons; the app has no custom splash screen (Android's own splash shows the icon), no logo on the home screen or its toolbar, and the list area is unaffected.

**Controls.** On/off settings are switch rows: the whole row, its text included, toggles it (exposed as a switch with its state). A choice of two or three short labels is a segmented row (light/dark, browser, Mixed/Even); when the labels would not fit (a narrow window or large text) it becomes selectable rows with radio buttons, never clipped labels. A choice with many fixed values (interval, article limit, cache size, columns) is one row that shows the current value and opens a dialog; choosing a value applies it and closes the dialog. The size is a slider. The five layouts are a picker of small schematic tiles with their names (below). Every control writes only its own stored setting, through one ordered queue, so a quick series of taps or two slider releases always ends with the last choice; a write that fails is not retried: the control keeps showing the stored value (a slider drops the value it was only previewing) and a message "Could not save the setting." appears on the page (the Home screen shows "Could not save the change." for the layout and the two "do not ask again" boxes); nothing else happens (no refresh, no article, read or saved change, no category change, no scroll reset, no image cache clearing, no database write).

**Layout picker.** One component for Settings, the display panel of the home screen and a category's own settings: List, Compact list, Magazine, Masonry, Bento, in that order and with the same names, as tiles that draw how the layout arranges articles, three and two per row on a phone and five in a row on a wide window; a tile is at least 88 dp wide and grows with the text size, so at large text there are fewer per row (down to one) and a name is never broken inside a word. The chosen tile has a thicker border, the primary container colour and a check badge, and is a radio button in the semantics. For a category, a first row "Use the default (<name of the global layout>)" belongs to the same group; it is a choice of its own. The same rules decide what follows: the arrangement only for Magazine, the columns for Magazine, Masonry and Bento (in a dialog whose first entry is "Use the default (…)", distinct from "Automatic"), nothing for the lists.

Rules:

- OPML export is labelled "subscriptions and categories only; read and saved state are not included", and warns that feed URLs may contain private tokens.
- "Articles to keep" is not a fetch limit: every article a feed returns is stored. After a confirmation that shows the numbers, it removes only the oldest articles that are both read and not saved, above the chosen number; unread and saved articles are never removed. Removed articles are remembered so that a feed that still lists them does not bring them back as new unread articles.
- "Clear image cache" deletes images only. It never touches subscriptions, categories, read state or saved state. There is no "reset database" or "delete history" action.

## 6. Position restore and new articles

The scroll position of each list is kept as the identity of an anchor article plus a pixel offset, keyed by account, scope, read filter, sort order and search text. The list layout is deliberately not part of the key, so changing the layout keeps the same article in view. Positions survive returning from the browser, rotation and process recreation (through saved instance state; at most 40 lists are remembered). If the anchor article no longer exists, the nearest remaining article is used. Switching to another category and back restores each category's own place.

**Back to the top** (the arrow button, tapping the selected tab, the new-article banner of a newest-first list) is one movement of the list, not a series of animations: an ease-in-out of about a quarter to two thirds of a second (the time grows with the distance, at most 650 ms; none at all when the system animations are switched off). A list far from its start skips once to about two and a half screens from it and moves the rest. Every frame moves the list by the share of the distance left that the curve asks for, and the distance left is measured again every frame, so the list does not stop and start again card by card, and it lands exactly on the first article. While it moves the list keeps the articles it was moving over; articles that arrive meanwhile (a refresh delivers feed after feed) are applied when it arrives, and for four seconds afterwards articles that arrive at the head are followed instantly, so the newest one is not left above the screen. A second request while it moves joins it, also when it is a real press on the arrow or on the new-article notice (those presses are the request, not a touch of the list). Any other touch of the list (a finger put down on a card or empty space, a drag, a long press, a tap on a card or its menu) or a wheel scroll ends the movement and the following at once and nothing starts them again. Choosing another category (tab or drawer) ends them at that moment, even while the page of the category left is still shown during the slide; changing the filter or leaving the screen ends them too. The articles the movement held are released then.

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
