package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class MenuRuntimeNavigationContractTest {
    private val uiRoot = Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui")

    @Test
    fun `管理メニューの子画面はRuntimeの履歴へ積む`() {
        CHILD_MENUS.forEach { fileName ->
            val source = uiRoot.resolve(fileName).readText()
            val openBody = functionBody(source, "open")
            assertTrue("runtime.navigate(" in openBody, "$fileName はnavigateで開く必要があります")
            assertFalse("runtime.open(" in openBody, "$fileName は親履歴を破棄してはいけません")

            val backBody = functionBody(source, "back")
            assertTrue("MenuUpdate.Back" in backBody, "$fileName はRuntimeのBackで戻る必要があります")
            assertFalse("MenuUpdate.Close" in backBody, "$fileName は戻る際に画面を閉じてはいけません")
        }
    }

    @Test
    fun `管理ワールド一覧も親メニューの履歴へ積む`() {
        val source = uiRoot.resolve("ChanponAdminWorldListMenu.kt").readText()
        assertTrue("runtime.navigate(player, listRoute(state))" in source)
        val backBody = functionBody(source, "back")
        assertTrue("MenuUpdate.Back" in backBody)
        assertFalse("MenuUpdate.Close" in backBody)
    }

    @Test
    fun `creation transitions leave the newly opened surface active`() {
        val source = uiRoot.resolve("ChanponPlayerWorldCapability.kt").readText()
        val body = functionBody(source, "handleCreate")
        assertTrue("startEasyCreation(player, returnRoute)" in body)
        assertTrue("MenuUpdate.None" in body)
        assertFalse("runTask" in body)
    }

    @Test
    fun `environment dialogs do not request inventory close`() {
        val source = uiRoot.resolve("ChanponEnvironmentGui.kt").readText()
        val body = functionBody(source, "setting")
        assertTrue("showCustomTimeDialog(context.player, worldData)" in body)
        assertTrue("showProtectionPasswordDialog(" in body)
        assertTrue(body.split("MenuUpdate.None").size >= 3)
        assertFalse("MenuUpdate.Close" in body)
        assertTrue("MenuUpdate.Replace(route(worldData.uuid))" in source)
    }

    @Test
    fun `easy creation returns to its source and confirms in inventory runtime`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/service/EasyVoidWorldCreationService.kt",
        ).readText()
        assertTrue("returnRoutes[player.uniqueId] = returnRoute" in source)
        assertTrue("runtime.replace(player, returnRoute)" in source)
        assertTrue("InventoryMenuDefinition(" in source)
        assertTrue("id = CONFIRM_ROUTE" in source)
        assertFalse("CONFIRM_DIALOG" in source)
        assertFalse("showConfirmDialog" in source)
        assertFalse("PLATFORM_MATERIAL_INPUT" in source)
        assertTrue("creation.easy.platform_material" in source)
    }

    @Test
    fun `easy creation platform material is managed by config`() {
        val config = Yaml().load<Map<String, Any>>(Path.of("src/main/resources/config.yml").readText())
        @Suppress("UNCHECKED_CAST")
        val creation = config["creation"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val easy = creation["easy"] as Map<String, Any>

        assertTrue(config["config_version"] == 4)
        assertTrue(easy["platform_material"] == "OBSIDIAN")
    }

    @Test
    fun `owner backup back action pops runtime history without reopening settings`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).readText()
        val body = functionBody(source, "back")

        assertTrue("MenuUpdate.Back" in body)
        assertFalse("worldSettingsGui.open" in body)
        assertFalse("MenuUpdate.Close" in body)
    }



    @Test
    fun `dialog cancellation replaces its source menu without adding history`() {
        val backup = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).readText()
        assertTrue("MenuUpdate.Resume" in backup)
        assertFalse("sourceSnapshot.request" in backup)

        val environment = uiRoot.resolve("ChanponEnvironmentGui.kt").readText()
        assertTrue("replaceCurrent: Boolean = false" in environment)
        assertTrue("runtime.replace(player, route(worldData.uuid))" in environment)
    }

    @Test
    fun `公開メニュープロバイダーはRuntimeの表示結果と親経路を保持する`() {
        val environment = uiRoot.resolve("ChanponEnvironmentGui.kt").readText()
        assertTrue("runtime.navigate(player, route(worldData.uuid))" in environment)
        assertFalse("runtime.open(player, route(worldData.uuid))" in environment)

    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name").takeIf { it >= 0 }
            ?: source.indexOf("fun $name").takeIf { it >= 0 }
            ?: error("$name が見つかりません")
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "$name の本体が見つかりません" }
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
        error("$name の本体が閉じられていません")
    }

    private companion object {
        val CHILD_MENUS = listOf(
            "SubmissionAdminMenu.kt",
            "WorldDataManagementMenu.kt",
            "AutomationSettingsMenu.kt",
            "WorldDataExportMenu.kt",
        )
    }
}
