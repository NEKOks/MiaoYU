package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.NarrationStyle
import me.rerere.rikkahub.data.model.RoleplayConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantRoleplayPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("角色扮演")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantRoleplayContent(
            innerPadding = innerPadding,
            roleplay = assistant.roleplay,
            assistantName = assistant.name,
            // 这里的 assistant 每次调用时才从 State 里读，连续编辑两个输入框不会互相覆盖
            onUpdate = { roleplay ->
                vm.update(assistant.copy(roleplay = roleplay))
            },
            // 导入角色卡：角色名和助手名一起换，其他段落只覆盖卡里有的
            onImportCard = { imported ->
                vm.update(
                    assistant.copy(
                        name = imported.name.ifBlank { assistant.name },
                        roleplay = assistant.roleplay.copy(
                            enabled = true,
                            characterName = imported.roleplay.characterName
                                .ifBlank { assistant.roleplay.characterName },
                            description = imported.roleplay.description,
                            personality = imported.roleplay.personality,
                            scenario = imported.roleplay.scenario,
                            exampleDialogue = imported.roleplay.exampleDialogue,
                            authorsNote = imported.roleplay.authorsNote,
                        ),
                    )
                )
            },
        )
    }
}

@Composable
private fun AssistantRoleplayContent(
    innerPadding: PaddingValues,
    roleplay: RoleplayConfig,
    assistantName: String,
    onUpdate: (RoleplayConfig) -> Unit,
    onImportCard: (Assistant) -> Unit,
) {
    val characterNameHint = if (assistantName.isNotBlank()) {
        "留空时使用助手名「$assistantName」"
    } else {
        "留空时使用助手名"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = "角色扮演",
            description = "启用后角色设定会作为额外提示词注入请求",
        )
        RoleplayCardImport(onImported = onImportCard)
        CardGroup {
            switchItem(
                checked = roleplay.enabled,
                onCheckedChange = { enabled ->
                    onUpdate(roleplay.copy(enabled = enabled))
                },
                headlineContent = { Text("启用角色扮演") },
                supportingContent = {
                    Text(
                        if (roleplay.isActive) {
                            "当前设定会注入提示词"
                        } else {
                            "关闭、或角色名/设定全为空时不会改变请求"
                        }
                    )
                },
            )
            formItem(
                headlineContent = { Text("旁白样式") },
                supportingContent = { Text("模型用哪种标记区分旁白和对白") },
            ) {
                val options = listOf(
                    NarrationStyle.ASTERISK to "星号",
                    NarrationStyle.CORNER to "括号",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (style, label) ->
                        SegmentedButton(
                            selected = roleplay.narrationStyle == style,
                            onClick = { onUpdate(roleplay.copy(narrationStyle = style)) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            Text(label)
                        }
                    }
                }
                Text(
                    text = when (roleplay.narrationStyle) {
                        NarrationStyle.ASTERISK -> "*动作* 与 \"对白\""
                        NarrationStyle.CORNER -> "（旁白）与「对白」"
                    },
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader(
            title = "角色",
            description = "角色本身是谁、怎么说话",
        )
        CardGroup {
            formItem(
                headlineContent = { Text("角色名") },
                supportingContent = { Text(characterNameHint) },
            ) {
                OutlinedTextField(
                    value = roleplay.characterName,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(characterName = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：林雾") },
                    singleLine = true,
                )
            }
            formItem(
                headlineContent = { Text("人设描述") },
                supportingContent = { Text("外貌、身份、经历，写得越具体越稳定") },
            ) {
                OutlinedTextField(
                    value = roleplay.description,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(description = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("外貌、身份、经历……") },
                    minLines = 4,
                    maxLines = 12,
                )
            }
            formItem(
                headlineContent = { Text("性格") },
                supportingContent = { Text("脾气、说话方式、口头禅") },
            ) {
                OutlinedTextField(
                    value = roleplay.personality,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(personality = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("冷淡、话少，但会记住我说过的细节……") },
                    minLines = 2,
                    maxLines = 6,
                )
            }
        }

        SectionHeader(
            title = "场景与身份",
            description = "故事发生在哪里，以及「我」是谁",
        )
        CardGroup {
            formItem(
                headlineContent = { Text("场景") },
                supportingContent = { Text("时间、地点、已经发生的事") },
            ) {
                OutlinedTextField(
                    value = roleplay.scenario,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(scenario = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("深夜的便利店，外面的雨一直没停……") },
                    minLines = 3,
                    maxLines = 8,
                )
            }
            formItem(
                headlineContent = { Text("我的名字") },
                supportingContent = { Text("角色称呼你时使用的名字") },
            ) {
                OutlinedTextField(
                    value = roleplay.userPersonaName,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(userPersonaName = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：阿澈") },
                    singleLine = true,
                )
            }
            formItem(
                headlineContent = { Text("我的设定") },
                supportingContent = { Text("你的身份与背景，角色会据此对待你") },
            ) {
                OutlinedTextField(
                    value = roleplay.userPersonaDescription,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(userPersonaDescription = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("身份、性格、和角色的关系……") },
                    minLines = 2,
                    maxLines = 6,
                )
            }
        }

        SectionHeader(
            title = "提示",
            description = "给模型的风格样本与临场提醒",
        )
        CardGroup {
            formItem(
                headlineContent = { Text("示例对话") },
                supportingContent = { Text("只作为风格样本进提示词，不会出现在消息列表里") },
            ) {
                OutlinedTextField(
                    value = roleplay.exampleDialogue,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(exampleDialogue = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "角色名: *把伞递过来* 外面雨很大。\n" +
                                "我的名字: 谢谢。"
                        )
                    },
                    minLines = 4,
                    maxLines = 14,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        fontFamily = JetbrainsMono,
                        lineHeight = 16.sp,
                    ),
                )
            }
            formItem(
                headlineContent = { Text("作者注释") },
                supportingContent = { Text("长对话里维持人设的短提醒，按深度插入") },
            ) {
                OutlinedTextField(
                    value = roleplay.authorsNote,
                    onValueChange = { text ->
                        onUpdate(roleplay.copy(authorsNote = text))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("保持冷淡语气，不要代替我说话。") },
                    minLines = 2,
                    maxLines = 6,
                )
            }
            formItem(
                headlineContent = { Text("作者注释深度") },
                supportingContent = { Text("从最新消息往前数的插入位置，最小 1") },
            ) {
                var depthInput by remember(roleplay.authorsNoteDepth) {
                    mutableStateOf(roleplay.authorsNoteDepth.toString())
                }
                OutlinedTextField(
                    value = depthInput,
                    onValueChange = { input ->
                        if (input.length <= 3 && input.all(Char::isDigit)) {
                            depthInput = input
                            input.toIntOrNull()
                                ?.takeIf { it >= 1 && it != roleplay.authorsNoteDepth }
                                ?.let { depth ->
                                    onUpdate(roleplay.copy(authorsNoteDepth = depth))
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        SectionHeader(
            title = "风格规则",
            description = "逐条写进提示词的硬约束，比如不要代替我说话",
            action = {
                SectionAddButton(
                    onClick = {
                        onUpdate(roleplay.copy(styleRules = roleplay.styleRules + ""))
                    }
                )
            },
        )
        if (roleplay.styleRules.isEmpty()) {
            CardGroup {
                item(
                    headlineContent = { Text("还没有风格规则") },
                    supportingContent = { Text("点右上角的加号添加一条") },
                )
            }
        } else {
            CardGroup {
                roleplay.styleRules.fastForEachIndexed { index, rule ->
                    formItem {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = rule,
                                onValueChange = { text ->
                                    onUpdate(
                                        roleplay.copy(
                                            styleRules = roleplay.styleRules.mapIndexed { i, old ->
                                                if (i == index) text else old
                                            }
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("一条风格约束") },
                                maxLines = 4,
                            )
                            IconButton(
                                onClick = {
                                    onUpdate(
                                        roleplay.copy(
                                            styleRules = roleplay.styleRules.filterIndexed { i, _ ->
                                                i != index
                                            }
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = "删除",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 从酒馆角色卡 JSON 导入：只填角色卡里有的段落，其余设置保持不变 */
@Composable
private fun RoleplayCardImport(onImported: (Assistant) -> Unit) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            try {
                val imported = readTavernAssistant(context = context, uri = uri, filesManager = filesManager)
                onImported(imported)
                toaster.show(
                    message = context.getString(R.string.text_area_import_success),
                    type = ToastType.Success,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                toaster.show(
                    message = e.message ?: context.getString(R.string.assistant_importer_import_failed),
                    type = ToastType.Error,
                )
            } finally {
                loading = false
            }
        }
    }

    OutlinedButton(
        onClick = { picker.launch(arrayOf("*/*")) },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (loading) "导入中…" else "导入角色卡（JSON / PNG）")
    }
}