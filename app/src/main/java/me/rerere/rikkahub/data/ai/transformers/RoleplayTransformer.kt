package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.NarrationStyle
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.RoleplayConfig

/**
 * 角色扮演注入。
 *
 * 把 [Assistant.roleplay] 里的角色卡翻译成提示词，复用 [applyInjections] 已有的位置插入逻辑，
 * 不新增注入位置、不改动 [PromptInjectionTransformer] 的世界书触发逻辑。
 *
 * 两种内容：
 * - 角色设定 / 场景 / 我的身份 / 示例对话 / 扮演规则 → AFTER_SYSTEM_PROMPT，位置稳定，利于提示词缓存
 * - 作者注释 → AT_DEPTH，长对话里维持人设
 *
 * 未启用时原样返回入参（同一个实例），保证关掉这个功能后的请求与没有它时完全一致。
 */
object RoleplayTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val injections = buildRoleplayInjections(ctx.assistant)
        if (injections.isEmpty()) return messages
        val byPosition = injections
            .sortedByDescending { it.priority }
            .groupBy { it.position }
        return applyInjections(messages, byPosition)
    }
}

/**
 * 构造角色扮演要注入的内容；未启用时返回空列表。
 *
 * 独立成纯函数，方便单测：`TransformerContext` 需要 Android `Context`，
 * 在 JVM 单测里构造不了，测试只覆盖这一层和 [applyInjections]。
 */
internal fun buildRoleplayInjections(assistant: Assistant): List<PromptInjection> {
    val config = assistant.roleplay
    if (!config.isActive) return emptyList()

    val injections = mutableListOf<PromptInjection>()
    val prompt = buildRoleplayPrompt(config, resolveCharacterName(config, assistant))
    if (prompt.isNotBlank()) {
        injections += PromptInjection.ModeInjection(
            name = "[roleplay]",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = prompt,
            role = MessageRole.SYSTEM,
        )
    }
    if (config.authorsNote.isNotBlank()) {
        injections += PromptInjection.ModeInjection(
            name = "[roleplay:note]",
            position = InjectionPosition.AT_DEPTH,
            content = "[作者注释] ${config.authorsNote.trim()}",
            injectDepth = config.authorsNoteDepth.coerceAtLeast(1),
            role = MessageRole.USER,
        )
    }
    return injections
}

/** 角色名没填时退回助手名，避免提示词里出现「你是「」」 */
internal fun resolveCharacterName(config: RoleplayConfig, assistant: Assistant): String =
    config.characterName.ifBlank { assistant.name }.ifBlank { "助手" }

/**
 * 角色卡正文。只写「模型应该怎么演」，不写剧情。
 *
 * 顺序固定、内容逐轮稳定，作为系统提示的一部分不会破坏提示词缓存；
 * 动态内容（世界书触发）由 [PromptInjectionTransformer] 排在它之后注入。
 */
internal fun buildRoleplayPrompt(config: RoleplayConfig, characterName: String): String {
    val userLabel = config.userPersonaName.ifBlank { "我" }
    return buildString {
        appendLine("[角色]")
        appendLine("你是「$characterName」。")
        if (config.description.isNotBlank()) {
            appendLine(config.description.trim())
        }
        if (config.personality.isNotBlank()) {
            appendLine("性格：${config.personality.trim()}")
        }

        if (config.scenario.isNotBlank()) {
            appendLine()
            appendLine("[场景]")
            appendLine(config.scenario.trim())
        }

        if (config.userPersonaName.isNotBlank() || config.userPersonaDescription.isNotBlank()) {
            appendLine()
            appendLine("[我的身份]")
            append(buildString {
                if (config.userPersonaName.isNotBlank()) {
                    append(userLabel)
                    if (config.userPersonaDescription.isNotBlank()) append("：")
                }
                if (config.userPersonaDescription.isNotBlank()) {
                    append(config.userPersonaDescription.trim())
                }
            })
            appendLine()
        }

        if (config.exampleDialogue.isNotBlank()) {
            appendLine()
            appendLine("[示例对话]")
            appendLine(config.exampleDialogue.trim())
        }

        appendLine()
        appendLine("[扮演规则]")
        appendLine("1. 始终以「$characterName」的视角说话和行动，不跳出角色；不提“作为 AI”、不解释设定、不总结剧情。")
        appendLine("2. 只描写「$characterName」的言行、神态、内心与环境；不要替${userLabel}说话、做决定、写台词或心理活动。")
        appendLine("3. ${narrationRule(config.narrationStyle)}")
        appendLine("4. 每次回复 2–5 段，不预告后续走向、不替对方推进时间。")
        append("5. 不主动结束场景，把下一步留给${userLabel}。")
        config.styleRules
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEachIndexed { index, rule ->
                appendLine()
                append("${index + 6}. $rule")
            }
        appendLine()
    }
}

/** 对白与旁白的标记约定，和渲染层的解析规则必须一致 */
internal fun narrationRule(style: NarrationStyle): String = when (style) {
    NarrationStyle.ASTERISK ->
        "对白写在引号内；动作、神态、环境描写用 *星号* 包裹；心理活动用（括号）。"
    NarrationStyle.CORNER ->
        "对白用「」，旁白、动作与心理活动用（括号）。"
}
