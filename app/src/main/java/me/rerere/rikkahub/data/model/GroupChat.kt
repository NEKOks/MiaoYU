package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import kotlin.uuid.Uuid

/**
 * 配置里记了几个群成员。
 *
 * 列表投影不读 config，会话是从轻量查询来的，那种情况由 [Conversation.summaryIsGroup] 带出标记。
 * 助手被删掉之后它仍然为 true，而实际能发言的人可能只剩一个；
 * 判断行为（发言、标题、发言人名字）一律用 [isGroupChat]。
 */
val Conversation.isGroup: Boolean
    get() = summaryIsGroup || (config?.groupMemberIds?.size ?: 0) >= 2

/**
 * 至少两个**有效**成员才算群聊：成员被删到只剩一个时按单聊处理，
 * 免得界面还顶着群聊的样子、实际只有一个人说话。
 */
fun Settings.isGroupChat(conversation: Conversation): Boolean =
    getGroupAssistants(conversation).size >= 2

/** 群成员 id（按用户排列的顺序）；非群聊返回空 */
val Conversation.groupMemberIds: List<Uuid>
    get() = config?.groupMemberIds.orEmpty()

/** 群聊里每个成员一轮最多发言次数，限制在合理范围 */
val Conversation.groupTurnsPerMember: Int
    get() = (config?.groupTurnsPerMember ?: 1).coerceIn(1, 5)

/** 模型自动接话的最大轮数 */
val Conversation.groupAutoRounds: Int
    get() = (config?.groupAutoRounds ?: 2).coerceIn(0, 6)

/** 群成员对应的助手（保持用户在群设置里的顺序，已删除的助手自动跳过） */
fun Settings.getGroupAssistants(conversation: Conversation): List<Assistant> =
    conversation.groupMemberIds.mapNotNull { getAssistantById(it) }

/** 群聊显示名：成员名用 " / " 连接 */
fun Settings.getGroupTitle(conversation: Conversation): String =
    getGroupAssistants(conversation).joinToString(" / ") { it.name.ifBlank { "未命名" } }

/** 一轮里成员随机顺序，避免总是同一个人先开口 */
fun List<Assistant>.shuffledForRound(
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<Assistant> = if (size <= 1) this else shuffled(random)

/**
 * 按消息反查发言的群成员。
 *
 * 优先用消息上记的 [UIMessage.speakerAssistantId]：两个成员用同一个模型时也能分清。
 * 老消息没有这个字段，退回按模型 id 猜——模型被多个成员共用、或者没有模型时返回 null。
 */
fun List<Assistant>.memberOfMessage(message: UIMessage, defaultModelId: Uuid): Assistant? {
    message.speakerAssistantId?.let { speakerId -> return firstOrNull { it.id == speakerId } }
    val modelId = message.modelId ?: return null
    return filter { (it.chatModelId ?: defaultModelId) == modelId }.singleOrNull()
}

/**
 * 群聊里叫某个成员发言的提示。
 *
 * 不落库，只追加在本次请求末尾：请求以 user 收尾，模型才会新开一条回复而不是接着上一个人的发言往下写，
 * runAgent 也是按最后一条消息的角色决定续写还是新建。
 */
fun groupTurnPrompt(speaker: Assistant): UIMessage = UIMessage.user(
    "（群聊）现在轮到「${speaker.name.ifBlank { "助手" }}」发言，" +
        "只写你自己的发言，不要续写、复述或总结上面别人的内容。"
).copy(isSynthetic = true)

/**
 * 显示用：去掉模型自己加在发言开头的「【名字】」。
 *
 * 归属前缀只加给**别人**的发言，但模型看到历史里都是这个格式，会跟着模仿，
 * 于是自己的回复也以「【香子兰】」开头。气泡上已经有名字和头像，这里剥掉。
 * 只剥开头、且必须正好是自己的名字，正文中间出现的「【…】」不动。
 */
fun MessageNode.withoutSpeakerPrefix(speakerName: String): MessageNode {
    val name = speakerName.trim()
    if (name.isEmpty()) return this
    val current = runCatching { currentMessage }.getOrNull() ?: return this
    val firstTextIndex = current.parts.indexOfFirst { it is UIMessagePart.Text && it.text.isNotBlank() }
    if (firstTextIndex < 0) return this
    val part = current.parts[firstTextIndex] as UIMessagePart.Text
    val prefix = "【$name】"
    val text = part.text.trimStart()
    if (!text.startsWith(prefix)) return this
    val stripped = part.copy(text = text.removePrefix(prefix).trimStart())
    val parts = current.parts.toMutableList().also { it[firstTextIndex] = stripped }
    val messages = messages.toMutableList().also { it[selectIndex] = current.copy(parts = parts) }
    return copy(messages = messages)
}