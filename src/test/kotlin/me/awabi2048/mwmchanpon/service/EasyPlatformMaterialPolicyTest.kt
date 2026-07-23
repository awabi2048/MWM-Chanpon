package me.awabi2048.mwmchanpon.service

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EasyPlatformMaterialPolicyTest {
    @Test
    fun `obsidian is the default platform material`() {
        assertEquals(Material.OBSIDIAN, EasyPlatformMaterialPolicy.defaultMaterial)
    }
}
