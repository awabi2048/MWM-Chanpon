package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuTargetPolicy
import com.awabi2048.ccsystem.api.gui.PublicMenuDefinition
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.extension.ChanponCommandExtension
import me.awabi2048.mwmchanpon.extension.ChanponWorldCreationGuard
import me.awabi2048.mwmchanpon.extension.ChanponWorldAccessPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldMenuAccessPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldPortalPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldPublishPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldRuntimePolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldPlayerStatePolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldWorkPermissionPolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldSettingsStatePolicy
import me.awabi2048.mwmchanpon.extension.ChanponWorldSettingsCapabilities
import me.awabi2048.mwmchanpon.extension.LinkedPortalCapability
import me.awabi2048.mwmchanpon.extension.ProductionToggleExtension
import me.awabi2048.mwmchanpon.extension.ProductionWorldGuard
import me.awabi2048.mwmchanpon.extension.ToolPermissionCapability
import me.awabi2048.mwmchanpon.extension.WorldBackupCapability
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
import me.awabi2048.mwmchanpon.ui.ChanponEnvironmentGui
import me.awabi2048.mwmchanpon.ui.ChanponPlayerWorldCapability
import me.awabi2048.mwmchanpon.ui.ChanponWorldSettingsMenu
import me.awabi2048.mwmchanpon.ui.SubmissionAdminMenu
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
    private lateinit var productionWorldGuard: ProductionWorldGuard
    private lateinit var submittedProtectionService: SubmittedWorldProtectionService
    private lateinit var chanponWorldSettingsMenu: ChanponWorldSettingsMenu
    private lateinit var playerWorldCapability: ChanponPlayerWorldCapability
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
    private lateinit var toolPermissionCapability: ToolPermissionCapability
    private lateinit var worldBackupCapability: WorldBackupCapability
    private lateinit var linkedPortalCapability: LinkedPortalCapability
    private lateinit var environmentGui: ChanponEnvironmentGui
    private lateinit var archiveService: ArchiveService
    private lateinit var annualArchiveFlow: AnnualArchiveFlow
    private lateinit var worldAccessPolicy: ChanponWorldAccessPolicy
    private lateinit var worldMenuAccessPolicy: ChanponWorldMenuAccessPolicy
    private lateinit var worldPortalPolicy: ChanponWorldPortalPolicy
    private lateinit var worldPublishPolicy: ChanponWorldPublishPolicy
    private lateinit var worldRuntimePolicy: ChanponWorldRuntimePolicy
    private lateinit var worldWorkPermissionPolicy: ChanponWorldWorkPermissionPolicy
    private lateinit var commandExtension: ChanponCommandExtension
    private lateinit var worldCreationGuard: ChanponWorldCreationGuard
    private lateinit var worldPlayerStatePolicy: ChanponWorldPlayerStatePolicy
    private lateinit var worldSettingsStatePolicy: ChanponWorldSettingsStatePolicy
    private lateinit var worldSettingsCapabilities: ChanponWorldSettingsCapabilities
    private lateinit var globalStorage: ChanponGlobalStorage
    private lateinit var adminCommand: ChanponAdminCommand
    private lateinit var spawnWorldService: ChanponSpawnWorldService
    private lateinit var toolController: ToolPermissionController
    private lateinit var spawnProtectionService: SpawnWorldProtectionService
    private lateinit var buildPermissionService: WorldBuildPermissionService
    private lateinit var easyVoidWorldCreationService: EasyVoidWorldCreationService
    private lateinit var publishLevelSynchronizer: PublishLevelSynchronizer

    override fun onEnable() {
        if (!verifyGuiRuntimeContract()) return
        CCSystem.getAPI().getConfigSchemaService().register(
            "mwm-chanpon",
            listOf(
                com.awabi2048.ccsystem.api.config.ManagedConfigSpec(
                    owner = "mwm-chanpon",
                    sourcePlugin = this,
                    resourcePath = "config.yml",
                    targetPath = dataFolder.resolve("config.yml").toPath(),
                    currentVersion = 4,
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
                        },
                        3 to com.awabi2048.ccsystem.api.config.ConfigMigration { config ->
                            if (!config.contains("creation.easy.platform_material")) {
                                config.set("creation.easy.platform_material", "OBSIDIAN")
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
                        require(
                            me.awabi2048.mwmchanpon.service.EasyPlatformMaterialPolicy.resolve(
                                config.getString("creation.easy.platform_material").orEmpty()
                            ) != null
                        ) {
                            "creation.easy.platform_material must be a placeable solid block material"
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
        chanponAdminWorldListMenu = ChanponAdminWorldListMenu(this, chanponStorage)
        environmentGui = ChanponEnvironmentGui(this, chanponStorage, submittedProtectionService, buildPermissionService)
        chanponWorldSettingsMenu = ChanponWorldSettingsMenu(this, chanponStorage, submittedProtectionService, environmentGui)
        worldSettingsCapabilities = ChanponWorldSettingsCapabilities(
            chanponWorldSettingsMenu,
            chanponStorage,
        )
        easyVoidWorldCreationService = EasyVoidWorldCreationService(this)
        val archiveOperationGuard = ArchiveOperationGuard(globalStorage)
        playerWorldCapability = ChanponPlayerWorldCapability(this, chanponStorage, easyVoidWorldCreationService)
        MyWorldManagerApi.registerPlayerWorldRouteCapability(playerWorldCapability)
        worldAccessPolicy = ChanponWorldAccessPolicy(archiveOperationGuard, chanponStorage)
        worldMenuAccessPolicy = ChanponWorldMenuAccessPolicy(chanponStorage)
        worldPortalPolicy = ChanponWorldPortalPolicy(chanponStorage)
        worldPublishPolicy = ChanponWorldPublishPolicy(chanponStorage)
        worldRuntimePolicy = ChanponWorldRuntimePolicy()
        commandExtension = ChanponCommandExtension(archiveOperationGuard)
        worldCreationGuard = ChanponWorldCreationGuard(this, globalStorage)
        worldPlayerStatePolicy = ChanponWorldPlayerStatePolicy(globalStorage)
        worldSettingsStatePolicy =
            ChanponWorldSettingsStatePolicy(chanponStorage, submittedProtectionService)
        MyWorldManagerApi.registerWorldCreationGuard(worldCreationGuard)
        MyWorldManagerApi.registerWorldPlayerStatePolicy(worldPlayerStatePolicy)
        MyWorldManagerApi.registerWorldSettingsStatePolicy(worldSettingsStatePolicy)
        MyWorldManagerApi.registerWorldDeleteGuard(productionWorldGuard)
        // Discovery / favorite / visit menus stay on MWM standard providers; Chanpon only supplies policies.
        MyWorldManagerApi.registerWorldEvacuationProvider(spawnWorldService)
        MyWorldManagerApi.registerWorldAccessPolicy(worldAccessPolicy)
        MyWorldManagerApi.registerWorldMenuAccessPolicy(worldMenuAccessPolicy)
        MyWorldManagerApi.registerWorldPortalPolicy(worldPortalPolicy)
        MyWorldManagerApi.registerWorldWorkPermissionPolicy(worldWorkPermissionPolicy)
        MyWorldManagerApi.registerWorldPublishPolicy(worldPublishPolicy)
        MyWorldManagerApi.registerWorldRuntimePolicy(worldRuntimePolicy)
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
        if (server.pluginManager.isPluginEnabled("Chanpon-Utilities")) {
            server.pluginManager.registerEvents(FreecamWorldPolicyListener(chanponStorage), this)
        }
        server.pluginManager.registerEvents(publishLevelSynchronizer, this)
        server.pluginManager.registerEvents(SubmittedWorldProtectionListener(submittedProtectionService), this)
        server.pluginManager.registerEvents(SpawnWorldProtectionListener(globalStorage, spawnProtectionService), this)

        val toolMenu = ToolPermissionMenu(this, toolController, toolDefinitions, chanponStorage)
        toolPermissionCapability =
            ToolPermissionCapability(toolMenu, toolController, toolDefinitions)
        worldBackupCapability = WorldBackupCapability(
            this,
            chanponStorage,
            submittedProtectionService,
            backupService,
            automationService,
            manualRollbackService,
        )
        linkedPortalCapability = LinkedPortalCapability()
        productionToggleExtension.syncForcedTagsForProductionWorlds()
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

    private fun verifyGuiRuntimeContract(): Boolean {
        val ccSystemPlugin = server.pluginManager.getPlugin("CC-System")
        if (ccSystemPlugin == null || !ccSystemPlugin.isEnabled) {
            logger.severe("CC-System is missing or disabled; MWM-Chanpon cannot safely register GUI runtime integrations.")
            server.pluginManager.disablePlugin(this)
            return false
        }
        val actualVersion = runCatching { CCSystem.getAPI().guiRuntimeContractVersion }
            .getOrElse { exception ->
                logger.severe("Failed to read CC-System GUI runtime contract version: ${exception.message ?: exception.javaClass.simpleName}")
                server.pluginManager.disablePlugin(this)
                return false
            }
        if (actualVersion != REQUIRED_GUI_RUNTIME_CONTRACT_VERSION) {
            logger.severe(
                "CC-System GUI runtime contract version mismatch: " +
                    "expected=$REQUIRED_GUI_RUNTIME_CONTRACT_VERSION, actual=$actualVersion",
            )
            server.pluginManager.disablePlugin(this)
            return false
        }
        return true
    }

    override fun onDisable() {
        if (::playerWorldCapability.isInitialized) {
            MyWorldManagerApi.unregisterPlayerWorldRouteCapability(playerWorldCapability)
        }
        runCatching { CCSystem.getAPI().getMenuReversibleStateProviderRegistry().unregisterOwner("mwm-chanpon") }
        runCatching { CCSystem.getAPI().getConfigSchemaService().unregister("mwm-chanpon") }
        runCatching { CCSystem.getAPI().getMenuCommandService().unregisterOwner("mwm-chanpon") }
        runCatching { CCSystem.getAPI().getMenuCapabilityService().unregisterOwner("mwm-chanpon") }
        runCatching { CCSystem.getAPI().getMenuRuntimeService().unregisterOwner("mwm-chanpon") }
        if (::spawnWorldService.isInitialized) {
            MyWorldManagerApi.unregisterWorldEvacuationProvider(spawnWorldService)
        }
        if (::productionWorldGuard.isInitialized) {
            MyWorldManagerApi.unregisterWorldDeleteGuard(productionWorldGuard)
        }
        if (::worldAccessPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldAccessPolicy(worldAccessPolicy)
        }
        if (::worldMenuAccessPolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldMenuAccessPolicy(worldMenuAccessPolicy)
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
        if (::commandExtension.isInitialized) {
            MyWorldManagerApi.unregisterCommandPolicy(commandExtension)
        }
        if (::worldCreationGuard.isInitialized) {
            MyWorldManagerApi.unregisterWorldCreationGuard(worldCreationGuard)
        }
        if (::worldPlayerStatePolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldPlayerStatePolicy(worldPlayerStatePolicy)
        }
        if (::worldSettingsStatePolicy.isInitialized) {
            MyWorldManagerApi.unregisterWorldSettingsStatePolicy(
                worldSettingsStatePolicy,
            )
        }
        if (::automationService.isInitialized) {
            automationService.stop()
        }
        if (::toolController.isInitialized) {
            toolController.clearOnlineActivePermissions()
        }
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
        private const val REQUIRED_GUI_RUNTIME_CONTRACT_VERSION = 5
    }

}
