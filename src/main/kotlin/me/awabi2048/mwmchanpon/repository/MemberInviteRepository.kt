package me.awabi2048.mwmchanpon.repository

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.MemberInviteData
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class MemberInviteRepository(private val plugin: MWMChanponPlugin) {
    private val rootDirectory: File
        get() = File(plugin.dataFolder.parentFile, "member_invites")

    private fun file(): File = File(rootDirectory, "member_invites.yml")

    fun loadAll(): List<MemberInviteData> {
        val file = file()
        if (!file.exists()) {
            return emptyList()
        }

        val config = YamlConfiguration.loadConfiguration(file)
        return config.getMapList("member_invites").mapNotNull { map ->
            runCatching {
                MemberInviteData(
                    id = UUID.fromString(map["id"] as String),
                    worldUuid = UUID.fromString(map["world_uuid"] as String),
                    senderUuid = UUID.fromString(map["sender_uuid"] as String),
                    targetUuid = UUID.fromString(map["target_uuid"] as String),
                    createdAt = when (val value = map["created_at"]) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull() ?: 0L
                        else -> 0L
                    },
                )
            }.getOrNull()
        }
    }

    fun findByTarget(targetUuid: UUID): List<MemberInviteData> {
        return loadAll().filter { it.targetUuid == targetUuid }.sortedBy { it.createdAt }
    }

    fun findByWorld(worldUuid: UUID): List<MemberInviteData> {
        return loadAll().filter { it.worldUuid == worldUuid }.sortedBy { it.createdAt }
    }

    fun findByWorldAndTarget(worldUuid: UUID, targetUuid: UUID): MemberInviteData? {
        return loadAll().firstOrNull { it.worldUuid == worldUuid && it.targetUuid == targetUuid }
    }

    fun findById(inviteId: UUID): MemberInviteData? {
        return loadAll().firstOrNull { it.id == inviteId }
    }

    fun add(worldUuid: UUID, senderUuid: UUID, targetUuid: UUID): MemberInviteData {
        val invite = MemberInviteData(
            id = UUID.randomUUID(),
            worldUuid = worldUuid,
            senderUuid = senderUuid,
            targetUuid = targetUuid,
            createdAt = System.currentTimeMillis(),
        )
        val current = loadAll().toMutableList()
        current += invite
        saveAll(current)
        return invite
    }

    fun remove(inviteId: UUID) {
        val current = loadAll().filterNot { it.id == inviteId }
        saveAll(current)
    }

    fun removeByWorldAndTarget(worldUuid: UUID, targetUuid: UUID) {
        val current = loadAll().filterNot { it.worldUuid == worldUuid && it.targetUuid == targetUuid }
        saveAll(current)
    }

    private fun saveAll(invites: List<MemberInviteData>) {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }

        val config = YamlConfiguration()
        config.set(
            "member_invites",
            invites.map {
                mapOf(
                    "id" to it.id.toString(),
                    "world_uuid" to it.worldUuid.toString(),
                    "sender_uuid" to it.senderUuid.toString(),
                    "target_uuid" to it.targetUuid.toString(),
                    "created_at" to it.createdAt,
                )
            },
        )
        config.save(file())
    }
}
