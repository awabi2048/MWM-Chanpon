package me.awabi2048.mwmchanpon.tour

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuConfirmationDraft
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponPreviewTourKeys
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import io.papermc.paper.event.player.PlayerOpenSignEvent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * プレビューツアーの看板を介した開始・登録・撤去と、参加者の離脱系イベントを扱います。
 *
 * 看板は「ツアー看板アイテム」を設置して登録し、ブロック側PDCと登録情報の両方で識別します。
 */
class PreviewTourListener(
    private val plugin: MWMChanponPlugin,
    private val storage: PreviewTourStorage,
    private val service: PreviewTourService,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val signBlock = event.clickedBlock ?: return
        if (!Tag.ALL_SIGNS.isTagged(signBlock.type)) return
        val sign = resolveSign(signBlock) ?: return
        event.isCancelled = true

        val player = event.player
        if (service.isParticipant(player.uniqueId)) {
            player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_ALREADY_IN_TOUR))
            player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_LEAVE_HINT))
            return
        }
        val tour = storage.getTour(sign.tourUuid)
        if (tour == null) {
            // ツアー削除後に残った看板は入口として機能しません。
            player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_TOUR_DELETED))
            return
        }
        openStartConfirm(player, tour)
    }

    @EventHandler(ignoreCancelled = true)
    fun onSignPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        val tourUuid = item.itemMeta
            ?.persistentDataContainer
            ?.get(SIGN_ITEM_KEY, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        val tour = storage.getTour(tourUuid)
        if (tour == null) {
            event.isCancelled = true
            event.player.sendMessage(i18nComponent(event.player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_TOUR_DELETED))
            return
        }
        if (!event.player.hasPermission(ADMIN_PERMISSION)) {
            event.isCancelled = true
            event.player.sendMessage(i18nComponent(event.player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_SIGN_NO_PERMISSION))
            return
        }
        val signData = PreviewTourSignData(
            uuid = UUID.randomUUID(),
            tourUuid = tourUuid,
            worldName = event.blockPlaced.world.name,
            x = event.blockPlaced.x,
            y = event.blockPlaced.y,
            z = event.blockPlaced.z,
        )
        storage.saveSign(signData)
        // 表示テキストは登録時に固定で書き込み、以後の看板編集画面は開かせません。
        (event.blockPlaced.state as? Sign)?.let { state ->
            state.persistentDataContainer.set(SIGN_BLOCK_KEY, PersistentDataType.STRING, signData.uuid.toString())
            writeSignLines(state, tour.title)
            state.update()
        }
        event.player.sendMessage(
            i18nComponent(event.player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_SIGN_REGISTERED, mapOf("tour" to tour.title)),
        )
    }

    /**
     * 登録済みツアー看板は編集画面を開かせません。
     * 設置直後に出る編集画面もここで抑止するため、設置時に書いたテキストがそのまま残ります。
     */
    @EventHandler(ignoreCancelled = true)
    fun onOpenSign(event: PlayerOpenSignEvent) {
        if (resolveSign(event.sign.block) != null) {
            event.isCancelled = true
        }
    }

    /** 編集画面経由以外からのテキスト変更も受け付けず、登録時の表示を維持します。 */
    @EventHandler(ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (resolveSign(event.block) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val sign = resolveSign(event.block) ?: return
        if (!event.player.hasPermission(ADMIN_PERMISSION)) {
            event.isCancelled = true
            event.player.sendMessage(i18nComponent(event.player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_SIGN_NO_PERMISSION))
            return
        }
        storage.deleteSign(sign.uuid)
        event.player.sendMessage(i18nComponent(event.player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_SIGN_BROKEN))
    }

    /**
     * MWMの終了時位置保存（MONITOR）より先に開始位置へ戻します。
     * 先にテレポートしておくと、MWMが記録する終了位置も帰還後の場所になります。
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onQuit(event: PlayerQuitEvent) {
        service.handleQuit(event.player)
    }

    private fun openStartConfirm(player: Player, tour: PreviewTourData) {
        val totalDuration = PreviewTourService.formatDuration(tour.totalDurationSeconds)
        val dataLines = listOf(
            GuiLoreLine.Text(
                "§7" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_STOPS) +
                    ": §f" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_FIELD_STOP_COUNT_VALUE, mapOf("count" to tour.stops.size)),
            ),
            GuiLoreLine.Text(
                "§7" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_DURATION) +
                    ": §f" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_FIELD_TOTAL_DURATION_VALUE, mapOf("duration" to totalDuration)),
            ),
        )
        val descriptionLines = tour.description.takeIf { it.isNotBlank() }
            ?.lines()
            ?.map { GuiLoreLine.Text("§7$it") }
            .orEmpty()
        val previewItem = GuiItemSpec(
            material = Material.FILLED_MAP,
            name = GuiNameSpec.FixedLabel(Component.text("§b${tour.title}")),
            lore = GuiLoreSpec.Blocks(listOf(GuiLoreBlock(descriptionLines + dataLines))),
            role = GuiElementRole.CONTENT,
            amount = 1,
        )
        val confirmItem = GuiItemSpec(
            material = Material.LIME_CONCRETE,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_START_CONFIRM)),
            lore = GuiLoreSpec.None,
            role = GuiElementRole.CONFIRM,
            amount = 1,
        )
        val cancelItem = GuiItemSpec(
            material = Material.RED_CONCRETE,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_START_CANCEL)),
            lore = GuiLoreSpec.None,
            role = GuiElementRole.CANCEL,
            amount = 1,
        )
        CCSystem.getAPI().getMenuConfirmationService().open(
            player,
            MenuConfirmationDraft(
                owner = OWNER,
                menuId = "preview_tour_start",
                title = i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_START_TITLE),
                previewItem = previewItem,
                confirmItem = confirmItem,
                cancelItem = cancelItem,
                confirmActionText = i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_START_CONFIRM_ACTION),
                cancelActionText = i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_START_CANCEL_ACTION),
                onConfirm = {
                    when (service.startRun(tour, player)) {
                        PreviewTourService.StartResult.STARTED -> MenuActionResult.Success(MenuUpdate.Close)
                        PreviewTourService.StartResult.NO_STOPS -> MenuActionResult.Rejected(
                            Component.text(i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_NO_STOPS)),
                        )
                        PreviewTourService.StartResult.NO_PARTICIPANTS -> MenuActionResult.Rejected(
                            Component.text(i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_NO_PARTICIPANTS)),
                        )
                        PreviewTourService.StartResult.ALREADY_IN_TOUR -> MenuActionResult.Rejected(
                            Component.text(i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_ALREADY_IN_TOUR)),
                        )
                    }
                },
                onAbandon = {},
            ),
        )
    }

    private fun writeSignLines(state: Sign, tourTitle: String) {
        val serializer = LegacyComponentSerializer.legacySection()
        val side = state.getSide(Side.FRONT)
        side.line(0, serializer.deserialize("§1§l" + i18n(null, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_SIGN_TEXT_HEADER)))
        side.line(1, serializer.deserialize("§f" + tourTitle))
        side.line(2, Component.empty())
        side.line(3, serializer.deserialize("§8" + i18n(null, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_SIGN_TEXT_HINT)))
    }

    /** ブロック側PDCを優先し、PDCが失われていても登録座標で復元できるよう両方で照合します。 */
    private fun resolveSign(block: org.bukkit.block.Block): PreviewTourSignData? {
        val state = block.state
        if (state is Sign) {
            state.persistentDataContainer.get(SIGN_BLOCK_KEY, PersistentDataType.STRING)
                ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                ?.let { storage.getSign(it) }
                ?.let { return it }
        }
        return storage.findSign(block.world.name, block.x, block.y, block.z)
    }

    companion object {
        private const val OWNER = "mwm-chanpon"
        const val ADMIN_PERMISSION = "mwmchanpon.admin"

        /** ツアー看板アイテムと設置済み看板ブロックの双方に使う識別キー名です。 */
        private const val SIGN_KEY_NAME = "preview_tour_sign"
        private const val SIGN_BLOCK_KEY_NAME = "preview_tour_sign_uuid"
        private lateinit var pluginRef: MWMChanponPlugin

        fun initKeys(plugin: MWMChanponPlugin) {
            pluginRef = plugin
        }

        val SIGN_ITEM_KEY: NamespacedKey
            get() = NamespacedKey(pluginRef, SIGN_KEY_NAME)

        val SIGN_BLOCK_KEY: NamespacedKey
            get() = NamespacedKey(pluginRef, SIGN_BLOCK_KEY_NAME)
    }

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): Component =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)
}
