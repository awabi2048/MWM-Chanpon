package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.command.ChanponAdminCommand
import me.awabi2048.mwmchanpon.extension.ChanponCommandExtension
import me.awabi2048.mwmchanpon.extension.ChanponWorldAccessPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldPublishPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldRuntimePolicy
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.mwmchanpon.extension.ProductionWorldGuard
import me.awabi2048.mwmchanpon.extension.ToolPermissionMenuExtension
import me.awabi2048.mwmchanpon.extension.WorldBackupMenuExtension
import me.awabi2048.mwmchanpon.listener.MemberPermissionSyncListener
import me.awabi2048.mwmchanpon.listener.FeatureOverrideListener
import me.awabi2048.mwmchanpon.listener.PlayerJoinListener
import me.awabi2048.mwmchanpon.listener.SubmittedWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.ToolPermissionMenuListener
import me.awabi2048.mwmchanpon.listener.WorldCleanupListener
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.WizardSessionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.tool.LuckPermsToolPermissionService
import me.awabi2048.mwmchanpon.tool.ToolPermissionConfig
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.ui.AdminWizardGui
import me.awabi2048.mwmchanpon.ui.AutomationSettingsMenu
import me.awabi2048.mwmchanpon.ui.BackupListMenu
import me.awabi2048.mwmchanpon.ui.ChanponAdminMenu
import me.awabi2048.mwmchanpon.ui.ChanponAdminWorldListMenu
import me.awabi2048.mwmchanpon.ui.SubmissionAdminMenu
import me.awabi2048.mwmchanpon.ui.SubmittedWorldMenuProvider
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.mwmchanpon.ui.WorldDataManagementMenu
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
    private lateinit var submittedProtectionService: SubmittedWorldProtectionService
    private lateinit var submittedWorldMenuProvider: SubmittedWorldMenuProvider
    private lateinit var chanponAdminWorldListMenu: ChanponAdminWorldListMenu
    private lateinit var submissionAdminMenu: SubmissionAdminMenu
    private lateinit var backupService: BackupService
    private lateinit var backupListMenu: BackupListMenu
    private lateinit var worldDataManagementMenu: WorldDataManagementMenu
    private lateinit var automationService: BackupAutomationService
    private lateinit var automationSettingsMenu: AutomationSettingsMenu
    private lateinit var chanponAdminMenu: ChanponAdminMenu
    private lateinit var toolPermissionExtension: ToolPermissionMenuExtension
    private lateinit var worldBackupMenuExtension: WorldBackupMenuExtension
    private lateinit var worldAccessPolicy: ChanponWorldAccessPolicy
    private lateinit var worldPublishPolicy: ChanponWorldPublishPolicy
    private lateinit var worldRuntimePolicy: ChanponWorldRuntimePolicy
    private lateinit var commandExtension: ChanponCommandExtension
    private var featureOverrideTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()

        val timeoutSeconds = config.getLong("wizard.timeout_seconds", 300)
        wizardSessionService = WizardSessionService(timeoutSeconds, logger)

        adminWizardGui = AdminWizardGui(this, coreApiBridge)
        server.pluginManager.registerEvents(adminWizardGui, this)

        val chanponStorage = ChanponWorldStorage(dataFolder)
        val luckPerms = LuckPermsProvider.get()
        val toolDefinitions = ToolPermissionConfig(this).load()
        val toolController = ToolPermissionController(
            chanponStorage,
            LuckPermsToolPermissionService(luckPerms, logger),
            toolDefinitions
        )

        backupService = BackupService(this, chanponStorage)
        backupListMenu = BackupListMenu(this, backupService)
        worldDataManagementMenu = WorldDataManagementMenu(this, chanponStorage, backupService, backupListMenu)
        automationService = BackupAutomationService(this, chanponStorage, backupService)
        automationSettingsMenu = AutomationSettingsMenu(this, backupService, automationService)

        productionWorldGuard = ProductionWorldGuard(chanponStorage)
        submittedProtectionService = SubmittedWorldProtectionService(chanponStorage, logger)
        backupService.setPostRestoreHandler { restoredWorlds ->
            val repository = MyWorldManagerApi.getWorldRepository() ?: return@setPostRestoreHandler
            restoredWorlds.mapNotNull(repository::findByUuid).forEach { worldData ->
                submittedProtectionService.sync(worldData)
                toolController.syncWorld(worldData)
            }
        }
        productionToggleExtension = ProductionToggleExtension(
            this,
            chanponStorage,
            submittedProtectionService,
            toolController
        )
        submissionAdminMenu = SubmissionAdminMenu(
            this,
            chanponStorage,
            submittedProtectionService,
            toolController
        )
        chanponAdminMenu = ChanponAdminMenu(this, submissionAdminMenu, worldDataManagementMenu, automationSettingsMenu)
        submissionAdminMenu.setParentMenuOpener(chanponAdminMenu::open)
        worldDataManagementMenu.setParentMenuOpener(chanponAdminMenu::open)
        automationSettingsMenu.setParentMenuOpener(chanponAdminMenu::open)
        backupListMenu.setParentMenuOpener(worldDataManagementMenu::open)
        chanponAdminWorldListMenu = ChanponAdminWorldListMenu(this, chanponStorage)
        submittedWorldMenuProvider = SubmittedWorldMenuProvider(
            this,
            chanponStorage,
            submittedProtectionService,
            toolController
        )
        worldAccessPolicy = ChanponWorldAccessPolicy(chanponStorage)
        worldPublishPolicy = ChanponWorldPublishPolicy(chanponStorage)
        worldRuntimePolicy = ChanponWorldRuntimePolicy()
        commandExtension = ChanponCommandExtension(this, adminWizardGui)
        MyWorldManagerApi.registerWorldDeleteGuard(productionWorldGuard)
        MyWorldManagerApi.registerMenuExtension(productionToggleExtension)
        MyWorldManagerApi.registerAdminWorldListProvider(chanponAdminWorldListMenu)
        MyWorldManagerApi.registerWorldSettingsMenuProvider(submittedWorldMenuProvider)
        MyWorldManagerApi.registerWorldAccessPolicy(worldAccessPolicy)
        MyWorldManagerApi.registerWorldPublishPolicy(worldPublishPolicy)
        MyWorldManagerApi.registerWorldRuntimePolicy(worldRuntimePolicy)
        MyWorldManagerApi.registerCommandPolicy(commandExtension)
        MyWorldManagerApi.registerCreateCommandHandler(commandExtension)
        val adminCommand = ChanponAdminCommand(chanponAdminMenu)
        getCommand("mwmchanpon")?.setExecutor(adminCommand)
        getCommand("mwmchanpon")?.tabCompleter = adminCommand
        server.pluginManager.registerEvents(productionToggleExtension, this)
        server.pluginManager.registerEvents(submissionAdminMenu, this)
        server.pluginManager.registerEvents(chanponAdminMenu, this)
        server.pluginManager.registerEvents(worldDataManagementMenu, this)
        server.pluginManager.registerEvents(backupListMenu, this)
        server.pluginManager.registerEvents(automationSettingsMenu, this)
        server.pluginManager.registerEvents(chanponAdminWorldListMenu, this)
        server.pluginManager.registerEvents(submittedWorldMenuProvider, this)
        server.pluginManager.registerEvents(SubmittedWorldProtectionListener(submittedProtectionService), this)
        val featureOverrideListener = FeatureOverrideListener(this, chanponStorage)
        server.pluginManager.registerEvents(featureOverrideListener, this)
        featureOverrideTask = server.scheduler.runTaskTimer(
            this,
            Runnable {
                featureOverrideListener.sanitizeOpenInventories()
            },
            20L,
            20L
        )

        val toolMenu = ToolPermissionMenu(toolController, toolDefinitions)
        toolPermissionExtension = ToolPermissionMenuExtension(toolMenu, toolController, toolDefinitions)
        worldBackupMenuExtension = WorldBackupMenuExtension(this, chanponStorage, backupService)
        MyWorldManagerApi.registerMenuExtension(toolPermissionExtension)
        MyWorldManagerApi.registerMenuExtension(worldBackupMenuExtension)
        server.pluginManager.registerEvents(
            ToolPermissionMenuListener(toolMenu, toolController, toolDefinitions),
            this
        )
        server.pluginManager.registerEvents(worldBackupMenuExtension, this)
        server.pluginManager.registerEvents(MemberPermissionSyncListener(toolController), this)
        server.pluginManager.registerEvents(PlayerJoinListener(toolController), this)
        server.pluginManager.registerEvents(WorldCleanupListener(chanponStorage), this)

        server.scheduler.runTask(this, Runnable {
            MyWorldManagerApi.getWorldRepository()?.findAll()?.let {
                submittedProtectionService.syncAll(it)
                it.forEach(toolController::syncWorld)
            }
        })
        automationService.start()

        logger.info("MWM-Chanpon addon has been enabled.")
    }

    override fun onDisable() {
        if (::productionToggleExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(productionToggleExtension)
        }
        if (::chanponAdminWorldListMenu.isInitialized) {
            MyWorldManagerApi.unregisterAdminWorldListProvider(chanponAdminWorldListMenu)
        }
        if (::submittedWorldMenuProvider.isInitialized) {
            MyWorldManagerApi.unregisterWorldSettingsMenuProvider(submittedWorldMenuProvider)
        }
        if (::toolPermissionExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(toolPermissionExtension)
        }
        if (::worldBackupMenuExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(worldBackupMenuExtension)
        }
        if (::productionWorldGuard.isInitialized) {
            MyWorldManagerApi.unregisterWorldDeleteGuard(productionWorldGuard)
        }
        if (::worldAccessPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldAccessPolicy(worldAccessPolicy)
        }
        if (::worldPublishPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldPublishPolicy(worldPublishPolicy)
        }
        if (::worldRuntimePolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldRuntimePolicy(worldRuntimePolicy)
        }
        if (::commandExtension.isInitialized) {
            MyWorldManagerApi.unregisterCreateCommandHandler(commandExtension)
            MyWorldManagerApi.unregisterCommandPolicy(commandExtension)
        }
        featureOverrideTask?.cancel()
        featureOverrideTask = null
        if (::automationService.isInitialized) {
            automationService.stop()
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

    fun isSubmissionClosed(): Boolean = config.getBoolean("submission.closed", false)

}
