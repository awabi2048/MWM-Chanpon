package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponSystemKeys

/**
 * ワールド設定を閲覧しているプレイヤーの役割です。
 *
 * 表示用の警告は、権限判定そのものを置き換えません。実行可否は各設定の
 * MWM契約／Chanpon capabilityが決定し、この値は「なぜ表示だけなのか」を
 * Loreへ伝えるためにだけ使います。
 */
internal enum class WorldSettingsViewerRole {
    OWNER,
    MODERATOR,
    MEMBER,
    NONE,
}

internal fun worldSettingsViewerRole(
    owner: Boolean,
    moderator: Boolean,
    member: Boolean,
): WorldSettingsViewerRole = when {
    owner -> WorldSettingsViewerRole.OWNER
    moderator -> WorldSettingsViewerRole.MODERATOR
    member -> WorldSettingsViewerRole.MEMBER
    else -> WorldSettingsViewerRole.NONE
}

/**
 * 警告ブロックの優先順位を一箇所で固定します。
 *
 * ワールド外では、メンバー／モデレーターの権限警告を重ねず、既存の
 * 「ワールド内にいる必要があります」だけを表示します。
 */
internal fun worldSettingsRestrictionWarningKey(
    role: WorldSettingsViewerRole,
    outsideTargetWorld: Boolean,
): LocalizationKey<String>? = when {
    outsideTargetWorld -> ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING
    role == WorldSettingsViewerRole.MEMBER -> ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MEMBER_CANNOT_CHANGE_WARNING
    role == WorldSettingsViewerRole.MODERATOR -> ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MODERATOR_CANNOT_CHANGE_WARNING
    else -> null
}
