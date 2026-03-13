package dev.awabi2048.mwmchanpon

import org.bukkit.plugin.java.JavaPlugin

class MWMChanponPlugin : JavaPlugin() {
    override fun onEnable() {
        logger.info("MWM-Chanpon has been enabled.")
    }

    override fun onDisable() {
        logger.info("MWM-Chanpon has been disabled.")
    }
}
