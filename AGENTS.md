# mwm_chanpon_plugin 作業方針

このリポジトリは `crafter_crossing` ワークスペース内の MyWorldManager アドオンです。
Paper 1.21.8 向けに Kotlin / Maven で実装し、MyWorldManager の公開 API と CC-System の共通 API を前提にします。

## 責務

- MyWorldManager 本体のワールド作成・設定・メンバー管理フローを拡張する。
- Chanpon 固有のワールド補助データは MWM-Chanpon 側で保持する。
- MyWorldManager 本体の WorldService / Repository / Model を再実装しない。
- MyWorldManager 内部実装へ直接依存せず、公開 API・イベント・メニュー拡張を使う。

## MyWorldManager 連携

- GUI追加は `MenuExtension` を使う。
- 削除防止は `WorldDeleteGuard` を使う。
- Discovery、Visit、Meet、入場可否は `WorldAccessPolicy` を使う。
- 公開状態の表示・切り替えは `WorldPublishPolicy` を使う。
- 作成費用、スロット上限、拡張費用、環境費用、ポータル費用は `WorldRuntimePolicy` を使う。
- `/mwm` サブコマンドの無効化は `CommandPolicy` を使う。
- `/mwm create` の差し替えは `CreateCommandHandler` を使う。

## 禁止事項

- `/mwm create` を `PlayerCommandPreprocessEvent` で横取りしない。
- `/mwm` の補完を `TabCompleteEvent` で後処理しない。
- MWM の `config.set(...)` で費用・上限・機能可否を実行時に書き換えない。
- Chanpon の公開状態を MWM の `publishLevel` に保存しない。
- Bedrock Form 経由でも MWM の `publishLevel` を直接更新しない。
- MWM 側の言語キーに Chanpon 固有文言を追加しない。
- 新規の反射連携を追加しない。

## 表示テキストと言語ファイル

- MWM-Chanpon の新規表示テキストは、原則として `cc-system/src/main/resources/lang/<locale>/chanpon/*.yml` に追加する。
- MWM-Chanpon 側の Kotlin には、表示名・Lore・操作メッセージの本文を直書きしない。
- コード側では `CCSystem.getAPI().getI18nString(...)`、`getI18nStringList(...)`、`getI18nComponent(...)` を使う。
- Lore の区切り線・クリック行・警告行などの整形は、ローカル実装を増やさず `CCSystemAPI` の Lore API を使う。

## データ保存

- MyWorldManager が所有するワールド基本データは MWM-Chanpon 側で複製しない。
- Chanpon 固有データは `plugins/MWMChanpon/my_worlds/<worldUuid>.yml` に保存する。
- 操作履歴が必要な機能は、対象ワールドごとの Chanpon データに追記保存する。

## ビルドとデプロイ

- ソースコードを編集した場合は、最後に `mvn clean package` を実行する。
- ビルド成功後、作業対象の成果物 `target/MWM-Chanpon-0.2.0-SNAPSHOT.jar` を `Chiyogami 1.21.8/plugins/` に配置する。
- 配置前に既存の同系 JAR 名を確認し、無関係な JAR、設定ファイル、ワールドデータは削除しない。

## Git

- Git 操作はこのリポジトリのディレクトリ内で行う。
- 複数ファイル変更や実装作業の前に、ユーザーの許可を得て作業ブランチを作成する。
- コミット、push、マージ、ブランチ削除はユーザーが許可するまで行わない。
