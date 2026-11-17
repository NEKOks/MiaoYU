package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rerere.ui.toast.ToastType
import me.rerere.ui.toast.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.RoleplayConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): Assistant
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): Assistant =
        parseTavernCard(context = context, json = json, background = background)
}


private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): Assistant =
        parseTavernCard(context = context, json = json, background = background)
}

/**
 * chara_card_v2 / v3 的字段一致，解析只留一份。
 *
 * 以前这里把 name / description / personality / scenario 拼成一段英文 system prompt，
 * 结果是中英混读、示例对话整段丢弃、长对话没有维持人设的锚点。
 * 现在这些字段原样搬进 RoleplayConfig，提示词由 RoleplayTransformer 统一生成；
 * 卡里自带的 system_prompt 仍然作为真正的系统提示保留。
 */
private fun parseTavernCard(
    context: Context,
    json: JsonObject,
    background: String?,
): Assistant {
    val data = json["data"]?.jsonObject
        ?: error(context.getString(R.string.assistant_importer_missing_data_field))
    val name = data.cardText("name")
        ?: error(context.getString(R.string.assistant_importer_missing_name_field))
    val firstMessage = data.cardText("first_mes")

    return Assistant(
        name = name,
        presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
        systemPrompt = data.cardText("system_prompt").orEmpty(),
        background = background,
        roleplay = RoleplayConfig(
            // 导入即用：填了角色卡就默认打开，用户可以在助手的「角色扮演」页里关掉
            enabled = true,
            characterName = name,
            description = data.cardText("description").orEmpty(),
            personality = data.cardText("personality").orEmpty(),
            scenario = data.cardText("scenario").orEmpty(),
            exampleDialogue = data.cardText("mes_example").orEmpty(),
            authorsNote = data.cardText("post_history_instructions").orEmpty(),
        ),
    )
}

/** 角色卡里的文本字段；缺失或全空白都当作没填 */
private fun JsonObject.cardText(key: String): String? =
    this[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): Assistant {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

// endregion

/**
 * 读一个角色卡文件（PNG 内嵌 JSON 或纯 JSON 文本）并解析成助手，失败时抛出。
 *
 * 新增助手的弹窗和「角色扮演」页都用它，保证两边解析结果一致。
 *
 * 不按 MIME 严格挑格式：很多系统的文件提供方把 .json 报成 text/plain 或
 * application/octet-stream，卡得太死会让文件在选择器里直接置灰、选不中。
 * 这里只有 PNG 走图片解析，其余一律当文本读再按 JSON 解析。
 */
internal suspend fun readTavernAssistant(
    context: Context,
    uri: Uri,
    filesManager: FilesManager,
): Assistant {
    val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
    val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
        if (mime == "image/png") {
            val result = ImageUtils.getTavernCharacterMeta(context, uri)
            result.map { base64Data ->
                val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                json to bg
            }.getOrElse { throw it }
        } else {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                .use { it?.readText() }
                ?: error(context.getString(R.string.assistant_importer_read_json_failed))
            json to null
        }
    }
    val json = try {
        Json.parseToJsonElement(jsonString).jsonObject
    } catch (e: Exception) {
        error(context.getString(R.string.assistant_importer_invalid_json))
    }
    return parseAssistantFromJson(context = context, json = json, background = backgroundStr)
}

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
) {
    try {
        onImport(readTavernAssistant(context = context, uri = uri, filesManager = filesManager))
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
