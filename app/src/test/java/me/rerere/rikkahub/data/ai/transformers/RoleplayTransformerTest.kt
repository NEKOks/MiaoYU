package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.NarrationStyle
import me.rerere.rikkahub.data.model.RoleplayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayTransformerTest {

    private fun createAssistant(
        name: String = "助手",
        roleplay: RoleplayConfig = RoleplayConfig(),
    ) = Assistant(name = name, roleplay = roleplay)

    private fun createConfig(
        enabled: Boolean = true,
        characterName: String = "凛",
        description: String = "银发的旅人，说话很轻。",
        personality: String = "冷静",
        scenario: String = "雨夜的旅店。",
        userPersonaName: String = "",
        userPersonaDescription: String = "",
        exampleDialogue: String = "",
        authorsNote: String = "",
        authorsNoteDepth: Int = 4,
        styleRules: List<String> = emptyList(),
        narrationStyle: NarrationStyle = NarrationStyle.ASTERISK,
    ) = RoleplayConfig(
        enabled = enabled,
        characterName = characterName,
        description = description,
        personality = personality,
        scenario = scenario,
        userPersonaName = userPersonaName,
        userPersonaDescription = userPersonaDescription,
        exampleDialogue = exampleDialogue,
        authorsNote = authorsNote,
        authorsNoteDepth = authorsNoteDepth,
        styleRules = styleRules,
        narrationStyle = narrationStyle,
    )

    private fun applyRoleplay(assistant: Assistant, messages: List<UIMessage>): List<UIMessage> {
        val injections = buildRoleplayInjections(assistant)
        if (injections.isEmpty()) return messages
        return applyInjections(
            messages = messages,
            byPosition = injections.sortedByDescending { it.priority }.groupBy { it.position },
        )
    }

    private fun systemText(messages: List<UIMessage>): String =
        messages.first { it.role == MessageRole.SYSTEM }
            .parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }

    // region 开关

    @Test
    fun `disabled roleplay injects nothing`() {
        val assistant = createAssistant(roleplay = createConfig(enabled = false))

        assertTrue(buildRoleplayInjections(assistant).isEmpty())
    }

    @Test
    fun `enabled but blank roleplay injects nothing`() {
        // 只开了开关没填内容时不该往请求里塞空设定，否则白改提示词前缀
        val assistant = createAssistant(
            roleplay = RoleplayConfig(
                enabled = true,
                characterName = "",
                description = "",
                personality = "",
                scenario = "",
            ),
        )

        assertFalse(assistant.roleplay.isActive)
        assertTrue(buildRoleplayInjections(assistant).isEmpty())
    }

    @Test
    fun `disabled roleplay leaves messages untouched`() {
        val messages = listOf(UIMessage.system("base"), UIMessage.user("hi"))
        val assistant = createAssistant(roleplay = createConfig(enabled = false))

        assertEquals(messages, applyRoleplay(assistant, messages))
    }

    // endregion

    // region 提示词内容

    @Test
    fun `character prompt is appended to system message`() {
        val assistant = createAssistant(roleplay = createConfig())

        val result = applyRoleplay(assistant, listOf(UIMessage.system("base"), UIMessage.user("hi")))
        val system = systemText(result)

        assertTrue(system.startsWith("base"))
        assertTrue(system.contains("你是「凛」。"))
        assertTrue(system.contains("银发的旅人，说话很轻。"))
        assertTrue(system.contains("性格：冷静"))
        assertTrue(system.contains("[场景]"))
        assertTrue(system.contains("雨夜的旅店。"))
    }

    @Test
    fun `character name falls back to assistant name`() {
        val config = createConfig(characterName = "")
        val assistant = createAssistant(name = "小助手", roleplay = config)

        assertEquals("小助手", resolveCharacterName(config, assistant))
        assertTrue(buildRoleplayPrompt(config, "小助手").contains("你是「小助手」。"))
    }

    @Test
    fun `blank user persona omits identity section`() {
        val prompt = buildRoleplayPrompt(createConfig(), "凛")

        assertFalse(prompt.contains("[我的身份]"))
        // 没有填身份时规则里用「我」指代对方
        assertTrue(prompt.contains("不要替我说话"))
    }

    @Test
    fun `user persona is rendered when provided`() {
        val prompt = buildRoleplayPrompt(
            createConfig(userPersonaName = "旅人", userPersonaDescription = "刚推门进来，浑身湿透。"),
            "凛",
        )

        assertTrue(prompt.contains("[我的身份]"))
        assertTrue(prompt.contains("旅人：刚推门进来，浑身湿透。"))
        assertTrue(prompt.contains("不要替旅人说话"))
    }

    @Test
    fun `asterisk style asks for asterisk actions`() {
        val prompt = buildRoleplayPrompt(createConfig(narrationStyle = NarrationStyle.ASTERISK), "凛")

        assertTrue(prompt.contains("*星号*"))
        assertFalse(prompt.contains("对白用「」"))
    }

    @Test
    fun `corner style asks for corner quotes`() {
        val prompt = buildRoleplayPrompt(createConfig(narrationStyle = NarrationStyle.CORNER), "凛")

        assertTrue(prompt.contains("对白用「」"))
        assertFalse(prompt.contains("*星号*"))
    }

    @Test
    fun `style rules are numbered after built-in rules`() {
        val prompt = buildRoleplayPrompt(
            createConfig(styleRules = listOf("不要用现代词汇。", "  ", "每段不超过三句。")),
            "凛",
        )

        assertTrue(prompt.contains("6. 不要用现代词汇。"))
        assertTrue(prompt.contains("7. 每段不超过三句。"))
        // 空白规则被过滤，不会占编号
        assertFalse(prompt.contains("7.   "))
    }

    @Test
    fun `example dialogue is included as style sample`() {
        val prompt = buildRoleplayPrompt(
            createConfig(exampleDialogue = "凛：“来了。”"),
            "凛",
        )

        assertTrue(prompt.contains("[示例对话]"))
        assertTrue(prompt.contains("凛：“来了。”"))
    }

    // endregion

    // region 作者注释

    @Test
    fun `authors note is injected at depth`() {
        val assistant = createAssistant(
            roleplay = createConfig(authorsNote = "凛始终冷淡。", authorsNoteDepth = 2),
        )
        val messages = listOf(
            UIMessage.system("base"),
            UIMessage.user("第一句"),
            UIMessage.assistant("第二句"),
            UIMessage.user("第三句"),
        )

        val result = applyRoleplay(assistant, messages)

        // depth=2 表示插入到倒数第二条之前：在 4 条消息上算出 index=2，插入后落在 index 2
        val noteIndex = result.indexOfFirst { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.startsWith("[作者注释]") }
        }
        assertEquals(2, noteIndex)
        assertEquals(MessageRole.USER, result[noteIndex].role)
        assertEquals(
            "[作者注释] 凛始终冷淡。",
            result[noteIndex].parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text },
        )
        // 原有消息顺序不变
        assertEquals(5, result.size)
    }

    @Test
    fun `authors note depth below one is clamped`() {
        val assistant = createAssistant(
            roleplay = createConfig(authorsNote = "提醒", authorsNoteDepth = 0),
        )

        val injection = buildRoleplayInjections(assistant).single { it.position == InjectionPosition.AT_DEPTH }

        assertEquals(1, injection.injectDepth)
    }

    @Test
    fun `authors note without character card still injects`() {
        // 只有作者注释、没有角色设定时，isActive 为 false，整段都不注入，避免半截配置生效
        val assistant = createAssistant(
            roleplay = RoleplayConfig(
                enabled = true,
                characterName = "",
                description = "",
                personality = "",
                scenario = "",
                authorsNote = "提醒",
            ),
        )

        assertTrue(buildRoleplayInjections(assistant).isEmpty())
    }

    // endregion
}
