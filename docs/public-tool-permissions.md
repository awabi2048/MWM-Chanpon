# 非メンバー向けツール権限

## 権限契約

建築許可が「誰でも」の間だけ、ワールドごとの非メンバー用ツール設定を参照します。
オーナー・モデレーター・メンバーは対象外で、既存の個別設定と既定値を維持します。
非メンバーの過去の個別設定は参照せず、未設定の公開権限はすべて無効です。
全体停止、提出状態、本番の自動ロールバックロック、アーカイブ除外を維持します。
LuckPermsへの付与は既存の一時グループ・明示ノード付与経路を使います。

## 画面契約

既存の45スロットの環境設定画面を拡張し、寸法・背景・既存8項目・戻る位置40を維持します。
新しい4項目は下段28・30・32・34にWorldEdit、VoxelSniper、Axiom、かんたんコマンダーの順で配置します。
素材は既存の個人ツール権限画面と同じカタログを使います。
Nameはツール名のみ、説明には非メンバー向けであること、データには保存された有効・無効を表示します。
「誰でも」以外では適用されない旨を警告表示し、公開前の設定は可能とします。
操作権限は環境設定と同じオーナー・管理者、対象ワールド内でのみ変更できます。
背景、Lore、操作案内はCC-Systemの共通APIで描画します。
左右クリックと共通メニュー実行経路から設定を反転し、環境設定の可逆契約へ統合します。
設定変更、公開範囲変更、取り消し、メンバー加入・脱退時には滞在中の非メンバーも同期します。
ワールド移動・ログイン・退出は既存の同期経路を使います。

## 保存

ワールドファイルの public_tool_permissions.<ツールID> に真偽値で保存します。
個人別の tool_permissions とは別の領域です。

## 検証記録（2026年10月27日）

- MWM-Chanpon 26.1027.1: Gradle build成功、212テスト・失敗0。
- CC-System 26.1027.2: Gradle build成功、513テスト・失敗0。全localeのキー集合・値型・placeholder集合と生成済みキー索引の検証を含みます。
- ワークツリーでは -PworkspaceMavenRepository=D:/こた鯖/dev/crafter_crossing/.m2-paper26-kotlin2320 を指定します。
- 既存MWM依存成果物がローカルに存在しなかったため、MWM本体はソース変更せず、CC-System参照先を一時Gradle init scriptで今回版へ差し替えてworkspaceへpublishしました。MWMソース・サーバーJARの変更はありません。
- dev-env: 規定ショートカットからPID 6960で起動。14:13:49起動完了。MWMのmwm-smokeがPASS（失敗0）。
- full-env: 規定ショートカットからPID 19424で起動。14:15:22起動完了。専用chanpon-public-toolsスイートが14:16:00にPASS（失敗0）。
- 両環境のRCON応答、プラグインの実行版、生成物と配置JARのSHA-256一致を確認しました。
- 両環境のMWMChanpon/config.ymlは同一ハッシュで、設定項目の追加保存はワールドごとの操作時に行います。full-envの移行ステータスは待機・失敗とも0です。
- 試験後は両環境をsave-all flushとstopで正常停止し、対象PID・サーバーポート・RCONポートの終了を確認しました。
- オンラインプレイヤーは0名でした。ゲーム画面の目視確認と実プレイヤーを使った付与・移動試験は未実施です。判定・永続化・メンバーとの分離・全体停止・提出ロック・取り消し・同時編集競合は自動テストで検証しました。

### 検証環境で確認した対象外の問題

- WindowsのOSHI性能情報取得でPerflib関連エラーが出ています。
- dev-envのKantanCommanderは保存済みプログラム3件で構文・型の読み込み警告を出しています。
- full-envの汎用mwm-smokeは、Multiverse-Legacy-ApiのgetPluginMetaに対するAbstractMethodErrorで中断しました。専用スイートでは対象外のMultiverseメタデータ取得を避け、CC-System・MWMChanpon・MWM・LuckPerms・WorldGuardの有効化と設定コマンド登録を確認しています。
- full-envにはLuckPermsのbuilderグループがない旨のMWM警告があります。今回のツール用グループ付与とは別の作業グループ設定です。

### 成果物ハッシュ

- CC-System-26.1027.2.jar: 5E71C200B20AD36A9137272FF0030D3A44D59CC614A743AB23DC3A54ADFFDD71
- MWM-Chanpon-26.1027.1.jar: DC8C5C944882576796E0DE0DCBFC7DF16A2CE91E6D88DA361BF32B4FD0DC995B

専用スモークスイートは src/test/resources/chanpon-public-tools.yml に保存しています。
