package me.awabi2048.mwmchanpon

import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.listener.CreateCommandInterceptor
import me.awabi2048.mwmchanpon.service.WizardSessionService
import me.awabi2048.mwmchanpon.ui.AdminWizardGui
import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    lateinit var wizardSessionService: WizardSessionService
        private set
    lateinit var adminWizardGui: AdminWizardGui
        private set
    private val coreApiBridge = CoreApiBridge()

    override fun onEnable() {
        saveDefaultConfig()
        saveResource("lang/ja_jp.yml", false)
        saveResource("lang/en_us.yml", false)

        val timeoutSeconds = config.getLong("wizard.timeout_seconds", 300)
        wizardSessionService = WizardSessionService(timeoutSeconds, logger)

        adminWizardGui = AdminWizardGui(this, coreApiBridge)
        server.pluginManager.registerEvents(adminWizardGui, this)

        server.pluginManager.registerEvents(CreateCommandInterceptor(this, adminWizardGui), this)

        logger.info("MWM-Chanpon addon has been enabled.")
    }

    override fun onDisable() {
        wizardSessionService.clearAll()
        logger.info("MWM-Chanpon addon has been disabled.")
    }

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge
}
