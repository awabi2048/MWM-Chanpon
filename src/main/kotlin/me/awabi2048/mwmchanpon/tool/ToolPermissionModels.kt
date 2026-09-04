package me.awabi2048.mwmchanpon.tool

import org.bukkit.Material

data class ToolPermissionDefinition(
    val id: String,
    val displayName: String,
    val material: Material,
    val groupName: String,
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
) {
    fun definition(groupName: String): ToolPermissionDefinition = ToolPermissionDefinition(
        id = id,
        displayName = displayName,
        material = material,
        groupName = groupName,
    )
}

/** KantanCommanderを含めず、MWM-Chanponが外部連携するツールの固定カタログです。 */
internal object ToolPermissionCatalog {
    const val CONFIG_PATH = "tool_permissions.tools"

    val types: List<ToolPermissionType> = listOf(
        ToolPermissionType("worldedit", "WorldEdit", Material.WOODEN_AXE, "worldeditor"),
        ToolPermissionType("voxelsniper", "VoxelSniper", Material.ARROW, "voxelsniper"),
        ToolPermissionType("axiom", "Axiom", Material.ENDER_EYE, "axiom"),
    )

    private val typesById = types.associateBy(ToolPermissionType::id)

    fun find(id: String): ToolPermissionType? = typesById[id.lowercase()]
}
