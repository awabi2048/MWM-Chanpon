package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.extension.CreationConfirmationMenuProvider
import me.awabi2048.myworldmanager.session.WorldCreationSession
import me.awabi2048.myworldmanager.session.WorldCreationType
import me.awabi2048.myworldmanager.util.GuiHelper
import me.awabi2048.myworldmanager.util.GuiItemFactory
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ChanponCreationConfirmationMenuProvider(
    private val productionToggle: ProductionToggleExtension
) : CreationConfirmationMenuProvider {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_CONFIRMATION,
                renderer = { context ->
                    val session = mwm().creationSessionManager.getSession(context.player.uniqueId)
                    if (session == null) {
                        InventoryMenuView(9, Component.text("Creation"), emptyList())
                    } else {
                        render(context.player, session)
                    }
                },
                actions = mapOf(
                    ACTION_SELECT to MenuActionHandler { context ->
                        val player = context.player
                        val session = mwm().creationSessionManager.getSession(player.uniqueId)
                            ?: return@MenuActionHandler MenuActionResult.Rejected()
                        if (ItemTag.getExtensionId(context.item) == ProductionToggleExtension.EXTENSION_ID) {
                            productionToggle.toggleCreationSelection(player, session)
                            MenuActionResult.Success(MenuUpdate.Refresh)
                        } else if (mwm().creationGuiListener.handleConfirmationClick(
                                player,
                                context.click,
                                context.item,
                            )
                        ) {
                            MenuActionResult.Success(MenuUpdate.None)
                        } else {
                            MenuActionResult.Ignored
                        }
                    },
                ),
            ),
        )
    }

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, session: WorldCreationSession): Boolean {
        runtime.open(player, MenuRoute(RUNTIME_OWNER, ROUTE_CONFIRMATION))
        return true
    }

    private fun render(player: Player, session: WorldCreationSession): InventoryMenuView {
        val mwm = mwm()
        val lang = mwm.languageManager
        val title = GuiHelper.inventoryTitle(lang.getMessage(player, "gui.creation.title_confirm"))
        val layout = GuiHelper.confirmationLayout()
        val elements = mutableListOf<MenuElement>()

        val cleanName = (session.worldName ?: lang.getMessage(player, "general.unknown"))
            .replace(Regex("\\s?\\(.*?\\)"), "")
            .trim()
        val generationLine = when (session.creationType) {
            WorldCreationType.TEMPLATE -> {
                val template = session.templateId?.let(mwm.templateRepository::findById)
                GuiLoreLine.SubData(
                    lang.getMessage(player, "gui.creation.confirm.template_label"),
                    template?.name ?: session.templateId.orEmpty()
                )
            }
            WorldCreationType.SEED -> GuiLoreLine.SubData(
                lang.getMessage(player, "gui.creation.confirm.seed_label"),
                session.inputSeedString.orEmpty()
            )
            WorldCreationType.RANDOM -> GuiLoreLine.SubData(
                lang.getMessage(player, "gui.creation.confirm.generation_label"),
                lang.getMessage(player, "gui.creation.confirm.random_generation")
            )
            null -> GuiLoreLine.SubData(
                lang.getMessage(player, "gui.creation.confirm.generation_label"),
                lang.getMessage(player, "general.unknown")
            )
        }
        val infoLines = buildList {
            add(GuiLoreLine.Data(
                lang.getMessage(player, "gui.creation.confirm.world_name_label"),
                cleanName,
                "§e"
            ))
            add(generationLine)
            if (session.creationType == WorldCreationType.TEMPLATE) {
                val origin = session.templateId
                    ?.let(mwm.templateRepository::findById)
                    ?.originLocation
                add(GuiLoreLine.SubData(
                    lang.getMessage(player, "gui.creation.confirm.template_spawn_label"),
                    origin?.let { "(${it.blockX}, ${it.blockY}, ${it.blockZ})" }
                        ?: lang.getMessage(player, "general.unknown")
                ))
            }
            if (session.creationType == WorldCreationType.SEED) {
                add(GuiLoreLine.SubData(
                    lang.getMessage(player, "gui.creation.confirm.dimension_label"),
                    environmentName(player, session.seedEnvironment)
                ))
            }
        }
        elements += MenuElement(
            layout.previewSlot,
            item(
                Material.PAPER,
                lang.getMessage(player, "gui.creation.confirm.name"),
                ItemTag.TYPE_GUI_INFO,
                GuiLoreSpec.Rich(infoLines, GuiLoreFrame.BOTH)
            ),
            GuiElementRole.CONTENT,
        )
        elements += MenuElement(
            layout.confirmSlot,
            item(
                Material.LIME_CONCRETE,
                lang.getMessage(player, "gui.common.confirm"),
                ItemTag.TYPE_GUI_CONFIRM
            ),
            GuiElementRole.CONFIRM,
            ACTION_SELECT,
        )
        elements += MenuElement(
            layout.cancelSlot,
            item(
                Material.RED_CONCRETE,
                lang.getMessage(player, "gui.common.cancel"),
                ItemTag.TYPE_GUI_CANCEL
            ),
            GuiElementRole.CANCEL,
            ACTION_SELECT,
        )

        when (session.creationType) {
            WorldCreationType.SEED -> {
                elements += actionElement(39, dimensionItem(player, session))
                elements += actionElement(
                    40,
                    productionToggle.createCreationItem(
                        session.extras[ProductionToggleExtension.EXTRA_KEY] as? Boolean ?: false
                    ),
                )
                elements += actionElement(41, spawnItem(player, session))
            }
            WorldCreationType.TEMPLATE -> {
                elements += actionElement(38, item(
                    Material.ENDER_EYE,
                    lang.getMessage(player, "gui.creation.template_detail.preview"),
                    ItemTag.TYPE_GUI_CREATION_TEMPLATE_PREVIEW
                ))
                elements += actionElement(39, item(
                    Material.NAME_TAG,
                    lang.getMessage(player, "gui.creation.confirm.change_name"),
                    ItemTag.TYPE_GUI_BACK
                ))
                elements += actionElement(
                    40,
                    productionToggle.createCreationItem(
                        session.extras[ProductionToggleExtension.EXTRA_KEY] as? Boolean ?: false
                    ),
                )
                elements += actionElement(41, item(
                    Material.MAP,
                    lang.getMessage(player, "gui.creation.confirm.change_template"),
                    ItemTag.TYPE_GUI_CREATION_TEMPLATE_CHANGE
                ))
            }
            WorldCreationType.RANDOM, null -> {
                elements += actionElement(
                    40,
                    productionToggle.createCreationItem(
                        session.extras[ProductionToggleExtension.EXTRA_KEY] as? Boolean ?: false
                    ),
                )
            }
        }

        return InventoryMenuView(layout.size, title, elements)
    }

    private fun dimensionItem(player: Player, session: WorldCreationSession): ItemStack {
        val lang = mwm().languageManager
        val options = listOf(
            World.Environment.NORMAL to "§a",
            World.Environment.NETHER to "§c",
            World.Environment.THE_END to "§5"
        )
        val lore = GuiLoreSpec.Rich(buildList {
            add(GuiLoreLine.Data(
                lang.getMessage(player, "gui.creation.confirm.dimension.current_label"),
                environmentName(player, session.seedEnvironment),
                options.first { it.first == session.seedEnvironment }.second
            ))
            add(GuiLoreLine.Text(lang.getMessage(player, "gui.creation.confirm.dimension.description")))
            add(GuiLoreLine.Spacer)
            options.forEach { (environment, color) ->
                add(GuiLoreLine.Option(
                    environmentName(player, environment),
                    environment == session.seedEnvironment,
                    color,
                    "§7"
                ))
            }
            add(GuiLoreLine.Spacer)
            addAll(GuiLoreActions.cyclePreviousNext(lang, player))
        }, GuiLoreFrame.BOTH)
        val material = when (session.seedEnvironment) {
            World.Environment.NORMAL -> Material.GRASS_BLOCK
            World.Environment.NETHER -> Material.NETHERRACK
            World.Environment.THE_END -> Material.END_STONE
            else -> Material.GRASS_BLOCK
        }
        return item(
            material,
            lang.getMessage(player, "gui.creation.confirm.dimension.display"),
            ItemTag.TYPE_GUI_CREATION_DIMENSION,
            lore
        )
    }

    private fun spawnItem(player: Player, session: WorldCreationSession): ItemStack {
        val lang = mwm().languageManager
        val coordinates = session.spawnCoordinates
            ?.let { "§6(${it.x}, ${it.y}, ${it.z})" }
            ?: "§b${lang.getMessage(player, "gui.creation.confirm.spawn_location.default")}"
        return item(
            Material.COMPASS,
            lang.getMessage(player, "gui.creation.confirm.spawn_location.display"),
            ItemTag.TYPE_GUI_CREATION_SPAWN_LOCATION,
            GuiLoreSpec.Blocks(listOf(GuiLoreBlock(listOf(
                GuiLoreLine.Text(lang.getMessage(player, "gui.creation.confirm.spawn_location.description")),
                GuiLoreLine.Text(lang.getMessage(player, "gui.creation.confirm.spawn_location.default_help")),
                GuiLoreLine.Data(
                    lang.getMessage(player, "gui.creation.confirm.spawn_location.current_label"),
                    coordinates,
                    ""
                ),
                GuiLoreActions.singleClick(
                    lang,
                    player,
                    lang.getMessage(player, "gui.creation.confirm.spawn_location.action")
                )
            ))))
        )
    }

    private fun environmentName(player: Player, environment: World.Environment): String {
        val suffix = when (environment) {
            World.Environment.NORMAL -> "normal"
            World.Environment.NETHER -> "nether"
            World.Environment.THE_END -> "the_end"
            else -> "normal"
        }
        return mwm().languageManager.getMessage(
            player,
            "gui.creation.confirm.dimension.options.$suffix"
        )
    }

    private fun item(
        material: Material,
        name: String,
        tag: String,
        lore: GuiLoreSpec = GuiLoreSpec.None
    ): ItemStack = GuiItemFactory.item(material, name, lore, tag)

    private fun actionElement(slot: Int, item: ItemStack): MenuElement =
        MenuElement(slot, item, GuiElementRole.ACTION, ACTION_SELECT)

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    companion object {
        private const val PROVIDER_ID = "chanpon.creation_confirmation"
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_CONFIRMATION = "creation-confirmation"
        private const val ACTION_SELECT = "select"
    }
}
