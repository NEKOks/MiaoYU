package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.model.NarrationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplaySegmentsTest {

    private fun parse(text: String, style: NarrationStyle = NarrationStyle.ASTERISK) =
        parseRoleplaySegments(text, style)

    @Test
    fun `plain text is a single dialogue segment`() {
        val text = "雨还在下，我推门走了进去。"

        assertEquals(listOf(RoleplaySegment(RoleplaySegmentType.DIALOGUE, text)), parse(text))
    }

    @Test
    fun `asterisk style splits action and dialogue`() {
        val segments = parse("*她抬起头* “你来了。”")

        assertEquals(RoleplaySegmentType.ACTION, segments[0].type)
        assertEquals("她抬起头", segments[0].text)
        assertEquals(RoleplaySegmentType.DIALOGUE, segments[1].type)
        // 对白保留引号，切段只加样式、不改内容
        assertEquals("“你来了。”", segments[1].text)
    }

    @Test
    fun `corner style treats parentheses as narration`() {
        val segments = parse("（雨停了）「走吧」", NarrationStyle.CORNER)

        assertEquals(RoleplaySegmentType.NARRATION, segments[0].type)
        assertEquals("雨停了", segments[0].text)
        assertEquals(RoleplaySegmentType.DIALOGUE, segments[1].type)
        assertEquals("「走吧」", segments[1].text)
    }

    @Test
    fun `asterisk style treats parentheses as thought`() {
        val segments = parse("（他其实很紧张）")

        assertEquals(RoleplaySegmentType.THOUGHT, segments.single().type)
        assertEquals("他其实很紧张", segments.single().text)
    }

    @Test
    fun `unclosed marker stays plain text`() {
        // 流式生成到一半：只有一个星号
        val text = "他顿了顿 *然后没有说下去"

        val segments = parse(text)

        assertEquals(RoleplaySegmentType.DIALOGUE, segments.single().type)
        assertTrue(segments.single().text.contains("然后没有说下去"))
        assertTrue(segments.single().text.contains("*"))
    }

    @Test
    fun `markdown bold is not treated as action`() {
        val text = "他说 **真的** 就走了"

        val segments = parse(text)

        assertEquals(RoleplaySegmentType.DIALOGUE, segments.single().type)
        assertEquals(text, segments.single().text)
    }

    @Test
    fun `code fence is never split`() {
        val text = "看这个：\n```kotlin\nval a = \"*x*\"\n```\n就这样。"

        val segments = parse(text)

        assertEquals(RoleplaySegmentType.DIALOGUE, segments.single().type)
        assertEquals(text, segments.single().text)
    }

    @Test
    fun `empty text yields no segments`() {
        assertTrue(parse("").isEmpty())
    }

    @Test
    fun `whitespace only text is preserved`() {
        assertEquals(1, parse("   ").size)
    }

    @Test
    fun `dialogue keeps its quote marks`() {
        val segments = parse("“我会等你的。”")

        assertEquals(RoleplaySegmentType.DIALOGUE, segments.single().type)
        assertEquals("“我会等你的。”", segments.single().text)
    }

    @Test
    fun `quotes inside a sentence do not split it into several blocks`() {
        // 相邻同类段会合并，否则一句话会被引号切成三段、多出一堆段间距
        val text = "他说：“好。”"

        val segments = parse(text)

        assertEquals(1, segments.size)
        assertEquals(text, segments.single().text)
    }

    @Test
    fun `actions are separated from surrounding dialogue`() {
        val segments = parse("“冷吗？”*他把外套递过来*")

        assertEquals(2, segments.size)
        assertEquals(RoleplaySegmentType.DIALOGUE, segments[0].type)
        assertEquals("“冷吗？”", segments[0].text)
        assertEquals(RoleplaySegmentType.ACTION, segments[1].type)
        assertEquals("他把外套递过来", segments[1].text)
    }
}
