package me.awabi2048.mwmchanpon

import me.awabi2048.mwmchanpon.command.MwmCommand
import me.awabi2048.mwmchanpon.listener.WizardListener
import me.awabi2048.mwmchanpon.repository.WorldRepository
import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.PlatformUiService
import me.awabi2048.mwmchanpon.service.TemplateService
import me.awabi2048.mwmchanpon.service.WorldService
import me.awabi2048.mwmchanpon.service.WizardSessionService
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    lateinit var templateService: TemplateService
        private set
    lateinit var worldRepository: WorldRepository
        private set
    lateinit var wizardSessionService: WizardSessionService
        private set
    lateinit var worldService: WorldService
        private set
    lateinit var platformUiService: PlatformUiService
        private set
    lateinit var localizationService: LocalizationService
        private set

    override fun onEnable() {
        saveDefaultConfig()
        saveResource("templates.yml", false)
        saveResource("lang/ja_jp.yml", false)
        saveResource("lang/en_us.yml", false)

        localizationService = LocalizationService(this)
        templateService = TemplateService(this)
        worldRepository = WorldRepository(this)
        wizardSessionService = WizardSessionService(this)
        worldService = WorldService(this, worldRepository, templateService)
        platformUiService = PlatformUiService(this, localizationService, wizardSessionService, templateService, worldService)

        val mwmCommand = MwmCommand(localizationService, wizardSessionService, platformUiService)
        val commandName = config.getString("menu_command", "mwm") ?: "mwm"
        val command = requireNotNull(getCommand(commandName)) {
            "menu_command で指定されたコマンドが plugin.yml に見つかりません"
        }
        registerCommand(command, mwmCommand)

        server.pluginManager.registerEvents(WizardListener(this, wizardSessionService, platformUiService), this)
        templateService.reload()

        logger.info("MWM-Chanpon has been enabled.")
    }

    override fun onDisable() {
        wizardSessionService.clearAll()
        logger.info("MWM-Chanpon has been disabled.")
    }

    private fun registerCommand(command: PluginCommand, executor: MwmCommand) {
        command.setExecutor(executor)
        command.tabCompleter = executor
    }
}
