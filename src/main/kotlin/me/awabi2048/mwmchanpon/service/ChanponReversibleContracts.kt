package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleContract
import java.util.UUID

/**
 * Chanpon が所有する可逆操作の契約です。
 *
 * provider ごとに状態境界を一つだけ担当させ、画面上のスロットや画面名ではなく、
 * aggregate_key と target_id で復元対象を識別します。
 */
object ChanponReversibleContracts {
    private const val OWNER = "mwm-chanpon"
    const val AGGREGATE_KEY = "aggregate_key"
    const val TARGET_ID = "target_id"
    const val OPERATION = "operation"

    private const val AUTOMATION_PROVIDER = "$OWNER:automation-settings"
    private const val ADMIN_WORLD_PROVIDER = "$OWNER:admin-world-controls"
    private const val ENVIRONMENT_PROVIDER = "$OWNER:world-environment"
    private const val PUBLICATION_PROVIDER = "$OWNER:world-publication"
    private const val NOTIFICATION_PROVIDER = "$OWNER:world-notification"
    private const val SUBMISSION_PROVIDER = "$OWNER:submission"
    private const val TOOL_PERMISSION_PROVIDER = "$OWNER:tool-permission"
    private const val CREATION_PRODUCTION_PROVIDER = "$OWNER:creation-production-selection"
    private const val EASY_CREATION_PROVIDER = "$OWNER:easy-creation"

    internal val providerIds: Set<String> = setOf(
        AUTOMATION_PROVIDER,
        ADMIN_WORLD_PROVIDER,
        ENVIRONMENT_PROVIDER,
        PUBLICATION_PROVIDER,
        NOTIFICATION_PROVIDER,
        SUBMISSION_PROVIDER,
        TOOL_PERMISSION_PROVIDER,
        CREATION_PRODUCTION_PROVIDER,
        EASY_CREATION_PROVIDER,
    )

    fun automation(configPath: String) = contract(
        AUTOMATION_PROVIDER,
        "automation-settings",
        configPath,
        "toggle",
    )

    fun adminWorldControls(operation: String, targetId: String = "global") = contract(
        ADMIN_WORLD_PROVIDER,
        "admin-world-controls",
        targetId,
        operation,
    )

    fun environment(worldUuid: UUID) = contract(
        ENVIRONMENT_PROVIDER,
        "world-environment",
        worldUuid.toString(),
        "cycle",
    )

    /** Capability 定義は登録時点で対象ワールドを持たないため、実行引数のキーを宣言します。 */
    fun publication() = contract(
        PUBLICATION_PROVIDER,
        "world-publication",
        "argument:world_uuid",
        "cycle-visibility",
    )

    fun notification() = contract(
        NOTIFICATION_PROVIDER,
        "world-notification",
        "argument:world_uuid",
        "toggle-notification",
    )

    fun submission(targetId: String, operation: String) = contract(
        SUBMISSION_PROVIDER,
        "submission",
        targetId,
        operation,
    )

    fun toolPermission(worldUuid: UUID, playerUuid: UUID, operation: String) = contract(
        TOOL_PERMISSION_PROVIDER,
        "tool-permission",
        "$worldUuid/$playerUuid",
        operation,
    )

    fun creationProductionSelection() = contract(
        CREATION_PRODUCTION_PROVIDER,
        "creation-production-selection",
        "attribute:creation-draft",
        "toggle",
    )

    fun easyCreation() = MenuReversibleContract(
        EASY_CREATION_PROVIDER,
        mapOf(
            AGGREGATE_KEY to "easy-creation-flow",
            TARGET_ID to "player-flow",
            OPERATION to "start",
        ) + EasyCreationReturnRouteContract.arguments,
    )

    private fun contract(
        providerId: String,
        aggregateKey: String,
        targetId: String,
        operation: String,
    ) = MenuReversibleContract(
        providerId,
        mapOf(
            AGGREGATE_KEY to aggregateKey,
            TARGET_ID to targetId,
            OPERATION to operation,
        ),
    )
}

/**
 * Easy作成で開始元へ戻るためのRouteを、可逆tokenへ明示的に結び付けます。
 *
 * Capability定義は共有されるため、実値はInvocation属性で渡します。providerは
 * 現在表示中のRouteを参照せず、この契約が指す属性だけから期待状態を再構成します。
 */
internal object EasyCreationReturnRouteContract {
    const val OWNER_ARGUMENT = "return_route_owner"
    const val ID_ARGUMENT = "return_route_id"
    const val PAYLOAD_ARGUMENT = "return_route_payload"

    const val OWNER_ATTRIBUTE = "mwm-chanpon.easy-creation.return-route.owner"
    const val ID_ATTRIBUTE = "mwm-chanpon.easy-creation.return-route.id"
    const val PAYLOAD_ATTRIBUTE = "mwm-chanpon.easy-creation.return-route.payload"

    val arguments: Map<String, String> = mapOf(
        OWNER_ARGUMENT to "attribute:$OWNER_ATTRIBUTE",
        ID_ARGUMENT to "attribute:$ID_ATTRIBUTE",
        PAYLOAD_ARGUMENT to "attribute:$PAYLOAD_ATTRIBUTE",
    )

    fun attributes(route: com.awabi2048.ccsystem.api.gui.MenuRoute): Map<String, Any> = mapOf(
        OWNER_ATTRIBUTE to route.owner,
        ID_ATTRIBUTE to route.id,
        PAYLOAD_ATTRIBUTE to route.payload.toMap(),
    )

    fun resolve(
        contractArguments: Map<String, String>,
        interactionAttributes: Map<String, Any>,
    ): com.awabi2048.ccsystem.api.gui.MenuRoute? {
        val owner = attributeValue(contractArguments, interactionAttributes, OWNER_ARGUMENT) as? String
            ?: return null
        val id = attributeValue(contractArguments, interactionAttributes, ID_ARGUMENT) as? String
            ?: return null
        val payload = attributeValue(contractArguments, interactionAttributes, PAYLOAD_ARGUMENT) as? Map<*, *>
            ?: return null
        val normalizedPayload = buildMap<String, String> {
            payload.forEach { (key, value) ->
                if (key !is String || value !is String) return null
                put(key, value)
            }
        }
        return com.awabi2048.ccsystem.api.gui.MenuRoute(owner, id, normalizedPayload)
    }

    private fun attributeValue(
        contractArguments: Map<String, String>,
        interactionAttributes: Map<String, Any>,
        argument: String,
    ): Any? {
        val attribute = contractArguments[argument]
            ?.removePrefix("attribute:")
            ?.takeIf { contractArguments[argument]?.startsWith("attribute:") == true }
            ?: return null
        return interactionAttributes[attribute]
    }
}
