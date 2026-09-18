package me.awabi2048.mwmchanpon.auth

/**
 * 管理者による権限貫通の単一定義です。
 * MWM管理系（`myworldmanager.admin` 権限または管理フロー）と
 * Chanpon管理系（`mwmchanpon.admin` 権限）のいずれかを満たせば管理者として扱います。
 * 各画面のオーナー判定に散在させず、ここを経由して判定します。
 */
internal object AdminAccess {
    fun isAdmin(mwmAdminAccess: Boolean, chanponAdmin: Boolean): Boolean =
        mwmAdminAccess || chanponAdmin

    fun isOwnerOrAdmin(isOwner: Boolean, mwmAdminAccess: Boolean, chanponAdmin: Boolean): Boolean =
        isOwner || isAdmin(mwmAdminAccess, chanponAdmin)
}
