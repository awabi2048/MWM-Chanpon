package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldListMenuContractTest {
    @Test
    fun `Chanpon Discovery exposes only explanation and navigation controls`() {
        val source = Files.readString(sourceRoot().resolve("ui/ChanponDiscoveryMenu.kt"))

        assertTrue(source.contains("i18nComponent(player, \"info.name\")"))
        assertTrue(source.contains("i18nList(player, \"info.lore\")"))
        assertTrue(source.contains("GuiElementRole.CONTENT"))
        assertTrue(source.contains("sevenColumnPage"))
        assertFalse(source.contains("ACTION_TAG"))
        assertFalse(source.contains("ACTION_SORT"))
        assertFalse(source.contains("SPECIAL_FILTER"))
        assertFalse(source.contains("DiscoverySort.SPOTLIGHT"))
        assertFalse(source.contains("spotlightRepository"))
        assertFalse(source.contains("ACTION_SPOTLIGHT"))
        assertFalse(source.contains("DiscoverySort"))
        assertFalse(source.contains("DiscoverySpecialFilter"))
    }

    @Test
    fun `Chanpon Favorite list has no tag filter but retains the three footer routes`() {
        val source = Files.readString(sourceRoot().resolve("ui/ChanponFavoriteListMenu.kt"))

        assertFalse(source.contains("ACTION_TAG"))
        assertFalse(source.contains("tag_filter"))
        assertFalse(source.contains("selectedTag"))
        assertTrue(source.contains("ACTION_OTHER_WORLDS"))
        assertTrue(source.contains("ACTION_TOGGLE_CURRENT"))
        assertTrue(source.contains("createCurrentWorldMenuElement"))
        assertTrue(source.contains("sevenColumnPage"))
    }

    private fun sourceRoot(): Path = Path.of("src/main/kotlin/me/awabi2048/mwmchanpon")
}
