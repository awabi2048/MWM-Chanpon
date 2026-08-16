package me.awabi2048.mwmchanpon.service

import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import me.awabi2048.myworldmanager.model.PublishLevel
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

/**
 * Chanpon管理中のMWM公開状態を、安全側のPRIVATEへ固定する。
 * 実際の可視性はChanponWorldStorageを正本としてPolicyが判定する。
 */
class PublishLevelSynchronizer : Listener {
    private var synchronizationGate: (UUID) -> Boolean = { true }

    /**
     * MWM metadataの公開状態を同期する処理も外部データの読み取り結果に依存するため、
     * 移行前・隔離中のワールドを推測値で上書きしないよう最終ゲートを持たせます。
     */
    fun setSynchronizationGate(gate: (UUID) -> Boolean) {
        synchronizationGate = gate
    }

    fun sync(worldUuid: UUID): Boolean {
        if (!synchronizationGate(worldUuid)) return false
        val repository = MyWorldManagerApi.getWorldRepository() ?: return false
        val worldData = repository.findByUuid(worldUuid) ?: return false
        if (worldData.publishLevel == PublishLevel.PRIVATE) return true
        worldData.publishLevel = PublishLevel.PRIVATE
        worldData.publicAt = null
        repository.save(worldData)
        return true
    }

    fun syncAll(): Int {
        val repository = MyWorldManagerApi.getWorldRepository() ?: return 0
        var changed = 0
        repository.findAll().forEach { worldData ->
            if (!synchronizationGate(worldData.uuid)) return@forEach
            if (worldData.publishLevel != PublishLevel.PRIVATE || worldData.publicAt != null) {
                worldData.publishLevel = PublishLevel.PRIVATE
                worldData.publicAt = null
                repository.save(worldData)
                changed++
            }
        }
        return changed
    }

    @EventHandler
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        sync(event.worldUuid)
    }
}
