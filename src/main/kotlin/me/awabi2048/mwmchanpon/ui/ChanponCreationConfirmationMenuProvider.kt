package me.awabi2048.mwmchanpon.ui
import me.awabi2048.mwmchanpon.ui.ManagedMenuPresenter

import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.extension.CreationConfirmationMenuProvider
import me.awabi2048.myworldmanager.gui.CreationGui
import me.awabi2048.myworldmanager.gui.CreationGui.CreationMenuType
import me.awabi2048.myworldmanager.session.WorldCreationSession
import me.awabi2048.myworldmanager.session.WorldCreationType
import me.awabi2048.myworldmanager.util.GuiHelper
import me.awabi2048.myworldmanager.util.GuiItemFactory
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ChanponCreationConfirmationMenuProvider(
    private val productionToggle: ProductionToggleExtension
) : CreationConfirmationMenuProvider, Listener {

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, session: WorldCreationSession): Boolean {
        val mwm = mwm()
        val lang = mwm.languageManager
        val holder = CreationGui.CreationGuiHolder(CreationMenuType.CONFIRM)
        val title = GuiHelper.inventoryTitle(lang.getMessage(player, "gui.creation.title_confirm"))
        val inventory = GuiHelper.createConfirmationInventory(holder, title)
        holder.inv = inventory
        GuiHelper.applyConfirmationFrame(inventory)

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
        GuiHelper.setConfirmationItems(
            inventory,
            item(
                Material.PAPER,
                lang.getMessage(player, "gui.creation.confirm.name"),
                ItemTag.TYPE_GUI_INFO,
                GuiLoreSpec.Rich(infoLines, GuiLoreFrame.BOTH)
            ),
            item(
                Material.LIME_CONCRETE,
                lang.getMessage(player, "gui.common.confirm"),
                ItemTag.TYPE_GUI_CONFIRM
            ),
            item(
                Material.RED_CONCRETE,
                lang.getMessage(player, "gui.common.cancel"),
                ItemTag.TYPE_GUI_CANCEL
            )
        )

        when (session.creationType) {
            WorldCreationType.SEED -> {
                inventory.setItem(39, dimensionItem(player, session))
                inventory.setItem(40, productionToggle.createCreationItem(
                    session.extras[ProductionToggleExtension.EXTRA_KEY] as? Boolean ?: false
                ))
                inventory.setItem(41, spawnItem(player, session))
            }
            WorldCreationType.TEMPLATE -> {
                inventory.setItem(38, item(
                    Material.ENDER_EYE,
                    lang.getMessage(player, "gui.creation.template_detail.preview"),
                    ItemTag.TYPE_GUI_CREATION_TEMPLATE_PREVIEW
                ))
                inventory.setItem(39, item(
                    Material.NAME_TAG,
                    lang.getMessage(player, "gui.creation.confirm.change_name"),
                    ItemTag.TYPE_GUI_BACK
                ))
                inventory.setItem(40, productionToggle.createCreationItem(
                    session.extras[ProductionToggleExtension.EXTRA_KEY] as? Boolean ?: false
                ))
                inventory.setItem(41, item(
                    Material.MAP,
                    lang.getMessage(player, "gui.creation.confirm.change_template"),
                    ItemTag.TYPE_GUI_CREATION_TEMPLATE_CHANGE
                ))
            }
            WorldCreationType.RANDOM, null -> {
                inventory.setItem(40, productionToggle.createCreationItem(
                    session.extras[ProductionToggleExtension.EXTRA_KEY] as? Boolean ?: false
                ))
            }
        }

        mwm.soundManager.playMenuOpenSound(player, "creation")
        ManagedMenuPresenter.open(player, inventory)
        return true
    }

    @EventHandler(ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.topInventory.holder !is CreationGui.CreationGuiHolder) return
        if (event.clickedInventory != event.view.topInventory) return
        val clicked = event.currentItem ?: return
        if (ItemTag.getExtensionId(clicked) != ProductionToggleExtension.EXTENSION_ID) return
        event.cancelWithDebug("ChanponCreationConfirmationMenuProvider.onClick: production_toggle")
        val session = mwm().creationSessionManager.getSession(player.uniqueId) ?: return
        event.inventory.setItem(event.slot, productionToggle.toggleCreationSelection(player, session))
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

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    companion object {
        private const val PROVIDER_ID = "chanpon.creation_confirmation"
    }
}
