package me.awabi2048.mwmchanpon.service

/**
 * GUI操作の安全区分を、画面やスロットではなく操作の状態境界で台帳化します。
 *
 * ここにないREVERSIBLE操作を追加してはならず、状態変更でない一覧操作は
 * NAVIGATION_ONLYに分類します。
 */
internal object ChanponReversibleActionClassification {
    enum class Kind { STATE_AGGREGATE, ROUTE_ONLY }

    /**
     * 実際のGUI action/capabilityが参照する台帳キーです。
     *
     * 文字列を各callsiteに再記述させず、この型だけを受け渡すことで、
     * 台帳と完成したCapability/actionの対応を一意に保ちます。
     */
    enum class Operation(
        val key: String,
        val kind: Kind,
        val aggregateKey: String,
        val providerId: String? = null,
    ) {
        AUTOMATION_TOGGLE("automation-toggle", Kind.STATE_AGGREGATE, "automation-settings", "mwm-chanpon:automation-settings"),
        BUILDING_TOGGLE("building-toggle", Kind.STATE_AGGREGATE, "admin-world-controls", "mwm-chanpon:admin-world-controls"),
        CREATION_AVAILABILITY_CYCLE("creation-availability-cycle", Kind.STATE_AGGREGATE, "admin-world-controls", "mwm-chanpon:admin-world-controls"),
        SPAWN_WORLD_SET("spawn-world-set", Kind.STATE_AGGREGATE, "admin-world-controls", "mwm-chanpon:admin-world-controls"),
        ENVIRONMENT_DIRECT_SETTING_CYCLE("environment-direct-setting-cycle", Kind.STATE_AGGREGATE, "world-environment", "mwm-chanpon:world-environment"),
        ENVIRONMENT_PROTECTION_CYCLE("environment-protection-cycle", Kind.STATE_AGGREGATE, "world-environment", "mwm-chanpon:world-environment"),
        PUBLICATION_CYCLE("publication-cycle", Kind.STATE_AGGREGATE, "world-publication", "mwm-chanpon:world-publication"),
        WORLD_NOTIFICATION_TOGGLE("world-notification-toggle", Kind.STATE_AGGREGATE, "world-notification", "mwm-chanpon:world-notification"),
        SUBMISSION_CLOSURE("submission-closure", Kind.STATE_AGGREGATE, "submission", "mwm-chanpon:submission"),
        SUBMISSION_COMPLETION("submission-completion", Kind.STATE_AGGREGATE, "submission", "mwm-chanpon:submission"),
        TOOL_ENABLE_TOGGLE("tool-enable-toggle", Kind.STATE_AGGREGATE, "tool-permission", "mwm-chanpon:tool-permission"),
        MEMBER_ROLE_TOGGLE("member-role-toggle", Kind.STATE_AGGREGATE, "tool-permission", "mwm-chanpon:tool-permission"),
        CREATION_PRODUCTION_SELECTION("creation-production-selection", Kind.STATE_AGGREGATE, "creation-production-selection", "mwm-chanpon:creation-production-selection"),
        CURRENT_PLAYER_WORLD_DISPLAY_ORDER("current-player-world-display-order", Kind.STATE_AGGREGATE, "world-display-order", "myworldmanager:world-display-order"),
        AWAY_PLAYER_WORLD_DISPLAY_ORDER("away-player-world-display-order", Kind.STATE_AGGREGATE, "world-display-order", "myworldmanager:world-display-order"),
        STANDARD_CREATION_SESSION("standard-creation-session", Kind.STATE_AGGREGATE, "creation-session", "myworldmanager:creation-session"),
        ADMIN_WORLD_LIST_FILTER("admin-world-list-filter", Kind.ROUTE_ONLY, "route-payload"),
        ADMIN_WORLD_LIST_SORT("admin-world-list-sort", Kind.ROUTE_ONLY, "route-payload"),
        SUBMISSION_LIST_FILTER("submission-list-filter", Kind.ROUTE_ONLY, "route-payload"),
        SUBMISSION_LIST_SORT("submission-list-sort", Kind.ROUTE_ONLY, "route-payload"),
        EXPORT_SELECTION_TOGGLE("export-selection-toggle", Kind.ROUTE_ONLY, "route-payload"),
        EXPORT_SELECTION_CONTROLS("export-selection-controls", Kind.ROUTE_ONLY, "route-payload"),
    }

    data class Entry(
        val operation: String,
        val kind: Kind,
        val aggregateKey: String,
        val providerId: String? = null,
    )

    val entries: List<Entry> = Operation.entries.map { operation ->
        Entry(operation.key, operation.kind, operation.aggregateKey, operation.providerId)
    }

    fun validateActionContract(operation: Operation, contractProviderId: String?, safety: com.awabi2048.ccsystem.api.gui.MenuActionSafety) {
        when (operation.kind) {
            Kind.STATE_AGGREGATE -> {
                require(safety == com.awabi2048.ccsystem.api.gui.MenuActionSafety.REVERSIBLE) {
                    "State aggregate must be REVERSIBLE: ${operation.key}"
                }
                require(contractProviderId == operation.providerId) {
                    "State aggregate contract provider mismatch: ${operation.key} expected=${operation.providerId} actual=$contractProviderId"
                }
            }
            Kind.ROUTE_ONLY -> require(safety == com.awabi2048.ccsystem.api.gui.MenuActionSafety.NAVIGATION_ONLY) {
                "Route-only operation must be NAVIGATION_ONLY: ${operation.key}"
            }
        }
    }

    fun validate(): List<String> = buildList {
        entries.groupBy(Entry::operation).filterValues { it.size > 1 }.keys
            .forEach { add("duplicate reversible classification: $it") }
        entries.filter { it.kind == Kind.STATE_AGGREGATE }.forEach { entry ->
            if (entry.providerId.isNullOrBlank()) add("state aggregate has no provider: ${entry.operation}")
            if (entry.aggregateKey.isBlank()) add("state aggregate has no aggregate key: ${entry.operation}")
        }
        entries.filter { it.kind == Kind.ROUTE_ONLY && it.providerId != null }
            .forEach { add("route-only operation has provider: ${it.operation}") }
    }
}
