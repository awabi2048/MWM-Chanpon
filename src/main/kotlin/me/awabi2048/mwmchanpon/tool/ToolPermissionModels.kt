package me.awabi2048.mwmchanpon.tool

import org.bukkit.Material

data class ToolPermissionDefinition(
    val id: String,
    val displayName: String,
    val material: Material,
    val groupName: String,
    /**
     * ツールの有効化と同じワールド単位の判定へ接続する明示的な権限ノードです。
     * KantanCommanderの判定とplugin.ymlはKantan側が所有し、Chanponは既存の
     * LuckPerms transient node付与だけを担当します。
     */
    val permissionNode: String? = null,
)

/**
 * MWM-Chanponがメンバー管理画面で扱う外部ツールの表示契約です。
 *
 * 表示名と素材はツールの種類そのものに属するため、運用設定へ持たせません。
 * 設定ファイルが差し替わっても画面の寸法・表示・状態表現が変わらないよう、
 * LuckPermsのグループ名だけを [ToolPermissionConfig] から注入します。
 */
internal data class ToolPermissionType(
    val id: String,
    val displayName: String,
    val material: Material,
    val defaultGroupName: String,
    val permissionNode: String? = null,
) {
    fun definition(groupName: String): ToolPermissionDefinition = ToolPermissionDefinition(
        id = id,
        displayName = displayName,
        material = material,
        groupName = groupName,
        permissionNode = permissionNode,
    )
}

/**
 * MWM-Chanponがメンバー管理画面から一時付与する権限の固定カタログです。
 *
 * KantanCommanderの追加権限は判定とplugin.ymlをKantan側が所有しますが、
 * 既存のcommandblock操作に紐づく付与導線はこのカタログで維持します。
 */
internal object ToolPermissionCatalog {
    const val CONFIG_PATH = "tool_permissions.tools"
    const val KANTAN_COMMAND_BLOCK_ID = "commandblock"
    const val KANTAN_COMMAND_BLOCK_GROUP = "commandblock"
    const val KANTAN_EXTENDED_COMMAND_BLOCK_PERMISSION = "kankoma.extended_command_block"

    val types: List<ToolPermissionType> = listOf(
        ToolPermissionType("worldedit", "WorldEdit", Material.WOODEN_AXE, "worldeditor"),
        ToolPermissionType("voxelsniper", "VoxelSniper", Material.ARROW, "voxelsniper"),
        ToolPermissionType("axiom", "Axiom", Material.ENDER_EYE, "axiom"),
        ToolPermissionType(
            KANTAN_COMMAND_BLOCK_ID,
            "拡張コマンドブロック",
            Material.COMMAND_BLOCK,
            KANTAN_COMMAND_BLOCK_GROUP,
            KANTAN_EXTENDED_COMMAND_BLOCK_PERMISSION,
        ),
    )

    private val typesById = types.associateBy(ToolPermissionType::id)

    fun find(id: String): ToolPermissionType? = typesById[id.lowercase()]
}
