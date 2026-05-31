package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.mwmchanpon.extension.ProductionWorldGuard
import me.awabi2048.mwmchanpon.extension.ToolPermissionMenuExtension
import me.awabi2048.mwmchanpon.listener.MemberPermissionSyncListener
import me.awabi2048.mwmchanpon.listener.PlayerJoinListener
import me.awabi2048.mwmchanpon.listener.ToolPermissionMenuListener
import me.awabi2048.mwmchanpon.listener.WorldCleanupListener
import me.awabi2048.mwmchanpon.service.WizardSessionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.tool.LuckPermsToolPermissionService
import me.awabi2048.mwmchanpon.tool.ToolPermissionConfig
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.ui.AdminWizardGui
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    lateinit var wizardSessionService: WizardSessionService
        private set
    lateinit var adminWizardGui: AdminWizardGui
        private set
    private val coreApiBridge = CoreApiBridge()
    private lateinit var productionToggleExtension: ProductionToggleExtension
    private lateinit var productionWorldGuard: ProductionWorldGuard
    private lateinit var toolPermissionExtension: ToolPermissionMenuExtension

    override fun onEnable() {
        saveDefaultConfig()

        val timeoutSeconds = config.getLong("wizard.timeout_seconds", 300)
        wizardSessionService = WizardSessionService(timeoutSeconds, logger)

        adminWizardGui = AdminWizardGui(this, coreApiBridge)
        server.pluginManager.registerEvents(adminWizardGui, this)

        val chanponStorage = ChanponWorldStorage(dataFolder)
        productionWorldGuard = ProductionWorldGuard(chanponStorage)
        productionToggleExtension = ProductionToggleExtension(this, chanponStorage)
        MyWorldManagerApi.registerWorldDeleteGuard(productionWorldGuard)
        MyWorldManagerApi.registerMenuExtension(productionToggleExtension)
        server.pluginManager.registerEvents(productionToggleExtension, this)

        val luckPerms = LuckPermsProvider.get()
        val toolDefinitions = ToolPermissionConfig(this).load()
        val toolController = ToolPermissionController(
            chanponStorage,
            LuckPermsToolPermissionService(luckPerms, logger),
            toolDefinitions
        )
        val toolMenu = ToolPermissionMenu(toolController, toolDefinitions)
        toolPermissionExtension = ToolPermissionMenuExtension(toolMenu, toolController, toolDefinitions)
        MyWorldManagerApi.registerMenuExtension(toolPermissionExtension)
        server.pluginManager.registerEvents(
            ToolPermissionMenuListener(toolMenu, toolController, toolDefinitions),
            this
        )
        server.pluginManager.registerEvents(MemberPermissionSyncListener(toolController), this)
        server.pluginManager.registerEvents(PlayerJoinListener(toolController), this)
        server.pluginManager.registerEvents(WorldCleanupListener(chanponStorage), this)

        logger.info("MWM-Chanpon addon has been enabled.")
    }

    override fun onDisable() {
        if (::productionToggleExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(productionToggleExtension)
        }
        if (::toolPermissionExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(toolPermissionExtension)
        }
        if (::productionWorldGuard.isInitialized) {
            MyWorldManagerApi.unregisterWorldDeleteGuard(productionWorldGuard)
        }
        wizardSessionService.clearAll()
        logger.info("MWM-Chanpon addon has been disabled.")
    }

    fun getMessage(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String {
        var raw = CCSystem.getAPI().getI18nString(player, key)
        for ((k, v) in placeholders) raw = raw.replace("{$k}", v.toString())
        return raw
    }

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge
}
