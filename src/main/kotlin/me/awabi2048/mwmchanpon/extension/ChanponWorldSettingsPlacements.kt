package me.awabi2048.mwmchanpon.extension

/**
 * Chanponが所有するワールド設定画面専用の配置契約です。
 *
 * MWM本体の配置名を再利用しないことで、Chanpon要素がMWM画面へ流入することを防ぎます。
 */
internal object ChanponWorldSettingsPlacements {
    const val EXPANSION = "mwm-chanpon.world-settings.expansion"
    const val PUBLISH = "mwm-chanpon.world-settings.publish"
    const val NOTIFICATION = "mwm-chanpon.world-settings.notification"
    const val ENVIRONMENT = "mwm-chanpon.world-settings.environment"
    const val CRITICAL = "mwm-chanpon.world-settings.critical"
    const val FOOTER = "mwm-chanpon.world-settings.footer"
    const val FOOTER_LEFT = "mwm-chanpon.world-settings.footer-left"
    const val FOOTER_RIGHT = "mwm-chanpon.world-settings.footer-right"
}

