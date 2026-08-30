package me.awabi2048.mwmchanpon.tool

import org.bukkit.Material

data class ToolPermissionDefinition(
    val id: String,
    val displayName: String,
    val material: Material,
    val groupName: String,
    val color: String = "§7",
    /**
     * ツールを有効化したプレイヤーへ一時付与する明示的な権限ノードです。
     * 外部プラグインがグループ名の命名規則へ依存せず、同じワールド単位の
     * 判定を利用できるよう、必要なツールだけ設定します。
     */
    val permissionNode: String? = null,
)
