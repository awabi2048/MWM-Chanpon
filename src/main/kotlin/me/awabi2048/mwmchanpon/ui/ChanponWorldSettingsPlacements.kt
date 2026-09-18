package me.awabi2048.mwmchanpon.ui

/**
 * Chanpon画面専用のワールド設定capability配置IDです。
 *
 * MWM側 [WorldSettingsCapabilityPlacements][me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements]
 * とは文字列を共有せず、配置の完全分離を保証します。
 * スロット番号は各rendererの引数で決まるため、ID自体は検索キーとしてのみ機能します。
 */
object ChanponWorldSettingsPlacements {
    const val EXPANSION_ACTION = "mwm-chanpon:world-settings:expansion-action"
    const val PUBLISH_ACTION = "mwm-chanpon:world-settings:publish-action"
    const val NOTIFICATION_ACTION = "mwm-chanpon:world-settings:notification-action"
    const val ENVIRONMENT_ACTION = "mwm-chanpon:world-settings:environment-action"
    const val CRITICAL_ACTION = "mwm-chanpon:world-settings:critical-action"
    const val FOOTER_ACTIONS = "mwm-chanpon:world-settings:footer-actions"
    const val FOOTER_LEFT_ACTIONS = "mwm-chanpon:world-settings:footer-left-actions"
    const val FOOTER_RIGHT_ACTIONS = "mwm-chanpon:world-settings:footer-right-actions"
}
