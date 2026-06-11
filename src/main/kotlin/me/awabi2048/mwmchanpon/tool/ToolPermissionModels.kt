package me.awabi2048.mwmchanpon.tool

import org.bukkit.Material

data class ToolPermissionDefinition(
    val id: String,
    val displayName: String,
    val material: Material,
    val groupName: String,
    val color: String = "§7"
)
