package me.awabi2048.mwmchanpon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveSafetySourceContractTest {
    @Test
    fun archiveUsesSchemaWorldKeyAndValidatesImportData() {
        val source = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/service/ArchiveService.kt"),
            StandardCharsets.UTF_8
        )
        assertTrue(source.contains("schema_version"))
        assertTrue(source.contains("world_key"))
        assertTrue(source.contains("archive_world_data_missing"))
        assertTrue(source.contains("world_folder_conflict"))
    }
}
