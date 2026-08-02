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

    internal val providerIds: Set<String> = setOf(
        AUTOMATION_PROVIDER,
        ADMIN_WORLD_PROVIDER,
        ENVIRONMENT_PROVIDER,
        PUBLICATION_PROVIDER,
        NOTIFICATION_PROVIDER,
        SUBMISSION_PROVIDER,
        TOOL_PERMISSION_PROVIDER,
        CREATION_PRODUCTION_PROVIDER,
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
