package me.awabi2048.mwmchanpon.protection

import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import net.luckperms.api.LuckPerms
import net.luckperms.api.node.types.PermissionNode

/**
 * Mirrors each MyWorld build mode into FAWE's independent WorldGuard-region permission check.
 * FAWE checks region owner/member identity rather than WorldGuard's BUILD flag unless its
 * separate flag permission is granted, so syncing only the Bukkit build region is insufficient.
 */
class PublicWorldFawePermissionService(
    private val luckPerms: LuckPerms,
    private val logger: Logger,
    private val permissionNodeFactory: (String, String, Boolean) -> PermissionNode = { permission, worldName, value ->
        PermissionNode.builder(permission)
            .withContext("world", worldName)
            .value(value)
            .build()
    },
) {
    // All MyWorld edits touch the shared default group. Serialize saves so toggles in separate
    // worlds cannot race by loading and then overwriting the same LuckPerms group snapshot.
    private var pendingMutation: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun sync(worldName: String, mode: ChanponWorldProtectionMode): CompletableFuture<Void> =
        enqueueMutation(worldName, mode)

    /** Remove only this world's managed nodes when its MyWorld is deleted. */
    fun clear(worldName: String): CompletableFuture<Void> = enqueueMutation(worldName, mode = null)

    @Synchronized
    private fun enqueueMutation(worldName: String, mode: ChanponWorldProtectionMode?): CompletableFuture<Void> {
        val next = pendingMutation
            .handle { _, _ -> null }
            .thenCompose { mutateDefaultGroup(worldName, mode) }
        pendingMutation = next
        return next
    }

    private fun mutateDefaultGroup(worldName: String, mode: ChanponWorldProtectionMode?): CompletableFuture<Void> =
        luckPerms.groupManager.loadGroup(DEFAULT_GROUP)
            .thenCompose { optionalGroup ->
                val group = optionalGroup.orElse(null)
                if (group == null) {
                    logger.warning("LuckPerms default group '$DEFAULT_GROUP' is unavailable; FAWE region policy was not synced for $worldName.")
                    return@thenCompose CompletableFuture.completedFuture(null)
                }

                // Remove both values because a mode transition replaces the contextual grant
                // with a negation. Preserve every permission node for other worlds.
                FAWE_WORLDGUARD_MEMBER_PERMISSION_VALUES.forEach { value ->
                    group.data().remove(permissionNodeFactory(FAWE_WORLDGUARD_MEMBER_PERMISSION, worldName, value))
                }
                group.data().remove(permissionNodeFactory(FAWE_REGION_BYPASS_PERMISSION, worldName, true))

                // FAWE authorizes members through this separate node, independently of the
                // WorldGuard BUILD flag. OWNER_ONLY must negate inherited member access; MEMBERS
                // explicitly allows it; PUBLIC needs the narrower region bypass because it has
                // no MyWorld access region for FAWE to accept.
                when (mode) {
                    ChanponWorldProtectionMode.OWNER_ONLY ->
                        group.data().add(permissionNodeFactory(FAWE_WORLDGUARD_MEMBER_PERMISSION, worldName, false))
                    ChanponWorldProtectionMode.MEMBERS ->
                        group.data().add(permissionNodeFactory(FAWE_WORLDGUARD_MEMBER_PERMISSION, worldName, true))
                    ChanponWorldProtectionMode.PUBLIC ->
                        group.data().add(permissionNodeFactory(FAWE_REGION_BYPASS_PERMISSION, worldName, true))
                    null -> Unit
                }
                luckPerms.groupManager.saveGroup(group)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to sync FAWE region permissions for $worldName: ${error.message}")
                }
            }

    companion object {
        const val FAWE_REGION_BYPASS_PERMISSION = "fawe.bypass.regions"
        const val FAWE_WORLDGUARD_MEMBER_PERMISSION = "fawe.worldguard.member"
        private val FAWE_WORLDGUARD_MEMBER_PERMISSION_VALUES = listOf(false, true)
        private const val DEFAULT_GROUP = "default"
    }
}
