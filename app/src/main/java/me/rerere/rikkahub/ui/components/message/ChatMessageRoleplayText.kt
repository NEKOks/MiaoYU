package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.NarrationStyle
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/** 一段回复里的一种成分 */
enum class RoleplaySegmentType {
    /** 角色说的话 */
    DIALOGUE,

    /** 旁白、环境描写 */
    NARRATION,

    /** 动作、神态 */
    ACTION,

    /** 心理活动 */
    THOUGHT,
}

data class RoleplaySegment(
    val type: RoleplaySegmentType,
    val text: String,
)

private data class Marker(
    val open: Char,
    val close: Char,
    val type: RoleplaySegmentType,
)

private val ASTERISK_MARKERS = listOf(
    Marker('*', '*', RoleplaySegmentType.ACTION),
    Marker('"', '"', RoleplaySegmentType.DIALOGUE),
    Marker('“', '”', RoleplaySegmentType.DIALOGUE),
    Marker('（', '）', RoleplaySegmentType.THOUGHT),
    Marker('(', ')', RoleplaySegmentType.THOUGHT),
)

private val CORNER_MARKERS = listOf(
    Marker('「', '」', RoleplaySegmentType.DIALOGUE),
    Marker('“', '”', RoleplaySegmentType.DIALOGUE),
    Marker('（', '）', RoleplaySegmentType.NARRATION),
    Marker('(', ')', RoleplaySegmentType.NARRATION),
    Marker('*', '*', RoleplaySegmentType.ACTION),
)

/**
 * 把一条回复按角色扮演的标记约定切段。
 *
 * 三条硬约束：
 * - **永不丢字**：认不出标记的文字原样当对白，切分只是加样式，不改变内容。
 * - **流式容错**：还没闭合的标记（生成到一半的 `*`）先当普通文字，配对后再升级样式，避免文字跳来跳去。
 * - **不碰代码块**：出现 ``` 时整段交给 Markdown，否则代码里的 `*`、引号会被切开。
 */
internal fun parseRoleplaySegments(text: String, style: NarrationStyle): List<RoleplaySegment> {
    if (text.isEmpty()) return emptyList()
    if (text.contains("```")) return listOf(RoleplaySegment(RoleplaySegmentType.DIALOGUE, text))

    val markers = when (style) {
        NarrationStyle.ASTERISK -> ASTERISK_MARKERS
        NarrationStyle.CORNER -> CORNER_MARKERS
    }
    val segments = mutableListOf<RoleplaySegment>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isEmpty()) return
        val content = plain.toString()
        plain.setLength(0)
        if (content.isNotBlank()) segments += RoleplaySegment(RoleplaySegmentType.DIALOGUE, content.trim())
    }

    var index = 0
    while (index < text.length) {
        val marker = markers.firstOrNull { it.open == text[index] && canOpen(text, index, it) }
        val closeIndex = marker?.let { findClose(text, index + 1, it) } ?: -1
        if (marker == null || closeIndex < 0) {
            plain.append(text[index])
            index++
            continue
        }
        flushPlain()
        val raw = text.substring(index, closeIndex + 1)
        segments += RoleplaySegment(
            type = marker.type,
            // 对白保留引号本身：切段只是加样式，读起来仍是一句完整的台词；
            // 旁白/动作/心理用排印代替标记，所以去掉括号与星号
            text = if (marker.type == RoleplaySegmentType.DIALOGUE) {
                raw
            } else {
                // removeSurrounding 只接受 CharSequence，标记本身是 Char
                raw.removeSurrounding(marker.open.toString(), marker.close.toString()).trim()
            },
        )
        index = closeIndex + 1
    }
    flushPlain()
    return coalesce(segments).ifEmpty { listOf(RoleplaySegment(RoleplaySegmentType.DIALOGUE, text)) }
}

/**
 * 相邻的同类段合并。
 *
 * 「他说：」+「“好。”」+「。」这种被引号切碎的一行，合并后与原文一致，
 * 不会因为每段各自一个 Markdown 块而多出一堆段间距。
 */
private fun coalesce(segments: List<RoleplaySegment>): List<RoleplaySegment> {
    if (segments.size <= 1) return segments
    val merged = mutableListOf<RoleplaySegment>()
    segments.forEach { segment ->
        val last = merged.lastOrNull()
        if (last != null && last.type == segment.type) {
            merged[merged.lastIndex] = last.copy(text = last.text + segment.text)
        } else {
            merged += segment
        }
    }
    return merged
}

/**
 * `*` 同时也是 Markdown 的加粗/斜体标记，`**加粗**` 的单个星号不能当成动作段的开头。
 */
private fun canOpen(text: String, index: Int, marker: Marker): Boolean {
    if (marker.open != '*') return true
    return text.getOrNull(index + 1) != '*'
}

private fun findClose(text: String, from: Int, marker: Marker): Int {
    var index = from
    while (index < text.length) {
        if (text[index] == marker.close) {
            // `*` 同时是 Markdown 的强调标记：`**加粗**` 两端的星号相邻，
            // 认成动作段的边界会把加粗吃掉，所以两侧任一侧还挨着 `*` 就跳过
            val isMarkdownPair = marker.close == '*' &&
                (text.getOrNull(index - 1) == '*' || text.getOrNull(index + 1) == '*')
            // 空段落没有意义
            if (!isMarkdownPair && index > from) return index
        }
        index++
    }
    return -1
}

/**
 * 角色扮演用的消息正文：对白走 Markdown，旁白/动作/心理走弱化的排印。
 *
 * 没有任何标记时行为与 [MarkdownBlock] 一致。
 */
@Composable
fun ChatMessageRoleplayText(
    text: String,
    style: NarrationStyle,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onClickCitation: (String) -> Unit = {},
) {
    val segments = remember(text, style) { parseRoleplaySegments(text, style) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            when (segment.type) {
                RoleplaySegmentType.DIALOGUE -> MarkdownBlock(
                    content = segment.text,
                    streaming = streaming && isLast,
                    onClickCitation = onClickCitation,
                )

                RoleplaySegmentType.NARRATION -> RoleplayAside(
                    text = segment.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    italic = false,
                )

                RoleplaySegmentType.ACTION -> RoleplayAside(
                    text = segment.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    italic = true,
                )

                RoleplaySegmentType.THOUGHT -> RoleplayAside(
                    text = segment.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    italic = true,
                    indent = true,
                )
            }
        }
    }
}

@Composable
private fun RoleplayAside(
    text: String,
    color: Color,
    italic: Boolean,
    indent: Boolean = false,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        ),
        modifier = Modifier.padding(start = if (indent) 16.dp else 0.dp),
    )
}

/**
 * 助手正文的统一入口：开了角色扮演就按段落渲染，否则保持原来的 Markdown。
 *
 * 单独抽出来是为了让 [ChatMessage] 里两个气泡分支（有气泡 / 无气泡）共用同一套逻辑。
 */
@Composable
internal fun AssistantMessageText(
    text: String,
    streaming: Boolean,
    roleplayStyle: NarrationStyle?,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
) {
    if (roleplayStyle == null) {
        MarkdownBlock(
            content = text,
            streaming = streaming,
            onClickCitation = onClickCitation,
            modifier = modifier,
        )
    } else {
        ChatMessageRoleplayText(
            text = text,
            style = roleplayStyle,
            modifier = modifier,
            streaming = streaming,
            onClickCitation = onClickCitation,
        )
    }
}
