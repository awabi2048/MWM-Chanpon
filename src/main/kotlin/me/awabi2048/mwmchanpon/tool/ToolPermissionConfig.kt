package me.awabi2048.mwmchanpon.tool

import org.bukkit.Material
import org.bukkit.configuration.Configuration

class ToolPermissionConfig(private val config: Configuration) {
    fun load(): List<ToolPermissionDefinition> {
        val section = config.getConfigurationSection(ToolPermissionCatalog.CONFIG_PATH) ?: return emptyList()
        val configuredIds = section.getKeys(false).associateBy(String::lowercase)
        return ToolPermissionCatalog.types.mapNotNull { type ->
            val configuredId = configuredIds[type.id] ?: return@mapNotNull null

            val base = "${ToolPermissionCatalog.CONFIG_PATH}.$configuredId"
            // 移行完了前の旧設定はgroupだけ読み取り、表示情報や権限ノードは再利用しません。
            val groupName = scalarString(config, base)
                ?: config.getString("$base.group")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            type.definition(groupName)
        }
    }

    private fun scalarString(config: Configuration, path: String): String? {
        if (config.isConfigurationSection(path)) return null
        return config.getString(path)?.trim()?.takeIf(String::isNotEmpty)
    }
}

/**
 * config.yml v4以前のツール設定を、現行の「ツール種類: グループ名」形式へ正規化します。
 * 呼び出し側でバックアップを作成してから実行するため、旧設定の不要項目を安全に除去できます。
 */
internal object ToolPermissionConfigSchema {
    private const val MENU_SLOT_PATH = "tool_permissions.menu_slot"

    fun migrate(config: Configuration) {
        val section = config.getConfigurationSection(ToolPermissionCatalog.CONFIG_PATH)
        val sourceIds = section?.getKeys(false).orEmpty().associateBy(String::lowercase)
        val groups = ToolPermissionCatalog.types.mapNotNull { type ->
            val sourceId = sourceIds[type.id] ?: return@mapNotNull null
            val base = "${ToolPermissionCatalog.CONFIG_PATH}.$sourceId"
            val group = scalarString(config, base)
                ?: config.getString("$base.group")?.trim()?.takeIf(String::isNotEmpty)
                ?: type.defaultGroupName
            type.id to group
        }

        // menu_slotと表示情報を除去し、commandblockを含む既存の権限グループを残します。
        config.set(MENU_SLOT_PATH, null)
        config.set(ToolPermissionCatalog.CONFIG_PATH, null)
        groups.forEach { (id, group) ->
            config.set("${ToolPermissionCatalog.CONFIG_PATH}.$id", group)
        }
    }

    /**
     * v5移行で一度削除されたKantanの設定を、既定グループで現行形式へ戻します。
     * 既に運用者が残したグループ名は上書きせず、付与導線だけを復元します。
     */
    fun ensureKantanCommandBlock(config: Configuration) {
        val path = "${ToolPermissionCatalog.CONFIG_PATH}.${ToolPermissionCatalog.KANTAN_COMMAND_BLOCK_ID}"
        if (!config.contains(path)) {
            config.set(path, ToolPermissionCatalog.KANTAN_COMMAND_BLOCK_GROUP)
        }
    }

    private fun scalarString(config: Configuration, path: String): String? {
        if (config.isConfigurationSection(path)) return null
        return config.getString(path)?.trim()?.takeIf(String::isNotEmpty)
    }

    fun validate(config: Configuration) {
        if (config.contains(MENU_SLOT_PATH)) {
            error("$MENU_SLOT_PATH is no longer supported")
        }
        if (!config.contains(ToolPermissionCatalog.CONFIG_PATH)) return

        val section = config.getConfigurationSection(ToolPermissionCatalog.CONFIG_PATH)
            ?: error("${ToolPermissionCatalog.CONFIG_PATH} must be a section")
        section.getKeys(false).forEach { rawId ->
            val id = rawId.lowercase()
            require(ToolPermissionCatalog.find(id) != null) {
                "${ToolPermissionCatalog.CONFIG_PATH}.$rawId is not a supported tool permission"
            }
            val value = config.get("${ToolPermissionCatalog.CONFIG_PATH}.$rawId")
            require(value is String && value.isNotBlank()) {
                "${ToolPermissionCatalog.CONFIG_PATH}.$rawId must be a non-blank group name"
            }
        }
    }
}
