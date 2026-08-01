package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleOpaqueState

/** Chanpon provider stateをCC-Systemのallowlist snapshotへ変換する共通境界です。 */
internal class ChanponReversibleOpaqueState private constructor(
    private val providerId: String,
    private val stateType: String,
    private val payload: Map<String, Any?>,
) : MenuReversibleOpaqueState {
    override fun snapshot(): Any = mapOf(
        SCHEMA_VERSION_KEY to SCHEMA_VERSION,
        PROVIDER_ID_KEY to providerId,
        STATE_TYPE_KEY to stateType,
        PAYLOAD_KEY to payload,
    )

    companion object {
        fun of(providerId: String, stateType: String, payload: Map<String, Any?>): ChanponReversibleOpaqueState {
            require(providerId.isNotBlank())
            require(stateType.isNotBlank())
            return ChanponReversibleOpaqueState(providerId, stateType, payload.toMap())
        }
    }
}

internal data class ChanponDecodedOpaqueState(
    val payload: Map<String, Any?>,
)

internal fun decodeChanponOpaqueState(
    value: Any?,
    expectedProviderId: String,
    expectedStateType: String,
    expectedPayloadKeys: Set<String>,
): ChanponDecodedOpaqueState? {
    val envelope = value as? Map<*, *> ?: return null
    if (envelope.keys != ENVELOPE_KEYS) return null
    if (envelope[SCHEMA_VERSION_KEY] != SCHEMA_VERSION) return null
    if (envelope[PROVIDER_ID_KEY] != expectedProviderId) return null
    if (envelope[STATE_TYPE_KEY] != expectedStateType) return null
    val rawPayload = envelope[PAYLOAD_KEY] as? Map<*, *> ?: return null
    if (rawPayload.keys != expectedPayloadKeys) return null
    val payload = LinkedHashMap<String, Any?>(rawPayload.size)
    rawPayload.forEach { (key, nested) ->
        if (key !is String) return null
        payload[key] = nested
    }
    return ChanponDecodedOpaqueState(payload)
}

internal fun Map<String, Any?>.strictUuid(key: String): java.util.UUID? = this[key] as? java.util.UUID
internal fun Map<String, Any?>.strictString(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.strictBoolean(key: String): Boolean? = this[key] as? Boolean
internal fun Map<String, Any?>.strictInt(key: String): Int? = this[key] as? Int

internal fun Map<String, Any?>.strictUuidList(key: String): List<java.util.UUID>? {
    val values = this[key] as? List<*> ?: return null
    if (values.any { it !is java.util.UUID }) return null
    @Suppress("UNCHECKED_CAST")
    return values as List<java.util.UUID>
}

private const val SCHEMA_VERSION = 1
private const val SCHEMA_VERSION_KEY = "schemaVersion"
private const val PROVIDER_ID_KEY = "providerId"
private const val STATE_TYPE_KEY = "stateType"
private const val PAYLOAD_KEY = "payload"
private val ENVELOPE_KEYS = setOf(SCHEMA_VERSION_KEY, PROVIDER_ID_KEY, STATE_TYPE_KEY, PAYLOAD_KEY)
