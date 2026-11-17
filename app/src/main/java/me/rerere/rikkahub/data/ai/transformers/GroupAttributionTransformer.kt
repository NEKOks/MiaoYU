package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.memberOfMessage
import kotlin.uuid.Uuid

/** 查不到发言者时用的名字，避免把匿名发言安到某个具体成员头上 */
private const val UNKNOWN_SPEAKER = "其他人"

/**
 * 群聊归属标记。
 *
 * 群聊历史是多个助手的发言拼在一起的，模型看到一串 assistant 消息会分不清哪条是自己的。
 * 这里给其他成员的助手消息加「【名字】」前缀，并在系统提示词末尾说明群聊关系和本轮发言者；
 * 正在发言的成员自己的消息保持原样。只在群聊里启用（groupMembers 非空），单聊请求完全不受影响。
 * 改动只作用于发给模型的副本，会话里保存的消息不会被改写。
 */
class GroupAttributionTransformer(
    private val speaker: Assistant,
    private val members: List<Assistant>,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val defaultModelId = ctx.settings.chatModelId
        val attributed = messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) return@map message
            val name = groupSpeakerName(message, speaker, members, defaultModelId) ?: return@map message
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Text && part.text.isNotBlank()) {
                    part.copy(text = "【$name】${part.text}")
                } else {
                    part
                }
            }
            if (parts == message.parts) message else message.copy(parts = parts)
        }
        val hint = PromptInjection.ModeInjection(
            name = "[group]",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = buildGroupPrompt(speaker, members),
            role = MessageRole.SYSTEM,
        )
        return applyInjections(attributed, mapOf(InjectionPosition.AFTER_SYSTEM_PROMPT to listOf(hint)))
    }
}

/**
 * 这条助手消息是谁说的：返回 null 表示是正在发言的成员自己说的，不用加前缀。
 * 按群成员里使用同一模型的助手反查；同一模型被多个成员共用或查不到时算「其他人」，
 * 宁可标错也不要把别人的话当成自己刚说过的。
 */
internal fun groupSpeakerName(
    message: UIMessage,
    speaker: Assistant,
    members: List<Assistant>,
    defaultModelId: Uuid,
): String? {
    val owner = members.memberOfMessage(message, defaultModelId) ?: return UNKNOWN_SPEAKER
    return if (owner.id == speaker.id) null else owner.name.ifBlank { UNKNOWN_SPEAKER }
}

/** 群聊关系说明：成员名单、「你是谁」，以及只写自己发言的要求 */
internal fun buildGroupPrompt(speaker: Assistant, members: List<Assistant>): String {
    val speakerName = speaker.name.ifBlank { UNKNOWN_SPEAKER }
    val names = members.joinToString("、") { it.name.ifBlank { UNKNOWN_SPEAKER } }
    return buildString {
        appendLine("[群聊]")
        appendLine("你在群聊「$names」里，你的身份是「$speakerName」。")
        appendLine("以「【名字】」开头的是其他成员的发言，不是用户说的话，也不是你要接着写的内容。")
        appendLine("你自己的发言不要以「【$speakerName】」或任何「【名字】」开头——界面会显示你是谁，不用自己报名。")
        append("本轮只写「$speakerName」自己的发言：不要替其他成员说话，不要复述别人的内容，不要总结全场。")
    }
}