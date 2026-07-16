package me.awabi2048.mwmchanpon.ui.bedrock

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiBedrockFormService
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
            title = text(player, "chanpon.admin_wizard.title"),
            content = text(player, "chanpon.admin_wizard.select_template"),
            buttons = labels,
            onSelect = { index ->
                val current = sessions[player.uniqueId] ?: return@sendSimpleForm
                val selected = current.templates.getOrNull(index) ?: return@sendSimpleForm
                current.template = selected
                openNameInput(player, current, forms)
            },
            onClosed = { cancel(player) }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun openNameInput(player: Player, session: Session, forms: ApiBedrockFormService, error: String? = null): Boolean {
        val label = buildString {
            append(text(player, "chanpon.admin_wizard.input_name"))
            if (!error.isNullOrBlank()) append("\n$error")
        }
        val opened = forms.sendCustomInputForm(
            player = player,
            title = text(player, "chanpon.admin_wizard.title"),
            label = label,
            placeholder = text(player, "chanpon.admin_wizard.name_placeholder"),
            defaultValue = session.worldName.orEmpty(),
            onSubmit = { rawName ->
                val name = normalizeName(rawName)
                if (name == null) {
                    openNameInput(player, session, forms, text(player, "chanpon.admin_wizard.invalid_name"))
                    return@sendCustomInputForm
                }
                val validationError = core.getWorldService()?.validateWorldName(player, name)
                if (validationError != null) {
                    openNameInput(player, session, forms, plainText.serialize(validationError))
                    return@sendCustomInputForm
                }
                session.worldName = name
                openConfirmation(player, session, forms)
            },
            onClosed = { cancel(player) }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun openConfirmation(player: Player, session: Session, forms: ApiBedrockFormService): Boolean {
        val template = session.template ?: return fail(player, session.request)
        val name = session.worldName ?: return fail(player, session.request)
        val opened = forms.sendSimpleForm(
            player = player,
            title = text(player, "chanpon.admin_wizard.confirm_title"),
            content = text(
                player,
                "chanpon.admin_wizard.confirm_content",
                "owner" to session.request.ownerName,
                "template" to template.name,
                "world" to name
            ),
            buttons = listOf(
                text(player, "chanpon.admin_wizard.create"),
                text(player, "chanpon.admin_wizard.cancel")
            ),
            onSelect = { index ->
                if (index != 0) {
                    cancel(player)
                    return@sendSimpleForm
                }
                create(player, session, template, name)
            },
            onClosed = { cancel(player) }
        )
        if (!opened) fail(player, session.request)
        return opened
    }

    private fun create(player: Player, session: Session, template: TemplateData, name: String) {
        val service = core.getWorldService() ?: run {
            fail(player, session.request)
            return
        }
        service.createFromTemplate(template.id, session.request.ownerUuid, name, 0).whenComplete { success, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (error != null || success != true) {
                    player.sendMessage(CCSystem.getAPI().getI18nComponent(player, "chanpon.admin_wizard.create_failed"))
                } else {
                    player.sendMessage(CCSystem.getAPI().getI18nComponent(player, "chanpon.admin_wizard.created"))
                }
                finish(player, session)
            })
        }
    }

    private fun cancel(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        player.sendMessage(CCSystem.getAPI().getI18nComponent(player, "chanpon.admin_wizard.cancelled"))
        finish(player, session)
    }

    private fun fail(player: Player, request: Request): Boolean {
        player.sendMessage(CCSystem.getAPI().getI18nComponent(player, "chanpon.admin_wizard.unavailable"))
        request.returnToExistingUi(player)
        return false
    }

    private fun finish(player: Player, session: Session) {
        if (session.completed) return
        session.completed = true
        sessions.remove(player.uniqueId, session)
        session.request.returnToExistingUi(player)
    }

    private fun text(player: Player, key: String, vararg placeholders: Pair<String, Any>): String =
        CCSystem.getAPI().getI18nString(player, key, placeholders.toMap())

    companion object {
        fun normalizeName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= 32 }
    }
}
