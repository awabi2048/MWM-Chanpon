package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.mwmchanpon.extension.ProductionWorldGuard
import me.awabi2048.mwmchanpon.extension.ToolPermissionMenuExtension
import me.awabi2048.mwmchanpon.listener.CreateCommandInterceptor
import me.awabi2048.mwmchanpon.listener.MemberPermissionSyncListener
import me.awabi2048.mwmchanpon.listener.FeatureOverrideListener
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
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class MWMChanponPlugin : JavaPlugin() {
    lateinit var wizardSessionService: WizardSessionService
        private set
    lateinit var adminWizardGui: AdminWizardGui
        private set
    private val coreApiBridge = CoreApiBridge()
    private lateinit var productionToggleExtension: ProductionToggleExtension
    private lateinit var productionWorldGuard: ProductionWorldGuard
    private lateinit var toolPermissionExtension: ToolPermissionMenuExtension
    private var featureOverrideTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()

        val timeoutSeconds = config.getLong("wizard.timeout_seconds", 300)
        wizardSessionService = WizardSessionService(timeoutSeconds, logger)

        adminWizardGui = AdminWizardGui(this, coreApiBridge)
        server.pluginManager.registerEvents(adminWizardGui, this)
        server.pluginManager.registerEvents(CreateCommandInterceptor(this, adminWizardGui), this)

        val chanponStorage = ChanponWorldStorage(dataFolder)
        productionWorldGuard = ProductionWorldGuard(chanponStorage)
        productionToggleExtension = ProductionToggleExtension(this, chanponStorage)
        MyWorldManagerApi.registerWorldDeleteGuard(productionWorldGuard)
        MyWorldManagerApi.registerMenuExtension(productionToggleExtension)
        server.pluginManager.registerEvents(productionToggleExtension, this)
        val featureOverrideListener = FeatureOverrideListener(this, chanponStorage)
        server.pluginManager.registerEvents(featureOverrideListener, this)
        applyCoreRuntimeOverrides()
        featureOverrideTask = server.scheduler.runTaskTimer(
            this,
            Runnable {
                applyCoreRuntimeOverrides()
                featureOverrideListener.sanitizeOpenInventories()
            },
            20L,
            20L
        )

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
        featureOverrideTask?.cancel()
        featureOverrideTask = null
        wizardSessionService.clearAll()
        logger.info("MWM-Chanpon addon has been disabled.")
    }

    fun getMessage(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String {
        var raw = CCSystem.getAPI().getI18nString(player, key)
        for ((k, v) in placeholders) raw = raw.replace("{$k}", v.toString())
        return raw
    }

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge

    private fun applyCoreRuntimeOverrides() {
        val mwm = server.pluginManager.getPlugin("MyWorldManager") as? MyWorldManager ?: return
        val config = mwm.config

        config.set("creation_cost.template", 0)
        config.set("creation_cost.seed", 0)
        config.set("creation_cost.random", 0)
        config.set("creation.max_create_count_default", config.getInt("creation.max_total_world_count", 50).coerceAtLeast(50))
        config.set("creation.max_world_slots_limit", 0)
        config.set("deletion.reduce_owner_slot", false)

        config.set("expansion.base_cost", 0)
        config.set("expansion.cost_multiplier", 0.0)
        config.getConfigurationSection("expansion.costs")?.getKeys(false)?.forEach { key ->
            config.set("expansion.costs.$key", 0)
        }
        config.set("environment.gravity.cost", 0)
        config.set("environment.weather.cost", 0)
        config.set("environment.biome.cost", 0)
        config.set("portal.world_gate.point_cost_per_block", 0)
    }
}
