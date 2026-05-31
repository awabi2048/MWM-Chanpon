package me.awabi2048.mwmchanpon.tool

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import net.luckperms.api.LuckPerms
import net.luckperms.api.node.types.PermissionNode

class LuckPermsToolPermissionService(
    private val luckPerms: LuckPerms,
    private val logger: Logger
) {
    fun syncPlayer(
        playerUuid: UUID,
        worldName: String,
        definitions: List<ToolPermissionDefinition>,
        enabledToolIds: Set<String>
    ): CompletableFuture<Void> {
        return luckPerms.userManager.loadUser(playerUuid)
            .thenCompose { user ->
                definitions.forEach { definition ->
                    definition.permissions.forEach { permission ->
                        val node = PermissionNode.builder(permission)
                            .withContext("world", worldName)
                            .value(true)
                            .build()
                        if (definition.id in enabledToolIds) {
                            user.data().add(node)
                        } else {
                            user.data().remove(node)
                        }
                    }
                }
                luckPerms.userManager.saveUser(user)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to sync tool permissions for $playerUuid in $worldName: ${error.message}")
                } else {
                    logger.fine("Synced tool permissions for $playerUuid in $worldName")
                }
            }
    }

    fun removePermissions(
        playerUuid: UUID,
        worldName: String,
        definitions: List<ToolPermissionDefinition>
    ): CompletableFuture<Void> {
        return luckPerms.userManager.loadUser(playerUuid)
            .thenCompose { user ->
                definitions.forEach { definition ->
                    definition.permissions.forEach { permission ->
                        val node = PermissionNode.builder(permission)
                            .withContext("world", worldName)
                            .value(true)
                            .build()
                        user.data().remove(node)
                    }
                }
                luckPerms.userManager.saveUser(user)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to remove tool permissions for $playerUuid in $worldName: ${error.message}")
                } else {
                    logger.fine("Removed tool permissions for $playerUuid in $worldName")
                }
            }
    }
}
