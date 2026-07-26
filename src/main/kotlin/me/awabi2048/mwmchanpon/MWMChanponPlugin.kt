package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuTargetPolicy
import com.awabi2048.ccsystem.api.gui.PublicMenuDefinition
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.extension.ChanponCommandExtension
import me.awabi2048.mwmchanpon.extension.ChanponWorldCreationGuard
import me.awabi2048.mwmchanpon.extension.ChanponWorldAccessPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldMenuAccessProvider
import me.awabi2048.mwmchanpon.extension.ChanponWorldPortalPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldPublishPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldRuntimePolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldPlayerStatePolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldWorkPermissionPolicy
import me.awabi2048.mwmchanpon.extension.LinkedPortalMenuExtension
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.mwmchanpon.extension.ProductionWorldGuard
import me.awabi2048.mwmchanpon.extension.ToolPermissionMenuExtension
import me.awabi2048.mwmchanpon.extension.WorldBackupMenuExtension
import me.awabi2048.mwmchanpon.listener.MemberPermissionSyncListener
import me.awabi2048.mwmchanpon.listener.FreecamWorldPolicyListener
import me.awabi2048.mwmchanpon.listener.PlayerJoinListener
import me.awabi2048.mwmchanpon.listener.SubmittedWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.SpawnWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.ToolPermissionWorldListener
import me.awabi2048.mwmchanpon.listener.WorldCleanupListener
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ChanponSpawnWorldService
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.service.ArchiveOperationGuard
import me.awabi2048.mwmchanpon.service.ManualRollbackService
import me.awabi2048.mwmchanpon.service.PublishLevelSynchronizer
import me.awabi2048.mwmchanpon.service.WorldDataExportService
import me.awabi2048.mwmchanpon.service.ArchiveService
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.command.ChanponAdminCommand
import me.awabi2048.mwmchanpon.tool.LuckPermsToolPermissionService
import me.awabi2048.mwmchanpon.tool.ToolPermissionConfig
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.ui.AutomationSettingsMenu
import me.awabi2048.mwmchanpon.ui.AnnualArchiveFlow
import me.awabi2048.mwmchanpon.ui.BackupListMenu
import me.awabi2048.mwmchanpon.ui.ChanponAdminMenu
import me.awabi2048.mwmchanpon.ui.ChanponAdminWorldListMenu
import me.awabi2048.mwmchanpon.ui.ChanponCreationConfirmationMenuProvider
import me.awabi2048.mwmchanpon.ui.ChanponEnvironmentGui
import me.awabi2048.mwmchanpon.ui.ChanponPlayerWorldMenuProvider
import me.awabi2048.mwmchanpon.ui.ChanponWorldSettingsMenuProvider
import me.awabi2048.mwmchanpon.ui.SubmissionAdminMenu
import me.awabi2048.mwmchanpon.ui.SubmittedWorldMenuProvider
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.mwmchanpon.ui.WorldDataExportMenu
import me.awabi2048.mwmchanpon.ui.WorldDataManagementMenu
import me.awabi2048.mwmchanpon.ui.bedrock.AdminWizardForm
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    private val coreApiBridge = CoreApiBridge()
    private lateinit var productionToggleExtension: ProductionToggleExtension
    private lateinit var creationConfirmationMenuProvider: ChanponCreationConfirmationMenuProvider
    private lateinit var productionWorldGuard: ProductionWorldGuard
    private lateinit var submittedProtectionService: SubmittedWorldProtectionService
    private lateinit var submittedWorldMenuProvider: SubmittedWorldMenuProvider
    private lateinit var chanponWorldSettingsMenuProvider: ChanponWorldSettingsMenuProvider
    private lateinit var playerWorldMenuProvider: ChanponPlayerWorldMenuProvider
    private lateinit var chanponAdminWorldListMenu: ChanponAdminWorldListMenu
    private lateinit var submissionAdminMenu: SubmissionAdminMenu
    private lateinit var backupService: BackupService
    private lateinit var backupListMenu: BackupListMenu
    private lateinit var worldDataManagementMenu: WorldDataManagementMenu
    private lateinit var worldDataExportService: WorldDataExportService
    private lateinit var worldDataExportMenu: WorldDataExportMenu
    private lateinit var automationService: BackupAutomationService
    private lateinit var manualRollbackService: ManualRollbackService
    private lateinit var automationSettingsMenu: AutomationSettingsMenu
    private lateinit var chanponAdminMenu: ChanponAdminMenu
    private lateinit var toolPermissionExtension: ToolPermissionMenuExtension
    private lateinit var worldBackupMenuExtension: WorldBackupMenuExtension
    private lateinit var linkedPortalMenuExtension: LinkedPortalMenuExtension
    private lateinit var environmentGui: ChanponEnvironmentGui
    private lateinit var archiveService: ArchiveService
    private lateinit var annualArchiveFlow: AnnualArchiveFlow
    private lateinit var worldAccessPolicy: ChanponWorldAccessPolicy
    private lateinit var worldMenuAccessProvider: ChanponWorldMenuAccessProvider
    private lateinit var worldPortalPolicy: ChanponWorldPortalPolicy
    private lateinit var worldPublishPolicy: ChanponWorldPublishPolicy
    private lateinit var worldRuntimePolicy: ChanponWorldRuntimePolicy
    private lateinit var worldWorkPermissionPolicy: ChanponWorldWorkPermissionPolicy
    private lateinit var commandExtension: ChanponCommandExtension
    private lateinit var worldCreationGuard: ChanponWorldCreationGuard
    private lateinit var worldPlayerStatePolicy: ChanponWorldPlayerStatePolicy
    private lateinit var globalStorage: ChanponGlobalStorage
    private lateinit var adminCommand: ChanponAdminCommand
    private lateinit var spawnWorldService: ChanponSpawnWorldService
    private lateinit var toolController: ToolPermissionController
    private lateinit var spawnProtectionService: SpawnWorldProtectionService
    private lateinit var buildPermissionService: WorldBuildPermissionService
    private lateinit var easyVoidWorldCreationService: EasyVoidWorldCreationService
    private lateinit var publishLevelSynchronizer: PublishLevelSynchronizer

    override fun onEnable() {
        CCSystem.getAPI().getConfigSchemaService().register(
            "mwm-chanpon",
            listOf(
                com.awabi2048.ccsystem.api.config.ManagedConfigSpec(
                    owner = "mwm-chanpon",
                    sourcePlugin = this,
                    resourcePath = "config.yml",
                    targetPath = dataFolder.resolve("config.yml").toPath(),
                    currentVersion = 3,
                    classification = com.awabi2048.ccsystem.api.config.ConfigClassification.MANAGED_CONFIG,
                    migrations = mapOf(
                        1 to com.awabi2048.ccsystem.api.config.ConfigMigration { config ->
                            if (!config.contains("creation.max_worlds_per_player")) {
                                config.set("creation.max_worlds_per_player", 50)
                            }
                        },
                        2 to com.awabi2048.ccsystem.api.config.ConfigMigration { config ->
                            if (!config.contains("standalone_export.fiscal_year")) {
                                config.set("standalone_export.fiscal_year", 2026)
                            }
                        }
                    ),
                    validator = com.awabi2048.ccsystem.api.config.ConfigValidator { config ->
                        require(config.getInt("creation.max_worlds_per_player", 50) >= 1) {
                            "creation.max_worlds_per_player must be at least 1"
                        }
                        require(config.getInt("world_expansion.max_level", 3) >= 0) {
                            "world_expansion.max_level must not be negative"
                        }
                        require(config.isInt("standalone_export.fiscal_year")) {
                            "standalone_export.fiscal_year must be an integer"
                        }
                        require(config.getInt("standalone_export.fiscal_year") in 2000..9999) {
                            "standalone_export.fiscal_year must be between 2000 and 9999"
                        }
                    },
                    reloadAction = { reloadConfig() }
                )
            )
        )
        check(CCSystem.getAPI().getConfigSchemaService().prepare("mwm-chanpon").successful) {
            "MWM-Chanpon Config preparation failed"
        }
        reloadConfig()
        CCSystem.getAPI().getMenuNavigationService().registerMenuMatcher(MENU_OWNER) { inventory ->
            inventory.holder?.javaClass?.name?.startsWith("me.awabi2048.mwmchanpon") == true
        }

        val chanponStorage = ChanponWorldStorage(dataFolder)
        publishLevelSynchronizer = PublishLevelSynchronizer()
        chanponStorage.setVisibilitySyncHandler(publishLevelSynchronizer::sync)
        globalStorage = ChanponGlobalStorage(dataFolder)
        spawnWorldService = ChanponSpawnWorldService(this, globalStorage)
        val luckPerms = LuckPermsProvider.get()
        spawnProtectionService = SpawnWorldProtectionService()
        val toolDefinitions = ToolPermissionConfig(this).load()
        val isAutoRollbackEnabled = { config.getBoolean("backup.auto_rollback.enabled", false) }
        worldWorkPermissionPolicy = ChanponWorldWorkPermissionPolicy(chanponStorage, isAutoRollbackEnabled)
        toolController = ToolPermissionController(
            chanponStorage,
            LuckPermsToolPermissionService(luckPerms, logger),
            toolDefinitions,
            isAutoRollbackEnabled
        )

        backupService = BackupService(this, chanponStorage)
        worldDataExportService = WorldDataExportService(this, chanponStorage)
        backupListMenu = BackupListMenu(this, backupService)
        archiveService = ArchiveService(this, chanponStorage, globalStorage)
        annualArchiveFlow = AnnualArchiveFlow(this, archiveService)
        worldDataManagementMenu = WorldDataManagementMenu(
            this,
            chanponStorage,
            backupService,
            backupListMenu,
            annualArchiveFlow,
        )
        worldDataExportMenu = WorldDataExportMenu(this, worldDataExportService)
        automationService = BackupAutomationService(this, chanponStorage, backupService)
        manualRollbackService = ManualRollbackService(this)
        backupListMenu.setAutomationService(automationService)
        backupListMenu.setManualRollbackService(manualRollbackService)

        productionWorldGuard = ProductionWorldGuard(chanponStorage)
        submittedProtectionService = SubmittedWorldProtectionService(chanponStorage, logger, isAutoRollbackEnabled)
        buildPermissionService = WorldBuildPermissionService(chanponStorage, logger)
        automationSettingsMenu = AutomationSettingsMenu(this, backupService, automationService) {
            MyWorldManagerApi.getWorldRepository()?.findAll()?.let { worlds ->
                submittedProtectionService.syncAll(worlds)
                worlds.forEach(toolController::syncWorld)
            }
        }
        backupService.setPostRestoreHandler { restoredWorlds ->
            val repository = MyWorldManagerApi.getWorldRepository() ?: return@setPostRestoreHandler
            restoredWorlds.mapNotNull(repository::findByUuid).forEach { worldData ->
                publishLevelSynchronizer.sync(worldData.uuid)
                submittedProtectionService.sync(worldData)
                toolController.syncWorld(worldData)
            }
        }
        productionToggleExtension = ProductionToggleExtension(
            this,
            chanponStorage,
            globalStorage,
            submittedProtectionService,
            toolController
        )
        creationConfirmationMenuProvider = ChanponCreationConfirmationMenuProvider(productionToggleExtension)
        submissionAdminMenu = SubmissionAdminMenu(
            this,
            chanponStorage,
            globalStorage,
            submittedProtectionService,
            toolController
        )
        chanponAdminMenu = ChanponAdminMenu(this, globalStorage, spawnProtectionService, submissionAdminMenu, worldDataManagementMenu, automationSettingsMenu, worldDataExportMenu)
        CCSystem.getAPI().getMenuCommandService().unregisterOwner("mwm-chanpon")
        CCSystem.getAPI().getMenuCommandService().register(
            PublicMenuDefinition(
                owner = "mwm-chanpon",
                id = "admin",
                permission = "mwmchanpon.admin",
                targetPolicy = MenuTargetPolicy.SELF_ONLY,
                argumentKeys = emptySet(),
                opener = { player, _ ->
                    chanponAdminMenu.open(player)
                    true
                }
            )
        )
        submissionAdminMenu.setParentMenuOpener(chanponAdminMenu::open)
        worldDataManagementMenu.setParentMenuOpener(chanponAdminMenu::open)
        worldDataExportMenu.setParentMenuOpener(chanponAdminMenu::open)
        automationSettingsMenu.setParentMenuOpener(chanponAdminMenu::open)
        backupListMenu.setParentMenuOpener(worldDataManagementMenu::reopenFromChild)
        chanponAdminWorldListMenu = ChanponAdminWorldListMenu(this, chanponStorage)
        submittedWorldMenuProvider = SubmittedWorldMenuProvider(this, chanponStorage, submittedProtectionService)
        environmentGui = ChanponEnvironmentGui(this, chanponStorage, submittedProtectionService, buildPermissionService)
        chanponWorldSettingsMenuProvider = ChanponWorldSettingsMenuProvider(this, chanponStorage, submittedProtectionService, environmentGui)
        easyVoidWorldCreationService = EasyVoidWorldCreationService(this)
        val archiveOperationGuard = ArchiveOperationGuard(globalStorage)
        playerWorldMenuProvider = ChanponPlayerWorldMenuProvider(this, chanponStorage, easyVoidWorldCreationService)
        worldAccessPolicy = ChanponWorldAccessPolicy(archiveOperationGuard, chanponStorage)
        worldMenuAccessProvider = ChanponWorldMenuAccessProvider(chanponStorage)
        worldPortalPolicy = ChanponWorldPortalPolicy(chanponStorage)
        worldPublishPolicy = ChanponWorldPublishPolicy(chanponStorage)
        worldRuntimePolicy = ChanponWorldRuntimePolicy()
        commandExtension = ChanponCommandExtension(archiveOperationGuard)
        worldCreationGuard = ChanponWorldCreationGuard(this, globalStorage)
        worldPlayerStatePolicy = ChanponWorldPlayerStatePolicy(globalStorage)
        MyWorldManagerApi.registerWorldCreationGuard(worldCreationGuard)
        MyWorldManagerApi.registerWorldPlayerStatePolicy(worldPlayerStatePolicy)
        MyWorldManagerApi.registerWorldDeleteGuard(productionWorldGuard)
        MyWorldManagerApi.registerMenuExtension(productionToggleExtension)
        MyWorldManagerApi.registerCreationConfirmationMenuProvider(creationConfirmationMenuProvider)
        MyWorldManagerApi.registerAdminWorldListProvider(chanponAdminWorldListMenu)
        MyWorldManagerApi.registerWorldSettingsMenuProvider(submittedWorldMenuProvider)
        MyWorldManagerApi.registerWorldSettingsMenuProvider(chanponWorldSettingsMenuProvider)
        MyWorldManagerApi.registerPlayerWorldMenuProvider(playerWorldMenuProvider)
        // Discovery / favorite / visit menus stay on MWM standard providers; Chanpon only supplies policies.
        MyWorldManagerApi.registerWorldEvacuationProvider(spawnWorldService)
        MyWorldManagerApi.registerWorldAccessPolicy(worldAccessPolicy)
        MyWorldManagerApi.registerWorldMenuAccessProvider(worldMenuAccessProvider)
        MyWorldManagerApi.registerWorldPortalPolicy(worldPortalPolicy)
        MyWorldManagerApi.registerWorldWorkPermissionPolicy(worldWorkPermissionPolicy)
        MyWorldManagerApi.registerWorldPublishPolicy(worldPublishPolicy)
        MyWorldManagerApi.registerWorldRuntimePolicy(worldRuntimePolicy)
        MyWorldManagerApi.registerAdminMenuProvider(chanponAdminMenu)
        MyWorldManagerApi.registerCommandPolicy(commandExtension)
        adminCommand = ChanponAdminCommand(
            chanponAdminMenu,
            archiveService,
            AdminWizardForm(this)
        )
        getCommand("chanpon-mwm")?.let { command ->
            command.setExecutor(adminCommand)
            command.tabCompleter = adminCommand
        }
        server.pluginManager.registerEvents(productionToggleExtension, this)
        server.pluginManager.registerEvents(creationConfirmationMenuProvider, this)
        server.pluginManager.registerEvents(chanponWorldSettingsMenuProvider, this)
        server.pluginManager.registerEvents(environmentGui, this)
        if (server.pluginManager.isPluginEnabled("Chanpon-Utilities")) {
            server.pluginManager.registerEvents(FreecamWorldPolicyListener(chanponStorage), this)
        }
        server.pluginManager.registerEvents(playerWorldMenuProvider, this)
        server.pluginManager.registerEvents(publishLevelSynchronizer, this)
        server.pluginManager.registerEvents(SubmittedWorldProtectionListener(submittedProtectionService), this)
        server.pluginManager.registerEvents(SpawnWorldProtectionListener(globalStorage, spawnProtectionService), this)

        val toolMenu = ToolPermissionMenu(this, toolController, toolDefinitions)
        toolPermissionExtension = ToolPermissionMenuExtension(toolMenu, toolController, toolDefinitions)
        worldBackupMenuExtension = WorldBackupMenuExtension(this, chanponStorage, backupService, automationService, manualRollbackService)
        linkedPortalMenuExtension = LinkedPortalMenuExtension()
        MyWorldManagerApi.registerMenuExtension(toolPermissionExtension)
        MyWorldManagerApi.registerMenuExtension(worldBackupMenuExtension)
        MyWorldManagerApi.registerMenuExtension(linkedPortalMenuExtension)
        productionToggleExtension.syncForcedTagsForProductionWorlds()
        server.pluginManager.registerEvents(worldBackupMenuExtension, this)
        server.pluginManager.registerEvents(MemberPermissionSyncListener(toolController, chanponStorage), this)
        server.pluginManager.registerEvents(PlayerJoinListener(toolController, spawnWorldService), this)
        server.pluginManager.registerEvents(ToolPermissionWorldListener(toolController), this)
        server.pluginManager.registerEvents(WorldCleanupListener(chanponStorage, toolController), this)

        server.scheduler.runTask(this, Runnable {
            MyWorldManagerApi.getWorldRepository()?.findAll()?.let {
                publishLevelSynchronizer.syncAll()
                submittedProtectionService.syncAll(it)
                it.forEach(toolController::syncWorld)
            }
            globalStorage.getSpawnWorldUuid()
                ?.takeUnless(globalStorage::isSpawnPolicyInitialized)
                ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }
                ?.let { worldData ->
                    if (spawnProtectionService.applyDefaults(worldData)) {
                        globalStorage.setSpawnPolicyInitialized(worldData.uuid)
                    }
                }
            toolController.syncOnlinePlayers()
        })
        automationService.start()

        logger.info("MWM-Chanpon addon has been enabled.")
    }

    override fun onDisable() {
        runCatching { CCSystem.getAPI().getConfigSchemaService().unregister("mwm-chanpon") }
        runCatching { CCSystem.getAPI().getMenuCommandService().unregisterOwner("mwm-chanpon") }
        runCatching { CCSystem.getAPI().getMenuRuntimeService().unregisterOwner("mwm-chanpon") }
        runCatching {
            CCSystem.getAPI().getMenuNavigationService().closeOwnedMenus(MENU_OWNER, server.onlinePlayers)
        }
        if (::productionToggleExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(productionToggleExtension)
        }
        if (::creationConfirmationMenuProvider.isInitialized) {
            MyWorldManagerApi.unregisterCreationConfirmationMenuProvider(creationConfirmationMenuProvider)
        }
        if (::chanponAdminWorldListMenu.isInitialized) {
            MyWorldManagerApi.unregisterAdminWorldListProvider(chanponAdminWorldListMenu)
        }
        if (::submittedWorldMenuProvider.isInitialized) {
            MyWorldManagerApi.unregisterWorldSettingsMenuProvider(submittedWorldMenuProvider)
        }
        if (::chanponWorldSettingsMenuProvider.isInitialized) {
            MyWorldManagerApi.unregisterWorldSettingsMenuProvider(chanponWorldSettingsMenuProvider)
        }
        if (::playerWorldMenuProvider.isInitialized) {
            MyWorldManagerApi.unregisterPlayerWorldMenuProvider(playerWorldMenuProvider)
        }
        if (::spawnWorldService.isInitialized) {
            MyWorldManagerApi.unregisterWorldEvacuationProvider(spawnWorldService)
        }
        if (::toolPermissionExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(toolPermissionExtension)
        }
        if (::worldBackupMenuExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(worldBackupMenuExtension)
        }
        if (::linkedPortalMenuExtension.isInitialized) {
            MyWorldManagerApi.unregisterMenuExtension(linkedPortalMenuExtension)
        }
        if (::productionWorldGuard.isInitialized) {
            MyWorldManagerApi.unregisterWorldDeleteGuard(productionWorldGuard)
        }
        if (::worldAccessPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldAccessPolicy(worldAccessPolicy)
        }
        if (::worldMenuAccessProvider.isInitialized) {
            MyWorldManagerApi.unregisterWorldMenuAccessProvider(worldMenuAccessProvider)
        }
        if (::worldPortalPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldPortalPolicy(worldPortalPolicy)
        }
        if (::worldWorkPermissionPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldWorkPermissionPolicy(worldWorkPermissionPolicy)
        }
        if (::worldPublishPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldPublishPolicy(worldPublishPolicy)
        }
        if (::worldRuntimePolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldRuntimePolicy(worldRuntimePolicy)
        }
        if (::chanponAdminMenu.isInitialized) {
            MyWorldManagerApi.unregisterAdminMenuProvider(chanponAdminMenu)
        }
        if (::commandExtension.isInitialized) {
            MyWorldManagerApi.unregisterCommandPolicy(commandExtension)
        }
        if (::worldCreationGuard.isInitialized) {
            MyWorldManagerApi.unregisterWorldCreationGuard(worldCreationGuard)
        }
        if (::worldPlayerStatePolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldPlayerStatePolicy(worldPlayerStatePolicy)
        }
        if (::automationService.isInitialized) {
            automationService.stop()
        }
        if (::toolController.isInitialized) {
            toolController.clearOnlineActivePermissions()
        }
        runCatching { CCSystem.getAPI().getMenuNavigationService().unregisterOwner(MENU_OWNER) }
        logger.info("MWM-Chanpon addon has been disabled.")
    }

    fun setBuildingAllowed(allowed: Boolean) {
        globalStorage.setBuildingAllowed(allowed)
        MyWorldManagerApi.syncOnlineWorldPlayerStates()
    }

    fun getMessage(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String {
        var raw = CCSystem.getAPI().getI18nString(player, key)
        for ((k, v) in placeholders) raw = raw.replace("{$k}", v.toString())
        return raw
    }

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge

    fun isSubmissionClosed(): Boolean = config.getBoolean("submission.closed", false)

    private companion object {
        private const val MENU_OWNER = "mwm-chanpon"
    }

}
