package me.awabi2048.mwmchanpon.protection

import java.lang.reflect.Proxy
import java.util.Optional
import java.util.OptionalInt
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import net.luckperms.api.LuckPerms
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
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
    fun `FAWE owner and member checks match the expected build matrix`() {
        val nodes = linkedSetOf<NodeSnapshot>()
        val service = service(nodes)
        val worldName = "my_world.matrix"
        val expectedByMode = mapOf(
            ChanponWorldProtectionMode.OWNER_ONLY to mapOf("owner" to true, "member" to false, "guest" to false),
            ChanponWorldProtectionMode.MEMBERS to mapOf("owner" to true, "member" to true, "guest" to false),
            ChanponWorldProtectionMode.PUBLIC to mapOf("owner" to true, "member" to true, "guest" to true),
        )

        for (mode in ChanponWorldProtectionMode.entries) {
            service.sync(worldName, mode).join()
            val memberPermission = nodes.singleOrNull {
                it.worldName == worldName &&
                    it.permission == PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION
            }?.value == true
            val regionBypass = nodes.any {
                it.worldName == worldName &&
                    it.permission == PublicWorldFawePermissionService.FAWE_REGION_BYPASS_PERMISSION && it.value
            }
            val actual = mapOf(
                // FAWE's WorldGuard adapter accepts region owners without the member permission.
                "owner" to true,
                "member" to (regionBypass || memberPermission),
                "guest" to regionBypass,
            )

            assertEquals(expectedByMode.getValue(mode), actual, "FAWE $mode")
        }
    }

    @Test
    fun `each build mode aligns FAWE region permissions in its own world`() {
        val nodes = linkedSetOf<NodeSnapshot>()
        val service = service(nodes)
        val firstWorld = "my_world.first"
        val secondWorld = "my_world.second"

        service.sync(firstWorld, ChanponWorldProtectionMode.PUBLIC).join()
        service.sync(secondWorld, ChanponWorldProtectionMode.MEMBERS).join()

        assertEquals(
            setOf(
                NodeSnapshot(PublicWorldFawePermissionService.FAWE_REGION_BYPASS_PERMISSION, true, firstWorld),
                NodeSnapshot(PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION, true, secondWorld),
            ),
            nodes,
        )
        assertFalse(nodes.any { it.permission == "fawe.bypass" })

        // OWNER_ONLY must replace a member grant with a contextual denial so a broad
        // fawe.worldguard.member grant inherited from the tool group cannot widen access.
        service.sync(firstWorld, ChanponWorldProtectionMode.OWNER_ONLY).join()
        assertEquals(
            setOf(
                NodeSnapshot(PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION, false, firstWorld),
                NodeSnapshot(PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION, true, secondWorld),
            ),
            nodes,
        )

        service.sync(secondWorld, ChanponWorldProtectionMode.OWNER_ONLY).join()
        assertTrue(nodes.contains(
            NodeSnapshot(PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION, false, secondWorld),
        ))
        assertFalse(nodes.any { it.worldName == secondWorld && it.value })

        service.sync(firstWorld, ChanponWorldProtectionMode.MEMBERS).join()
        assertTrue(nodes.contains(
            NodeSnapshot(PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION, true, firstWorld),
        ))
        assertFalse(nodes.any { it.worldName == firstWorld && !it.value })
    }

    @Test
    fun `deleting a world removes only its contextual FAWE nodes`() {
        val nodes = linkedSetOf<NodeSnapshot>()
        val service = service(nodes)
        service.sync("my_world.first", ChanponWorldProtectionMode.PUBLIC).join()
        service.sync("my_world.second", ChanponWorldProtectionMode.OWNER_ONLY).join()

        service.clear("my_world.first").join()

        assertEquals(
            setOf(
                NodeSnapshot(
                    PublicWorldFawePermissionService.FAWE_WORLDGUARD_MEMBER_PERMISSION,
                    false,
                    "my_world.second",
                ),
            ),
            nodes,
        )
    }

    private fun service(nodes: MutableSet<NodeSnapshot>): PublicWorldFawePermissionService {
        val nodeMap = Proxy.newProxyInstance(
            NodeMap::class.java.classLoader,
            arrayOf(NodeMap::class.java),
        ) { _, method, arguments ->
            val node = arguments?.firstOrNull() as? Node
            when (method.name) {
                "add" -> if (nodes.add(snapshot(requireNotNull(node)))) {
                    DataMutateResult.SUCCESS
                } else {
                    DataMutateResult.FAIL_ALREADY_HAS
                }
                "remove" -> if (nodes.remove(snapshot(requireNotNull(node)))) {
                    DataMutateResult.SUCCESS
                } else {
                    DataMutateResult.FAIL_LACKS
                }
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

        return PublicWorldFawePermissionService(luckPerms, Logger.getAnonymousLogger()) { permission, worldName, value ->
            Proxy.newProxyInstance(
                PermissionNode::class.java.classLoader,
                arrayOf(PermissionNode::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getKey", "getPermission" -> permission
                    "getValue" -> value
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
