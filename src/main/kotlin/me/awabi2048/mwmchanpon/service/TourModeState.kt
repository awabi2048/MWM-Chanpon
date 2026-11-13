package me.awabi2048.mwmchanpon.service

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/** 観光モードの状態を一箇所で参照し、常駐化と操作時の保護を一致させます。 */
internal object TourModeState {
    fun isEnabled(): Boolean {
        val utilities = Bukkit.getPluginManager().getPlugin("Chanpon-Utilities") as? JavaPlugin ?: return false
        return utilities.isEnabled && utilities.config.getBoolean("features.tour_mode.enabled", false)
    }
}
