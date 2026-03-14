package me.awabi2048.mwmchanpon.model

import org.bukkit.Material

data class TemplateDefinition(
    val key: String,
    val path: String,
    val name: String,
    val description: List<String>,
    val icon: Material,
    val originLocation: TemplateOriginLocation?,
)

data class TemplateOriginLocation(
    val x: Double,
    val y: Double,
    val z: Double,
)
