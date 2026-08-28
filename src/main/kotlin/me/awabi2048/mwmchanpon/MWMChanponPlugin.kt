package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.CCSystemAPI
import com.awabi2048.ccsystem.api.localization.LocalizationKey
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
import me.awabi2048.mwmchanpon.extension.ChanponMemberManagementPasswordCapability
import me.awabi2048.mwmchanpon.extension.WorldBackupCapability
import me.awabi2048.mwmchanpon.listener.MemberPermissionSyncListener
import me.awabi2048.mwmchanpon.listener.FreecamWorldPolicyListener
import me.awabi2048.mwmchanpon.listener.MyWorldSafetyDefaultsListener
import me.awabi2048.mwmchanpon.listener.PlayerJoinListener
import me.awabi2048.mwmchanpon.listener.SubmittedWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.SpawnWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.ToolPermissionWorldListener
import me.awabi2048.mwmchanpon.listener.WorldCleanupListener
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.mwmchanpon.protection.MyWorldSafetyDefaultsService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ChanponSpawnWorldService
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.service.ArchiveOperationGuard
import me.awabi2048.mwmchanpon.service.ManualRollbackService
import me.awabi2048.mwmchanpon.service.OfflineMemberInviteNotifier
import me.awabi2048.mwmchanpon.service.PublishLevelSynchronizer
import me.awabi2048.mwmchanpon.service.SubmissionCompletionMacroService
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
import me.awabi2048.mwmchanpon.ui.ChanponDiscoveryMenu
import me.awabi2048.mwmchanpon.ui.ChanponFavoriteListMenu
import me.awabi2048.mwmchanpon.ui.ChanponPlayerWorldCapability
import me.awabi2048.mwmchanpon.ui.ChanponWorldSettingsMenu
import me.awabi2048.mwmchanpon.ui.SubmissionAdminMenu
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.mwmchanpon.ui.WorldDataExportMenu
import me.awabi2048.mwmchanpon.ui.WorldDataManagementMenu
import me.awabi2048.mwmchanpon.ui.bedrock.AdminWizardForm
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipant
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantState
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class MWMChanponPlugin : JavaPlugin() {
    private val coreApiBridge = CoreApiBridge()
    private lateinit var productionToggleExtension: ProductionToggleExtension
    private lateinit var productionWorldGuard: ProductionWorldGuard
    private lateinit var submittedProtectionService: SubmittedWorldProtectionService
    private lateinit var chanponWorldSettingsMenu: ChanponWorldSettingsMenu
    private lateinit var chanponDiscoveryMenu: ChanponDiscoveryMenu
    private lateinit var chanponFavoriteListMenu: ChanponFavoriteListMenu
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
    private lateinit var offlineMemberInviteNotifier: OfflineMemberInviteNotifier
    private lateinit var spawnProtectionService: SpawnWorldProtectionService
    private lateinit var buildPermissionService: WorldBuildPermissionService
    private lateinit var worldSafetyDefaultsService: MyWorldSafetyDefaultsService
    private lateinit var easyVoidWorldCreationService: EasyVoidWorldCreationService
    private lateinit var publishLevelSynchronizer: PublishLevelSynchronizer
    private lateinit var configMigrationParticipant: ChanponConfigMigrationParticipant
    private lateinit var chanponStorage: ChanponWorldStorage
    private lateinit var submissionCompletionMacroService: SubmissionCompletionMacroService

    override fun onEnable() {
        if (!verifyGuiRuntimeContract()) return
        saveDefaultConfig()
        configMigrationParticipant = ChanponConfigMigrationParticipant(this)
        MyWorldManagerApi.registerMigrationParticipant(configMigrationParticipant, configMigrationParticipant::executeMigration)
        reloadConfig()
        submissionCompletionMacroService = createSubmissionCompletionMacroService()
        chanponStorage = ChanponWorldStorage(dataFolder)
        publishLevelSynchronizer = PublishLevelSynchronizer()
        publishLevelSynchronizer.setSynchronizationGate(chanponStorage::hasCurrentWorldData)
        chanponStorage.setVisibilitySyncHandler(publishLevelSynchronizer::sync)
        chanponStorage.setWorldWriteGate { worldUuid ->
            isConfigurationMigrationCurrent() && !MyWorldManagerApi.isWorldMigrationPending(worldUuid)
        }
        globalStorage = ChanponGlobalStorage(dataFolder)
        globalStorage.setCurrentWriteGate {
            isConfigurationMigrationCurrent()
        }
        offlineMemberInviteNotifier = OfflineMemberInviteNotifier(globalStorage, logger)
        spawnWorldService = ChanponSpawnWorldService(this, globalStorage)
        val luckPerms = LuckPermsProvider.get()
        spawnProtectionService = SpawnWorldProtectionService()
        worldSafetyDefaultsService = MyWorldSafetyDefaultsService(logger)
        val toolDefinitions = ToolPermissionConfig(this).load()
        val isAutoRollbackEnabled = {
            isConfigurationMigrationCurrent() && config.getBoolean("backup.auto_rollback.enabled", false)
        }
        worldWorkPermissionPolicy = ChanponWorldWorkPermissionPolicy(chanponStorage, isAutoRollbackEnabled).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
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
        submittedProtectionService = SubmittedWorldProtectionService(chanponStorage, logger, isAutoRollbackEnabled).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
        buildPermissionService = WorldBuildPermissionService(chanponStorage, logger).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
        automationSettingsMenu = AutomationSettingsMenu(this, backupService, automationService) {
            MyWorldManagerApi.getWorldRepository()?.findAll()?.let { worlds ->
                submittedProtectionService.syncAll(worlds)
                worlds.forEach(toolController::syncWorld)
            }
        }
        backupService.setPostRestoreHandler { restoredWorlds ->
            val repository = MyWorldManagerApi.getWorldRepository() ?: return@setPostRestoreHandler
            restoredWorlds.mapNotNull(repository::findByUuid).forEach { worldData ->
                worldSafetyDefaultsService.sync(worldData)
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
            toolController,
            submissionCompletionMacroService,
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
        MyWorldManagerApi.registerWorldSettingsRouteCapability(chanponWorldSettingsMenu)
        worldSettingsCapabilities = ChanponWorldSettingsCapabilities(
            chanponWorldSettingsMenu,
            chanponStorage,
        )
        easyVoidWorldCreationService = EasyVoidWorldCreationService(this)
        val archiveOperationGuard = ArchiveOperationGuard(globalStorage)
        archiveOperationGuard.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        playerWorldCapability = ChanponPlayerWorldCapability(this, chanponStorage, easyVoidWorldCreationService)
        MyWorldManagerApi.registerPlayerWorldRouteCapability(playerWorldCapability)
        chanponDiscoveryMenu = ChanponDiscoveryMenu(this, chanponStorage)
        MyWorldManagerApi.registerDiscoveryRouteCapability(chanponDiscoveryMenu)
        chanponFavoriteListMenu = ChanponFavoriteListMenu(this)
        MyWorldManagerApi.registerFavoriteListRouteCapability(chanponFavoriteListMenu)
        worldAccessPolicy = ChanponWorldAccessPolicy(archiveOperationGuard, chanponStorage)
        worldMenuAccessPolicy = ChanponWorldMenuAccessPolicy(chanponStorage)
        worldPortalPolicy = ChanponWorldPortalPolicy(chanponStorage)
        worldPublishPolicy = ChanponWorldPublishPolicy(chanponStorage).also {
            it.setConfigurationWriteGate(::isConfigurationMigrationCurrent)
        }
        worldRuntimePolicy = ChanponWorldRuntimePolicy()
        commandExtension = ChanponCommandExtension(archiveOperationGuard)
        worldCreationGuard = ChanponWorldCreationGuard(this, globalStorage)
        worldPlayerStatePolicy = ChanponWorldPlayerStatePolicy(globalStorage, chanponStorage).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
        worldSettingsStatePolicy =
            ChanponWorldSettingsStatePolicy(chanponStorage, submittedProtectionService)
        MyWorldManagerApi.registerWorldCreationGuard(worldCreationGuard)
        MyWorldManagerApi.registerWorldPlayerStatePolicy(worldPlayerStatePolicy)
        MyWorldManagerApi.registerWorldSettingsStatePolicy(worldSettingsStatePolicy)
        MyWorldManagerApi.registerWorldDeleteGuard(productionWorldGuard)
        // Discovery / Favoriteは既存Route CapabilityでChanpon画面へ差し替え、MWMの標準画面はフォールバックとして残す。
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
        server.pluginManager.registerEvents(SubmittedWorldProtectionListener(this, submittedProtectionService), this)
        server.pluginManager.registerEvents(SpawnWorldProtectionListener(this, globalStorage, spawnProtectionService), this)
        server.pluginManager.registerEvents(MyWorldSafetyDefaultsListener(this, worldSafetyDefaultsService), this)

        val toolMenu = ToolPermissionMenu(this, toolController, toolDefinitions, chanponStorage)
        toolPermissionCapability =
            ToolPermissionCapability(toolMenu, toolController, toolDefinitions)
        // メンバー参加パスワード設定をメンバー管理メニューのフッター3マス目へ提供します。
        ChanponMemberManagementPasswordCapability(chanponStorage, buildPermissionService)
        worldBackupCapability = WorldBackupCapability(
            this,
            chanponStorage,
            submittedProtectionService,
            backupService,
            automationService,
            manualRollbackService,
        )
        linkedPortalCapability = LinkedPortalCapability()
        if (isConfigurationMigrationCurrent()) {
            productionToggleExtension.syncForcedTagsForProductionWorlds()
        } else {
            logger.warning("Production forced tags are paused until /mwm migration completes.")
        }
        server.pluginManager.registerEvents(MemberPermissionSyncListener(toolController, chanponStorage), this)
        server.pluginManager.registerEvents(
            PlayerJoinListener(toolController, spawnWorldService, offlineMemberInviteNotifier),
            this,
        )
        server.pluginManager.registerEvents(ToolPermissionWorldListener(toolController), this)
        server.pluginManager.registerEvents(WorldCleanupListener(chanponStorage, toolController), this)

        server.scheduler.runTask(this, Runnable {
            if (!isConfigurationMigrationCurrent()) {
                logger.warning("Configuration-dependent Chanpon startup synchronization is paused until /mwm migration completes.")
                return@Runnable
            }
            MyWorldManagerApi.getWorldRepository()?.findAll()?.let {
                worldSafetyDefaultsService.syncAll(it)
                publishLevelSynchronizer.syncAll()
                submittedProtectionService.syncAll(it)
                it.forEach(toolController::syncWorld)
            }
            globalStorage.getSpawnWorldUuid()
                ?.takeUnless(globalStorage::isSpawnPolicyInitialized)
                ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }
                ?.let { worldData ->
                    if (spawnProtectionService.applyDefaults(worldData)) {
                        runCatching { globalStorage.setSpawnPolicyInitialized(worldData.uuid) }
                            .onFailure {
                                logger.warning("Spawn policy state was not persisted until /mwm migration completes.")
                            }
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
        if (::chanponWorldSettingsMenu.isInitialized) {
            MyWorldManagerApi.unregisterWorldSettingsRouteCapability(chanponWorldSettingsMenu)
        }
        if (::playerWorldCapability.isInitialized) {
            MyWorldManagerApi.unregisterPlayerWorldRouteCapability(playerWorldCapability)
        }
        if (::chanponDiscoveryMenu.isInitialized) {
            MyWorldManagerApi.unregisterDiscoveryRouteCapability(chanponDiscoveryMenu)
        }
        if (::chanponFavoriteListMenu.isInitialized) {
            MyWorldManagerApi.unregisterFavoriteListRouteCapability(chanponFavoriteListMenu)
        }
        runCatching { CCSystem.getAPI().getMenuReversibleStateProviderRegistry().unregisterOwner("mwm-chanpon") }
        if (::configMigrationParticipant.isInitialized) {
            MyWorldManagerApi.unregisterMigrationParticipant(configMigrationParticipant)
        }
        if (::chanponStorage.isInitialized) {
            MyWorldManagerApi.unregisterMigrationParticipant(chanponStorage.migrationParticipant)
        }
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

    /**
     * config.yml の通常更新は、/mwm migration が完了している場合だけ許可します。
     * JavaPlugin.saveConfig() を各機能から直接呼ばせると旧形式が暗黙に再保存されるため、
     * 既存の可逆操作・自動処理を含めてこのゲートを通します。
     */
    fun saveConfigIfCurrent() {
        check(isConfigurationMigrationCurrent()) {
            "MWM-Chanpon config.yml/settings.yml require /mwm migration before they can be modified"
        }
        val target = File(dataFolder, "config.yml")
        val temporary = File(dataFolder, "config.yml.save.tmp")
        try {
            config.save(temporary)
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            val verified = org.bukkit.configuration.file.YamlConfiguration().also { it.load(temporary) }
            check((verified.get("config_version") as? Number)?.toInt() == 4) {
                "Current Chanpon config was not verified after serialization"
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            Files.deleteIfExists(temporary.toPath())
            reloadConfig()
            throw IllegalStateException("Could not save current Chanpon config", error)
        }
    }

    fun isConfigurationMigrationCurrent(): Boolean =
        configMigrationParticipant.status().state == ApiMigrationParticipantState.CURRENT

    /**
     * Chanpon専用の設定を配置したまま、MyWorldManagerの既存マクロ基盤を生成します。
     * MyWorldManager本体のmacro.ymlとは別ファイルを使うため、両者の設定を混在させません。
     */
    private fun createSubmissionCompletionMacroService(): SubmissionCompletionMacroService {
        val macroFile = File(dataFolder, "macro.yml")
        if (!macroFile.exists()) {
            saveResource("macro.yml", false)
        }
        val macroService = MyWorldManagerApi.createMacroService(this, macroFile)
        return SubmissionCompletionMacroService(macroService, logger)
    }

    fun getMessage(
        player: Player?,
        key: LocalizationKey<String>,
        vararg placeholders: Pair<String, Any>,
    ): String = CCSystem.getAPI().getLocalized(player, key, placeholders.toMap())

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge

    fun isSubmissionClosed(): Boolean = config.getBoolean("submission.closed", false)

    private companion object {
        private const val MENU_OWNER = "mwm-chanpon"
        private const val REQUIRED_GUI_RUNTIME_CONTRACT_VERSION = CCSystemAPI.GUI_RUNTIME_CONTRACT_VERSION
    }

}
