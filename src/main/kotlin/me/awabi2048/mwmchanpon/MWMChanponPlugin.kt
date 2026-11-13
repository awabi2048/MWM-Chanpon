package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.CCSystemAPI
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.gui.MenuTargetPolicy
import com.awabi2048.ccsystem.api.gui.PublicMenuDefinition
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.integration.TourSimulationIntegration
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
import me.awabi2048.mwmchanpon.listener.BuildDenialReasonListener
import me.awabi2048.mwmchanpon.listener.MemberPermissionSyncListener
import me.awabi2048.mwmchanpon.listener.WorldVisitListener
import me.awabi2048.mwmchanpon.listener.FreecamWorldPolicyListener
import me.awabi2048.mwmchanpon.listener.MyWorldNaturalEntityCleanupListener
import me.awabi2048.mwmchanpon.listener.MyWorldSafetyDefaultsListener
import me.awabi2048.mwmchanpon.listener.PlayerJoinListener
import me.awabi2048.mwmchanpon.listener.SubmittedWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.WorldBuildPermissionListener
import me.awabi2048.mwmchanpon.listener.PortalSettingsAccessListener
import me.awabi2048.mwmchanpon.listener.SpawnWorldProtectionListener
import me.awabi2048.mwmchanpon.listener.ToolPermissionWorldListener
import me.awabi2048.mwmchanpon.listener.WorldCleanupListener
import me.awabi2048.mwmchanpon.protection.BuildDenialReasonResolver
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.mwmchanpon.protection.PublicWorldFawePermissionService
import me.awabi2048.mwmchanpon.protection.MyWorldSafetyDefaultsService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ChanponSpawnWorldService
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.service.HonbanSetMacroService
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
import me.awabi2048.mwmchanpon.tour.PreviewTourCommand
import me.awabi2048.mwmchanpon.tour.PreviewTourListener
import me.awabi2048.mwmchanpon.tour.PreviewTourService
import me.awabi2048.mwmchanpon.tour.PreviewTourStorage
import me.awabi2048.mwmchanpon.tool.LuckPermsToolPermissionService
import me.awabi2048.mwmchanpon.tool.ToolPermissionConfig
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
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
import me.awabi2048.mwmchanpon.ui.PreviewTourAdminMenu
import me.awabi2048.mwmchanpon.ui.SubmissionAdminMenu
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.mwmchanpon.ui.WorldDataExportMenu
import me.awabi2048.mwmchanpon.ui.WorldDataManagementMenu
import me.awabi2048.mwmchanpon.ui.bedrock.AdminWizardForm
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmSystemReloadEvent
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipant
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantState
import me.awabi2048.myworldmanager.api.service.ApiMacroService
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class MWMChanponPlugin : JavaPlugin() {
    private var tourSimulationIntegration: TourSimulationIntegration? = null
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
    private lateinit var toolDefinitions: MutableList<ToolPermissionDefinition>
    private lateinit var offlineMemberInviteNotifier: OfflineMemberInviteNotifier
    private lateinit var spawnProtectionService: SpawnWorldProtectionService
    private lateinit var buildPermissionService: WorldBuildPermissionService
    private lateinit var worldSafetyDefaultsService: MyWorldSafetyDefaultsService
    private lateinit var easyVoidWorldCreationService: EasyVoidWorldCreationService
    private lateinit var publishLevelSynchronizer: PublishLevelSynchronizer
    private lateinit var configMigrationParticipant: ChanponConfigMigrationParticipant
    private lateinit var chanponStorage: ChanponWorldStorage
    private lateinit var chanponMacroService: ApiMacroService
    private lateinit var submissionCompletionMacroService: SubmissionCompletionMacroService
    private lateinit var honbanSetMacroService: HonbanSetMacroService
    private lateinit var previewTourStorage: PreviewTourStorage
    private lateinit var previewTourService: PreviewTourService
    private lateinit var previewTourAdminMenu: PreviewTourAdminMenu

    override fun onEnable() {
        if (!verifyGuiRuntimeContract()) return
        saveDefaultConfig()
        configMigrationParticipant = ChanponConfigMigrationParticipant(this)
        MyWorldManagerApi.registerMigrationParticipant(configMigrationParticipant, configMigrationParticipant::executeMigration)
        reloadConfig()
        chanponMacroService = createChanponMacroService()
        submissionCompletionMacroService = SubmissionCompletionMacroService(chanponMacroService, logger)
        honbanSetMacroService = HonbanSetMacroService(chanponMacroService, logger)
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
        previewTourStorage = PreviewTourStorage(dataFolder)
        previewTourStorage.setCurrentWriteGate { isConfigurationMigrationCurrent() }
        previewTourService = PreviewTourService(this)
        previewTourAdminMenu = PreviewTourAdminMenu(this, previewTourStorage, previewTourService)
        PreviewTourListener.initKeys(this)
        spawnWorldService = ChanponSpawnWorldService(this, globalStorage)
        val luckPerms = LuckPermsProvider.get()
        spawnProtectionService = SpawnWorldProtectionService()
        worldSafetyDefaultsService = MyWorldSafetyDefaultsService(logger, chanponStorage::isRandomTickStopped)
        val publicWorldFawePermissionService = PublicWorldFawePermissionService(luckPerms, logger)
        buildPermissionService = WorldBuildPermissionService(
            chanponStorage,
            logger,
            isSpawnWorld = { worldUuid -> globalStorage.getSpawnWorldUuid() == worldUuid },
            publicWorldFawePermissionService = publicWorldFawePermissionService,
        ).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
        toolDefinitions = ToolPermissionConfig(config).load().toMutableList()
        val isAutoRollbackEnabled = {
            isConfigurationMigrationCurrent() && config.getBoolean("backup.auto_rollback.enabled", false)
        }
        // 建築許可の実効モード（未記録のスポーンワールドはオーナー限定）を作業権限判定にも使います。
        worldWorkPermissionPolicy = ChanponWorldWorkPermissionPolicy(
            chanponStorage,
            globalStorage,
            isAutoRollbackEnabled,
            buildPermissionService::effectiveProtectionMode,
            { data, playerId -> submittedProtectionService.isProtectedForPlayer(data, server.getPlayer(playerId)) },
        ).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
        toolController = ToolPermissionController(
            chanponStorage,
            LuckPermsToolPermissionService(luckPerms, logger),
            toolDefinitions,
            isAutoRollbackEnabled,
            globalStorage::isBuildingAllowed,
            chanponStorage::isGlobalBypassAllowed,
            buildPermissionService::effectiveProtectionMode,
            buildPermissionService::sync,
            { data, playerId -> submittedProtectionService.isProtectedForPlayer(data, server.getPlayer(playerId)) },
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
        submittedProtectionService = SubmittedWorldProtectionService(
            chanponStorage, logger, isAutoRollbackEnabled,
            globalStorage::isRepairBypassEnabled, globalStorage::isTourSimulationEnabled,
        ).also {
            it.setConfigurationAvailability(::isConfigurationMigrationCurrent)
        }
        // LuckPermsの権限変更は別スレッドでも届くため、リージョン更新はサーバースレッドへ戻します。
        luckPerms.eventBus.subscribe(this, net.luckperms.api.event.user.UserDataRecalculateEvent::class.java) { event ->
            server.scheduler.runTask(this, Runnable {
                val player = server.getPlayer(event.user.uniqueId) ?: return@Runnable
                val world = MyWorldManagerApi.getWorldRepository()?.findByWorldKey(player.world.key.toString()) ?: return@Runnable
                if (globalStorage.isTourSimulationEnabled(world.uuid)) submittedProtectionService.sync(world)
            })
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
            toolController,
            honbanSetMacroService,
        )
        submissionAdminMenu = SubmissionAdminMenu(
            this,
            chanponStorage,
            globalStorage,
            submittedProtectionService,
            toolController,
            submissionCompletionMacroService,
        )
        chanponAdminMenu = ChanponAdminMenu(
            this,
            globalStorage,
            spawnProtectionService,
            ::syncBuildPermissions,
            submissionAdminMenu,
            worldDataManagementMenu,
            automationSettingsMenu,
            worldDataExportMenu,
            previewTourAdminMenu,
        )
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
        environmentGui = ChanponEnvironmentGui(this, chanponStorage, submittedProtectionService, buildPermissionService, worldSafetyDefaultsService, toolController, toolDefinitions)
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
        if (server.pluginManager.isPluginEnabled("Chanpon-Utilities")) {
            tourSimulationIntegration = TourSimulationIntegration(this, globalStorage).also { it.start() }
        } else if (globalStorage.simulatedWorldUuids().isNotEmpty()) {
            logger.severe("観光用シミュレーションは保存されていますが、Chanpon-Utilities が無効なため観光モードを適用できません。")
        }
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
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onReload(event: MwmSystemReloadEvent) {
                runCatching { reloadChanponSettings() }.onFailure { error ->
                    logger.log(java.util.logging.Level.SEVERE, "MWM-Chanpon の設定再読込に失敗しました", error)
                    event.reportFailure("MWM-Chanpon")
                }
            }
        }, this)
        server.pluginManager.registerEvents(productionToggleExtension, this)
        if (server.pluginManager.isPluginEnabled("Chanpon-Utilities")) {
            server.pluginManager.registerEvents(FreecamWorldPolicyListener(chanponStorage), this)
        }
        server.pluginManager.registerEvents(me.awabi2048.mwmchanpon.listener.InteractionAdjacentPhysicsListener(this), this)
        server.pluginManager.registerEvents(publishLevelSynchronizer, this)
        server.pluginManager.registerEvents(SubmittedWorldProtectionListener(this, submittedProtectionService), this)
        server.pluginManager.registerEvents(WorldBuildPermissionListener(this, buildPermissionService), this)
        server.pluginManager.registerEvents(PortalSettingsAccessListener(globalStorage::isBuildingAllowed), this)
        server.pluginManager.registerEvents(SpawnWorldProtectionListener(this, globalStorage, spawnProtectionService), this)
        server.pluginManager.registerEvents(MyWorldSafetyDefaultsListener(this, worldSafetyDefaultsService), this)
        server.pluginManager.registerEvents(MyWorldNaturalEntityCleanupListener(this), this)
        // 建築拒否の理由通知は、拒否実施側（WorldGuard・外部ポリシー）の結果だけを
        // 観測するため、全リスナー完了後のMONITORでキャンセル済みイベントを拾います。
        server.pluginManager.registerEvents(
            BuildDenialReasonListener(
                this,
                BuildDenialReasonResolver(
                    chanponStorage,
                    globalStorage,
                    submittedProtectionService,
                    buildPermissionService,
                    ::isConfigurationMigrationCurrent,
                ),
            ),
            this,
        )

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
        server.pluginManager.registerEvents(WorldVisitListener(chanponStorage), this)
        server.pluginManager.registerEvents(
            PlayerJoinListener(toolController, spawnWorldService, offlineMemberInviteNotifier),
            this,
        )
        server.pluginManager.registerEvents(ToolPermissionWorldListener(toolController), this)
        server.pluginManager.registerEvents(WorldCleanupListener(chanponStorage, publicWorldFawePermissionService), this)
        server.pluginManager.registerEvents(PreviewTourListener(this, previewTourStorage, previewTourService), this)
        getCommand("preview-tour")?.let { command ->
            val executor = PreviewTourCommand(previewTourService)
            command.setExecutor(executor)
            command.tabCompleter = executor
        }

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
        me.awabi2048.mwmchanpon.service.CompletedWorldRetentionService(this, chanponStorage).start()
        automationService.start()
        previewTourService.start()

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
        tourSimulationIntegration?.close()
        tourSimulationIntegration = null
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
        if (::previewTourService.isInitialized) {
            previewTourService.shutdownAll()
        }
        if (::automationService.isInitialized) {
            automationService.stop()
        }
        if (::manualRollbackService.isInitialized) {
            manualRollbackService.stop()
        }
        if (::toolController.isInitialized) {
            toolController.clearOnlineActivePermissions()
        }
        logger.info("MWM-Chanpon addon has been disabled.")
    }

    /** 外部編集された設定の検証後、共有中の定義とワールド権限を同じ状態へ更新します。 */
    private fun reloadChanponSettings() {
        val status = configMigrationParticipant.status()
        check(status.state == ApiMigrationParticipantState.CURRENT) {
            status.message ?: "MWM-Chanpon の設定を検証できませんでした"
        }
        chanponMacroService.reload()
        reloadConfig()
        val definitions = ToolPermissionConfig(config).load()
        // 旧グループを先に外し、既存画面が保持するリストの参照を維持します。
        toolController.clearOnlineActivePermissions()
        toolDefinitions.clear()
        toolDefinitions.addAll(definitions)
        productionToggleExtension.syncForcedTagsForProductionWorlds()
        publishLevelSynchronizer.syncAll()
        MyWorldManagerApi.getWorldRepository()?.findAll()?.let { worlds ->
            worldSafetyDefaultsService.syncAll(worlds)
            submittedProtectionService.syncAll(worlds)
            worlds.forEach(toolController::syncWorld)
        }
        syncBuildingState()
        automationService.start()
        tourSimulationIntegration?.refresh()
        logger.info("MWM-Chanpon の設定を再読込しました。")
    }

    private fun syncBuildPermissions() {
        if (!::buildPermissionService.isInitialized) return
        // スポーン指定は未設定ワールドの実効モードも変えるため、領域だけでなく
        // 在室者の一時ツール権限と作業グループも同じ変更で更新します。
        val worlds = MyWorldManagerApi.getWorldRepository()?.findAll() ?: return
        if (::toolController.isInitialized) worlds.forEach(toolController::syncWorld)
        else worlds.forEach(buildPermissionService::sync)
    }

    /** 保存後に保護・作業権限を同じ実効状態へ収束させます。 */
    fun setRepairBypassEnabled(enabled: Boolean) {
        globalStorage.setRepairBypassEnabled(enabled)
        tourSimulationIntegration?.refresh()
        syncRepairProtection()
    }

    fun setTourSimulationEnabled(worldData: me.awabi2048.myworldmanager.model.WorldData, enabled: Boolean) {
        check(!enabled || isTourSimulationAvailable()) { "Chanpon-Utilities が利用できないため観光用シミュレーションを開始できません" }
        globalStorage.setTourSimulationEnabled(worldData.uuid, enabled)
        tourSimulationIntegration?.refresh()
        submittedProtectionService.sync(worldData)
        toolController.syncWorld(worldData)
        MyWorldManagerApi.syncOnlineWorldPlayerStates()
    }

    fun isRepairBypassEnabled(): Boolean = globalStorage.isRepairBypassEnabled()
    fun isTourSimulationAvailable(): Boolean = tourSimulationIntegration != null && server.pluginManager.isPluginEnabled("Chanpon-Utilities")
    fun isTourSimulationEnabled(worldUuid: java.util.UUID): Boolean = globalStorage.isTourSimulationEnabled(worldUuid)

    private fun syncRepairProtection() {
        val failures = mutableListOf<Throwable>()
        // 1ワールドの失敗で残りの保護同期を中断せず、最後に呼び出し元へ失敗を返します。
        MyWorldManagerApi.getWorldRepository()?.findAll()?.forEach { world ->
            runCatching { submittedProtectionService.sync(world) }.onFailure(failures::add)
            runCatching { toolController.syncWorld(world) }.onFailure(failures::add)
        }
        runCatching { syncBuildingState() }.onFailure(failures::add)
        if (failures.isNotEmpty()) {
            throw IllegalStateException("修正期間の状態を一部のワールドへ反映できませんでした", failures.first()).also { error ->
                failures.drop(1).forEach(error::addSuppressed)
            }
        }
    }

    fun setBuildingAllowed(allowed: Boolean) {
        globalStorage.setBuildingAllowed(allowed)
        syncBuildingState()
    }

    private fun syncBuildingState() {
        MyWorldManagerApi.syncOnlineWorldPlayerStates()
        // 全体の建築許可はツール活動と作業グループ付与の可否でもあるため、切替時に再同期します。
        toolController.syncOnlinePlayers()
        MyWorldManagerApi.getWorldRepository()?.findAll()?.forEach { worldData ->
            MyWorldManagerApi.syncWorldWorkPermissions(worldData.uuid)
        }
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
            check((verified.get("config_version") as? Number)?.toInt() == ChanponConfigMigrationParticipant.CURRENT_VERSION) {
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
    private fun createChanponMacroService(): ApiMacroService {
        val macroFile = File(dataFolder, "macro.yml")
        if (!macroFile.exists()) {
            saveResource("macro.yml", false)
        }
        return MyWorldManagerApi.createMacroService(this, macroFile)
    }

    fun getMessage(
        player: Player?,
        key: LocalizationKey<String>,
        vararg placeholders: Pair<String, Any>,
    ): String = CCSystem.getAPI().getLocalized(player, key, placeholders.toMap())

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge

    fun isSubmissionClosed(): Boolean = config.getBoolean("submission.closed", false)

    /** プレビューツアー等がワールドの提出・完成状態を参照するための公開窓口です。 */
    val chanponWorldStorage: ChanponWorldStorage
        get() = chanponStorage

    private companion object {
        private const val MENU_OWNER = "mwm-chanpon"
        private const val REQUIRED_GUI_RUNTIME_CONTRACT_VERSION = CCSystemAPI.GUI_RUNTIME_CONTRACT_VERSION
    }

}
