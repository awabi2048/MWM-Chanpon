package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.listener.CreateCommandInterceptor
import me.awabi2048.mwmchanpon.service.WizardSessionService
import me.awabi2048.mwmchanpon.ui.AdminWizardGui
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    lateinit var wizardSessionService: WizardSessionService
        private set
    lateinit var adminWizardGui: AdminWizardGui
        private set
    private val coreApiBridge = CoreApiBridge()

    override fun onEnable() {
        saveDefaultConfig()

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

    fun getMessage(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String {
        var raw = CCSystem.getAPI().getI18nString(player, key)
        for ((k, v) in placeholders) raw = raw.replace("{$k}", v.toString())
        return raw
    }

    fun getCoreApiBridge(): CoreApiBridge = coreApiBridge
}
