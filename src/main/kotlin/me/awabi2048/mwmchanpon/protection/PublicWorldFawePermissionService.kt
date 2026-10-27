package me.awabi2048.mwmchanpon.protection

import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import net.luckperms.api.LuckPerms
import net.luckperms.api.node.types.PermissionNode

/**
 * FAWE's region gate is independent from WorldGuard's build mode. A public MyWorld has no member
 * region for FAWE to authorize, so mirror the world-level PUBLIC policy into LuckPerms using the
 * same world-context group-node pattern as other per-world permissions.
 */
class PublicWorldFawePermissionService(
    private val luckPerms: LuckPerms,
    private val logger: Logger,
    private val permissionNodeFactory: (String, String) -> PermissionNode = { permission, worldName ->
        PermissionNode.builder(permission)
            .withContext("world", worldName)
            .value(true)
            .build()
    },
) {
    // All MyWorld edits touch the shared default group. Serialize saves so toggles in separate
    // worlds cannot race by loading and then overwriting the same LuckPerms group snapshot.
    private var pendingMutation: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun sync(worldName: String, mode: ChanponWorldProtectionMode): CompletableFuture<Void> =
        enqueueMutation(worldName, mode == ChanponWorldProtectionMode.PUBLIC)

    /** Remove only this world's managed node when its MyWorld is deleted. */
    fun clear(worldName: String): CompletableFuture<Void> = enqueueMutation(worldName, grant = false)

    @Synchronized
    private fun enqueueMutation(worldName: String, grant: Boolean): CompletableFuture<Void> {
        val next = pendingMutation
            .handle { _, _ -> null }
            .thenCompose { mutateDefaultGroup(worldName, grant) }
        pendingMutation = next
        return next
    }

    private fun mutateDefaultGroup(worldName: String, grant: Boolean): CompletableFuture<Void> =
        luckPerms.groupManager.loadGroup(DEFAULT_GROUP)
            .thenCompose { optionalGroup ->
                val group = optionalGroup.orElse(null)
                if (group == null) {
                    logger.warning("LuckPerms default group '$DEFAULT_GROUP' is unavailable; FAWE region policy was not synced for $worldName.")
                    return@thenCompose CompletableFuture.completedFuture(null)
                }

                val node = permissionNodeFactory(FAWE_REGION_BYPASS_PERMISSION, worldName)
                if (grant) {
                    group.data().add(node)
                } else {
                    group.data().remove(node)
                }
                luckPerms.groupManager.saveGroup(group)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to ${if (grant) "grant" else "remove"} FAWE region bypass for $worldName: ${error.message}")
                }
            }

    companion object {
        const val FAWE_REGION_BYPASS_PERMISSION = "fawe.bypass.regions"
        private const val DEFAULT_GROUP = "default"
    }
}
