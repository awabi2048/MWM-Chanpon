# 「メンバーのみ」の建築判定検証（2026年10月27日）

## 結果

現在のWorldBuildPermissionServiceが生成する設定では、管理者・WorldGuardバイパスのないメンバー本人も建築できません。
オーナー・モデレーターでも同じ不具合を再現しました。実装修正は行っていません。

| 対象 | 期待 | 現在の判定 |
|---|---|---|
| オーナー | 許可 | 拒否 |
| メンバー | 許可 | 拒否 |
| モデレーター | 許可 | 拒否 |
| 非メンバー | 拒否 | 拒否 |

## 検証方法と適用範囲

検証環境のworldguard-bukkit-7.0.19.jarと依存JARを使い、Javaのソースファイル実行で検証しました。
GlobalProtectedRegionにオーナー・メンバー・モデレーターのUUIDを登録し、LocalPlayerの代理オブジェクトはUUID・名前だけを提供します。
所属判定は代理実装せず、WorldGuard自身のLocalPlayer.getAssociation既定実装を呼び出しています。
それぞれOWNER・MEMBER・MEMBER・NON_MEMBERと認識されることを確認したうえで、RegionResultSet.testStateのBUILD判定を実行しました。
WorldGuardバイパスと他の上位保護は適用しない、保護モードそのものの検証です。

実際のゲームクライアントによるブロック設置・破壊試験は行っていません。
サーバーを起動せず、ワールドデータも変更していません。
保存済みデータはdev-envの1件とfull-envの2件を確認しましたが、いずれも登録メンバー・モデレーターは0人です。
したがって、保存済みの特定メンバー本人による実地再現ではなく、実際のWorldGuard実装にUUIDを与えて判定を再現した結果です。
dev-envの保存済みグローバルリージョンには、再現条件と同じbuild=deny / build-group=NON_MEMBERS / passthrough=denyが記録されていました。

## 根本原因と関連箇所

WorldGuardのFlagValueCalculator.getEffectiveFlagOfは、__global__かつpreventsAllowOnGlobalの状態フラグで、グループフィルターより前に明示値を返します。
BUILDの明示的なDENYはこの経路でそのまま適用され、NON_MEMBERSによる拒否対象の絞り込みが実行されません。
現在のWorldBuildPermissionServiceのコメント「DENYとグループ指定は通常通り有効」は実際の実装と一致しません。

対照試験でBUILDの明示値を除去すると、メンバー・オーナー・モデレーターは許可、非メンバーは拒否になりました。
「オーナーのみ」も同じ構造で全員拒否になります。こちらはメンバーを拒否しつつオーナーを許可する別途の設計が必要です。
「誰でも」は現行設定で全員許可になり、この不具合は再現しません。

MWMはmembersとmoderatorsをWorldGuardのメンバー領域へ登録するため、今回の再現は同期漏れが原因ではありません。
別途、MWMの初期化時にロード済みワールドの所属を再同期する処理が限定的である点も確認しましたが、保存済みのメンバー0件からは同期不整合を実証できません。

## テストの不足

既存のWorldBuildPermissionContractTestは、ソースにDENYとNON_MEMBERSという文字列が存在することだけを確認していました。
そのテストの成功や前回の全テスト成功は、WorldGuardの実際の建築可否を保証していません。
修正時には実際の判定器を使い、3つの保護モードと4つの役割を組み合わせた回帰テストが必要です。

再現コード: MemberBuildVerification.java
実行結果: member-build-result.txt
Java 25で、検証サーバーのlibraries配下JAR、WorldGuard JAR、FastAsyncWorldEdit JARをクラスパスへ追加して実行できます。
