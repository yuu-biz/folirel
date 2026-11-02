# Privacy

日本語: [privacy.ja.md](privacy.ja.md)

Folirel is a feed reader that runs on your device. It has no account and no server, and it does not collect or share
any data about you.

## What the app sends over the network

Folirel contacts only the sites you add, and only to fetch their content:

- your feeds, when you refresh or when the automatic refresh runs;
- the images in articles and the site icons, unless you turn images off;
- a site's page, to find its icon, and when you add a site or share a link to Folirel, to find its feed.

Each of these requests goes directly from your device to that site. Like any visit to a website, the site sees your
device's IP address and the time of the request. Folirel identifies itself with the User-Agent
`Folirel/<version> (Android; local RSS reader)`. It sends no cookies and no identifier of you or your device.

Articles open in your browser. From there, the browser and the site follow their own privacy policies.

## What the app does not do

- No analytics, no advertising and no crash reporting. No third-party service is contacted.
- Nothing is sent to the developer.
- Android's automatic cloud backup and device-to-device transfer are turned off for Folirel, because feed addresses can
  contain personal tokens.

## Where your data is kept

Your subscriptions, articles, read and saved states and settings are stored only in the app's storage on the device.

A Folirel backup or an exported OPML file is written only where you choose to save it. Folirel does not upload it. If
you save it to a cloud folder, that service keeps a copy.

## Deleting your data

- To delete everything: uninstall Folirel, or open Android's Settings > Apps > Folirel > Storage and tap **Clear
  storage**.
- To remove thumbnails and icons only: Settings > Storage and data > **Clear image cache** in Folirel.
- Backup and OPML files you saved stay where you saved them; delete them there.

## Contact

Questions about privacy: open an issue at https://github.com/yuu-biz/folirel/issues. Do not paste feed addresses or
OPML files there, since they can contain personal tokens.
