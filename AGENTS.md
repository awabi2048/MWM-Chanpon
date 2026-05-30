# mwm_chanpon_plugin 作業方針

このプロジェクトは、`crafter_crossing` ワークスペースに含まれる MyWorldManager のアドオンプラグインである。
Paper 1.21.8 向け、Kotlin / Maven で実装。MyWorldManager の公開 API に依存し、ワールドデータの永続化や基本機能を MyWorldManager に委譲する。

## 責務

- **管理者ウィザード**: `/mwm create <プレイヤー名>` を割り込み、テンプレート選択→ワールド名設定→即時作成のフローを提供
- **拡張UI**: 将来、MyWorldManager の標準UIを拡張する画面を追加予定（Floodgate/Bedrock対応含む）

## MyWorldManager との連携

- **ビルド**: `compileOnly` で `my-world-manager` (v1.4.8+) に依存
- **ランタイム**: `plugin.yml` の `softdepend` で `MyWorldManager` を指定
- **API 経由のデータ操作**: `MyWorldManagerApi` を使用してワールド作成・検索・テンプレート参照を行う
  - `ApiWorldService`: テンプレートからのワールド作成、テレポート
  - `ApiWorldRepository`: ワールド検索
  - `ApiTemplateRepository`: テンプレート一覧取得
  - `ApiMemberManager`: 招待・参加申請
  - `ApiWorldTagService`: タグ管理
- **コマンド割り込み**: `PlayerCommandPreprocessEvent` (HIGHEST) で `/mwm create <player>` を捕捉
- **独自データ永続化**: 行わない。全ワールドデータは MyWorldManager が管理

## プロジェクト構成

```
src/main/kotlin/me/awabi2048/mwmchanpon/
├── MWMChanponPlugin.kt          # エントリポイント
├── bridge/
│   └── CoreApiBridge.kt         # MyWorldManagerApi アクセスラッパー
├── listener/
│   └── CreateCommandInterceptor.kt  # /mwm create <player> 割り込み
├── service/
│   └── WizardSessionService.kt  # ウィザードセッション管理
└── ui/
    ├── AdminWizardGui.kt        # 管理者ウィザード GUI (Java)
    └── bedrock/
        └── AdminWizardForm.kt   # Floodgate Form (スタブ)
```

## 参照

- ローカル参照リンク: `crafter_crossing_my_world_manager_plugin` → MyWorldManager 実体へのシンボリックリンク
- 実体: `D:\こた鯖\dev\crafter_crossing\my_world_manager_plugin`
- API ドキュメント: MyWorldManager の `api/service/` パッケージを参照
- 主な参照先:
  - `src/main/kotlin/me/awabi2048/myworldmanager/api/MyWorldManagerApi.kt`
  - `src/main/kotlin/me/awabi2048/myworldmanager/api/service/`

## 基本ルール

- ビルドは Maven、開発言語は Kotlin を用いる。
- ソースコードを編集した場合は、最後に必ず `mvn clean package` を実行する。
- ビルド前に `my_world_manager_plugin` を `mvn install` しておく必要がある（API依存のため）。
- ドキュメントのみの編集では、ビルドと JAR 配置は不要とする。
- Git 操作はこのリポジトリのディレクトリ内で実行する。
- 実装作業や複数ファイル変更を行う前に、ユーザーの許可を得て作業ブランチを作成する。
- ユーザーが指示しない限り、運用上不要な後方互換ロジックは残さない。
- MyWorldManager の内部実装に依存せず、公開 API のみを使用する。
- 独自の WorldService/Repository/Model は持たず、すべて MyWorldManager API に委譲する。

## 言語ファイル編集ルール

- アドオン独自のメッセージは `cc-system/src/main/resources/lang/<locale>/chanpon/*.yml` に保持する。
- MyWorldManager 側の言語キーは利用せず、重複定義しない。
- YAML のインデント崩れや型不整合を禁止する。

## デプロイ

- JAR 配置が必要な場合は、事前に配置先と対象 JAR 名を確認する。
- 既定の配置先は `D:\Minecraft\Chiyogami 1.21.8\plugins\MWM-Chanpon-0.2.0-SNAPSHOT.jar` とする。
- 配置元の成果物は `target\MWM-Chanpon-0.2.0-SNAPSHOT.jar` とする。
- 実装作業後は、`mvn clean package` が成功したら既定の配置先へ JAR を反映する。
- MyWorldManager が先にロードされるよう、`softdepend` が正しく設定されていることを確認する。
- 対象はこのプロジェクトの成果物に限る。
- 無関係な JAR、設定ファイル、ワールドデータは削除しない。
