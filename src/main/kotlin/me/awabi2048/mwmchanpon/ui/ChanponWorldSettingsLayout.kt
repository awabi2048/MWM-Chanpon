package me.awabi2048.mwmchanpon.ui

internal object ChanponWorldSettingsLayout {
    const val PORTAL_MANAGEMENT_SLOT = 6

    fun backupSlot(inventorySize: Int): Int = inventorySize - 3

    fun productionSubmissionSlot(inventorySize: Int): Int = inventorySize - 2

    fun linkedPortalSlot(
        inventorySize: Int,
        isProtected: Boolean,
    ): Int {
        require(inventorySize > 0 && inventorySize % 9 == 0) {
            "inventorySize must be a positive multiple of 9"
        }
        if (isProtected) return 23
        // フッター左操作はオーナー判定によらず1マス目へ統一する。
        // 非オーナー時に footerStart + 2（54 なら 47）を返すと、内容領域のツアー項目（slot 47）と
        // 衝突して InventoryMenuView の一意性検証に失敗する。参照元の MWM 側も bottomRowStartSlot + 1
        // へ統一しているため、こちらも同じ予約に従う。
        val footerStart = inventorySize - 9
        return footerStart + 1
    }
}
