package me.awabi2048.mwmchanpon.tool

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import net.luckperms.api.LuckPerms
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.model.user.User

class LuckPermsToolPermissionService(
    private val luckPerms: LuckPerms,
    private val logger: Logger
) {
    fun applyActiveGroups(
        playerUuid: UUID,
        definitions: List<ToolPermissionDefinition>,
        enabledToolIds: Set<String>
    ): CompletableFuture<Void> {
        val loadedUser = luckPerms.userManager.getUser(playerUuid)
        if (loadedUser != null) {
            applyActiveGroups(loadedUser, definitions, enabledToolIds)
            return CompletableFuture.completedFuture(null)
        }

        return luckPerms.userManager.loadUser(playerUuid)
            .thenAccept { user ->
                applyActiveGroups(user, definitions, enabledToolIds)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to apply active tool permissions for $playerUuid: ${error.message}")
                } else {
                    logger.fine("Applied active tool permissions for $playerUuid")
                }
            }
    }

    fun clearActiveGroups(
        playerUuid: UUID,
        definitions: List<ToolPermissionDefinition>
    ): CompletableFuture<Void> {
        val loadedUser = luckPerms.userManager.getUser(playerUuid)
        if (loadedUser != null) {
            clearActiveGroups(loadedUser, definitions)
            return CompletableFuture.completedFuture(null)
        }

        return luckPerms.userManager.loadUser(playerUuid)
            .thenAccept { user ->
                clearActiveGroups(user, definitions)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to clear active tool permissions for $playerUuid: ${error.message}")
                } else {
                    logger.fine("Cleared active tool permissions for $playerUuid")
                }
            }
    }

    fun removeScopedPermissions(
        playerUuid: UUID,
        worldName: String,
        definitions: List<ToolPermissionDefinition>
    ): CompletableFuture<Void> {
        return luckPerms.userManager.loadUser(playerUuid)
            .thenCompose { user ->
                definitions.forEach { definition ->
                    val groupNode = InheritanceNode.builder(definition.groupName)
                        .withContext("world", worldName)
                        .value(true)
                        .build()
                    user.data().remove(groupNode)
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

    fun removePersistentGroupAssignment(
        playerUuid: UUID,
        worldName: String,
        groupName: String
    ): CompletableFuture<Void> {
        return luckPerms.userManager.loadUser(playerUuid)
            .thenCompose { user ->
                user.data().remove(
                    InheritanceNode.builder(groupName)
                        .withContext("world", worldName)
                        .value(true)
                        .build()
                )
                luckPerms.userManager.saveUser(user)
            }
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warning("Failed to remove legacy group $groupName for $playerUuid in $worldName: ${error.message}")
                }
            }
    }

    private fun applyActiveGroups(
        user: User,
        definitions: List<ToolPermissionDefinition>,
        enabledToolIds: Set<String>
    ) {
        clearActiveGroups(user, definitions)
        definitions
            .filter { it.id in enabledToolIds }
            .forEach { definition ->
                user.transientData().add(activeGroupNode(definition))
            }
    }

    private fun clearActiveGroups(user: User, definitions: List<ToolPermissionDefinition>) {
        definitions.forEach { definition ->
            user.transientData().remove(activeGroupNode(definition))
        }
    }

    private fun activeGroupNode(definition: ToolPermissionDefinition): InheritanceNode {
        return InheritanceNode.builder(definition.groupName)
            .value(true)
            .build()
    }
}
