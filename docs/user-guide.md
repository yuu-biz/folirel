[日本語版 / Japanese](user-guide.ja.md)

# Folirel user guide

Folirel is a local RSS/Atom reader for Android. You import a list of subscriptions (for example an OPML file exported from Feedly), and the app downloads each site's feed directly to your device, shows the articles in one list, and opens the original article in your browser. There is no account, no server and no sign-in.

Contents: [Install](#install) · [First run](#first-run) · [Import OPML](#import-opml) · [Add a feed](#add-a-feed-url) · [Share to Subscribe](#subscribe-from-another-app-share-to-subscribe) · [Daily use](#daily-use) · [Categories](#categories) · [How refreshing works](#how-refreshing-works) · [HTTP and HTTPS](#http-and-https) · [Managing subscriptions](#managing-subscriptions) · [Settings](#settings) · [Data, backup and uninstalling](#data-backup-and-uninstalling) · [Limitations](#limitations) · [Troubleshooting](#troubleshooting)

For developers: see [building.md](building.md). For the project overview: [../README.md](../README.md).

## Install

Folirel needs Android 9 (API 28) or newer. It is installed from the APK on the project's [GitHub Releases](https://github.com/yuu-biz/folirel/releases) page.

1. On the phone, download the APK of the latest release.
2. Open the downloaded file. If Android says that installing unknown apps is not allowed, tap **Settings**, turn on **Allow from this source** for your browser or file manager, and go back.
3. Tap **Install**.

To check that the APK is the one the project published, compare its signing certificate on a computer with the Android SDK build tools:

```sh
apksigner verify --print-certs folirel.apk
```

The line `Signer #1 certificate SHA-256 digest` must read `<RELEASE_CERT_SHA256>`. If it does not, do not install the file.

**Moving from a 0.x build.** Builds before 1.0 were signed with a development key, and Android cannot install 1.0 over them. Create a Folirel backup in the old app (Settings > Storage and data > **Create Folirel backup**), uninstall it, install 1.0, and restore the backup (Settings > Storage and data > **Restore from Folirel backup**). The steps, including how to check the backup before you uninstall, are in [Full backup and restore, section 7](backup-format.md#7-moving-to-a-differently-signed-app-release-signing). Builds before 0.10.0 have no full backup; export OPML there instead (subscriptions and categories only).

## First run

On first launch a welcome screen explains the basics:

- Your subscriptions are stored on this device. No account is needed.
- Articles are fetched directly from each site.
- "Save" is a bookmark, not an offline copy of the page.
- OPML moves subscriptions only, not read or saved state.

It also shows the option **Refresh every 2 hours** (only on unmetered networks; Android may run it later than planned). You can turn it off here or later in Settings. Tap **Skip** to continue.

With no subscriptions the home screen offers two buttons: **Import OPML** and **Add feed URL**.

## Import OPML

OPML is the standard file format for exchanging feed subscriptions. Most feed readers can export it.

1. Export an OPML file from your current reader. In Feedly this is the OPML export in its web interface (Feedly decides where the menu is; check its help if you cannot find it). Copy the file to your phone.
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
- A file is refused if it is too large or has too many entries, is not valid XML, is not an OPML document, or declares a DOCTYPE or entities (these are refused for safety). In that case nothing is changed.

## Add a feed URL

Choose **Add feed URL** (home screen menu, empty-state button, or the plus button on **Subscriptions**) and enter the address of a feed, or of the site or page you want to follow, then tap **Add**. Feed formats supported are RSS 2.0, Atom 1.0, RSS 1.0 (RDF) and JSON Feed 1.0 and 1.1.

- If the address is a feed, it is added at once.
- If it is a web page, Folirel looks for the feeds the page declares (and, if it declares none, once on the top page of the same site), checks that each is a real feed and lists them. Tap **Subscribe** on the one you want; nothing is added until you do. This is the same search as [Share to Subscribe](#subscribe-from-another-app-share-to-subscribe).
- If nothing is found, or the address cannot be read, Folirel says why. Cancel or OK takes you back to the address you typed, so you can correct it.
- You can leave out `https://` (for example `example.com`).
- An `http://` address is checked only after you allow HTTP in Settings (see [HTTP and HTTPS](#http-and-https)); until then Folirel says so, sends nothing and adds nothing.

## Subscribe from another app (Share to Subscribe)

In a browser or any app, share a page or a link to **Folirel** (the share sheet lists it for text). Folirel takes the addresses out of the shared text. With one address, it looks for a feed there: if the address is a feed itself, or the page declares feeds, it checks that each is a real feed. If the text has several addresses, you choose which one to look at.

- Nothing is added when you share. A dialog shows what was found (title, address, number of entries, latest date). Tap **Subscribe** to add it, or **Cancel** or Back to leave everything as it was. When a page offers several feeds you pick one.
- If you already subscribe to the feed, the dialog says so and offers **Feed details**. If you unsubscribed from it earlier, it offers **Subscribe again**, and its articles, read and saved state and categories come back as they were.
- The feed is added without a category; change that in the feed details. `http://` addresses follow the HTTP setting like everything else: while HTTP is off, Folirel says so and sends nothing.
- If the shared page (an article, say) declares no feed, Folirel looks once at the top page of the same site for the feeds it declares and says "Found on the top page of the site" for what it finds there. It never looks deeper, guesses addresses or searches the web. If there is still no feed, it says so; find the feed address yourself and add it with **Add feed URL**.
- If you share before finishing the first-run screen, the share waits and is handled once it is done.

## Daily use

### Reading

| Goal | How |
|---|---|
| Read the original article | Tap the row. It opens in an in-app browser tab (Custom Tabs) or your external browser, depending on **Open articles in** in Settings. Opening an article never marks it as read. |
| Save (bookmark) | Tap the bookmark icon on the row. Saved articles are shown with **Saved** in **Display and filters** (the button next to search). The navigation menu only chooses where to look, so **Saved** stays on when you change category. |
| Mark as read or unread, share, open in external browser | Tap the row's three-dot button (**More options**), or long-press the row. Menu items: **Mark as read** / **Mark as unread**, **Save** / **Remove from saved**, **Share**, **Open in external browser**, then **Show articles from this feed** (that feed as the list, nothing marked or opened; Back returns to where you were) and **Feed details** (the feed's page: address, status, pause). |
| Swipe a row | In **List** and **Compact list**, swipe a row right to mark it read or unread, or left to save it or remove it from saved (Settings can swap the directions or turn this off). The row slides back; a short vibration tells you when letting go will act, and a shorter swipe changes nothing. If the row no longer belongs in the list (for example marked read while **Unread** is shown), it leaves and **Undo** appears. While this is on, use the tabs to change category. Cards have no swipe. |
| Articles without a link | Tapping the row shows "This article has no link". Long-press the row, or choose **Show summary** in its three-dot menu, to read the summary the feed supplied. |
| Mark many as read | More-options menu, **Mark all as read**. A dialog shows the scope, the articles shown (All, Unread or Saved), the period, any search text, whether hidden keywords are applied, and the number of unread articles that will change. Only the articles you can see in that list are included. Nothing happens until you confirm. |
| Back to the top | Use the **Back to top** button that appears while scrolling. |

Read, unread and saved are changed only by explicit actions. Refreshing never overwrites them.

### Moving around

- **Navigation menu**: open it with the menu button at the top left (**Open navigation**). It chooses where to look and contains All articles, Uncategorized, your categories (expandable), Subscriptions and Settings.
- **Tabs and swiping**: tabs at the top show **All**, **Uncategorized**, your pinned categories and your other categories. Swipe the list left or right to move between them (in List and Compact list with **Swipe actions** on, a swipe on an article acts on the article instead, so use the tabs). The tab row moves with your finger, and the order stops at both ends and does not wrap around. Each tab remembers its scroll position. You can also slide the tab row itself to look for a tab; that does not change the category.
- **Go to a category**: press the category name at the top (with the small arrow). Choose a category from the list, or type part of its name or of its parent's name to search. The pin button on each row pins it to the front of the tabs.
- **Display and filters**: the button next to search opens **Display and filters**: **All** / **Unread** / **Saved**, the **Period** (**All**, **24 hours**, **3 days**, **7 days**, **30 days**, counted back from now; nothing is deleted), **Hidden keywords**, **Newest first** / **Oldest first**, and the layout. A small line under the category name shows the current choice, period and order.
- **Hidden keywords**: **Manage** in **Display and filters** opens a list where you add words (up to 32 words of up to 64 characters). Articles whose title or summary contains a word are left out of the lists, in every category and under **Saved**. Hiding is not reading and not deleting: turn the filter off or remove the word and the articles are back, with their read and saved state as before. A small dot on the display button shows that the filter is on.
- **Search**: the magnifier icon searches titles, summaries and feed names of articles already on the device, within the current scope. It never contacts the network.
- **Layout**: choose it in **Display and filters** (for the category you are looking at, or for everything when you are on All) or in Settings (the default for all categories). There are five layouts:
  - **List**: one column with headline, summary and thumbnail.
  - **Compact list**: one dense line per article.
  - **Magazine**: cards of mixed sizes (or all the same size, see Settings) in up to five columns on wide screens (the number follows the width of the window and the text size).
  - **Masonry**: cards of different heights in lanes (two on a phone), each picture in its own shape.
  - **Bento**: tiles of different shapes (small, wide, tall, big) that fill the width without gaps.

## Categories

Categories are optional and can be nested.

- Open **Subscriptions**, then the more-options menu, then **Categories**. Here you can create a **New category** or **New subcategory**, **Rename**, or **Delete category**.
- To place a feed in categories, open the feed in **Subscriptions** and tick its categories in the **Categories** section. A feed can be in several categories.
- Deleting a category (with its subcategories) does not delete feeds or articles. Feeds left without a category become uncategorized.
- **Order of the tabs**: more-options menu, **Organize categories and tabs** (or the same button at the bottom of the category list). Pin a category to put it in front, drag the handle to move it, or use the row's menu (**Move up** / **Move down**). **Back to the standard order** resets only the order; pins and each category's display settings stay. This changes the order of the tabs, not which categories contain which feeds.
- **Display of one category**: with that category open, **Display and filters** changes its layout only (the first choice, **Use the default**, follows Settings). **Arrangement and columns…** sets the Magazine arrangement and the most columns for that category. **Reset the display of this category** puts all three back to the default.

## How refreshing works

- **Pull down** on the list, or choose **Refresh** in the more-options menu. A refresh covers only what you are looking at (All, one category, or Uncategorized). **Unread**, **Saved** and search do not narrow it.
- If you are on a metered network, a dialog (**Metered network**) asks whether to refresh this time (**Refresh now**). Tick **Do not ask again on metered networks** to refresh without asking from then on. **Ask before refreshing on a metered network** in Settings brings the question back.
- **Automatic refresh** (Settings) runs in the background every 15 or 30 minutes, 1, 2, 6, 12 or 24 hours, or is **Off**. The default is every 2 hours. Android decides the exact time and may delay it. By default it runs only on unmetered networks, which usually means Wi-Fi; Android decides which networks are metered.
- **Refresh when the app opens** (on by default) refreshes when you open the app, at most once every 30 minutes, on the same networks as automatic refresh.
- Feeds are fetched up to 4 at a time, at most 2 from the same host, within about 6 minutes per refresh. Feeds that were not reached are fetched first next time. After an automatic refresh that ran out of time, up to two short follow-up runs continue with the unfinished feeds.
- Folirel asks the server whether a feed changed (ETag / Last-Modified) when the server supports it, so unchanged feeds cost little. Failed feeds wait before Folirel retries them automatically, and a server's `Retry-After` is respected. A manual refresh retries an ordinary failure immediately.
- **Result notice**: during and after a refresh the notice over the list shows progress and a summary such as "Feeds: 12 · OK 10 · failed 1 · waiting 1". Network trouble is never shown as an empty success: if some feeds were not refreshed the notice says so, with **Retry unfinished**.
- **Single feed**: open it from **Subscriptions** and use **Refresh this feed**. If a normal refresh shows no changes, use **Force refresh**. It fetches the feed without the stored change check; articles and read state are kept. A server or CDN may still return old content.
- **Feed details** show the last request, the response (with the HTTP status code) and how long it took, whether a conditional request was used, the last successful fetch (entries in the document, after merging duplicates, without date, date range, new and changed counts), the number of articles on the device, and the reason a feed cannot be fetched.
- Articles without a publication date are shown as **Date unknown**.

## HTTP and HTTPS

Unencrypted `http://` traffic can be read or changed on the network, so Folirel is careful with it.

- Feeds and images from `http://` addresses are **not fetched** unless you turn on **Allow unencrypted HTTP feeds and images** in Settings (off by default). Such feeds show **HTTP not allowed**. The setting affects only Folirel's own feed and image downloads, not your browser. Certificate checks for HTTPS are never relaxed, and only the system's trusted certificates are used.
- **Check HTTPS support of HTTP feeds** (in the **Subscriptions** menu, on the Subscriptions summary, and in Settings) fetches each `http://` feed once over HTTPS with normal certificate checks. Only feeds that return the same RSS or Atom feed over HTTPS are switched to HTTPS; their articles, categories and read and saved state stay as they are. Feeds that cannot be confirmed stay on HTTP, with a reason (for example "HTTPS returned a different feed", "Certificate error", "Timed out twice. Try later"). The check continues in the background if you leave the screen. After an OPML import with HTTP feeds, one confirmation starts the check for all of them.
- Allowing HTTP for the feeds that remain is a separate decision in Settings.
- **Opening an `http://` article**: Folirel asks first (**Unencrypted page**: **Open this time** or **Cancel**). Tick **Do not ask again before opening http:// articles** to stop the question. **Ask before opening http:// articles** (Settings > Opening articles) turns it back on. This is separate from the setting for downloading HTTP feeds and images, and the browser keeps its own warnings.

## Managing subscriptions

Open **Subscriptions** from the navigation menu.

- The top of the screen summarises the last results, for example "Feeds: 40 · OK 37 · failed 2 · HTTP not allowed 1". Counts that are zero are left out. Filter chips narrow the list to **Failed**, **Not fetched yet**, **HTTP not allowed**, **Waiting to retry** or **Paused**. **Search subscriptions** finds a feed by name.
- Tap a feed to see its details. There you can **Rename**, choose its **Categories**, turn **Pause this feed** on or off, **Refresh this feed**, and **Unsubscribe**.
- **Unsubscribe** stops refreshing the feed and removes it from the lists. Articles, saved items, read state and categories remain on the device, and saved articles stay available under **Saved**. The feed moves to the **Unsubscribed (saved articles kept)** section, where **Subscribe again** restores it. Adding the same URL again also restores it.
- **Several at once**: more-options menu, **Select feeds** (or long-press a feed), then **Unsubscribe N feeds**. The confirmation shows each feed's kind of failure, its failures in a row and its last success, and feeds that have started working again are kept subscribed. **Undo** is offered right after. A failure does not always mean the site is gone: the feed may have moved.
- **Find where this feed moved** (in the feed details): **Look on the site** looks for RSS or Atom links on the feed's own site and checks that each is a real feed, showing entry counts and whether it shares articles with your current feed. **Search the web by feed name** hands the feed name to your search app when you tap it. Paste an address you find and use **Check this URL**. Nothing is chosen for you. **Use this address** asks for confirmation (**Change address**); the feed then keeps its articles, categories and read and saved state, although some articles may appear again as new. This is the only way to change a feed's address. To use a different URL otherwise, add it as a new feed.
- The more-options menu of **Subscriptions** also contains **Categories**, **Check HTTPS support of HTTP feeds**, **Retry unfinished**, **Import OPML** and **Export OPML**.

## Settings

Settings opens on a short list of six pages. Each row shows the values it holds now. Open a page to change them; the back button returns to the list.

| Page | Option | Choices and notes |
|---|---|---|
| Appearance | Theme | System, Light, Dark |
| Appearance | Color | Classic (default), Leaf, Sand, Violet, Dynamic (takes colors from your wallpaper; Android 12 and later, shown as unavailable on older versions) and Custom. Theme and color are separate. **Custom** makes a light and a dark set from one base color you choose (**Choose color**: a square and a hue slider, or a `#RRGGBB` code; **Apply** saves it, Cancel does not; **Reset base color to default** returns to the default teal). The app chooses text and icon colors so they stay readable for any base color. Under **Details** you can set five colors of light and of dark yourself (Accent, Accent background, Secondary accent, Background, Text): **Edit light** / **Edit dark**, pick a color or type `#RRGGBB`, **Save**. Your colors are used as you chose them; a warning appears if text and background are hard to tell apart, but you can still save. Colors set by hand stay when you change the base color; **Regenerate … from the base color** makes them follow the base color again. Classic, Leaf, Sand, Violet and Dynamic draw text in neutral black and white. Every color except Classic also colors the top bars and the tab strip, and Leaf, Sand and Violet tint the background and cards more strongly. Photos and icons are never tinted |
| Appearance | Text size | A slider from 90% to 130% in steps of 5%, with **Reset to 100%**. The preview above it follows the slider; the size is saved when you let go. It scales the system font size |
| Layout and navigation | Category navigation | Auto, Pane when possible, Drawer only. On a wide screen, such as a tablet or an unfolded foldable, the categories stay open in a pane at the left, and the tabs and swiping stay too. Auto shows the pane on large screens (840 dp wide or more), Pane when possible also on medium ones (600 dp or more). Drawer only keeps the menu button and the drawer. A narrow window always uses the drawer |
| Layout and navigation | Open the last category at start | Off by default: the app starts on **All**. When on, it starts on the tab you chose last. **All** / **Unread** / **Saved**, the period and the order are kept either way |
| Layout and navigation | Layout | List, Compact list, Magazine, Masonry, Bento, shown as small pictures. This is the default for every category; a category can use its own |
| Layout and navigation | Magazine arrangement | Mixed, Even. Shown only for Magazine |
| Layout and navigation | Max columns | Automatic, 1, 2, 3, 4 or 5 columns, for Magazine, Masonry and Bento. If the chosen number would make headlines too narrow (small window, large text), fewer columns are used; text is never shrunk to keep columns |
| Layout and navigation | Show thumbnails and feed icons | On by default. When off, no thumbnails or feed icons are downloaded or shown. Icons come from each feed or its own site, never from a third-party service |
| Layout and navigation | Swipe actions | **Right: read or unread. Left: save** (default), **Right: save. Left: read or unread**, **Off**. For List and Compact list. When on, swiping an article acts on it, and you change category with the tabs |
| Opening articles | Open articles in | Custom Tabs, External browser |
| Opening articles | Ask before opening http:// articles | On by default |
| Refresh and network | Automatic refresh | Off, every 15 / 30 minutes, 1 / 2 (default) / 6 / 12 / 24 hours |
| Refresh and network | Refresh when the app opens | On by default; at most every 30 minutes |
| Refresh and network | Automatic refresh on unmetered networks only | On by default |
| Refresh and network | Ask before refreshing on a metered network | On by default |
| Refresh and network | Allow unencrypted HTTP feeds and images | Off by default. A shortcut to **Check HTTPS support of HTTP feeds** is on the same page |
| Storage and data | Articles to keep on the device | No limit (default), or about 1,000 / 3,000 / 5,000 / 10,000 / 30,000. See below |
| Storage and data | Image cache size | 64, 128 (default), 256 or 512 MB. It takes effect the next time Folirel starts, when older images beyond the new size are removed. Until then the page shows both the size in use and the new one. **Clear image cache** removes cached thumbnails only |
| Storage and data | Export subscription list (OPML) | Subscriptions and categories only |
| Storage and data | Create Folirel backup / Restore from Folirel backup | Everything Folirel holds, in one file. Restoring replaces the current data after a preview and a confirmation. See [Data, backup and uninstalling](#data-backup-and-uninstalling) |
| About | Version, build, licence summary | The version line shows the version, version code and build identifier |

The Storage and data page also shows the number of articles stored and the database size, and warns when the database is larger than 100 MB.

**Articles to keep on the device** is not a limit on fetching: every article a feed returns is stored. If you choose a number, Folirel shows how many old, read, unsaved articles would be removed and asks for confirmation. After that the same cleanup runs after each refresh (marking articles read does not remove them; the next refresh does). Unread and saved articles are never removed, so the count can stay above the number. Removed articles that a feed delivers again do not come back as unread.

## Data, backup and uninstalling

- All data (subscriptions, categories, articles, read and saved state, settings) is stored only on the device, in the app's private storage. Android's automatic backup and device-to-device transfer are turned off for Folirel on purpose, because feed URLs can contain private tokens.
- **Uninstalling the app, or clearing its storage in Android's settings, deletes everything.**
- **Create Folirel backup** (Settings > Storage and data) saves everything to one file you choose: subscriptions with their address history, categories with their order, pins and layouts, the articles kept on the device, read and saved state and all settings (including the Custom colors). Feed icons and the image cache are not included; Folirel downloads them again. The file is not encrypted and feed URLs may contain personal tokens, so keep it private.
- **Restore from Folirel backup** (same page) first shows what the backup holds and what is on the device now, and changes nothing until you confirm. Restoring **replaces** all current Folirel data with the backup; it does not merge. If anything goes wrong during the restore, your data and settings stay as they were. No feed is fetched during the restore; your next refresh fetches as usual.
- **Export subscription list (OPML)** (in **Subscriptions** or in Settings > Storage and data) saves only subscriptions and categories, in the format other readers understand. It does not include read or saved state, articles or settings. Importing OPML only adds and merges.
- To move to a new phone, or to a Folirel signed with another key (which must be installed fresh): create a Folirel backup, copy the file, install Folirel on the new device and restore the backup there. Details: [Full backup and restore](backup-format.md).
- Installing a new version over the old one keeps your data. That needs an APK with the same application ID and the same signing key; see [Install](#install).

## Limitations

- No built-in article reader, no full-text extraction, no offline copy of pages, no notifications, no widgets, no text-to-speech and no sync between devices. Articles are read on the original site.
- Only what a feed currently returns is available. Older articles are not retrieved by crawling archives or pages.
- A single feed document is limited to about 5 MiB and 2,000 entries; larger documents fail with "Too large or too many entries". OPML files are limited to 5 MiB.
- Supported feed formats: RSS 2.0, Atom 1.0, RSS 1.0 (RDF), JSON Feed 1.0 and 1.1. Other formats (for example Atom 0.3 or a site's own JSON API) are not supported.
- Automatic refresh times are approximate and depend on Android's power management.
- Older Android versions (for example Android 9) may fail to open HTTPS connections to some sites if their root certificates are out of date. Folirel does not weaken certificate checks to work around this.
- The language follows the system language: Japanese if the device is set to Japanese, English otherwise. On Android 13 and later you can also pick the app language in the system settings (Settings > Apps > Folirel > Language); there is no language switch inside the app.

## Troubleshooting

| What you see | What it means and what to do |
|---|---|
| No articles after import | Wait for the first fetch (**Fetching feeds…**), or pull down to refresh. Check the notice over the list and the **Subscriptions** summary for failures. |
| **HTTP not allowed** | The feed uses `http://`. Run **Check HTTPS support of HTTP feeds**, or allow HTTP in Settings. |
| **Web page, not a feed** | The address is a web page. Use **Find where this feed moved** on the feed details, or add the site's address again with **Add feed URL**, which lists the feeds the site declares. |
| **Not found** / **Feed removed** | The address may have moved or the feed was removed. Try **Find where this feed moved**, or unsubscribe. |
| **Access denied** | The server refuses the request. Private feeds that need a sign-in cannot be read. |
| **Too many requests** / **Server error** / **Timed out** / **Network error** | Usually temporary. Folirel retries later. To try sooner, use **Retry unfinished**. If the server asked Folirel to wait, it waits. The HTTP status code is on the feed details, in the **Response** row. |
| **Certificate error** | The site's HTTPS certificate cannot be verified (expired, wrong name, unknown issuer, or an outdated root list on an old device). Folirel will not bypass this. |
| **Broken feed (XML or JSON error)** / **Unsupported feed format** | The document is not a valid feed in a supported format. Nothing to fix on your side; tell the site owner or use another feed address. |
| **Too large or too many entries** | The feed exceeds the limits above. |
| **Blocked for safety** | The address (or a redirect) points to something Folirel refuses to contact, such as a local or private network address (Folirel contacts public internet addresses only). |
| **Could not save (check free space)** | The device is out of storage. Free some space. |
| A refresh changes nothing | Use **Force refresh** on the feed. The site or its CDN may still be serving old content. |
| The notice says **The last refresh was interrupted**, or a feed's result is **Not reached (time limit)** | The refresh stopped early. Feeds not reached go first next time, or use **Retry unfinished**. |
| Automatic refresh seems late | Android may delay background work (battery saver, Doze). Set the app's battery usage to unrestricted if you need more regular refreshes, and keep **Refresh when the app opens** on. |
| Cannot install a new version | The APK is signed with a different key than the installed one. See [Install](#install). |
| **No app can open this link** | Install or enable a browser, or switch **Open articles in** to the other option. |
| The first-run screen appears again and the settings are back to their defaults | The settings file could not be read (for example after a storage error) and was reset. Subscriptions, articles and read and saved state are kept; choose your settings again (HTTP is off until you allow it). |
