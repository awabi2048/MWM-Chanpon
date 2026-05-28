package me.awabi2048.mwmchanpon.bridge

import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiMemberManager
import me.awabi2048.myworldmanager.api.service.ApiTemplateRepository
import me.awabi2048.myworldmanager.api.service.ApiWorldRepository
import me.awabi2048.myworldmanager.api.service.ApiWorldService
import me.awabi2048.myworldmanager.api.service.ApiWorldTagService

class CoreApiBridge {

    fun getWorldService(): ApiWorldService? = MyWorldManagerApi.getWorldService()

    fun getWorldRepository(): ApiWorldRepository? = MyWorldManagerApi.getWorldRepository()

    fun getTemplateRepository(): ApiTemplateRepository? = MyWorldManagerApi.getTemplateRepository()

    fun getMemberManager(): ApiMemberManager? = MyWorldManagerApi.getMemberManager()

    fun getWorldTagService(): ApiWorldTagService? = MyWorldManagerApi.getWorldTagService()

    fun isCoreAvailable(): Boolean {
        return MyWorldManagerApi.getWorldService() != null
    }
}
