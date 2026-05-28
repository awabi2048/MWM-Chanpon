package me.awabi2048.mwmchanpon.model

enum class WorldWeatherSetting {
    CLEAR,
    RAIN,
}

enum class WorldTimeSetting(val time: Long) {
    DAY(1000L),
    NIGHT(13000L),
}
