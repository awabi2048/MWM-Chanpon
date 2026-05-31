package me.awabi2048.mwmchanpon.extension

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import me.awabi2048.myworldmanager.session.WorldCreationSession
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import me.awabi2048.myworldmanager.model.WorldData

class ProductionToggleExtension(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) : MenuExtension, Listener {
    private val pendingProduction = ConcurrentHashMap<Pair<UUID, String>, Boolean>()

    override fun getId(): String = EXTENSION_ID

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        when (context.menuType) {
            "creation_confirm" -> {
                val session = context.get<WorldCreationSession>("session") ?: return
                val enabled = session.extras[EXTRA_KEY] as? Boolean ?: false
                inventory.setItem(plugin.config.getInt("production.menu_slot", 40), createItem(enabled))
            }
            "world_settings" -> {
                val worldData = context.get<WorldData>("worldData") ?: return
                if (storage.isProductionWorld(worldData.uuid)) {
                    inventory.setItem(CRITICAL_SETTINGS_SLOT, inventory.getItem(BACKGROUND_SLOT)?.clone())
                }
            }
        }
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        if (context.menuType != "creation_confirm") return false
        val item = event.currentItem ?: return false
        if (ItemTag.getExtensionId(item) != getId()) return false
        if (ItemTag.getString(item, "chanpon_action") != "toggle_production") return false
        val session = context.get<WorldCreationSession>("session") ?: return false
        val next = !(session.extras[EXTRA_KEY] as? Boolean ?: false)
        session.extras[EXTRA_KEY] = next
        val worldName = session.worldName ?: return true
        pendingProduction[player.uniqueId to worldName] = next
        event.inventory.setItem(event.slot, createItem(next))
        return true
    }

    @EventHandler
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        val enabled = pendingProduction.remove(event.ownerUuid to event.worldName) ?: return
        if (enabled) storage.setProductionWorld(event.worldUuid, true)
    }

    private fun createItem(enabled: Boolean): ItemStack {
        val item = ItemStack(if (enabled) Material.YELLOW_WOOL else Material.GRAY_WOOL)
        item.editMeta { meta ->
            meta.displayName(
                Component.text("§e本番用ワールドに設定する").decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(
                listOf(
                    Component.text("§7提出用のワールドとして扱います").decoration(TextDecoration.ITALIC, false),
                    Component.text("§c本番用ワールドは削除できません").decoration(TextDecoration.ITALIC, false),
                    Component.text("§7状態: ${if (enabled) "§eオン" else "§7オフ"}").decoration(TextDecoration.ITALIC, false)
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        if (enabled) item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, getId())
        ItemTag.setString(item, "chanpon_action", "toggle_production")
        return item
    }

    companion object {
        private const val EXTENSION_ID = "chanpon.production_toggle"
        private const val EXTRA_KEY = "chanpon.production"
        private const val CRITICAL_SETTINGS_SLOT = 33
        private const val BACKGROUND_SLOT = 0
    }
}
