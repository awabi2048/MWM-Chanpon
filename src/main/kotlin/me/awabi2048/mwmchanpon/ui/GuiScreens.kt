package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.model.CreationMethod
import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.model.TemplateDefinition
import me.awabi2048.mwmchanpon.model.WizardSession
import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.WorldTagService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

sealed class GuiInventoryHolder : InventoryHolder {
    protected lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

class MethodSelectionHolder(title: String, size: Int) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, size, title)
    }
}

class TemplateSelectionHolder(title: String) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 54, title)
    }
}

class ConfirmationHolder(title: String) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 45, title)
    }
}

class MyWorldListHolder(title: String, size: Int, val page: Int, val worldIds: List<String>, val fromMenuCommand: Boolean) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, size, title)
    }
}

class PersonalSettingsHolder(title: String, size: Int, val fromMenuCommand: Boolean) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, size, title)
    }
}

enum class WorldSettingsBackTarget {
    NONE,
    MENU_COMMAND,
    MYWORLD_LIST,
}

class WorldSettingsHolder(title: String, val worldId: String, val backTarget: WorldSettingsBackTarget, val myWorldPage: Int) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 54, title)
    }
}

class WorldEnvironmentHolder(title: String, val worldId: String, val backTarget: WorldSettingsBackTarget, val myWorldPage: Int) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 45, title)
    }
}

class WorldTagEditorHolder(title: String, val worldId: String, val backTarget: WorldSettingsBackTarget, val myWorldPage: Int) : GuiInventoryHolder() {
    val tagSlots = linkedMapOf<Int, String>()

    init {
        backingInventory = GuiComponents.createInventory(this, 45, title)
    }
}

class MemberManagementHolder(
    title: String,
    val worldId: String,
    val backTarget: WorldSettingsBackTarget,
    val myWorldPage: Int,
    val page: Int,
    val bodyRows: Int,
) : GuiInventoryHolder() {
    val slotEntries = linkedMapOf<Int, MemberManagementEntry>()

    init {
        backingInventory = GuiComponents.createInventory(this, GuiComponents.inventorySizeForBodyRows(bodyRows), title)
    }
}

class MemberRemoveConfirmHolder(
    title: String,
    val worldId: String,
    val targetUuid: String,
    val backTarget: WorldSettingsBackTarget,
    val myWorldPage: Int,
    val page: Int,
) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 27, title)
    }
}

class MemberInviteCancelConfirmHolder(
    title: String,
    val worldId: String,
    val inviteId: String,
    val targetUuid: String,
    val backTarget: WorldSettingsBackTarget,
    val myWorldPage: Int,
    val page: Int,
) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 27, title)
    }
}

data class PersonalSettingsMenuItem(
    val material: Material,
    val displayName: String,
    val lore: List<String>,
    val actionText: String,
)

object GuiScreens {
    fun createMethodSelection(player: Player, messages: LocalizationService, hasTemplates: Boolean, returnToMyWorld: Boolean): Inventory {
        val holder = MethodSelectionHolder(messages.getString(player, "gui.creation.method.title"), 45)
        GuiComponents.decorate(holder.inventory)
        holder.inventory.setItem(
            20,
            GuiComponents.button(
                Material.CHEST,
                messages.getString(player, "gui.creation.method.template.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.creation.method.template.description"))
                    .apply {
                        if (hasTemplates) {
                            singleAction(messages.getString(player, "gui.creation.method.template.action"))
                        } else {
                            warning(messages.getString(player, "gui.creation.method.template.unavailable"))
                        }
                    }
                    .build(),
            ),
        )
        holder.inventory.setItem(
            22,
            GuiComponents.button(
                Material.GRASS_BLOCK,
                messages.getString(player, "gui.creation.method.random.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.creation.method.random.description"))
                    .singleAction(messages.getString(player, "gui.creation.method.random.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            24,
            GuiComponents.button(
                Material.MANGROVE_SIGN,
                messages.getString(player, "gui.creation.method.seed.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.creation.method.seed.description"))
                    .singleAction(messages.getString(player, "gui.creation.method.seed.action"))
                    .build(),
            ),
        )
        val footerSlot = 40
        val displayName = if (returnToMyWorld) messages.getString(player, "gui.common.back") else messages.getString(player, "gui.common.cancel")
        holder.inventory.setItem(footerSlot, GuiComponents.backButton(displayName))
        return holder.inventory
    }

    fun createTemplateSelection(player: Player, messages: LocalizationService, templates: List<TemplateDefinition>): Inventory {
        val holder = TemplateSelectionHolder(messages.getString(player, "gui.creation.template.title"))
        GuiComponents.decorate(holder.inventory)
        templates.take(28).forEachIndexed { index, template ->
            val row = index / 7
            val column = index % 7
            val slot = 10 + column + row * 9
            holder.inventory.setItem(
                slot,
                GuiComponents.button(
                    template.icon,
                    template.name,
                    GuiLoreBuilder(messages, player)
                        .block(template.description.ifEmpty { listOf(template.path) })
                        .singleAction(messages.getString(player, "gui.creation.template.action"))
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(49, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createConfirmation(player: Player, messages: LocalizationService, session: WizardSession, templateName: String?): Inventory {
        val holder = ConfirmationHolder(messages.getString(player, "gui.creation.confirm.title"))
        GuiComponents.decorate(holder.inventory)
        val methodText = when (session.creationMethod) {
            CreationMethod.TEMPLATE -> messages.getString(player, "gui.creation.confirm.description_template", mapOf("template" to (templateName ?: session.templateKey.orEmpty())))
            CreationMethod.RANDOM -> messages.getString(player, "gui.creation.confirm.description_random")
            CreationMethod.SEED -> messages.getString(player, "gui.creation.confirm.description_seed", mapOf("seed" to session.seedText.orEmpty()))
            null -> ""
        }
        holder.inventory.setItem(
            22,
            GuiComponents.button(
                Material.BOOK,
                messages.getString(player, "gui.creation.confirm.display"),
                GuiLoreBuilder(messages, player)
                    .block(listOf(methodText, messages.getString(player, "gui.creation.confirm.name_line", mapOf("name" to session.worldName.orEmpty()))))
                    .singleAction(messages.getString(player, "gui.creation.confirm.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(40, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createMyWorldList(
        player: Player,
        messages: LocalizationService,
        worlds: List<MyWorldData>,
        page: Int,
        canCreateNewWorld: Boolean,
        ownedWorldCount: Int,
        fromMenuCommand: Boolean,
    ): Inventory {
        val pageSize = 28
        val pageCount = ((worlds.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val normalizedPage = page.coerceIn(0, pageCount - 1)
        val pageWorlds = worlds.drop(normalizedPage * pageSize).take(pageSize)
        val bodyRows = maxOf(1, (pageWorlds.size + 6) / 7)
        val holder = MyWorldListHolder(
            title = messages.getString(player, "gui.myworld.list.title"),
            size = GuiComponents.inventorySizeForBodyRows(bodyRows),
            page = normalizedPage,
            worldIds = pageWorlds.map { it.uuid.toString() },
            fromMenuCommand = fromMenuCommand,
        )
        GuiComponents.decorate(holder.inventory)

        GuiComponents.centeredBodySlots(bodyRows).zip(pageWorlds).forEach { (slot, worldData) ->
            holder.inventory.setItem(slot, GuiComponents.worldIconButton(messages, player, worldData))
        }

        val footerBase = GuiComponents.footerBase(bodyRows)
        if (fromMenuCommand) {
            holder.inventory.setItem(footerBase, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        }
        if (canCreateNewWorld) {
            holder.inventory.setItem(
                footerBase + 2,
                GuiComponents.button(
                    Material.NETHER_STAR,
                    messages.getString(player, "gui.myworld.list.create.display"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.myworld.list.create.action"))
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(
            footerBase + 4,
            GuiComponents.playerHeadButton(
                player,
                messages.getString(player, "gui.myworld.list.profile.display", mapOf("player" to player.name)),
                GuiLoreBuilder(messages, player)
                    .data(messages.getString(player, "gui.myworld.list.profile.owned"), "§f$ownedWorldCount")
                    .build(),
            ),
        )
        holder.inventory.setItem(
            footerBase + 6,
            GuiComponents.button(
                Material.WRITABLE_BOOK,
                messages.getString(player, "gui.personal_settings.title"),
                GuiLoreBuilder(messages, player)
                    .singleAction(messages.getString(player, "gui.myworld.list.personal_settings.action"))
                    .build(),
            ),
        )
        if (pageCount > 1 && normalizedPage > 0) {
            holder.inventory.setItem(
                footerBase + 7,
                GuiComponents.button(
                    Material.ARROW,
                    messages.getString(player, "gui.myworld.list.page.previous"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.myworld.list.page.previous_action"))
                        .build(),
                ),
            )
        }
        if (pageCount > 1 && normalizedPage < pageCount - 1) {
            holder.inventory.setItem(
                footerBase + 8,
                GuiComponents.button(
                    Material.SPECTRAL_ARROW,
                    messages.getString(player, "gui.myworld.list.page.next"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.myworld.list.page.next_action"))
                        .build(),
                ),
            )
        }
        return holder.inventory
    }

    fun createPersonalSettings(
        player: Player,
        messages: LocalizationService,
        items: List<PersonalSettingsMenuItem>,
        fromMenuCommand: Boolean,
    ): Inventory {
        val bodyRows = maxOf(1, (items.size + 6) / 7)
        val holder = PersonalSettingsHolder(
            title = messages.getString(player, "gui.personal_settings.title"),
            size = GuiComponents.inventorySizeForBodyRows(bodyRows),
            fromMenuCommand = fromMenuCommand,
        )
        GuiComponents.decorate(holder.inventory)
        GuiComponents.centeredBodySlots(bodyRows).zip(items).forEach { (slot, item) ->
            holder.inventory.setItem(
                slot,
                GuiComponents.button(
                    item.material,
                    item.displayName,
                    GuiLoreBuilder(messages, player)
                        .block(item.lore)
                        .singleAction(item.actionText)
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(GuiComponents.footerBase(bodyRows) + 4, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createWorldSettings(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        isBedrock: Boolean,
    ): Inventory {
        val holder = WorldSettingsHolder(
            title = messages.getString(player, "gui.world_settings.title", mapOf("world" to worldData.name)),
            worldId = worldData.uuid.toString(),
            backTarget = backTarget,
            myWorldPage = myWorldPage,
        )
        GuiComponents.decorate(holder.inventory)
        val isInWorld = player.world.name == worldData.customWorldName

        holder.inventory.setItem(
            19,
            GuiComponents.button(
                Material.NAME_TAG,
                messages.getString(player, "gui.world_settings.info.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.info.description",
                            mapOf("name" to worldData.name, "description" to worldData.description.ifBlank { messages.getString(player, "gui.world_settings.common.unset") }),
                        ),
                    )
                    .singleAction(messages.getString(player, "gui.world_settings.info.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            20,
            GuiComponents.button(
                Material.ITEM_FRAME,
                messages.getString(player, "gui.world_settings.icon.display"),
                GuiLoreBuilder(messages, player)
                    .componentBlock(
                        listOf(
                            GuiComponents.legacyText(messages.getString(player, "gui.world_settings.icon.current_prefix"))
                                .append(GuiComponents.materialNameComponent(worldData.icon)),
                            GuiComponents.legacyText(messages.getString(player, "gui.world_settings.icon.description_line")),
                        ),
                    )
                    .singleAction(messages.getString(player, "gui.world_settings.icon.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            21,
            GuiComponents.button(
                Material.COMPASS,
                messages.getString(player, "gui.world_settings.spawn.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            if (isBedrock) "gui.world_settings.spawn.description_bedrock" else "gui.world_settings.spawn.description",
                        ),
                    )
                    .apply {
                        if (!isInWorld) {
                            warning(messages.getString(player, "gui.world_settings.common.must_be_in_world"))
                        }
                    }
                    .apply {
                        if (isBedrock) {
                            singleAction(messages.getString(player, "gui.world_settings.spawn.bedrock_action"))
                        } else {
                            multiActions(
                                listOf(
                                    LoreAction(messages.getString(player, "gui.world_settings.click.left"), messages.getString(player, "gui.world_settings.spawn.left_action")),
                                    LoreAction(messages.getString(player, "gui.world_settings.click.right"), messages.getString(player, "gui.world_settings.spawn.right_action")),
                                ),
                            )
                        }
                    }
                    .build(),
            ),
        )
        holder.inventory.setItem(
            23,
            GuiComponents.button(
                Material.MAP,
                messages.getString(player, "gui.world_settings.expand.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.expand.description",
                            mapOf("size" to formatWorldSize(worldData.borderSize)),
                        ),
                    )
                    .apply {
                        if (!isInWorld) {
                            warning(messages.getString(player, "gui.world_settings.common.must_be_in_world"))
                        }
                    }
                    .singleAction(messages.getString(player, "gui.world_settings.expand.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            24,
            GuiComponents.button(
                Material.OAK_DOOR,
                messages.getString(player, "gui.world_settings.publish.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.publish.description",
                            mapOf(
                                "level" to GuiComponents.publishLevelText(messages, player, worldData.publishLevel),
                                "detail" to messages.getString(player, "gui.world_settings.publish.detail.${worldData.publishLevel.name.lowercase()}"),
                            ),
                        ),
                    )
                    .block(buildPublishLevelList(messages, player, worldData.publishLevel))
                    .apply {
                        if (isBedrock) {
                            singleAction(messages.getString(player, "gui.world_settings.publish.action_bedrock"))
                        } else {
                            multiActions(
                                listOf(
                                    LoreAction(messages.getString(player, "gui.world_settings.click.left"), messages.getString(player, "gui.world_settings.publish.action_next")),
                                    LoreAction(messages.getString(player, "gui.world_settings.click.right"), messages.getString(player, "gui.world_settings.publish.action_previous")),
                                ),
                            )
                        }
                    }
                    .build(),
            ),
        )
        holder.inventory.setItem(
            25,
            GuiComponents.button(
                Material.PLAYER_HEAD,
                messages.getString(player, "gui.world_settings.members.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.members.description",
                            mapOf(
                                "count" to (worldData.members.size + 1).toString(),
                                "access" to messages.getString(player, if (worldData.owner == player.uniqueId) "gui.world_settings.members.access_owner" else "gui.world_settings.members.access_member"),
                            ),
                        ),
                    )
                    .singleAction(messages.getString(player, if (worldData.owner == player.uniqueId) "gui.world_settings.members.action_owner" else "gui.world_settings.members.action_member"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            28,
            GuiComponents.button(
                Material.BOOK,
                messages.getString(player, "gui.world_settings.tags.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.tags.description",
                            mapOf(
                                "count" to worldData.tags.size.toString(),
                                "tags" to formatTags(messages, player, worldData.tags),
                            ),
                        ),
                    )
                    .singleAction(messages.getString(player, "gui.world_settings.tags.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            29,
            GuiComponents.button(
                Material.OAK_SIGN,
                messages.getString(player, "gui.world_settings.announcement.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.announcement.description",
                            mapOf("message" to formatAnnouncement(messages, player, worldData.announcementMessages)),
                        ),
                    )
                    .singleAction(messages.getString(player, "gui.world_settings.announcement.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            30,
            GuiComponents.button(
                Material.BELL,
                messages.getString(player, "gui.world_settings.notification.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        messages.getStringList(
                            player,
                            "gui.world_settings.notification.description",
                            mapOf("status" to messages.getString(player, if (worldData.notificationEnabled) "gui.common.state.on" else "gui.common.state.off")),
                        ),
                    )
                    .singleAction(messages.getString(player, "gui.world_settings.notification.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            32,
            GuiComponents.button(
                Material.GRASS_BLOCK,
                messages.getString(player, "gui.world_settings.environment.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.world_settings.environment.description"))
                    .apply {
                        if (!isInWorld) {
                            warning(messages.getString(player, "gui.world_settings.common.must_be_in_world"))
                        }
                    }
                    .singleAction(messages.getString(player, "gui.world_settings.environment.action"))
                    .build(),
            ),
        )

        if (backTarget != WorldSettingsBackTarget.NONE) {
            holder.inventory.setItem(45, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        }
        holder.inventory.setItem(49, GuiComponents.worldIconButton(messages, player, worldData, showActions = false))
        return holder.inventory
    }

    fun createWorldTagEditor(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        worldTagService: WorldTagService,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
    ): Inventory {
        val holder = WorldTagEditorHolder(
            title = messages.getString(player, "gui.tag_editor.title", mapOf("world" to worldData.name)),
            worldId = worldData.uuid.toString(),
            backTarget = backTarget,
            myWorldPage = myWorldPage,
        )
        GuiComponents.decorate(holder.inventory)
        worldTagService.getEditableTagIds(worldData.tags).take(7).forEachIndexed { index, tagId ->
            val slot = 10 + index
            val selected = worldData.tags.contains(tagId)
            holder.tagSlots[slot] = tagId
            holder.inventory.setItem(
                slot,
                GuiComponents.button(
                    if (selected) Material.ENCHANTED_BOOK else Material.BOOK,
                    (if (selected) "§a" else "§7") + worldTagService.getDisplayName(player, tagId),
                    GuiLoreBuilder(messages, player)
                        .data(messages.getString(player, "gui.tag_editor.status"), messages.getString(player, if (selected) "gui.tag_editor.status_active" else "gui.tag_editor.status_inactive"))
                        .singleAction(messages.getString(player, "gui.tag_editor.click_toggle"))
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(22, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createWorldEnvironmentSettings(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
    ): Inventory {
        val holder = WorldEnvironmentHolder(
            title = messages.getString(player, "gui.world_environment.title", mapOf("world" to worldData.name)),
            worldId = worldData.uuid.toString(),
            backTarget = backTarget,
            myWorldPage = myWorldPage,
        )
        GuiComponents.decorate(holder.inventory)
        holder.inventory.setItem(
            20,
            GuiComponents.button(
                Material.WATER_BUCKET,
                messages.getString(player, "gui.world_environment.weather.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.world_environment.weather.description"))
                    .singleAction(messages.getString(player, "gui.world_environment.weather.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            24,
            GuiComponents.button(
                Material.CLOCK,
                messages.getString(player, "gui.world_environment.time.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.world_environment.time.description"))
                    .singleAction(messages.getString(player, "gui.world_environment.time.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(40, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createMemberManagement(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        memberManagementService: me.awabi2048.mwmchanpon.service.MemberManagementService,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
        isBedrock: Boolean,
    ): Inventory {
        val entries = memberManagementService.buildEntries(worldData)
        val pageSize = 28
        val pageCount = ((entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val normalizedPage = page.coerceIn(0, pageCount - 1)
        val pageEntries = entries.drop(normalizedPage * pageSize).take(pageSize)
        val bodyRows = maxOf(1, (pageEntries.size + 6) / 7)
        val holder = MemberManagementHolder(
            title = messages.getString(player, "gui.member_management.title", mapOf("world" to worldData.name)),
            worldId = worldData.uuid.toString(),
            backTarget = backTarget,
            myWorldPage = myWorldPage,
            page = normalizedPage,
            bodyRows = bodyRows,
        )
        GuiComponents.decorate(holder.inventory)

        val canManage = memberManagementService.canManage(player, worldData)
        val canForceAdd = canManage && memberManagementService.canForceAdd(player)

        GuiComponents.centeredBodySlots(bodyRows).zip(pageEntries).forEach { (slot, entry) ->
            holder.slotEntries[slot] = entry
            holder.inventory.setItem(
                slot,
                buildMemberItem(player, messages, worldData, entry, memberManagementService, canManage),
            )
        }

        val footerBase = GuiComponents.footerBase(bodyRows)
        holder.inventory.setItem(footerBase, GuiComponents.backButton(messages.getString(player, "gui.common.back")))

        holder.inventory.setItem(
            footerBase + 4,
            GuiComponents.button(
                Material.PAPER,
                messages.getString(player, "gui.member_management.invite.display"),
                GuiLoreBuilder(messages, player)
                    .block(
                        if (canManage) {
                            buildList {
                                add(messages.getString(player, "gui.member_management.invite.description"))
                                add(messages.getString(player, "gui.member_management.invite.online_only"))
                                add(
                                    if (canForceAdd) {
                                        messages.getString(player, "gui.member_management.invite.force_available")
                                    } else {
                                        messages.getString(player, "gui.member_management.invite.normal_only")
                                    },
                                )
                            }
                        } else {
                            listOf(messages.getString(player, "gui.member_management.invite.view_only"))
                        },
                    )
                    .singleAction(
                        if (canManage) {
                            messages.getString(player, "gui.member_management.invite.action")
                        } else {
                            messages.getString(player, "gui.member_management.invite.view_only_action")
                        },
                    )
                    .build(),
            ),
        )

        if (pageCount > 1 && normalizedPage > 0) {
            holder.inventory.setItem(
                footerBase + 1,
                GuiComponents.button(
                    Material.ARROW,
                    messages.getString(player, "gui.member_management.page.previous"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.member_management.page.previous_action"))
                        .build(),
                ),
            )
        }
        if (pageCount > 1 && normalizedPage < pageCount - 1) {
            holder.inventory.setItem(
                footerBase + 8,
                GuiComponents.button(
                    Material.SPECTRAL_ARROW,
                    messages.getString(player, "gui.member_management.page.next"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.member_management.page.next_action"))
                        .build(),
                ),
            )
        }
        return holder.inventory
    }

    fun createMemberRemoveConfirm(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        targetUuid: java.util.UUID,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
    ): Inventory {
        val holder = MemberRemoveConfirmHolder(
            title = messages.getString(player, "gui.member_management.remove_confirm.title"),
            worldId = worldData.uuid.toString(),
            targetUuid = targetUuid.toString(),
            backTarget = backTarget,
            myWorldPage = myWorldPage,
            page = page,
        )
        GuiComponents.decorate(holder.inventory)
        val targetName = Bukkit.getOfflinePlayer(targetUuid).name ?: targetUuid.toString()
        holder.inventory.setItem(
            13,
            GuiComponents.playerHeadButton(
                Bukkit.getOfflinePlayer(targetUuid),
                messages.getString(player, "gui.member_management.remove_confirm.player_name", mapOf("player" to targetName)),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.member_management.remove_confirm.lore", mapOf("player" to targetName, "world" to worldData.name)))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            11,
            GuiComponents.button(
                Material.LIME_WOOL,
                messages.getString(player, "gui.member_management.remove_confirm.cancel"),
                emptyList(),
            ),
        )
        holder.inventory.setItem(
            15,
            GuiComponents.button(
                Material.RED_WOOL,
                messages.getString(player, "gui.member_management.remove_confirm.confirm"),
                GuiLoreBuilder(messages, player)
                    .singleAction(messages.getString(player, "gui.member_management.remove_confirm.confirm_desc"))
                    .build(),
            ),
        )
        return holder.inventory
    }

    fun createMemberInviteCancelConfirm(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        targetUuid: java.util.UUID,
        inviteId: java.util.UUID,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
    ): Inventory {
        val holder = MemberInviteCancelConfirmHolder(
            title = messages.getString(player, "gui.member_management.pending_cancel_confirm.title"),
            worldId = worldData.uuid.toString(),
            inviteId = inviteId.toString(),
            targetUuid = targetUuid.toString(),
            backTarget = backTarget,
            myWorldPage = myWorldPage,
            page = page,
        )
        GuiComponents.decorate(holder.inventory)
        val targetName = Bukkit.getOfflinePlayer(targetUuid).name ?: targetUuid.toString()
        holder.inventory.setItem(
            13,
            GuiComponents.playerHeadButton(
                Bukkit.getOfflinePlayer(targetUuid),
                messages.getString(player, "gui.member_management.pending_item.name", mapOf("player" to targetName)),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.member_management.pending_cancel_confirm.body", mapOf("player" to targetName)))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            11,
            GuiComponents.button(
                Material.LIME_WOOL,
                messages.getString(player, "gui.member_management.pending_cancel_confirm.cancel"),
                emptyList(),
            ),
        )
        holder.inventory.setItem(
            15,
            GuiComponents.button(
                Material.RED_WOOL,
                messages.getString(player, "gui.member_management.pending_cancel_confirm.confirm"),
                GuiLoreBuilder(messages, player)
                    .singleAction(messages.getString(player, "gui.member_management.pending_cancel_confirm.confirm_desc"))
                    .build(),
            ),
        )
        return holder.inventory
    }

    private fun buildMemberItem(
        player: Player,
        messages: LocalizationService,
        worldData: MyWorldData,
        entry: MemberManagementEntry,
        memberManagementService: me.awabi2048.mwmchanpon.service.MemberManagementService,
        canManage: Boolean,
    ): org.bukkit.inventory.ItemStack {
        val offlinePlayer = Bukkit.getOfflinePlayer(entry.playerUuid)
        val displayName = memberManagementService.resolveDisplayName(entry.playerUuid)
        val isOnline = memberManagementService.isOnline(entry.playerUuid)
        val roleKey = when (entry.type) {
            MemberManagementEntryType.OWNER -> "gui.member_management.role.owner"
            MemberManagementEntryType.MEMBER -> "gui.member_management.role.member"
            MemberManagementEntryType.INVITE -> "gui.member_management.role.invite"
        }
        val statusKey = if (isOnline) "gui.member_management.item.online" else "gui.member_management.item.offline"
        val lore = mutableListOf<String>()
        lore += "§f§l| §7${messages.getString(player, "gui.member_management.item.status")} §f${messages.getString(player, statusKey)}"
        if (entry.type == MemberManagementEntryType.INVITE && entry.createdAt != null) {
            lore += "§f§l| §7${messages.getString(player, "gui.member_management.item.sent_at")} §f${memberManagementService.formatInviteTime(player, entry.createdAt)}"
        } else if (entry.type != MemberManagementEntryType.INVITE && !isOnline) {
            val lastSeen = offlinePlayer.lastSeen
            lore += if (lastSeen > 0L) {
                "§f§l| §7${messages.getString(player, "gui.member_management.item.last_online")} §f${memberManagementService.formatInviteTime(player, lastSeen)}"
            } else {
                "§f§l| §7${messages.getString(player, "gui.member_management.item.last_online")} §f-"
            }
        }
        lore += "§f§l| §7${messages.getString(player, "gui.member_management.item.role")} §f${messages.getString(player, roleKey)}"

        if (canManage) {
            when (entry.type) {
                MemberManagementEntryType.MEMBER -> lore += messages.getString(player, "gui.member_management.item.remove_action")
                MemberManagementEntryType.INVITE -> lore += messages.getString(player, "gui.member_management.item.cancel_action")
                MemberManagementEntryType.OWNER -> Unit
            }
        }

        val color = when {
            entry.type == MemberManagementEntryType.OWNER -> "§6"
            isOnline -> "§a"
            else -> "§7"
        }
        val titleKey = when (entry.type) {
            MemberManagementEntryType.OWNER -> "gui.member_management.item.owner_name"
            MemberManagementEntryType.MEMBER -> "gui.member_management.item.member_name"
            MemberManagementEntryType.INVITE -> "gui.member_management.item.invite_name"
        }
        return GuiComponents.playerHeadButton(
            offlinePlayer,
            "$color${messages.getString(player, titleKey, mapOf("player" to displayName))}",
            GuiLoreBuilder(messages, player).block(lore).build(),
        )
    }

    private fun buildPublishLevelList(messages: LocalizationService, player: Player, current: me.awabi2048.mwmchanpon.model.WorldPublishLevel): List<String> {
        return me.awabi2048.mwmchanpon.model.WorldPublishLevel.entries.map { level ->
            val key = "gui.world_settings.publish.option.${level.name.lowercase()}"
            if (level == current) {
                messages.getString(player, "gui.world_settings.publish.option_active", mapOf("level" to messages.getString(player, key)))
            } else {
                messages.getString(player, "gui.world_settings.publish.option_inactive", mapOf("level" to messages.getString(player, key)))
            }
        }
    }

    private fun formatTags(messages: LocalizationService, player: Player, tags: List<String>): String {
        return if (tags.isEmpty()) {
            messages.getString(player, "gui.world_settings.common.unset")
        } else {
            tags.joinToString(", ")
        }
    }

    private fun formatAnnouncement(messages: LocalizationService, player: Player, lines: List<String>): String {
        return if (lines.isEmpty()) {
            messages.getString(player, "gui.world_settings.common.unset")
        } else {
            lines.joinToString(" / ")
        }
    }

    private fun formatWorldSize(size: Double): String {
        return if (size % 1.0 == 0.0) {
            size.toInt().toString()
        } else {
            "%.1f".format(java.util.Locale.US, size)
        }
    }
}
