package me.awabi2048.mwmchanpon.service

import java.util.UUID

object StandaloneWorldExportLayout {
    const val NAMESPACE = "mwm_export"
    // 稼働サーバー26.2のversion.json（data_major=107、data_minor=1）に合わせます。
    const val DATA_PACK_FORMAT = 107
    const val DATA_PACK_MINOR = 1

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
            "min_format": [$DATA_PACK_FORMAT, $DATA_PACK_MINOR],
            "max_format": [$DATA_PACK_FORMAT, $DATA_PACK_MINOR],
            "description": "MWM-Chanpon standalone world dimensions"
          }
        }
        """.trimIndent()
}
