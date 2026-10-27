package me.awabi2048.mwmchanpon.protection

import java.lang.reflect.Proxy
import java.util.Optional
import java.util.OptionalInt
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import net.luckperms.api.LuckPerms
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.group.Group
import net.luckperms.api.model.group.GroupManager
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.PermissionNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicWorldFawePermissionServiceTest {
    @Test
    fun `PUBLIC adds a region-only permission in that world and other modes remove it`() {
        val nodes = linkedSetOf<NodeSnapshot>()
        val service = service(nodes)
        val firstWorld = "my_world.first"
        val secondWorld = "my_world.second"

        service.sync(firstWorld, ChanponWorldProtectionMode.PUBLIC).join()
        service.sync(secondWorld, ChanponWorldProtectionMode.PUBLIC).join()

        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.permission == PublicWorldFawePermissionService.FAWE_REGION_BYPASS_PERMISSION })
        assertTrue(nodes.all { it.value })
        assertTrue(nodes.any { it.worldName == firstWorld })
        assertTrue(nodes.any { it.worldName == secondWorld })
        assertFalse(nodes.any { it.permission == "fawe.bypass" })

        service.sync(firstWorld, ChanponWorldProtectionMode.MEMBERS).join()
        assertEquals(1, nodes.size)
        assertEquals(secondWorld, nodes.single().worldName)

        service.sync(secondWorld, ChanponWorldProtectionMode.OWNER_ONLY).join()
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun `deleting a public world removes only its contextual node`() {
        val nodes = linkedSetOf<NodeSnapshot>()
        val service = service(nodes)
        service.sync("my_world.first", ChanponWorldProtectionMode.PUBLIC).join()
        service.sync("my_world.second", ChanponWorldProtectionMode.PUBLIC).join()

        service.clear("my_world.first").join()

        assertEquals(1, nodes.size)
        assertEquals("my_world.second", nodes.single().worldName)
    }

    private fun service(nodes: MutableSet<NodeSnapshot>): PublicWorldFawePermissionService {
        val nodeMap = Proxy.newProxyInstance(
            NodeMap::class.java.classLoader,
            arrayOf(NodeMap::class.java),
        ) { _, method, arguments ->
            val node = arguments?.firstOrNull() as? Node
            when (method.name) {
                "add" -> { nodes.add(snapshot(requireNotNull(node))); null }
                "remove" -> { nodes.remove(snapshot(requireNotNull(node))); null }
                else -> error("Unexpected LuckPerms NodeMap call: ${method.name}")
            }
        } as NodeMap

        val group = Proxy.newProxyInstance(
            Group::class.java.classLoader,
            arrayOf(Group::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "data" -> nodeMap
                else -> error("Unexpected LuckPerms Group call: ${method.name}")
            }
        } as Group

        val groupManager = Proxy.newProxyInstance(
            GroupManager::class.java.classLoader,
            arrayOf(GroupManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "loadGroup" -> CompletableFuture.completedFuture(Optional.of(group))
                "saveGroup" -> CompletableFuture.completedFuture<Void>(null)
                else -> error("Unexpected LuckPerms GroupManager call: ${method.name}")
            }
        } as GroupManager

        val luckPerms = Proxy.newProxyInstance(
            LuckPerms::class.java.classLoader,
            arrayOf(LuckPerms::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getGroupManager" -> groupManager
                else -> error("Unexpected LuckPerms call: ${method.name}")
            }
        } as LuckPerms

        return PublicWorldFawePermissionService(luckPerms, Logger.getAnonymousLogger()) { permission, worldName ->
            Proxy.newProxyInstance(
                PermissionNode::class.java.classLoader,
                arrayOf(PermissionNode::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getKey", "getPermission" -> permission
                    "getValue" -> true
                    "getContexts" -> Proxy.newProxyInstance(
                        ImmutableContextSet::class.java.classLoader,
                        arrayOf(ImmutableContextSet::class.java),
                    ) { _, contextMethod, _ ->
                        when (contextMethod.name) {
                            "toMap" -> mapOf("world" to setOf(worldName))
                            "getValues" -> if (contextMethod.parameterTypes.firstOrNull() == String::class.java) {
                                setOf(worldName)
                            } else {
                                emptySet<String>()
                            }
                            else -> null
                        }
                    }
                    "isWildcard" -> false
                    "getWildcardLevel" -> OptionalInt.empty()
                    else -> null
                }
            } as PermissionNode
        }
    }

    private fun snapshot(node: Node) = NodeSnapshot(
        permission = node.key,
        value = node.value,
        worldName = requireNotNull(node.contexts.toMap()["world"]?.singleOrNull()),
    )

    private data class NodeSnapshot(
        val permission: String,
        val value: Boolean,
        val worldName: String,
    )
}
