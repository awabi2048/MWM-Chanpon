package me.awabi2048.mwmchanpon.service

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

internal class StandaloneAdditionalWorldDataPolicyTest {
    @ParameterizedTest
    @MethodSource("cases")
    fun `追加ワールドデータを衝突の有無で分類する`(
        path: String,
        expected: AdditionalWorldDataDisposition,
    ) {
        assertEquals(expected, StandaloneAdditionalWorldDataPolicy.classify(Path.of(path)))
    }

    internal companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of("raids.dat", AdditionalWorldDataDisposition.DIMENSION),
            Arguments.of("raids_end.dat", AdditionalWorldDataDisposition.DIMENSION),
            Arguments.of("random_sequences.dat", AdditionalWorldDataDisposition.DIMENSION),
            Arguments.of("persistent_data.dat", AdditionalWorldDataDisposition.DIMENSION),
            Arguments.of("map_0.dat", AdditionalWorldDataDisposition.PRIMARY_ONLY),
            Arguments.of("map_42.dat_old", AdditionalWorldDataDisposition.PRIMARY_ONLY),
            Arguments.of("idcounts.dat", AdditionalWorldDataDisposition.PRIMARY_ONLY),
            Arguments.of("scoreboard.dat", AdditionalWorldDataDisposition.PRIMARY_ONLY),
            Arguments.of("command_storage_minecraft.dat", AdditionalWorldDataDisposition.PRIMARY_ONLY),
            Arguments.of("plugin/cache.bin", AdditionalWorldDataDisposition.EXCLUDED),
            Arguments.of("uid.dat", AdditionalWorldDataDisposition.EXCLUDED),
        )
    }
}
