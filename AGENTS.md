# mwm_chanpon_plugin 作業方針

このプロジェクトは、`crafter_crossing` ワークスペースに含まれる Paper 1.21.8 向けの Kotlin / Maven プラグインである。
`crafter_crossing/my_world_manager_plugin` の実装は仕様参照元として扱うが、実体の変更はこのリポジトリの責務範囲に限定する。

## 参照元

- ローカル参照リンク: `crafter_crossing_my_world_manager_plugin`
- 実体: `D:\こた鯖\dev\crafter_crossing\my_world_manager_plugin`
- 主な参照先:
  - `src/main/kotlin/me/awabi2048/myworldmanager/gui/WorldSettingsGui.kt`
  - `src/main/resources/lang/ja_jp.yml`

## 基本ルール

- ビルドは Maven、開発言語は Kotlin を用いる。
- ソースコードを編集した場合は、最後に必ず `mvn clean package` を実行する。
- ドキュメントのみの編集では、ビルドと JAR 配置は不要とする。
- Git 操作はこのリポジトリのディレクトリ内で実行する。
- 実装作業や複数ファイル変更を行う前に、ユーザーの許可を得て作業ブランチを作成する。
- ユーザーが指示しない限り、運用上不要な後方互換ロジックは残さない。
- 既存の未コミット変更がある場合は、内容を確認し、無関係な差分を巻き戻さない。

## 言語ファイル編集ルール

- 既存の言語ファイルには、必要な変更以外を行わない。
- 新規テキスト追加は、ユーザーが指示しない限り `src/main/resources/lang/*.yml` を介する。
- 同一階層での重複キー定義を禁止する。
- YAML のインデント崩れや型不整合を禁止する。
- 言語キー不足をフォールバックで吸収しない。不足・型不正はエラーとして扱う。

## デプロイ

- JAR 配置が必要な場合は、事前に配置先と対象 JAR 名を確認する。
- 既定の配置先は `D:\Minecraft\Chiyogami 1.21.8\plugins\MWM-Chanpon-0.1.0-SNAPSHOT.jar` とする。
- 配置元の成果物は `target\MWM-Chanpon-0.1.0-SNAPSHOT.jar` とする。
- 実装作業後は、`mvn clean package` が成功したら既定の配置先へ JAR を反映する。
- 対象はこのプロジェクトの成果物に限る。
- 無関係な JAR、設定ファイル、ワールドデータは削除しない。
