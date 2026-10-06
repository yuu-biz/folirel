[日本語版 / Japanese](user-guide.ja.md)

# Folirel user guide

Folirel is a local RSS/Atom reader for Android. You import a list of subscriptions (for example an OPML file exported from Feedly), and the app downloads each site's feed directly to your device, shows the articles in one list, and opens the original article in your browser. There is no account, no server and no sign-in.

Contents: [Install](#install) · [First run](#first-run) · [Import OPML](#import-opml) · [Add a feed](#add-a-feed-url) · [Daily use](#daily-use) · [Categories](#categories) · [How updates work](#how-updates-work) · [HTTP and HTTPS](#http-and-https) · [Managing subscriptions](#managing-subscriptions) · [Settings](#settings) · [Data, backup and uninstalling](#data-backup-and-uninstalling) · [Limitations](#limitations) · [Troubleshooting](#troubleshooting)

For developers: see [building.md](building.md). For the project overview: [../README.md](../README.md).

## Install

Folirel needs Android 9 (API 28) or newer.

1. Get the APK: download it from the project's release page if one is published, or build it yourself by following [building.md](building.md).
2. Open the APK on the device. Android will ask you to allow installing apps from the source you used (browser or file manager); this is a one-time permission for that source.
3. Alternatively, install from a computer with `adb install -r app-debug.apk` while USB debugging is enabled.

Important if you use a debug build: debug APKs are signed with the debug key of the machine that built them. An APK built on another computer has a different signature and cannot be installed over your existing copy; Android requires uninstalling first, which deletes your data. Export your subscriptions as OPML before doing that (see [Data, backup and uninstalling](#data-backup-and-uninstalling)).

## First run

On first launch a welcome screen explains the basics:

- Articles are fetched directly from each site.
- Your subscriptions are stored on this device. No account is needed.
- OPML moves subscriptions only; read and saved state is not transferred.
- "Save" is a bookmark, not an offline copy of the page.

It also shows the option **Update every 2 hours** (only on unmetered networks; the system may delay updates). You can turn it off here or later in Settings. Tap **Skip** to continue.

With no subscriptions the home screen offers two buttons: **Import OPML** and **Add feed URL**.

## Import OPML

OPML is the standard file format for exchanging feed subscriptions. Most feed readers can export it.

1. Export an OPML file from your current reader. In Feedly this is the OPML export in its web interface (the exact menu location is decided by Feedly; check its help if you cannot find it). Copy the file to your phone.
2. In Folirel open **Import OPML** from the home screen's more-options menu (the three dots), or from the same menu on the **Subscriptions** screen.
3. Tap **Choose file** and pick the OPML file. Nothing is fetched yet.
4. Review the **Preview**. It lists counts such as New feeds, Already subscribed, Will be subscribed again, New categories, Category memberships to add, Feeds in several categories, http:// feeds, Invalid URLs (skipped), Unsupported items (skipped), Empty categories and Non-standard structures.
5. Tap **Import**. The data is saved on the device and the feeds are fetched. When it finishes, **Import complete** appears; tap **Go to articles**.

Notes:

- Importing only adds and merges. It never deletes your existing feeds, read state or saved articles. Importing the same file twice is harmless.
- Folders in the OPML become categories. A feed that appears in several folders is kept in each of them.
- OPML contains subscriptions only. Read and saved state, article history, and items that are not in the exported file (for example Feedly Boards or AI Feeds) are not transferred.
- If the file contains feeds you previously unsubscribed, the preview names them and says they will be subscribed again.
- If the import contains `http://` feeds, a dialog offers to check whether they work over HTTPS (see [HTTP and HTTPS](#http-and-https)).
- A file is rejected if it is too large or has too many entries, is not valid XML, is not an OPML document, or contains DOCTYPE/entity declarations (these are refused for safety). In that case nothing is changed.

## Add a feed URL

Choose **Add feed URL** (home screen menu, empty-state button, or the plus button on **Subscriptions**) and enter the address of an RSS or Atom feed. This must be the feed itself, not the web page of the site. If you enter a page address, the feed will later fail with "Not a feed (HTML page)". Feed formats supported are RSS 2.0, Atom 1.0 and RSS 1.0 (RDF).

If the URL starts with `http://`, the feed is registered but not fetched until you allow HTTP in Settings (see [HTTP and HTTPS](#http-and-https)).

## Daily use

### Reading

| Goal | How |
|---|---|
| Read the original article | Tap the row. It opens in an in-app browser tab (Custom Tabs) or your external browser, depending on **Open articles in** in Settings. Opening an article never marks it as read. |
| Save (bookmark) | Tap the bookmark icon on the row. Saved articles appear under **Saved articles** in the navigation menu, or with the **Saved** filter. |
| Mark as read or unread, share, open in external browser | Tap the row's three-dot button (**More options**), or long-press the row. Menu items: **Mark as read** / **Mark as unread**, **Save** / **Remove from saved**, **Share**, **Open in external browser**. |
| Articles without a link | Tapping shows **Show summary**, the summary that the feed supplied. |
| Mark many as read | More-options menu, **Mark all as read**. A dialog shows the scope, the active filter, any search text and the number of unread articles that will change. Nothing happens until you confirm. |
| Back to the top | Use the **Back to top** button that appears while scrolling. |

Read, unread and saved are changed only by explicit actions. Refreshing never overwrites them.

### Moving around

- **Navigation menu**: open it with the menu button at the top left (**Open navigation**). It contains All articles, Saved articles, Uncategorized, your Categories (expandable), Subscriptions and Settings.
- **Tabs and swiping**: tabs at the top show **All**, **Uncategorized**, your pinned categories and your other categories. Swipe the list left or right to move between them; the tab row moves with your finger, and the order stops at both ends and does not wrap around. Each tab remembers its scroll position. You can also slide the tab row itself to look for a tab; that does not change the category.
- **Go to a category**: press the category name at the top (with the small arrow). Choose a category from the list, or type part of a name or of its parent's name to search. The pin button on each row pins it to the front of the tabs.
- **Display and filters**: the button next to search opens **Display and filters**: **All** / **Unread** / **Saved**, **Newest first** / **Oldest first**, and the layout. A small line under the category name shows the current range and order.
- **Search**: the magnifier icon searches titles, summaries and sources of articles already on the device, within the current scope. It never contacts the network.
- **List style**: in **Display and filters** (for the category you are looking at, or for everything when you are on All) or in Settings (the default for all categories), choose one of five layouts:
  - **List**: one column with headline, summary and thumbnail.
  - **Compact list**: one dense line per article.
  - **Magazine**: cards of mixed sizes (or all the same size, see Settings) in up to three columns on wide screens.
  - **Masonry**: cards of different heights in lanes (two on a phone), each picture in its own shape.
  - **Bento**: tiles of different shapes (small, wide, tall, big) that fill the width without gaps.

## Categories

- **Order of the tabs**: more-options menu, **Organize categories and tabs** (or the same button at the bottom of the category list). Pin a category to put it in front, drag the handle to move it, or use the row's menu (**Move up** / **Move down**). **Back to the standard order** only resets the order; pins and each category's display settings stay. This changes the order of the tabs only, not which categories contain which feeds.
- **Display of one category**: with that category open, **Display and filters** changes its layout only (the first choice, **Use the default**, follows Settings). **Arrangement and columns…** sets the Magazine arrangement and the most columns for that category; **Reset the display of this category** puts all three back to the default.

Categories are optional and can be nested.

- Open **Subscriptions**, then the more-options menu, then **Categories**. Here you can create a **New category** or **New subcategory**, **Rename**, or **Delete category**.
- To place a feed in categories, open the feed in **Subscriptions** and tick its categories in the **Categories** section. A feed can be in several categories.
- Deleting a category (with its subcategories) does not delete feeds or articles. Feeds left without a category become uncategorized.

## How updates work

- **Pull down** on the list, or choose **Refresh** in the more-options menu. A refresh covers only what you are looking at (All, one category, or Uncategorized). The Unread, Saved and search filters do not narrow it.
- If you are on a metered network, a dialog (**Metered network**) asks whether to update this time (**Update now**). Tick **Do not ask again on metered networks** to update without asking from then on; **Confirm before updating on a metered network** in Settings brings the question back.
- **Automatic update** (Settings) runs in the background every 15 or 30 minutes, 1, 2, 6, 12 or 24 hours, or is **Off**. The default is every 2 hours. Android decides the exact time and may delay it. By default it runs only on unmetered networks (this uses Android's metered flag, which is not a strict Wi-Fi check).
- **Update when the app opens** (on by default) refreshes when you open the app, at most once every 30 minutes, under the same network rule.
- Feeds are fetched up to 4 at a time, at most 2 from the same host, within a time budget of about 6 minutes per update. Feeds that were not reached are fetched first next time. After a periodic update that ran out of time, up to two short follow-up runs continue with the unfinished feeds.
- Requests are conditional (ETag / Last-Modified) when the server supports it, so unchanged feeds cost little. Failed feeds wait before being retried automatically, and a server's `Retry-After` is respected. A manual refresh retries an ordinary failure immediately.
- **Result band**: during and after an update the band above the list shows progress and a summary such as "12 feeds: OK 10 · failed 1 · waiting 1". Network trouble is never shown as an empty success: if some feeds were not updated the band says so, with **Retry unfinished**.
- **Single feed**: open it from **Subscriptions** and use **Refresh this feed**. If a normal refresh does not bring changes, use **Revalidate and refetch**. It ignores stored validators and asks caches to revalidate; articles and read/saved state are kept. A server or CDN may still return old content.
- **Feed details** show the last request, response status and duration, whether a conditional request was used, the last feed document that was applied (entries in the document, after merging duplicates, without date, date range, new and updated counts), the number of articles on the device, and the reason a feed cannot be fetched.
- Articles without a publication date are shown as **Date unknown**.

## HTTP and HTTPS

Unencrypted `http://` traffic can be read or altered on the network, so Folirel is conservative.

- Feeds and images from `http://` addresses are **not fetched** unless you turn on **Allow unencrypted HTTP feeds and images** in Settings (off by default). Such feeds show **HTTP pending** / **HTTP held**. The setting affects only the app's own feed and image downloads. Certificate checks for HTTPS are never relaxed, and only the system's trusted certificates are used.
- **Check HTTPS support of HTTP feeds** (in the **Subscriptions** menu, on the Subscriptions summary, and in Settings) fetches each `http://` feed once over HTTPS with normal certificate checks. Only feeds that return the same RSS/Atom feed over HTTPS are switched to HTTPS; their articles, categories and read/saved state stay as they are. Feeds that cannot be confirmed stay on HTTP, with a reason (for example "HTTPS returned a different feed", "Certificate/TLS error", "Timed out (twice); try later"). The check continues in the background if you leave the screen. After an OPML import with HTTP feeds, one confirmation starts the check for all of them.
- Allowing HTTP for the feeds that remain is a separate decision in Settings.
- **Opening an `http://` article**: Folirel asks first (**Unencrypted page**: **Open this time** or **Cancel**). Tick **Do not ask again before opening http:// articles** to stop the question; **Confirm before opening http:// articles** in Settings turns it back on. This is independent of the setting for downloading HTTP feeds and images, and the browser keeps its own warnings.

## Managing subscriptions

Open **Subscriptions** from the navigation menu.

- The top of the screen summarises the last results (feeds OK, failed, HTTP pending, waiting, unfinished). Filter chips narrow the list to **Failed**, **Not fetched yet**, **HTTP held**, **Waiting to retry** or **Paused**. **Search subscriptions** finds a feed by name.
- Tap a feed to see its details. There you can **Rename**, choose its **Categories**, switch **Stop fetching this feed** (pause) on or off, **Refresh this feed**, and **Unsubscribe**.
- **Unsubscribe** stops updates and removes the feed from normal lists. Articles, saved items, read state and categories remain on the device, and saved articles stay available under **Saved**. The feed moves to the **Unsubscribed (saved articles kept)** section, where **Subscribe again** restores it. Adding the same URL again also restores it.
- **Several at once**: more-options menu, **Select feeds** (or long-press a feed), then **Unsubscribe N feeds**. The confirmation shows each feed's failure kind, HTTP status and last success, and feeds that have started working again are kept subscribed. **Undo** is offered right after. A failure does not prove a site is closed: a 404/410 can also mean the address moved.
- **Find where this feed moved** (in the feed details): **Look on the site** looks for RSS/Atom links on the feed's own site and checks that each is a real feed, showing entry counts and whether it shares articles with your current feed. **Search the web by feed name** hands the feed name to your device's search app when you tap it; you can paste an address you find and use **Check this address**. Nothing is chosen automatically. **Use this address** asks for confirmation (**Change address**); the feed then keeps its articles, categories and read/saved state, although some articles may reappear as new. Changing a feed's address this way is the only supported change: to use a different URL otherwise, add it as a new feed.
- The more-options menu of **Subscriptions** also contains **Categories**, **Check HTTPS support of HTTP feeds**, **Retry unfinished**, **Import OPML** and **Export OPML**.

## Settings

Settings opens on a short list of six pages; each row shows the values it holds now. Open a page to change them; the back button returns to the list.

| Page | Option | Choices / notes |
|---|---|---|
| Appearance | Light or dark | System, Light, Dark |
| Appearance | Color | Classic (the blue used so far, default), Leaf, Sand, Violet, Dynamic (takes colors from your wallpaper; Android 12 and later, shown as unavailable on older versions). Light/dark and color are separate. Photos and icons are never tinted |
| Appearance | Text size | A slider from 90% to 130% in steps of 5%, with **Reset to 100%**. The preview above it follows the slider; the size is saved when you let go. It multiplies the system font size |
| Default layout | Layout | List, Compact list, Magazine, Masonry, Bento, shown as small pictures. This is the default for every category; a category can use its own |
| Default layout | Magazine arrangement | Mixed, Even. Shown only for Magazine |
| Default layout | Max columns | Automatic, 1, 2 or 3 columns, for Magazine, Masonry and Bento. If the chosen number would make headlines too narrow (small window, large text), fewer columns are used; text is never shrunk to keep columns |
| Default layout | Show thumbnails and source icons | On by default. When off, no thumbnails or source icons are downloaded or shown. Icons are looked up once per feed from the feed or its own site, never from a third-party service |
| Opening articles | Open articles in | Custom Tabs, External browser |
| Opening articles | Confirm before opening http:// articles | On by default |
| Refresh & network | Automatic update | Off, every 15 / 30 minutes, 1 / 2 (default) / 6 / 12 / 24 hours |
| Refresh & network | Update when the app opens | On by default; at most every 30 minutes |
| Refresh & network | Automatic updates on unmetered networks only | On by default |
| Refresh & network | Confirm before updating on a metered network | On by default |
| Refresh & network | Allow unencrypted HTTP feeds and images | Off by default. A shortcut to **Check HTTPS support of HTTP feeds** is on the same page |
| Storage & data | Articles to keep on the device | No limit (default), or about 1,000 / 3,000 / 5,000 / 10,000 / 30,000. See below |
| Storage & data | Image cache size | 64, 128 (default), 256 or 512 MB; applies at the next app start. **Clear image cache** removes cached thumbnails only |
| Storage & data | Export OPML | Subscriptions and categories only |
| About | Version, build, licence summary | The version line shows the version, version code and build identifier |

The Storage & data page also shows the number of articles stored and the database size, and warns when the database is larger than 100 MB.

**Articles to keep on the device** is not a limit on fetching: every article a feed returns is stored. If you choose a number, Folirel shows how many old, read, unsaved articles would be removed and asks for confirmation. After that the same cleanup runs after updates. Unread and saved articles are never removed. Removed articles that a feed delivers again do not return as unread.

## Data, backup and uninstalling

- All data (subscriptions, categories, articles, read and saved state, settings) is stored only on the device, in the app's private storage. Android's automatic backup and device-to-device transfer are disabled for the app, deliberately, because feed URLs can contain private tokens.
- **Uninstalling the app, or clearing its storage in Android's settings, deletes everything.**
- **Export OPML** (in **Subscriptions** or in Settings, Data) saves your subscriptions and categories to a file you choose. It is the only backup. It does not include read or saved state or articles. Feed URLs may contain personal tokens, so keep the file private.
- To move to a new phone: export OPML, copy the file, import it on the new device. Read and saved state will not come along.
- Updating the app in place requires an APK with the same application ID and the same signing key. See [Install](#install) for debug builds.

## Limitations

- No built-in article reader, no full-text extraction, no offline copy of pages, no notifications, no widgets, no text-to-speech and no sync between devices. Articles are read on the original site.
- Only what a feed currently returns is available. Older articles are not retrieved by crawling archives or pages.
- A single feed document is limited to about 5 MiB and 2,000 entries; larger documents fail with "Too large (size/entry limit)". OPML files are limited to 5 MiB.
- Supported feed formats: RSS 2.0, Atom 1.0, RSS 1.0 (RDF). Other formats (for example JSON Feed) are not supported.
- Background update times are approximate and depend on Android's power management.
- Older Android versions (for example Android 9) may fail to open HTTPS connections to some sites if their root certificates are out of date. Folirel does not weaken certificate checks to work around this.
- The language follows the system language: Japanese if the device is set to Japanese, English otherwise. On Android 13 and later you can also pick the app language in the system settings (Settings > Apps > Folirel > Language); there is no language switch inside the app.

## Troubleshooting

| What you see | What it means and what to do |
|---|---|
| No articles after import | Wait for the first fetch (**Fetching feeds…**), or pull down to refresh. Check the band above the list and the **Subscriptions** summary for failures. |
| **HTTP pending** / **HTTP not allowed in settings** | The feed uses `http://`. Run **Check HTTPS support of HTTP feeds**, or allow HTTP in Settings. |
| **Not a feed (HTML page)** | The address is a web page. Find the site's RSS/Atom feed address, or use **Find where this feed moved** on the feed details. |
| **Not found (404)** / **Feed removed (410)** | The address may have moved or the feed was removed. Try **Find where this feed moved**, or unsubscribe. |
| **Access denied (401/403)** | The server refuses the request. Private feeds that need a sign-in cannot be read. |
| **Rate limited (429)** / **Server error (5xx)** / **Timed out** / **Network error** | Usually temporary. Folirel retries later; use **Retry unfinished** to try sooner. A server-requested wait is respected. |
| **Certificate error** | The site's HTTPS certificate cannot be verified (expired, wrong name, unknown issuer, or an outdated root list on an old device). Folirel will not bypass this. |
| **Broken feed (XML error)** / **Unsupported feed format** | The document is not valid RSS/Atom. Nothing to fix on your side; tell the site owner or use another feed address. |
| **Too large (size/entry limit)** | The feed exceeds the limits above. |
| **Blocked for safety** | The address (or a redirect) points to something Folirel refuses to contact, such as a local or private network address (Folirel contacts public internet addresses only). |
| **Could not save (check free space)** | The device is out of storage. Free some space. |
| A refresh changes nothing | Use **Revalidate and refetch** on the feed. The site or its CDN may still be serving old content. |
| The band says **Last update was interrupted**, or a feed's result is **Not reached (time limit)** | The update stopped early. Feeds not reached go first next time, or use **Retry unfinished**. |
| Automatic updates seem late | Android may delay background work (battery saver, Doze). Set the app's battery usage to unrestricted if you need more regular updates, and keep **Update when the app opens** on. |
| Cannot install an update | The APK is signed with a different key than the installed one. See [Install](#install). |
| **No app can open this link** | Install or enable a browser, or switch **Open articles in** to the other option. |
