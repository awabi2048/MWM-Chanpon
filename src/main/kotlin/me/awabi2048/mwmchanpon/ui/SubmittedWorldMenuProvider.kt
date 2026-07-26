package me.awabi2048.mwmchanpon.ui
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.ProtectionReason
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuProvider
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class SubmittedWorldMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService
) : WorldSettingsMenuProvider {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private val requests = ConcurrentHashMap<UUID, WorldSettingsMenuRequest>()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE,
                renderer = { context -> render(context.player, context.route) },
                actions = mapOf(ACTION_BACK to MenuActionHandler(::handleReturn)),
            ),
        )
    }
    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, worldData: WorldData, request: WorldSettingsMenuRequest): Boolean {
        if (!storage.isProductionWorld(worldData.uuid)) return false
        val reasons = protectionService.protectionReasons(worldData)
        if (reasons.isEmpty()) return false
        val state = try {
            storage.getSubmissionState(worldData.uuid)
        } catch (ex: IllegalStateException) {
            SubmissionState.UNSUBMITTED
        }

        requests[player.uniqueId] = request
        runtime.open(
            player,
            MenuRoute(OWNER, ROUTE, mapOf(WORLD_UUID to worldData.uuid.toString())),
        )
        return true
    }

    private fun render(player: Player, route: MenuRoute): InventoryMenuView {
        val worldData = route.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }
        val elements = mutableListOf<MenuElement>()
        if (worldData != null) {
            val state = runCatching { storage.getSubmissionState(worldData.uuid) }
                .getOrDefault(SubmissionState.UNSUBMITTED)
            elements += MenuElement(
                22,
                createWorldItem(player, worldData, state, protectionService.protectionReasons(worldData)),
                GuiElementRole.CONTENT,
            )
        }
        if (requests[player.uniqueId]?.showBackButton == true) {
            elements += MenuElement(36, createReturnItem(player), GuiElementRole.BACK, ACTION_BACK)
        }
        return InventoryMenuView(
            ChanponGuiElements.settingsLayout().size,
            i18nComponent(player, "submitted_menu.title"),
            elements,
        )
    }

    private fun handleReturn(context: MenuActionContext): MenuActionResult {
        requests.remove(context.player.uniqueId)
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun createWorldItem(
        player: Player,
        worldData: WorldData,
        state: SubmissionState,
        reasons: Set<ProtectionReason>
    ): ItemStack {
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§e${worldData.name}"))
            meta.lore(ChanponLore.build(listOf(
                GuiLoreLine.Data(
                    i18n(player, "submitted_menu.state_label"),
                    i18n(player, "state.${state.i18nKey}"),
                    when (state) {
                        SubmissionState.UNSUBMITTED -> "§6"
                        SubmissionState.SUBMITTED -> "§a"
                        SubmissionState.COMPLETED -> "§b"
                    }
                ),
                GuiLoreLine.Data(i18n(player, "submitted_menu.reason_label"), protectionReasonText(player, reasons), "§f"),
                GuiLoreLine.UserText(worldData.description.ifBlank { "-" })
            )))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun protectionReasonText(player: Player, reasons: Set<ProtectionReason>): String {
        return reasons.joinToString("§7, ") { reason ->
            when (reason) {
                ProtectionReason.SUBMITTED -> i18n(player, "protection_reason.submitted")
                ProtectionReason.COMPLETED -> i18n(player, "protection_reason.completed")
                ProtectionReason.AUTO_ROLLBACK -> i18n(player, "protection_reason.auto_rollback")
            }
        }
    }

    private fun createReturnItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "submitted_menu.back.display"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.submission.$key", placeholders)
    }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    companion object {
        private const val PROVIDER_ID = "chanpon.submitted_world_menu"
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE = "mwm-chanpon:submitted-world"
        private const val ACTION_BACK = "back"
        private const val WORLD_UUID = "world_uuid"
    }
}
