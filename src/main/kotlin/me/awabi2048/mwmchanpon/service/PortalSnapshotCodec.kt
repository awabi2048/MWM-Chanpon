package me.awabi2048.mwmchanpon.service

import java.io.File
import java.util.UUID
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration

/**
 * バックアップとアーカイブで共有するポータルスナップショットの厳格な入出力です。
 *
 * 不正なレコードを mapNotNull で捨てると、復元時に既存ポータルを消した状態を
 * 成功扱いにしてしまいます。そのため、1件でも形式不正・重複・対象外があれば
 * ファイル全体を拒否し、永続化前に呼出し元へ失敗を返します。
 */
internal object PortalSnapshotCodec {
    fun read(
        file: File,
        expectedWorldUuid: UUID? = null,
        expectedWorldKey: String? = null,
        requireFile: Boolean = true,
    ): List<ApiPortalSnapshot> {
        if (!file.exists()) {
            require(!requireFile) { "portal snapshot file is missing: ${file.path}" }
            return emptyList()
        }
        require(file.isFile && file.canRead()) { "portal snapshot file is not readable: ${file.path}" }
        val raw = YamlConfiguration.loadConfiguration(file).get("portals")
        require(raw is List<*>) { "portal snapshot portals must be a list: ${file.path}" }
        val ids = mutableSetOf<UUID>()
        return raw.mapIndexed { index, entry ->
            require(entry is Map<*, *>) { "portal snapshot record $index is not a map" }
            val snapshot = parse(entry, index)
            require(ids.add(snapshot.id)) { "portal snapshot contains duplicate id: ${snapshot.id}" }
            if (expectedWorldUuid != null || expectedWorldKey != null) {
                val related = snapshot.worldUuid == expectedWorldUuid ||
                    snapshot.worldKey == expectedWorldKey ||
                    snapshot.targetWorldKey == expectedWorldKey
                require(related) { "portal snapshot is unrelated to target world: ${snapshot.id}" }
            }
            snapshot
        }
    }

    fun write(file: File, snapshots: List<ApiPortalSnapshot>) {
        file.parentFile?.mkdirs()
        YamlConfiguration().also { config ->
            config.set("portals", snapshots.map(::toMap))
            config.save(file)
        }
    }

    fun writeToString(snapshots: List<ApiPortalSnapshot>): String {
        return YamlConfiguration().also { config ->
            config.set("portals", snapshots.map(::toMap))
        }.saveToString()
    }

    private fun parse(map: Map<*, *>, index: Int): ApiPortalSnapshot {
        val id = requiredUuid(map, "id", index)
        val worldKey = requiredString(map, "world_key", index)
        require(NamespacedKey.fromString(worldKey) != null) {
            "portal snapshot world_key is invalid: $id"
        }
        val targetWorldKey = optionalString(map, "target_world_key", index)
        if (targetWorldKey != null) {
            require(NamespacedKey.fromString(targetWorldKey) != null) {
                "portal snapshot target_world_key is invalid: $id"
            }
        }
        val type = optionalString(map, "type", index) ?: "portal"
        require(type.equals("portal", ignoreCase = true) || type.equals("gate", ignoreCase = true)) {
            "portal snapshot type is invalid: $id"
        }
        val minX = optionalInt(map, "min_x", index)
        val minY = optionalInt(map, "min_y", index)
        val minZ = optionalInt(map, "min_z", index)
        val maxX = optionalInt(map, "max_x", index)
        val maxY = optionalInt(map, "max_y", index)
        val maxZ = optionalInt(map, "max_z", index)
        val bounds = listOf(minX, minY, minZ, maxX, maxY, maxZ)
        require(bounds.all { it == null } || bounds.all { it != null }) {
            "portal snapshot area is incomplete: $id"
        }
        if (type.equals("gate", ignoreCase = true)) {
            require(bounds.all { it != null }) { "gate portal snapshot area is missing: $id" }
        }
        return ApiPortalSnapshot(
            id = id,
            worldKey = worldKey,
            x = requiredInt(map, "x", index),
            y = requiredInt(map, "y", index),
            z = requiredInt(map, "z", index),
            worldUuid = optionalUuid(map, "world_uuid", index),
            targetWorldKey = targetWorldKey,
            showText = optionalBoolean(map, "show_text", true, index),
            particleColorRgb = requiredInt(map, "particle_color_rgb", index).also {
                require(it in 0..0xFFFFFF) { "portal snapshot particle color is invalid: $id" }
            },
            ownerUuid = requiredUuid(map, "owner_uuid", index),
            createdAt = requiredString(map, "created_at", index),
            textDisplayUuid = optionalUuid(map, "text_display_uuid", index),
            type = type.lowercase(),
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
        )
    }

    private fun toMap(portal: ApiPortalSnapshot): Map<String, Any?> = linkedMapOf(
        "id" to portal.id.toString(),
        "world_key" to portal.worldKey,
        "x" to portal.x,
        "y" to portal.y,
        "z" to portal.z,
        "world_uuid" to portal.worldUuid?.toString(),
        "target_world_key" to portal.targetWorldKey,
        "show_text" to portal.showText,
        "particle_color_rgb" to portal.particleColorRgb,
        "owner_uuid" to portal.ownerUuid.toString(),
        "created_at" to portal.createdAt,
        "text_display_uuid" to portal.textDisplayUuid?.toString(),
        "type" to portal.type,
        "min_x" to portal.minX,
        "min_y" to portal.minY,
        "min_z" to portal.minZ,
        "max_x" to portal.maxX,
        "max_y" to portal.maxY,
        "max_z" to portal.maxZ,
    )

    private fun requiredString(map: Map<*, *>, key: String, index: Int): String {
        val value = map[key]
        require(value is String && value.isNotBlank()) { "portal snapshot $index.$key is missing or invalid" }
        return value
    }

    private fun optionalString(map: Map<*, *>, key: String, index: Int): String? {
        val value = map[key] ?: return null
        require(value is String) { "portal snapshot $index.$key is invalid" }
        return value.takeIf(String::isNotBlank)
    }

    private fun requiredUuid(map: Map<*, *>, key: String, index: Int): UUID {
        val raw = requiredString(map, key, index)
        return runCatching { UUID.fromString(raw) }
            .getOrElse { error("portal snapshot $index.$key is invalid") }
    }

    private fun optionalUuid(map: Map<*, *>, key: String, index: Int): UUID? {
        val raw = optionalString(map, key, index) ?: return null
        return runCatching { UUID.fromString(raw) }
            .getOrElse { error("portal snapshot $index.$key is invalid") }
    }

    private fun requiredInt(map: Map<*, *>, key: String, index: Int): Int {
        val value = map[key]
        require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0) {
            "portal snapshot $index.$key is missing or invalid"
        }
        val long = value.toLong()
        require(long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "portal snapshot $index.$key is out of range"
        }
        return long.toInt()
    }

    private fun optionalInt(map: Map<*, *>, key: String, index: Int): Int? {
        if (map[key] == null) return null
        return requiredInt(map, key, index)
    }

    private fun optionalBoolean(map: Map<*, *>, key: String, default: Boolean, index: Int): Boolean {
        val value = map[key] ?: return default
        require(value is Boolean) { "portal snapshot $index.$key is invalid" }
        return value
    }
}
