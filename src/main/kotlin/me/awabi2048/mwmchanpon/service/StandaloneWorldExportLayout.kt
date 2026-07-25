package me.awabi2048.mwmchanpon.service

import java.util.UUID

object StandaloneWorldExportLayout {
    const val NAMESPACE = "mwm_export"
    const val DATA_PACK_FORMAT = 101

    fun dimensionId(worldUuid: UUID): String =
        "world_${worldUuid.toString().replace("-", "")}"

    fun dimensionKey(worldUuid: UUID): String =
        "$NAMESPACE:${dimensionId(worldUuid)}"

    fun dimensionDefinition(): String =
        """
        {
          "type": "minecraft:overworld",
          "generator": {
            "type": "minecraft:noise",
            "biome_source": {
              "type": "minecraft:multi_noise",
              "preset": "minecraft:overworld"
            },
            "settings": "minecraft:overworld"
          }
        }
        """.trimIndent()

    fun packMetadata(): String =
        """
        {
          "pack": {
            "pack_format": $DATA_PACK_FORMAT,
            "description": "MWM-Chanpon standalone world dimensions"
          }
        }
        """.trimIndent()
}
