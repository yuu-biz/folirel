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

> **状況:** 1.0 に向けた最終段階（0.9.x）です。1.0 で予定した機能はそろい、自動試験で確認しています。端末での確認は手作業で行っています。
> debug 署名の APK を Releases ページで配布しています。Google Play には未掲載です。

## 主な機能

- **OPML の取り込みと書き出し。** 取り込み前に追加される内容を確認でき、追加とマージだけを行います。
  既存の既読・保存・カテゴリが上書きされることはありません。
- **共有から購読。** 他のアプリからページやリンクを Folirel に共有すると、そのアドレス自体、またはページが示す RSS/Atom フィードを確認し、確認操作のあとにだけ購読します。
- **フィードの直接取得。** RSS 2.0 / Atom 1.0 / RSS 1.0 に対応。条件付き取得、文字コード判定（Shift_JIS を含む）、
  取得に失敗したフィードごとの診断情報を備えています。
- **5 種類の一覧表示:** リスト、リスト（コンパクト）、マガジン（大きさの異なるカードにサムネイルと概要を表示）、段違い（Masonry：高さの違うカードを列に積む）、タイル（Bento：形の違うタイルを隙間なく並べる）。
- **カテゴリ。** 階層化、ナビゲーションドロワー（タブレットや開いた折りたたみ端末など広い画面では開いたままのカテゴリペイン）、フリックに合わせて動くカテゴリタブ、検索付きのカテゴリ選択、ピン留めと並べ替えができるタブ、カテゴリごとのレイアウト指定。
- **絞り込み。** 未読・保存、期間（24時間・3日・7日・30日）、一致する記事を一覧から外す非表示キーワード。
- **既読は明示操作だけ。** 既読・未読・保存は自分で操作したときだけ変わります。記事を開いても既読になりません。
- **元サイト優先。** 記事は Custom Tabs（または外部ブラウザ）で開きます。独自のリーダー画面はありません。
- **端末内検索。** タイトル・概要・配信元を検索します。
- **更新。** 表示中の範囲を手動で更新できるほか、バックグラウンドで定期更新します。従量制ネットワークでは確認が出ます。
- **フィードの整理。** サイトのアドレスからフィードを探す、同一フィードと確認できた HTTP フィードを HTTPS へ移す、
  取得できないフィードをまとめて整理する、といった機能があります。
- **英語と日本語の UI**（システム言語に従います。Android 13 以降はアプリ別の言語設定も可）、ライト/ダークテーマと6つの配色（Classic、Leaf、Sand、Violet、Android 12 以降の Dynamic、選んだ1色から作る Custom）、文字サイズ設定。

## インストール

1. このリポジトリの **Releases** ページから最新の APK をダウンロードします。
2. Android から求められたら、ブラウザやファイルマネージャーに「このアプリからのインストール」を許可し、APK を開きます。
   Android 9（API 28）以降が必要です。
3. Folirel を起動し、OPML ファイルを取り込むか、フィード URL を追加します。

現在の配布 APK は debug 鍵で署名しているため、同じ鍵で署名したビルドにしか上書き更新できません。
アンインストールするとデータは消えます。購読一覧は「OPML を書き出す」で保存できます。
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

- 購読・記事・既読状態は、アプリ専用の内部ストレージにだけ保存します。バックアップは無効です。
- 通信先は、購読しているフィードのサーバー（とフィードが指すサイト・画像のホスト）だけです。
  サードパーティ SDK もテレメトリもありません。
- フィードの内容は信頼しません。DTD/DOCTYPE や外部エンティティを含む XML は拒否し、TLS 検証は常に有効です。
  `http://` のフィードは許可するまで取得せず、URL は毎回、プライベート/ローカルアドレスでないか検査してから要求します。

詳細は [Fetch and security](docs/fetch-and-security.md)（英語）を参照してください。

## ドキュメント

| 文書 | 内容 |
|---|---|
| [ユーザーガイド](docs/user-guide.ja.md) / [English](docs/user-guide.md) | インストール、取り込み、日常の操作、設定 |
| [Product and UX](docs/product-and-ux.md) | 範囲、要件、画面と操作の規則 |
| [Architecture](docs/architecture.md) | 構成、データの流れ、拡張の境界 |
| [Data and import](docs/data-and-import.md) | データベース、OPML、記事の同一性、保持 |
| [Fetch and security](docs/fetch-and-security.md) | HTTP、解析、画像、更新、通信ポリシー |
| [Testing](docs/testing.md) | 試験の構成、fixture、シナリオ一覧 |
| [Building](docs/building.md) | ツールチェーン、ビルド、CI、依存ライブラリとライセンス |

技術文書は英語です。コントリビューター向けの規約は [AGENTS.md](AGENTS.md) と [CONTRIBUTING.md](CONTRIBUTING.md) にあります。

## コントリビュート

Issue と Pull Request を歓迎します。先に [CONTRIBUTING.md](CONTRIBUTING.md) を読んでください。製品の範囲
（アカウント、内蔵リーダー、自動既読などは意図的に対象外です）を説明しています。

## ライセンス

Folirel は [MIT License](LICENSE) で公開しています。サードパーティのライブラリはそれぞれのライセンスに従います。
詳細は [docs/building.md](docs/building.md) を参照してください。
