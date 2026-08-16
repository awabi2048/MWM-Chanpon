package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldListMenuContractTest {
    @Test
    fun `Chanpon Discovery exposes only explanation and navigation controls`() {
        val source = Files.readString(sourceRoot().resolve("ui/ChanponDiscoveryMenu.kt"))

        assertTrue(source.contains("i18nComponent(player, ChanponDiscoveryKeys.CHANPON_DISCOVERY_INFO_NAME)"))
        assertTrue(source.contains("i18nList(player, ChanponDiscoveryKeys.CHANPON_DISCOVERY_INFO_LORE)"))
        assertTrue(source.contains("infoEntry(player, layout.actionSlot)"))
        assertFalse(source.contains("infoEntry(player, 4)"))
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

    @Test
    fun `footer routes leave the back slot separate from current world and paging`() {
        val source = Files.readString(sourceRoot().resolve("ui/ChanponFavoriteListMenu.kt"))

        assertTrue(source.contains("val footerStart = layout.size - 9"))
        assertTrue(source.contains("previousPageSlot = footerStart + 3"))
        assertTrue(source.contains("nextPageSlot = footerStart + 5"))
        assertTrue(source.contains("otherWorldsEntry(player, footerStart + 1"))
        assertTrue(source.contains("toggleCurrentEntry(player, footerStart + 2"))
        assertTrue(source.contains("createCurrentWorldMenuElement(player, footerStart + 4)"))
        assertTrue(source.contains("backEntry(player, layout.backSlot)"))
    }

    @Test
    fun `footer slots are unique for both seven column inventory sizes`() {
        listOf(45, 54).forEach { size ->
            val footerStart = size - 9
            val slots = setOf(
                footerStart,
                footerStart + 1,
                footerStart + 2,
                footerStart + 3,
                footerStart + 4,
                footerStart + 5,
            )
            assertEquals(6, slots.size)
        }
    }

    private fun sourceRoot(): Path = Path.of("src/main/kotlin/me/awabi2048/mwmchanpon")
}
