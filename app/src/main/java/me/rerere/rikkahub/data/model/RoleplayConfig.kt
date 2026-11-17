package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 角色扮演回复里对白与旁白的标记约定。
 *
 * 只影响**提示词里的规则**和**消息渲染的解析**，不影响存储的内容本身：
 * 模型写出来的还是普通文本，标记只是渲染层分流的依据。
 */
@Serializable
enum class NarrationStyle {
    /** 酒馆习惯：`*动作*` 与 `"对白"`，模型见过的最多 */
    @SerialName("asterisk")
    ASTERISK,

    /** 中文语料更稳：`（旁白）` 与 `「对白」` */
    @SerialName("corner")
    CORNER,
}

/**
 * 助手的角色扮演配置。
 *
 * 挂在 [Assistant] 上而不是会话上：角色卡本质是「助手是谁」的一部分，和 systemPrompt、lorebookIds 同级。
 * 和 systemPrompt 一样，它跟随助手本身、不随会话固定（`ConversationConfig` 只快照模型/搜索/MCP/工作区/技能），
 * 所以改了角色卡，已有会话下一轮就会用新的设定。
 *
 * 全部字段都有默认值，旧版本写入的 JSON 缺少这个字段时按默认值解析，因此加这个字段不需要迁移 DataStore。
 */
@Serializable
data class RoleplayConfig(
    /** 总开关。关闭时生成的请求与没有这个功能时完全一致 */
    val enabled: Boolean = false,
    /** 角色名。为空时回落到 [Assistant.name] */
    val characterName: String = "",
    /** 人设、外貌、背景 */
    val description: String = "",
    /** 性格摘要，和 [description] 一起进提示词 */
    val personality: String = "",
    /** 场景与前提 */
    val scenario: String = "",
    /** 「我」是谁 */
    val userPersonaName: String = "",
    val userPersonaDescription: String = "",
    /** 示例对话。只进提示词充当风格样本，不进消息列表 */
    val exampleDialogue: String = "",
    /** 作者注释：长对话里维持人设的短提醒，按深度注入 */
    val authorsNote: String = "",
    /** 作者注释的注入深度，从最新消息往前数，至少 1 */
    val authorsNoteDepth: Int = 4,
    /** 用户自定义的风格约束，逐条列进提示词 */
    val styleRules: List<String> = emptyList(),
    /** 对白与旁白的标记约定 */
    val narrationStyle: NarrationStyle = NarrationStyle.ASTERISK,
) {
    /**
     * 是否真的会产出注入内容。
     *
     * 只打开开关但一个字都没填时视作未启用：否则会给请求塞一段空的角色设定，
     * 白白改变提示词前缀、破坏提示词缓存。
     */
    val isActive: Boolean
        get() = enabled && (
            characterName.isNotBlank() ||
                description.isNotBlank() ||
                personality.isNotBlank() ||
                scenario.isNotBlank()
            )
}
