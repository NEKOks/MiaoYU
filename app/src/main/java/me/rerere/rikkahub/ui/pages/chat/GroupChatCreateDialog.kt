package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import kotlin.uuid.Uuid

/**
 * 新建群聊：从全部助手里挑成员。
 *
 * 点选的顺序就是群里的发言顺序，第一个成员作为群主；至少选 2 个才能确定。
 */
@Composable
fun GroupChatCreateDialog(
    assistants: List<Assistant>,
    onDismiss: () -> Unit,
    onCreate: (List<Uuid>) -> Unit,
) {
    // 用 List 而不是 Set：顺序有意义
    var selectedIds by remember { mutableStateOf(emptyList<Uuid>()) }

    fun toggle(id: Uuid) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建群聊") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "选择至少 2 个助手，点选顺序就是发言顺序，第一个是群主",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items = assistants, key = { it.id }) { assistant ->
                        val order = selectedIds.indexOf(assistant.id)
                        val selected = order >= 0
                        Surface(
                            onClick = { toggle(assistant.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                UIAvatar(
                                    name = assistant.name,
                                    value = assistant.avatar,
                                    onClick = { toggle(assistant.id) },
                                    modifier = Modifier.size(36.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = assistant.name.ifBlank { "未命名助手" },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) {
                                        Text(
                                            text = if (order == 0) {
                                                "第 1 位（群主）"
                                            } else {
                                                "第 ${order + 1} 位"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { toggle(assistant.id) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(selectedIds) },
                enabled = selectedIds.size >= 2,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}