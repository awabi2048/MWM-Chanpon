package me.awabi2048.mwmchanpon.tour

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * プレビューツアー定義と開始看板の永続化です。
 * settings.yml と同じく原子的な一時ファイル経由で保存し、
 * /mwm migration 完了前の書き込みはゲートで遮断します。
 */
class PreviewTourStorage(
    dataFolder: File,
) {
    private val toursFile = File(dataFolder, "tours.yml")
    private var currentWriteGate: (() -> Boolean)? = null

    fun setCurrentWriteGate(gate: () -> Boolean) {
        currentWriteGate = gate
    }

    fun allTours(): List<PreviewTourData> {
        val config = load()
        val section = config.getConfigurationSection("tours") ?: return emptyList()
        return section.getKeys(false).mapNotNull { readTour(section.getConfigurationSection(it)) }
            .sortedBy { it.createdAt }
    }

    fun getTour(tourUuid: UUID): PreviewTourData? =
        readTour(load().getConfigurationSection("tours.${tourUuid}"))

    @Synchronized
    fun saveTour(tour: PreviewTourData) {
        ensureCurrentForWrite()
        val config = load()
        writeTour(config.createSection("tours.${tour.uuid}"), tour)
        saveAtomic(config)
    }

    /** 巡業の末尾にワールドを追加します。所要時間は1秒以上のみ受け付けます。 */
    @Synchronized
    fun addStop(tourUuid: UUID, worldUuid: UUID, durationSeconds: Int): Boolean {
        val tour = getTour(tourUuid) ?: return false
        if (durationSeconds <= 0) return false
        tour.stops.add(PreviewTourStop(worldUuid, durationSeconds))
        saveTour(tour)
        return true
    }

    @Synchronized
    fun removeStop(tourUuid: UUID, index: Int): Boolean {
        val tour = getTour(tourUuid) ?: return false
        if (index !in tour.stops.indices) return false
        tour.stops.removeAt(index)
        saveTour(tour)
        return true
    }

    @Synchronized
    fun moveStop(tourUuid: UUID, from: Int, to: Int): Boolean {
        val tour = getTour(tourUuid) ?: return false
        if (from !in tour.stops.indices || to !in tour.stops.indices) return false
        tour.stops.add(to, tour.stops.removeAt(from))
        saveTour(tour)
        return true
    }

    @Synchronized
    fun setStopDuration(tourUuid: UUID, index: Int, durationSeconds: Int): Boolean {
        val tour = getTour(tourUuid) ?: return false
        if (index !in tour.stops.indices || durationSeconds <= 0) return false
        tour.stops[index] = tour.stops[index].copy(durationSeconds = durationSeconds)
        saveTour(tour)
        return true
    }

    @Synchronized
    fun deleteTour(tourUuid: UUID) {
        ensureCurrentForWrite()
        val config = load()
        config.set("tours.${tourUuid}", null)
        // ツアー削除と同時に、そのツアーへ紐付く看板登録も一貫して消します。
        val signsSection = config.getConfigurationSection("signs")
        signsSection?.getKeys(false)?.forEach { signKey ->
            if (signsSection.getString("$signKey.tour") == tourUuid.toString()) {
                signsSection.set(signKey, null)
            }
        }
        saveAtomic(config)
    }

    fun allSigns(): List<PreviewTourSignData> {
        val section = load().getConfigurationSection("signs") ?: return emptyList()
        return section.getKeys(false).mapNotNull { readSign(section.getConfigurationSection(it)) }
    }

    fun getSign(signUuid: UUID): PreviewTourSignData? =
        readSign(load().getConfigurationSection("signs.${signUuid}"))

    fun findSign(worldName: String, x: Int, y: Int, z: Int): PreviewTourSignData? =
        allSigns().firstOrNull { it.worldName == worldName && it.x == x && it.y == y && it.z == z }

    fun signsForTour(tourUuid: UUID): List<PreviewTourSignData> =
        allSigns().filter { it.tourUuid == tourUuid }

    @Synchronized
    fun saveSign(sign: PreviewTourSignData) {
        ensureCurrentForWrite()
        val config = load()
        writeSign(config.createSection("signs.${sign.uuid}"), sign)
        saveAtomic(config)
    }

    @Synchronized
    fun deleteSign(signUuid: UUID) {
        ensureCurrentForWrite()
        val config = load()
        config.set("signs.${signUuid}", null)
        saveAtomic(config)
    }

    private fun readTour(section: ConfigurationSection?): PreviewTourData? {
        section ?: return null
        val uuid = runCatching { UUID.fromString(section.name) }.getOrNull() ?: return null
        val title = section.getString("title") ?: return null
        val createdBy = section.getString("created_by")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val stops = section.getMapList("stops").mapNotNull { entry ->
            val worldRaw = entry["world"] as? String ?: return@mapNotNull null
            val worldUuid = runCatching { UUID.fromString(worldRaw) }.getOrNull() ?: return@mapNotNull null
            val duration = (entry["duration_seconds"] as? Number)?.toInt() ?: return@mapNotNull null
            if (duration <= 0) return@mapNotNull null
            PreviewTourStop(worldUuid, duration)
        }.toMutableList()
        return PreviewTourData(
            uuid = uuid,
            title = title,
            description = section.getString("description").orEmpty(),
            stops = stops,
            createdBy = createdBy,
            createdAt = section.getString("created_at").orEmpty(),
        )
    }

    private fun writeTour(section: ConfigurationSection, tour: PreviewTourData) {
        section.set("title", tour.title)
        section.set("description", tour.description)
        section.set("created_by", tour.createdBy.toString())
        section.set("created_at", tour.createdAt)
        section.set(
            "stops",
            tour.stops.map {
                mapOf(
                    "world" to it.worldUuid.toString(),
                    "duration_seconds" to it.durationSeconds,
                )
            },
        )
    }

    private fun readSign(section: ConfigurationSection?): PreviewTourSignData? {
        section ?: return null
        val uuid = runCatching { UUID.fromString(section.name) }.getOrNull() ?: return null
        val tourUuid = section.getString("tour")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val worldName = section.getString("world") ?: return null
        return PreviewTourSignData(
            uuid = uuid,
            tourUuid = tourUuid,
            worldName = worldName,
            x = section.getInt("x"),
            y = section.getInt("y"),
            z = section.getInt("z"),
        )
    }

    private fun writeSign(section: ConfigurationSection, sign: PreviewTourSignData) {
        section.set("tour", sign.tourUuid.toString())
        section.set("world", sign.worldName)
        section.set("x", sign.x)
        section.set("y", sign.y)
        section.set("z", sign.z)
    }

    private fun load(): YamlConfiguration {
        if (!toursFile.exists()) toursFile.createNewFile()
        return YamlConfiguration.loadConfiguration(toursFile)
    }

    /** settings.yml と同様に、一時ファイルへ検証付きで書き出してから原子的に置き換えます。 */
    private fun saveAtomic(config: YamlConfiguration) {
        val temporary = File(toursFile.parentFile, toursFile.name + ".save.tmp")
        try {
            config.save(temporary)
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(
                    temporary.toPath(),
                    toursFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), toursFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            Files.deleteIfExists(temporary.toPath())
            throw IllegalStateException("Could not save preview tour data", error)
        }
    }

    /** 旧形式の設定を移行完了前に暗黙再保存しないための書き込み境界です。 */
    private fun ensureCurrentForWrite() {
        check(currentWriteGate?.invoke() != false) {
            "tours.yml requires /mwm migration before it can be modified"
        }
    }
}
