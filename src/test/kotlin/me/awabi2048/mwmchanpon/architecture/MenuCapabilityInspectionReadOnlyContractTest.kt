package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MenuCapabilityInspectionReadOnlyContractTest {
    @Test
    fun `全presentation providerの直接宣言は操作を行わない`() {
        val sources = PROVIDER_SOURCES.map { it.readText() }

        assertEquals(
            12,
            sources.sumOf { source -> Regex("presentationProvider\\s*=").findAll(source).count() },
        )
        sources.forEach { source ->
            Regex(
                "presentationProvider\\s*=.*?(?=\\n\\s*actions\\s*=)",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).findAll(source).forEach { declaration ->
                assertReadOnly(declaration.value, "presentation provider宣言")
            }
        }
    }

    @Test
    fun `providerが呼ぶ照会関数はinspect中に副作用を持たない`() {
        INSPECTION_FUNCTIONS.forEach { (path, functions) ->
            val source = path.readText()
            functions.forEach { function ->
                assertReadOnly(functionBody(source, function), "${path.fileName}: $function")
            }
        }
    }

    private fun assertReadOnly(source: String, scope: String) {
        FORBIDDEN_SIDE_EFFECTS.forEach { sideEffect ->
            assertFalse(
                sideEffect.containsMatchIn(source),
                "$scope はinspect中に副作用を実行してはいけません: ${sideEffect.pattern}",
            )
        }
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name")
        require(start >= 0) { "関数が見つかりません: $name" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "関数本体が見つかりません: $name" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("関数本体が閉じていません: $name")
    }

    private companion object {
        val PROVIDER_SOURCES = listOf(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldSettingsCapabilities.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminMenu.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminWorldListMenu.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/LinkedPortalCapability.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ProductionToggleExtension.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ToolPermissionCapability.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldCapability.kt"),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt"),
        )

        val INSPECTION_FUNCTIONS = mapOf(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponWorldSettingsMenu.kt") to listOf(
                "isFeatureVisible",
                "isFeatureActionable",
                "renderFeature",
                "featureActionText",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminMenu.kt") to listOf(
                "switchToChanponPresentation",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminWorldListMenu.kt") to listOf(
                "adminMenuWorldListPresentation",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/LinkedPortalCapability.kt") to listOf(
                "portalPresentation",
                "worldData",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ProductionToggleExtension.kt") to listOf(
                "isCapabilityAvailable",
                "creationDraft",
                "resolveCapabilityPresentation",
                "resolveCapabilityRoute",
                "capabilityActionText",
                "creationPresentation",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ToolPermissionCapability.kt") to listOf(
                "canManage",
                "toolPermissionLoreBlock",
                "subject",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/tool/ToolPermissionController.kt") to listOf(
                "getMemberToolStates",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldCapability.kt") to listOf(
                "creationPresentation",
                "primaryWorldActionText",
                "createWorldPresentation",
                "createPlayerInfoPresentation",
                "canOpenSettings",
                "isCurrentWorld",
                "creationBlockReason",
                "subject",
                "world",
            ),
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt") to listOf(
                "entryPresentation",
                "ownedProductionWorld",
                "canManage",
                "worldUuid",
            ),
        )

        val FORBIDDEN_SIDE_EFFECTS = listOf(
            Regex("sendMessage\\s*\\("),
            Regex("\\brunTask\\b"),
            Regex("callEvent\\s*\\("),
            Regex("\\.save\\s*\\("),
            Regex("\\.\\s*set[A-Z]\\w*\\s*\\("),
            Regex("dispatchCommand"),
            Regex("runtime\\s*\\."),
            Regex("teleport\\w*\\s*\\("),
            Regex("addItem\\s*\\("),
            Regex("dropItem"),
        )
    }
}
