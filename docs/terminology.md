# Terminology

The words Folirel uses on screen and in the user guides, in English and Japanese. Use one term per concept. When you
add or change a string in `res/values/strings.xml` or `res/values-ja/strings.xml`, pick the term from this table and
avoid the words in the last column.

## Terms

| Concept | English | Japanese | Not used |
|---|---|---|---|
| Downloading new articles (the user action) | Refresh, refreshing | 更新、更新中 | Update, Updating (EN). "Update" is only for installing a newer app. |
| Installing a newer version of the app | update the app | アップデート | 更新 (JA, it means a refresh) |
| Downloading one feed (what a refresh does per feed) | fetch | 取得 | |
| A subscription | feed | フィード | source, 配信元, 購読先 |
| The subscription list screen | Subscriptions | 購読管理 | |
| Subscribe, unsubscribe, subscribe again | Subscribe, Unsubscribe, Subscribe again | 購読する、購読を解除、再購読 | |
| An `http://` feed while HTTP is not allowed | HTTP not allowed | HTTP未許可 | HTTP pending, HTTP held, HTTP保留 |
| A feed the user paused | Paused, Pause this feed | 一時停止中、このフィードを一時停止 | Stopped, 停止中, 更新停止中 |
| An address | URL in input labels and in messages about an input field; address in other text | 入力欄とその案内は URL、それ以外はアドレス | web address, Webアドレス |
| OPML in and out | Import, Export | 読み込む、書き出す | 取り込む, インポート, エクスポート, 出力 |
| The read filter in the display panel (All, Unread, Saved) | Articles | 表示する記事 | 範囲 |
| What "Mark all as read" applies to | Scope | 対象 | 範囲 |
| Light, dark or system | Theme | テーマ | Light or dark, 明暗モード |
| Classic, Leaf, Sand and the other sets | Color | 配色 | |
| Metered and unmetered networks | metered network, unmetered network | 従量制ネットワーク、従量制ではないネットワーク（短い要約では従量制以外） | 非従量制 |
| Bypass caches for one feed | Force refresh | 強制的に更新 | Revalidate and refetch |
| The file with everything | Folirel backup | Folirelのバックアップ | |

## Rules

- Notes under settings say what the user gets, in one or two short sentences. They do not name dp values, HTTP
  status codes, validators, the metered flag, spec ids or earlier versions of the app.
- HTTP status codes appear only on the feed details screen (the **Response** row). Error labels on lists, in the bulk
  unsubscribe dialog and in the HTTPS check use words.
- English headers and labels use "and", never "&".
- English counts use `<plurals>`, never "(s)".
- Japanese buttons, headers, chips and setting names have no です/ます. A sentence ends in です/ます. A status shown on a list row may be a short phrase such as 見つかりません.
- Japanese punctuation: "・" (never "·"), "%d件" with no space, すでに (never 既に), half-width ": " after a label,
  full-width （） around a remark.
- A reassurance ("Saved articles are kept") is written only where the reader could fear data loss, and once.
