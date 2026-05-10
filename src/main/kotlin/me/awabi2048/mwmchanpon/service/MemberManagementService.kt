package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.MemberInviteData
import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.repository.MemberInviteRepository
import me.awabi2048.mwmchanpon.repository.WorldRepository
import me.awabi2048.mwmchanpon.ui.MemberManagementEntry
import me.awabi2048.mwmchanpon.ui.MemberManagementEntryType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MemberManagementService(
    private val plugin: MWMChanponPlugin,
    private val worldRepository: WorldRepository,
    private val inviteRepository: MemberInviteRepository,
) {
    private val language = plugin.localizationService

    fun canView(player: Player, worldData: MyWorldData): Boolean {
        return worldData.owner == player.uniqueId || worldData.members.contains(player.uniqueId)
    }

    fun canManage(player: Player, worldData: MyWorldData): Boolean {
        return worldData.owner == player.uniqueId
    }

    fun canForceAdd(player: Player): Boolean {
        return player.hasPermission("myworldmanager.force_add_member")
    }

    fun buildEntries(worldData: MyWorldData): List<MemberManagementEntry> {
        val entries = mutableListOf<MemberManagementEntry>()
        entries += MemberManagementEntry(MemberManagementEntryType.OWNER, worldData.owner)
        entries += worldData.members.distinct().filterNot { it == worldData.owner }.map {
            MemberManagementEntry(MemberManagementEntryType.MEMBER, it)
        }
        entries += inviteRepository.findByWorld(worldData.uuid).map {
            MemberManagementEntry(
                MemberManagementEntryType.INVITE,
                it.targetUuid,
                inviteId = it.id,
                createdAt = it.createdAt,
            )
        }
        return entries
    }

    fun invitePlayer(owner: Player, worldData: MyWorldData, targetName: String, forceAdd: Boolean): Result<Unit> {
        val target = Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(targetName, ignoreCase = true) }
            ?: return Result.failure(IllegalArgumentException(language.getString(owner, "messages.member_invite_target_offline", mapOf("player" to targetName))))

        if (target.uniqueId == owner.uniqueId) {
            return Result.failure(IllegalArgumentException(language.getString(owner, "messages.member_invite_target_self")))
        }

        if (worldData.owner == target.uniqueId || worldData.members.contains(target.uniqueId)) {
            return Result.failure(IllegalArgumentException(language.getString(owner, "messages.member_invite_target_already_member", mapOf("player" to target.name))))
        }

        if (inviteRepository.findByWorldAndTarget(worldData.uuid, target.uniqueId) != null) {
            return Result.failure(IllegalArgumentException(language.getString(owner, "messages.member_invite_already_sent", mapOf("player" to target.name))))
        }

        if (forceAdd) {
            if (!canForceAdd(owner)) {
                return Result.failure(IllegalArgumentException(language.getString(owner, "messages.no_permission")))
            }
            val updated = worldData.copy(members = (worldData.members + target.uniqueId).distinct().toMutableList())
            worldRepository.save(updated)
            target.sendMessage(language.getString(target, "messages.member_force_added_self", mapOf("world" to worldData.name)))
            target.playSound(target.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)
            notifyMembersAdded(owner, target, updated, forceAdded = true)
            return Result.success(Unit)
        }

        val invite = inviteRepository.add(worldData.uuid, owner.uniqueId, target.uniqueId)
        sendInvitePrompt(owner, target, worldData, invite)
        owner.sendMessage(language.getString(owner, "messages.member_invite_sent_success", mapOf("player" to target.name, "world" to worldData.name)))
        return Result.success(Unit)
    }

    fun handleAccept(player: Player, inviteId: UUID? = null): Result<Unit> {
        val invite = resolveInviteForCommand(player, inviteId) ?: return Result.failure(IllegalStateException(language.getString(player, "messages.member_invite_none")))
        return acceptInvite(player, invite)
    }

    fun handleDeny(player: Player, inviteId: UUID? = null): Result<Unit> {
        val invite = resolveInviteForCommand(player, inviteId) ?: return Result.failure(IllegalStateException(language.getString(player, "messages.member_invite_none")))
        inviteRepository.remove(invite.id)
        notifyInviteDenied(player, invite)
        player.sendMessage(language.getString(player, "messages.member_invite_denied_self"))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
        return Result.success(Unit)
    }

    fun notifyPendingInvites(player: Player) {
        val invites = inviteRepository.findByTarget(player.uniqueId)
        if (invites.isEmpty()) {
            return
        }
        if (invites.size > 1) {
            player.sendMessage(language.getString(player, "messages.member_invite_multiple_pending_notice", mapOf("count" to invites.size.toString())))
        }
        invites.forEach { invite ->
            val worldData = worldRepository.load(invite.worldUuid) ?: return@forEach
            val senderName = Bukkit.getOfflinePlayer(invite.senderUuid).name ?: invite.senderUuid.toString()
            sendInvitePrompt(null, player, worldData, invite, senderName)
        }
    }

    fun cancelInvite(owner: Player, worldData: MyWorldData, inviteId: UUID): Result<Unit> {
        if (!canManage(owner, worldData)) {
            return Result.failure(IllegalArgumentException(language.getString(owner, "messages.no_permission")))
        }
        val invite = inviteRepository.loadAll().firstOrNull { it.id == inviteId && it.worldUuid == worldData.uuid }
            ?: return Result.failure(IllegalStateException(language.getString(owner, "messages.member_invite_cancel_not_found")))
        inviteRepository.remove(invite.id)
        val targetName = Bukkit.getOfflinePlayer(invite.targetUuid).name ?: invite.targetUuid.toString()
        owner.sendMessage(language.getString(owner, "messages.member_invite_cancelled", mapOf("player" to targetName)))
        if (invite.senderUuid != owner.uniqueId) {
            val senderPlayer = Bukkit.getPlayer(invite.senderUuid)?.takeIf { it.isOnline }
            if (senderPlayer != null) {
                senderPlayer.sendMessage(language.getString(senderPlayer, "messages.member_invite_cancelled_sender", mapOf("player" to targetName)))
            }
        }
        return Result.success(Unit)
    }

    fun removeMember(owner: Player, worldData: MyWorldData, targetUuid: UUID): Result<MyWorldData> {
        if (!canManage(owner, worldData)) {
            return Result.failure(IllegalArgumentException(language.getString(owner, "messages.no_permission")))
        }
        if (targetUuid == worldData.owner) {
            return Result.failure(IllegalStateException(language.getString(owner, "messages.member_remove_owner_forbidden")))
        }
        if (!worldData.members.contains(targetUuid)) {
            return Result.failure(IllegalStateException(language.getString(owner, "messages.member_remove_not_found")))
        }
        val updated = worldData.copy(members = worldData.members.filterNot { it == targetUuid }.toMutableList())
        worldRepository.save(updated)
        inviteRepository.removeByWorldAndTarget(worldData.uuid, targetUuid)
        val targetName = Bukkit.getOfflinePlayer(targetUuid).name ?: targetUuid.toString()
        owner.sendMessage(language.getString(owner, "messages.member_deleted", mapOf("player" to targetName, "world" to worldData.name)))
        Bukkit.getPlayer(targetUuid)?.takeIf { it.isOnline }?.let { targetPlayer ->
            targetPlayer.sendMessage(language.getString(targetPlayer, "messages.member_deleted_self", mapOf("world" to worldData.name)))
        }
        return Result.success(updated)
    }

    fun formatInviteTime(player: Player?, timestamp: Long): String {
        val zone = ZoneId.systemDefault()
        val formatter = if (player != null && resolveLanguage(player) == "ja_jp") {
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm")
        } else {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        }
        return Instant.ofEpochMilli(timestamp).atZone(zone).format(formatter)
    }

    fun resolveDisplayName(uuid: UUID): String {
        return Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
    }

    fun isOnline(uuid: UUID): Boolean {
        return Bukkit.getPlayer(uuid)?.isOnline == true
    }

    fun getInvitesForTarget(targetUuid: UUID): List<MemberInviteData> {
        return inviteRepository.findByTarget(targetUuid)
    }

    private fun resolveInviteForCommand(player: Player, inviteId: UUID?): MemberInviteData? {
        return if (inviteId != null) {
            inviteRepository.findById(inviteId)?.takeIf { it.targetUuid == player.uniqueId }
        } else {
            inviteRepository.findByTarget(player.uniqueId).firstOrNull()
        }
    }

    private fun acceptInvite(player: Player, invite: MemberInviteData): Result<Unit> {
        val worldData = worldRepository.load(invite.worldUuid)
            ?: run {
                inviteRepository.remove(invite.id)
                return Result.failure(IllegalStateException(language.getString(player, "messages.member_invite_world_not_found")))
            }
        if (worldData.owner == player.uniqueId || worldData.members.contains(player.uniqueId)) {
            inviteRepository.remove(invite.id)
            return Result.failure(IllegalStateException(language.getString(player, "messages.member_invite_already_member")))
        }
        val updated = worldData.copy(members = (worldData.members + player.uniqueId).distinct().toMutableList())
        worldRepository.save(updated)
        inviteRepository.remove(invite.id)
        notifyInviteAccepted(player, invite, updated)
        player.sendMessage(language.getString(player, "messages.member_invite_accepted_self", mapOf("world" to updated.name)))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)
        return Result.success(Unit)
    }

    private fun notifyInviteAccepted(player: Player, invite: MemberInviteData, worldData: MyWorldData) {
        val recipients = linkedSetOf<UUID>()
        recipients.add(worldData.owner)
        recipients.addAll(worldData.members)
        recipients.remove(player.uniqueId)
        recipients.remove(invite.senderUuid)
        recipients.forEach { uuid ->
            val recipient = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline }
            if (recipient != null) {
                recipient.sendMessage(language.getString(recipient, "messages.member_joined_notify", mapOf("player" to player.name, "world" to worldData.name)))
            }
        }
        Bukkit.getPlayer(invite.senderUuid)?.takeIf { it.isOnline }?.let { senderPlayer ->
            senderPlayer.sendMessage(language.getString(senderPlayer, "messages.member_invite_accepted_sender", mapOf("player" to player.name, "world" to worldData.name)))
        }
    }

    private fun notifyInviteDenied(player: Player, invite: MemberInviteData) {
        val senderPlayer = Bukkit.getPlayer(invite.senderUuid)?.takeIf { it.isOnline } ?: return
        val targetName = Bukkit.getOfflinePlayer(invite.targetUuid).name ?: invite.targetUuid.toString()
        senderPlayer.sendMessage(language.getString(senderPlayer, "messages.member_invite_denied_sender", mapOf("player" to targetName)))
    }

    private fun notifyMembersAdded(owner: Player, target: Player, worldData: MyWorldData, forceAdded: Boolean) {
        val key = if (forceAdded) "messages.member_force_add_success" else "messages.member_joined_notify"
        owner.sendMessage(language.getString(owner, key, mapOf("player" to target.name, "world" to worldData.name)))
        val recipients = linkedSetOf<UUID>()
        recipients.add(worldData.owner)
        recipients.addAll(worldData.members)
        recipients.remove(target.uniqueId)
        recipients.remove(owner.uniqueId)
        recipients.forEach { uuid ->
            val recipient = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline }
            if (recipient != null) {
                recipient.sendMessage(language.getString(recipient, "messages.member_joined_notify", mapOf("player" to target.name, "world" to worldData.name)))
            }
        }
    }

    private fun sendInvitePrompt(owner: Player?, target: Player, worldData: MyWorldData, invite: MemberInviteData, senderName: String? = null) {
        val senderDisplay = senderName ?: owner?.name ?: Bukkit.getOfflinePlayer(invite.senderUuid).name ?: invite.senderUuid.toString()
        if (isBedrock(target)) {
            val message = Component.text("")
                .append(Component.text(language.getString(target, "messages.member_invite_text", mapOf("player" to senderDisplay, "world" to worldData.name)), NamedTextColor.YELLOW))
                .append(Component.text("\n"))
                .append(Component.text(language.getString(target, "messages.member_invite_bedrock_accept", mapOf("id" to invite.id.toString())), NamedTextColor.GREEN))
                .append(Component.text("\n"))
                .append(Component.text(language.getString(target, "messages.member_invite_bedrock_deny", mapOf("id" to invite.id.toString())), NamedTextColor.RED))
            target.sendMessage(message)
            return
        }

        val accept = Component.text(language.getString(target, "messages.member_invite_click_accept"), NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true)
            .clickEvent(ClickEvent.runCommand("/myworld accept ${invite.id}"))
            .hoverEvent(HoverEvent.showText(Component.text(language.getString(target, "messages.member_invite_hover"), NamedTextColor.GRAY)))
        val deny = Component.text(language.getString(target, "messages.member_invite_click_deny"), NamedTextColor.RED)
            .decoration(TextDecoration.BOLD, true)
            .clickEvent(ClickEvent.runCommand("/myworld deny ${invite.id}"))
            .hoverEvent(HoverEvent.showText(Component.text(language.getString(target, "messages.member_invite_hover"), NamedTextColor.GRAY)))
        target.sendMessage(
            Component.text("")
                .append(Component.text(language.getString(target, "messages.member_invite_text", mapOf("player" to senderDisplay, "world" to worldData.name)), NamedTextColor.YELLOW))
                .append(Component.text("\n"))
                .append(accept)
                .append(Component.text(" "))
                .append(deny)
        )
    }

    private fun isBedrock(player: Player): Boolean {
        val floodgatePlugin = Bukkit.getPluginManager().getPlugin("floodgate") ?: return false
        if (!floodgatePlugin.isEnabled) {
            return false
        }
        return runCatching { org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(player.uniqueId) }.getOrDefault(false)
    }

    private fun resolveLanguage(player: Player): String {
        return plugin.playerSettingsRepository.load(player.uniqueId)?.language?.lowercase(Locale.ROOT)
            ?: player.locale().toLanguageTag().replace('-', '_').lowercase(Locale.ROOT)
    }
}
