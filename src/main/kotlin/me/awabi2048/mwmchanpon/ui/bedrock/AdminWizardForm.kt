package me.awabi2048.mwmchanpon.ui.bedrock

import com.awabi2048.ccsystem.api.localization.generated.ChanponAdminWizardKeys

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiBedrockFormService
import me.awabi2048.myworldmanager.api.service.ApiTemplateValidationIssue
import me.awabi2048.myworldmanager.model.TemplateData
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chanpon管理者向けのBedrock作成Wizardです。
 *
 * Cumulusの生成や応答解析はMWMのFloodgateFormBridgeに委譲し、ここでは
 * Wizardの状態遷移とMWM公開APIの呼び出しだけを所有します。
 */
class AdminWizardForm(
    private val plugin: MWMChanponPlugin,
    private val core: CoreApiBridge = CoreApiBridge()
) {
    data class Request(
        val ownerUuid: UUID,
        val ownerName: String,
        val returnToExistingUi: (Player) -> Unit
    )

    private data class Session(
        val request: Request,
        val templates: List<TemplateData>,
        var template: TemplateData? = null,
        var worldName: String? = null,
        var completed: Boolean = false
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val plainText = PlainTextComponentSerializer.plainText()

    fun open(player: Player, request: Request): Boolean {
        val forms = MyWorldManagerApi.getBedrockFormService() ?: return fail(player, request)
        if (!forms.isBedrock(player)) return fail(player, request)

        val templates = core.getTemplateRepository()?.findAll().orEmpty()
        if (templates.isEmpty()) return fail(player, request)

        sessions[player.uniqueId] = Session(request, templates)
        return openTemplateSelection(player, sessions[player.uniqueId]!!, forms)
    }

    private fun openTemplateSelection(player: Player, session: Session, forms: ApiBedrockFormService): Boolean {
        val labels = session.templates.map { it.name }
        val opened = forms.sendSimpleForm(
            player = player,
            title = text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_TITLE),
            content = text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_SELECT_TEMPLATE),
            buttons = labels,
            onSelect = { index ->
                val current = sessions[player.uniqueId] ?: return@sendSimpleForm MenuActionResult.Ignored
                val selected = current.templates.getOrNull(index) ?: return@sendSimpleForm MenuActionResult.Ignored
                current.template = selected
                openTemplateDetail(player, current, forms)
                noUpdate()
            },
            onClosed = {
                cancel(player)
                noUpdate()
            }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun openTemplateDetail(
        player: Player,
        session: Session,
        forms: ApiBedrockFormService
    ): Boolean {
        val template = session.template ?: return fail(player, session.request)
        val repository = core.getTemplateRepository() ?: return fail(player, session.request)
        val validationIssue = repository.validationIssue(template.id)
        val usable = validationIssue == null
        val spawn = template.originLocation?.let {
            "(${it.blockX}, ${it.blockY}, ${it.blockZ})"
        } ?: text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_UNSET)
        val validationReason = when (validationIssue) {
            ApiTemplateValidationIssue.MISSING_DIRECTORY ->
                text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_VALIDATION_MISSING_DIRECTORY)
            ApiTemplateValidationIssue.MISSING_ORIGIN ->
                text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_VALIDATION_MISSING_ORIGIN)
            null -> null
        }
        val content = text(
            player,
            if (usable) {
                ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_DETAIL_CONTENT_AVAILABLE
            } else {
                ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_DETAIL_CONTENT_UNAVAILABLE
            },
            "description" to template.description.joinToString("\n").ifBlank {
                text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_NO_DESCRIPTION)
            },
            "spawn" to spawn,
            "status" to text(
                player,
                if (usable) {
                    ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_STATUS_AVAILABLE
                } else {
                    ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_STATUS_UNAVAILABLE
                }
            ),
            "reason" to validationReason.orEmpty()
        )
        val buttons = buildList {
            if (usable) {
                add(text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_USE_TEMPLATE))
                add(text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_PREVIEW))
            }
            add(text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_BACK))
        }
        val opened = forms.sendSimpleForm(
            player = player,
            title = template.name,
            content = content,
            buttons = buttons,
            onSelect = { index ->
                if (!usable) {
                    openTemplateSelection(player, session, forms)
                    return@sendSimpleForm noUpdate()
                }
                when (index) {
                    0 -> {
                        openNameInput(player, session, forms)
                        noUpdate()
                    }
                    1 -> {
                        val service = core.getWorldService() ?: run {
                            fail(player, session.request)
                            return@sendSimpleForm noUpdate()
                        }
                        val started = service.previewTemplate(player, template.id) {
                            val current = sessions[player.uniqueId] ?: return@previewTemplate
                            val currentForms = MyWorldManagerApi.getBedrockFormService()
                                ?: return@previewTemplate
                            openTemplateDetail(player, current, currentForms)
                        }
                        if (!started) {
                            openTemplateDetail(player, session, forms)
                        }
                        noUpdate()
                    }
                    else -> {
                        openTemplateSelection(player, session, forms)
                        noUpdate()
                    }
                }
            },
            onClosed = {
                cancel(player)
                noUpdate()
            }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun openNameInput(player: Player, session: Session, forms: ApiBedrockFormService, error: String? = null): Boolean {
        val label = buildString {
            append(text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_INPUT_NAME))
            if (!error.isNullOrBlank()) append("\n$error")
        }
        val opened = forms.sendCustomInputForm(
            player = player,
            title = text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_TITLE),
            label = label,
            placeholder = text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_NAME_PLACEHOLDER),
            defaultValue = session.worldName.orEmpty(),
            onSubmit = { rawName ->
                val name = normalizeName(rawName)
                if (name == null) {
                    openNameInput(player, session, forms, text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_INVALID_NAME))
                    return@sendCustomInputForm noUpdate()
                }
                val validationError = core.getWorldService()?.validateWorldName(player, name)
                if (validationError != null) {
                    openNameInput(player, session, forms, plainText.serialize(validationError))
                    return@sendCustomInputForm noUpdate()
                }
                session.worldName = name
                openConfirmation(player, session, forms)
                noUpdate()
            },
            onClosed = {
                cancel(player)
                noUpdate()
            }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun openConfirmation(player: Player, session: Session, forms: ApiBedrockFormService): Boolean {
        val template = session.template ?: return fail(player, session.request)
        val name = session.worldName ?: return fail(player, session.request)
        val opened = forms.sendSimpleForm(
            player = player,
            title = text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CONFIRM_TITLE),
            content = text(
                player,
                ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CONFIRM_CONTENT,
                "owner" to session.request.ownerName,
                "template" to template.name,
                "world" to name,
                "spawn" to (
                    template.originLocation?.let {
                        "(${it.blockX}, ${it.blockY}, ${it.blockZ})"
                    } ?: text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_UNSET)
                ),
                "cost" to 0
            ),
            buttons = listOf(
                text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CREATE),
                text(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CANCEL)
            ),
            onSelect = { index ->
                if (index != 0) {
                    cancel(player)
                    return@sendSimpleForm noUpdate()
                }
                create(player, session, template, name)
                noUpdate()
            },
            onClosed = {
                cancel(player)
                noUpdate()
            }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun create(player: Player, session: Session, template: TemplateData, name: String) {
        val service = core.getWorldService() ?: run {
            fail(player, session.request)
            return
        }
        service.createFromTemplate(
            template.id,
            session.request.ownerUuid,
            name,
            0,
            me.awabi2048.myworldmanager.api.service.WorldPointBillingMode.NONE
        ).whenComplete { success, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (error != null || success != true) {
                    player.sendMessage(CCSystem.getAPI().getI18nComponent(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CREATE_FAILED))
                } else {
                    player.sendMessage(CCSystem.getAPI().getI18nComponent(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CREATED))
                }
                finish(player, session)
            })
        }
    }

    private fun cancel(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        player.sendMessage(CCSystem.getAPI().getI18nComponent(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_CANCELLED))
        finish(player, session)
    }

    private fun fail(player: Player, request: Request): Boolean {
        player.sendMessage(CCSystem.getAPI().getI18nComponent(player, ChanponAdminWizardKeys.CHANPON_ADMIN_WIZARD_UNAVAILABLE))
        request.returnToExistingUi(player)
        return false
    }

    private fun finish(player: Player, session: Session) {
        if (session.completed) return
        session.completed = true
        sessions.remove(player.uniqueId, session)
        session.request.returnToExistingUi(player)
    }

    private fun text(player: Player, key: LocalizationKey<String>, vararg placeholders: Pair<String, Any>): String =
        CCSystem.getAPI().getLocalized(player, key, placeholders.toMap())

    private fun noUpdate(): MenuActionResult = MenuActionResult.Success(MenuUpdate.None)

    companion object {
        fun normalizeName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= 32 }
    }
}
