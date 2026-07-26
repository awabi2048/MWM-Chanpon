package me.awabi2048.mwmchanpon.ui

internal object ChanponWorldSettingsLayout {
    const val PORTAL_MANAGEMENT_SLOT = 52

    fun linkedPortalSlot(
        inventorySize: Int,
        isOwner: Boolean,
        isProtected: Boolean,
    ): Int {
        require(inventorySize > 0 && inventorySize % 9 == 0) {
            "inventorySize must be a positive multiple of 9"
        }
        if (isProtected) return 23
        val footerStart = inventorySize - 9
        return if (isOwner) footerStart + 1 else footerStart + 2
    }
}
