<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/folirel/folirel-logo-dark.svg">
    <img src="artwork/folirel/folirel-logo.svg" alt="Folirel" height="72">
  </picture>
</h1>

[English](README.md) | [日本語](README.ja.md)

Folirel は、端末だけで完結する Android 用の RSS/Atom リーダーです。購読先のサーバーから直接フィードを取得し、
データはすべて端末内に保存します。アカウントも同期サービスも解析ツールも広告もなく、閲覧履歴をどこにも送りません。

Feedly などから OPML ファイルで購読一覧を移し、最新記事を情報量の多い一覧で眺めて、元のページをブラウザで開けます。

**Folirel（フォリレル）** は *folio* と *reader* から着想した造語です。*folio* の語源であるラテン語 *folium*（葉・紙）にちなみ、自分で選んだフィードを誌面のように眺めるRSSリーダーを表しています。ロゴは2枚の葉で、大きい方の葉に紙面のような文字の行が並んでいます。

## 主な機能

- **OPML の読み込みと書き出し。** 読み込む前に追加される内容を確認でき、追加とマージだけを行います。
  既存の既読・保存・カテゴリが上書きされることはありません。
- **端末内の完全バックアップと復元。** 購読（変更履歴を含む）、カテゴリ、端末に保持している記事、既読・保存、設定をすべて1つのファイルに保存し、機種変更や再インストールに使えます。復元はまず内容を表示し、確認したあとでだけデータを置き換えます。失敗した場合は何も変わりません。
- **共有から購読。** 他のアプリからページやリンクを Folirel に共有すると、そのアドレス自体、またはページが示す RSS/Atom フィードを確認し、確認操作のあとにだけ購読します。
- **フィードの直接取得。** RSS 2.0 / Atom 1.0 / RSS 1.0 に対応。条件付き取得、文字コード判定（Shift_JIS を含む）、
  取得に失敗したフィードごとの診断情報を備えています。
- **5 種類の一覧表示:** リスト、リスト（コンパクト）、マガジン（大きさの異なるカードにサムネイルと概要を表示）、段違い（Masonry：高さの違うカードを列に積む）、タイル（Bento：形の違うタイルを隙間なく並べる）。
- **カテゴリ。** 階層化、ナビゲーションドロワー（タブレットや開いた折りたたみ端末など広い画面では開いたままのカテゴリペイン）、フリックに合わせて動くカテゴリタブ、検索付きのカテゴリ選択、ピン留めと並べ替えができるタブ、カテゴリごとのレイアウト指定。
- **絞り込み。** 未読・保存、期間（24時間・3日・7日・30日）、一致する記事を一覧から外す非表示キーワード。
- **既読は明示操作だけ。** 既読・未読・保存は自分で操作したときだけ変わります。記事を開いても既読になりません。
- **元サイト優先。** 記事は Custom Tabs（または外部ブラウザ）で開きます。独自のリーダー画面はありません。
- **端末内検索。** タイトル・概要・フィード名を検索します。
- **更新。** 表示中の範囲を手動で更新できるほか、バックグラウンドで定期更新します。従量制ネットワークでは確認が出ます。
- **フィードの整理。** サイトのアドレスからフィードを探す、同一フィードと確認できた HTTP フィードを HTTPS へ移す、
  取得できないフィードをまとめて整理する、といった機能があります。
- **英語と日本語の UI**（システム言語に従います。Android 13 以降はアプリ別の言語設定も可）、ライト/ダークテーマと6つの配色（Classic、Leaf、Sand、Violet、Android 12 以降の Dynamic、選んだ1色から作る Custom）、文字サイズ設定。

## インストール

Android 9（API 28）以降が必要です。APK はこのリポジトリの
[Releases](https://github.com/yuu-biz/folirel/releases) ページで配布しています。

1. スマートフォンで最新リリースの APK をダウンロードして開きます。
2. 「不明なアプリのインストール」が許可されていないと表示されたら、「設定」を押し、使っているブラウザやファイル管理アプリで
   「この提供元のアプリを許可」をオンにして戻ります。
3. 「インストール」を押し、Folirel を開いて、OPML ファイルを読み込むかフィード URL を追加します。

### 署名証明書の確認

1.0 以降の APK は、1つの release 鍵で署名しています。ダウンロードしたファイルを確かめるには、Android SDK のビルドツールがある
パソコンで次を実行します。

```sh
apksigner verify --print-certs folirel.apk
```

`Signer #1 certificate SHA-256 digest` の行が次と一致することを確かめてください。

```text
c740ae3b5a57bf46be12edd7894b6a017095b95a3f7217e2cc4b9402cc9b00b1
```

一致しないファイルはインストールしないでください。

### 0.x の debug 版からの移行

1.0 より前の版は debug 鍵で署名されているため、1.0 を上書きインストールできません。アンインストールするとデータが消えるので、
Folirel のバックアップでデータを移します。

1. 古いアプリで、設定 > 保存とデータ > 「Folirelのバックアップを作成」を開き、アプリの外（ダウンロードなど）に保存します。
2. バックアップを確かめます。「Folirelのバックアップから復元」でファイルを選び、表示された内容をアプリと比べてから「キャンセル」を押します。
3. 古いアプリをアンインストールし、1.0 をインストールします。
4. 1.0 で、設定 > 保存とデータ > 「Folirelのバックアップから復元」を開き、確定します。

詳しい手順は [Full backup and restore の7節](docs/backup-format.md#7-moving-to-a-differently-signed-app-release-signing)（英語）にあります。
0.10.0 より前の版には完全バックアップがないため、「OPMLを書き出す」で購読リストを残してください。

詳細は[ユーザーガイド](docs/user-guide.ja.md)を参照してください。

## ソースからのビルド

必要なもの: JDK 21 と Android SDK（platform `android-37.0`、build-tools `36.0.0`）。`local.properties` に
`sdk.dir=<Android SDK のパス>` を書いたうえで、次を実行します。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。Windows では `.\gradlew.bat` を使います。
詳細は [docs/building.md](docs/building.md)（英語）を参照してください。

## プライバシーとセキュリティ

- 購読・記事・既読状態は、アプリ専用の内部ストレージにだけ保存します。Android の自動バックアップは無効です。完全バックアップは、指示したときだけ選んだファイルに作成します（暗号化はされません）。
- 通信先は、購読しているフィードのサーバー（とフィードが指すサイト・画像のホスト）だけです。
  サードパーティ SDK もテレメトリもありません。
- フィードの内容は信頼しません。DTD/DOCTYPE や外部エンティティを含む XML は拒否し、TLS 検証は常に有効です。
  `http://` のフィードは許可するまで取得せず、URL は毎回、プライベート/ローカルアドレスでないか検査してから要求します。

プライバシーについての説明: [docs/privacy.ja.md](docs/privacy.ja.md)。詳細は [Fetch and security](docs/fetch-and-security.md)（英語）を参照してください。

## ドキュメント

| 文書 | 内容 |
|---|---|
| [ユーザーガイド](docs/user-guide.ja.md) / [English](docs/user-guide.md) | インストール、読み込み、日常の操作、設定 |
| [Terminology](docs/terminology.md) | アプリで使う英語と日本語の用語 |
| [Product and UX](docs/product-and-ux.md) | 範囲、要件、画面と操作の規則 |
| [Architecture](docs/architecture.md) | 構成、データの流れ、拡張の境界 |
| [Data and import](docs/data-and-import.md) | データベース、OPML、記事の同一性、保持 |
| [Full backup and restore](docs/backup-format.md) | バックアップの内容と形式、復元の安全性、別の署名鍵への移行 |
| [Fetch and security](docs/fetch-and-security.md) | HTTP、解析、画像、更新、通信ポリシー |
| [Testing](docs/testing.md) | 試験の構成、fixture、シナリオ一覧 |
| [Building](docs/building.md) | ツールチェーン、ビルド、CI、依存ライブラリとライセンス |
| [Releasing](docs/releasing.md) | リリース署名、確認、GitHub Release の手順、開発者確認 |
| [プライバシー](docs/privacy.ja.md) / [English](docs/privacy.md) | アプリが送るもの、保存するもの、集めないもの |

技術文書は英語です。コントリビューター向けの規約は [AGENTS.md](AGENTS.md) と [CONTRIBUTING.md](CONTRIBUTING.md) にあります。

## コントリビュート

Issue と Pull Request を歓迎します。先に [CONTRIBUTING.md](CONTRIBUTING.md) を読んでください。製品の範囲
（アカウント、内蔵リーダー、自動既読などは意図的に対象外です）を説明しています。

## ライセンス

Folirel は [MIT License](LICENSE) で公開しています。サードパーティのライブラリはそれぞれのライセンスに従います。
詳細は [docs/building.md](docs/building.md) を参照してください。
