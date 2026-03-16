package me.awabi2048.mwmchanpon

import me.awabi2048.mwmchanpon.command.MyWorldCommand
import me.awabi2048.mwmchanpon.command.MwmCommand
import me.awabi2048.mwmchanpon.command.WorldMenuCommand
import me.awabi2048.mwmchanpon.repository.PlayerSettingsRepository
import me.awabi2048.mwmchanpon.listener.WizardListener
import me.awabi2048.mwmchanpon.repository.WorldRepository
import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.PlayerSettingsService
import me.awabi2048.mwmchanpon.service.PlatformUiService
import me.awabi2048.mwmchanpon.service.TemplateService
import me.awabi2048.mwmchanpon.service.WorldService
import me.awabi2048.mwmchanpon.service.WizardSessionService
import org.bukkit.command.PluginCommand
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    lateinit var templateService: TemplateService
        private set
    lateinit var worldRepository: WorldRepository
        private set
    lateinit var playerSettingsRepository: PlayerSettingsRepository
        private set
    lateinit var wizardSessionService: WizardSessionService
        private set
    lateinit var worldService: WorldService
        private set
    lateinit var platformUiService: PlatformUiService
        private set
    lateinit var playerSettingsService: PlayerSettingsService
        private set
    lateinit var localizationService: LocalizationService
        private set
    private var wizardListener: WizardListener? = null

    override fun onEnable() {
        initializePluginState()
        logger.info("MWM-Chanpon has been enabled.")
    }

    fun reloadPluginState() {
        wizardSessionService.clearAll()
        wizardListener?.let(HandlerList::unregisterAll)
        reloadConfig()
        initializePluginState()
    }

    override fun onDisable() {
        wizardSessionService.clearAll()
        wizardListener?.let(HandlerList::unregisterAll)
        logger.info("MWM-Chanpon has been disabled.")
    }

    private fun initializePluginState() {
        saveDefaultConfig()
        saveResource("templates.yml", false)
        saveResource("lang/ja_jp.yml", false)
        saveResource("lang/en_us.yml", false)

        worldRepository = WorldRepository(this)
        playerSettingsRepository = PlayerSettingsRepository(this)
        playerSettingsService = PlayerSettingsService(playerSettingsRepository)
        localizationService = LocalizationService(this, playerSettingsService)
        localizationService.clearCache()
        templateService = TemplateService(this)
        wizardSessionService = WizardSessionService(this)
        worldService = WorldService(this, worldRepository, templateService)
        platformUiService = PlatformUiService(this, localizationService, playerSettingsService, wizardSessionService, templateService, worldService)

        val mwmCommand = MwmCommand(this, localizationService, wizardSessionService, platformUiService)
        val commandName = config.getString("menu_command", "mwm") ?: "mwm"
        val command = requireNotNull(getCommand(commandName)) {
            "menu_command で指定されたコマンドが plugin.yml に見つかりません"
        }
        registerCommand(command, mwmCommand)
        registerCommand(requireNotNull(getCommand("myworld")) { "myworld コマンドが plugin.yml に見つかりません" }, MyWorldCommand(localizationService, platformUiService))
        registerCommand(requireNotNull(getCommand("worldmenu")) { "worldmenu コマンドが plugin.yml に見つかりません" }, WorldMenuCommand(localizationService, worldService, platformUiService))

        wizardListener = WizardListener(this, wizardSessionService, platformUiService)
        server.pluginManager.registerEvents(wizardListener!!, this)
        templateService.reload()
    }

    private fun registerCommand(command: PluginCommand, executor: org.bukkit.command.CommandExecutor) {
        command.setExecutor(executor)
        if (executor is org.bukkit.command.TabCompleter) {
            command.tabCompleter = executor
        }
    }
}
