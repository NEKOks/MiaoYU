# 喵语 — RikkaHub 3.3.0 二次开发记录

> 基线：RikkaHub **3.3.0**（Android / Kotlin / Jetpack Compose）
> 源码目录：`仓库根目录`
> 记录时间：2026-11-17

---

## 一、迁移与基础改造

从 3.2.0 迁移全部自定义功能到 3.3.0，并做去品牌化。

| 项目 | 处理方式 | 涉及文件 |
|---|---|---|
| 应用名 | 「喵语」，8 个语言文件同步 | `res/values*/strings.xml` 的 `app_name` |
| 图标 | 猫图标 | `drawable/ic_cat_{background,foreground,foreground_light,monochrome}.xml`、`drawable/ic_bg_{dark,pink,night}.xml`、`mipmap-anydpi-v26/ic_launcher*.xml` |
| 外观切换 | 设置 ▸ 界面 ▸ 外观（喵语/猫匣/绒/星语），运行时切换桌面名称和图标 | `utils/AppAppearance.kt`、`ui/pages/setting/SettingAppearancePage.kt`、`AndroidManifest.xml`（4 个 `activity-alias`）、`RikkaHubApp.kt` |
| 默认助手 | 精简为 1 个 | `data/datastore/DefaultSettings.kt` |
| 微信解禁 | 从 Phone Use 黑名单删除 `com.tencent.mm` | `data/ai/tools/local/PhoneUseTool.kt` |
| 抽屉清理 | 去掉「媒体创作」「AI 翻译」入口与底部 ✨ 生图菜单 | `ui/pages/chat/ChatDrawer.kt` |
| 开发模式角标 | 去掉顶部 `[开发模式]` | `RouteActivity.kt` |
| 构建适配 | 中文路径、国内镜像、pnpm 缺失跳过 | `gradle.properties`（`android.overridePathCheck=true`）、`gradle/wrapper/gradle-wrapper.properties`（华为云镜像）、`web/build.gradle.kts`、`.gitignore` |

---

## 二、角色扮演

| 文件 | 内容 |
|---|---|
| `data/model/RoleplayConfig.kt` | 新增。字段：`enabled`、`characterName`、`description`、`personality`、`scenario`、`userPersonaName`、`userPersonaDescription`、`exampleDialogue`、`authorsNote`、`authorsNoteDepth`、`styleRules`、`narrationStyle`；`isActive` 判定 |
| `data/model/Assistant.kt` | 新增 `roleplay: RoleplayConfig` 字段 |
| `data/ai/transformers/RoleplayTransformer.kt` | 新增。角色卡注入 `AFTER_SYSTEM_PROMPT`、作者注释注入 `AT_DEPTH`；排在 `PromptInjectionTransformer` **之前**（prompt cache 稳定性） |
| `data/ai/ChatRequestFactory.kt` | `inputTransformers` 接入 `RoleplayTransformer` |
| `ui/components/message/ChatMessageRoleplayText.kt` | 新增。对白/旁白/动作/心理分段解析与渲染；两种旁白样式（`*动作*` 与 `（）`） |
| `ui/components/message/ChatMessage.kt` | 文本分支按 `assistant.roleplay.narrationStyle` 走新渲染 |
| `ui/pages/assistant/detail/AssistantRoleplayPage.kt` | 新增。助手设置里的「角色扮演」页 |
| `ui/pages/assistant/detail/AssistantImporter.kt` | SillyTavern `chara_card_v2` / `v3` 导入直接填 `RoleplayConfig`（不再拼英文 prompt） |
| `RouteActivity.kt` | `Screen.AssistantRoleplay(id)` + 入口 |
| 测试 | `app/src/test/.../RoleplayTransformerTest.kt`（14 例）、`RoleplaySegmentsTest.kt`（12 例） |

---

## 三、语音

### 3.1 语音模式（回合制）

| 文件 | 内容 |
|---|---|
| `ui/pages/chat/VoiceMode.kt` | 打字说「语音聊天」自动进入；`VOICE_START_PHRASES` |
| `ui/pages/chat/VoiceSessionController.kt` | 回合循环；25 秒无人说话 → 主动找话题；退出短语「下次再聊」「我们打字聊天吧」 |

### 3.2 本地断句（让 DeepSeek / MiMo / Step 也能进语音模式）

| 文件 | 内容 |
|---|---|
| `speech/.../asr/providers/LocalVadAsrController.kt` | **新增**。包装整段上传式 ASR：监听音量，静音 `silenceMs` 后 `stop()` 上传该段，合成 `speechEnded + finalText` 的语音回合 |
| `speech/.../asr/ASRProviderSetting.kt` | 新增 `supportsVoiceMode` = 服务端断句三家（OpenAI Realtime / DashScope / Volcengine）+ DeepSeek / MiMo / Step |
| `ui/pages/chat/VoiceMode.kt` | 三家接上包装器；`ChatPage.kt` 语音入口判定改用 `supportsVoiceMode` |

可调参数（构造参数）：`silenceMs = 900`、`amplitudeThreshold = 0.18`、`minSpeechMs = 300`。

### 3.3 后台语音

| 文件 | 内容 |
|---|---|
| `service/VoiceListeningForegroundService.kt` | **新增**。`foregroundServiceType="microphone"` 的前台服务，提供 Android 14+ 后台录音合法性 |
| `AndroidManifest.xml` | 新增 `FOREGROUND_SERVICE_MICROPHONE` 权限 + 服务声明 |
| `ui/pages/chat/VoiceMode.kt` | 去掉 `ON_STOP → voice.stop()`，改为仅在页面销毁时收尾；启动/结束语音时开关前台服务 |

### 3.4 语音时浮窗不自动放大

| 文件 | 内容 |
|---|---|
| `service/VoiceActivity.kt` | **新增**。全局语音活动标记 |
| `ui/pages/chat/VoiceSessionController.kt` | 开始/结束语音时更新标记 |
| `service/ChatFloatingTracker.kt` | 自动展开条件加 `!VoiceActivity.isActive`（手动点开不受影响） |

### 3.5 TTS 只念台词

| 文件 | 内容 |
|---|---|
| `utils/StringUtils.kt` | 新增 `removeAsteriskContent()`（剥掉 `*动作*`） |
| `ui/pages/chat/VoiceMode.kt`、`ui/pages/chat/TTSAutoPlay.kt` | 角色扮演激活时强制只念引号内台词，其次退化为「去掉 `*动作*` 和 `（心理）`」，都不剩则不出声 |

### 3.6 系统 TTS 发音人选择

| 文件 | 内容 |
|---|---|
| `speech/.../tts/provider/TTSProviderSetting.kt` | `SystemTTS` 加 `voiceName: String = ""` |
| `speech/.../tts/provider/providers/SystemTTSProvider.kt` | 合成前按 `voiceName` 找 `TextToSpeech.Voice` 并 `setVoice()` |
| `ui/pages/setting/components/SystemTtsVoices.kt` | **新增**。读取系统引擎的中文发音人列表 |
| `ui/pages/setting/components/TTSProviderConfigure.kt` | System TTS 配置页加「发音人」下拉 |

### 3.7 麦克风健壮性

`speech/.../asr/providers/DashScopeASRController.kt`：`VOICE_COMMUNICATION` 失败回退 `MIC`、录音状态校验、读失败重试。

---

## 四、群聊

### 4.1 数据模型

| 文件 | 内容 |
|---|---|
| `data/model/ConversationConfig.kt` | 新增 `groupMemberIds`、`groupTurnsPerMember`(1–5)、`groupAutoRounds`(0–6)；`withAssistantUpdate` 保留这三个字段（否则改温度会退化成单聊） |
| `data/model/GroupChat.kt` | **新增**。`isGroup`、`groupMemberIds`、`groupTurnsPerMember`、`groupAutoRounds`、`getGroupAssistants`、`getGroupTitle`、`shuffledForRound`、`memberOfMessage`、`groupTurnPrompt` |

### 4.2 生成侧

| 文件 | 内容 |
|---|---|
| `ai/.../agent/Agent.kt` | `AgentRequest.forceNewMessage: Boolean = false` |
| `ai/.../agent/AgentLoop.kt` | `init` 中 `if (last?.role == ASSISTANT && !request.forceNewMessage)` |
| `data/ai/transformers/GroupAttributionTransformer.kt` | **新增**。给其他成员的助手消息加 `【名字】` 前缀 + 追加群聊说明（含「你是谁」「只写自己发言」） |
| `data/ai/ChatRequestFactory.kt` | `createContext(..., assistantOverride)`（成员用自己的模型/角色卡/记忆）；`createAgent(..., forceNewMessage)`；`ChatTurnContext.groupMembers`；群聊时追加归属转换器 |
| `service/ChatService.kt` | `replyTargetOf(..., forceNewMessage)`；`runTurn(..., assistantOverride, forceNewMessage, finishTurn)`；`runGroupTurn`（随机顺序 + 每人 1–5 次 + 自动接话 ≤2 轮 + 失败即停）；`finishGroupTurn`；`groupMemberOf`；`createGroupConversation`；`sendQueuedMessage` / `regenerateAtMessage` / `handleToolApproval` 的群聊分支 |

### 4.3 界面

| 文件 | 内容 |
|---|---|
| `ui/pages/chat/GroupChatCreateDialog.kt` | **新增**。成员多选对话框（标「第 N 位」、≥2 人才能确定） |
| `ui/pages/chat/ChatDrawer.kt` | 「新建群聊」入口 |
| `ui/pages/chat/ChatDrawerVM.kt` | 转发 `createGroupConversation` |
| `ui/pages/chat/ChatPage.kt` | 群聊标题显示 `A / B` |
| `ui/pages/chat/ChatList.kt` | 助手气泡上方显示发言者名字 |
| `ui/pages/chat/ConversationList.kt` | 抽屉列表里群聊加小图标 |

### 4.4 行为说明

- 每轮成员按**随机顺序**发言，每人 `groupTurnsPerMember` 次
- 之后**自动接话**最多 `groupAutoRounds` 轮
- 任何一次失败 / 需要工具审批 / 被取消 → 本轮立即停（但已发过言就照样收尾，见第九节）
- 每个成员使用**真实助手**（`settings.getAssistantById`），模型、角色卡、记忆、工具都各用各的
- **群主是用户**：会话只挂在用户当前助手下面（`assistantId` 非空是存储要求），那个助手不参与发言，
  默认模型用用户的全局模型，不偏向任何成员
- 记忆：成员读自己的记忆库（与私聊共用），整理时也**各写各的库**，谁说的进谁的（见第十节）

---

## 五、构建

```powershell
$env:JAVA_HOME = "<你的 JDK 21>"
Set-Location <仓库根目录>
./gradlew assembleGithubDebug --no-configuration-cache
```

产物目录：`app/build/outputs/apk/github/debug/`
- `app-github-arm64-v8a-debug.apk`（真机）
- `app-github-universal-debug.apk`
- `app-github-x86_64-debug.apk`（模拟器）

安装：

```powershell
adb install -r "仓库根目录\app\build\outputs\apk\github\debug\app-github-arm64-v8a-debug.apk"
```

---

## 六、已知限制

| # | 问题 | 影响 |
|---|---|---|
| 1 | 群聊中连续多条 assistant 消息 | Anthropic / Gemini 要求严格交替，可能 400；DeepSeek / OpenAI 兼容端正常 |
| 2 | ~~两个成员共用同一模型 → 显示「其他人」~~ | 已修：消息上记发言者 id，见第十节 |
| 3 | 群聊中途自动压缩 | 用当轮发言成员的模型（不是群主，早期文档写错了） |
| 4 | 群聊「每人次数 / 接话轮数」 | 建群时写死（1 次 / 2 轮），需加群设置页才能改 |
| 5 | 群聊中某成员触发工具审批 | 本轮剩余成员不再发言，审批后只续写该成员 |
| 6 | 本地 VAD 的延迟 | 比 DashScope 多约 1 秒（等静音 + 上传 + 识别） |
| 7 | 系统 TTS 音色 | 取决于已安装的系统语音引擎（讯飞/小米等） |
| 8 | ~~群聊记忆整理只写群主~~ | 已修：按记忆库分别记进度，各成员各写各的，见第十节 |
| 9 | 群聊的记忆整理不参与"重启补整理" | 待整理队列只在内存里，进程被杀后要等下一轮对话才重新排队（单聊不受影响） |
| 10 | 群聊里别人的发言只留摘要 | 整理 A 的记忆时，B/C 的发言加「【名字】」标记并截到 120 字，只当背景；既不详细记录，也不会被算成 A 说过的 |

## 七、未做（明确取消）

- 抽屉会话列表按助手分组（可折叠）
- 浮窗内的语音入口（语音状态/按钮）
- 手机端本地 TTS 模型集成（sherpa-onnx）

---

## 八、踩过的坑（留给以后）

1. **路径含中文** → `android.overridePathCheck=true`
2. **PowerShell 的 `Get-Content` 默认按 GBK 读 UTF-8**，会把中文注释读乱并吞掉 `\r` 导致行合并。此项目所有脚本化编辑必须用：
   ```powershell
   $c = [System.IO.File]::ReadAllText($p)
   [System.IO.File]::WriteAllText($p, $c, (New-Object System.Text.UTF8Encoding($false)))
   ```
   曾因此损坏 `ai/.../AgentLoop.kt` 与 `Agent.kt`，最终从 `rikkahub-master.zip` 恢复。
3. **`bindConfig()` 会在会话首次落库时用助手快照生成 config**（不含群字段），所以建群必须一次写全 `groupMemberIds` 并立即 `flush()`。
4. **语音控制器对状态的容忍度很窄**：`VoiceSessionController` 只接受 `Listening` 或「已完整」，包装器的初始状态必须是 `Connecting`，收尾期间不能透传底层的 `Stopping/Idle`。
5. **`replyTargetOf()` 会续写上一条助手消息**（单聊优化），群聊里必须靠 `forceNewMessage` + 末尾补一条 user 提示来强制新开消息。

---

## 九、修静默出错（2026-11-17 下午）

这一轮不动功能，只修「出错时用户看不出来」的地方。

| # | 问题 | 处理 |
|---|---|---|
| 1 | 群成员被删到只剩一个时，界面还顶着群聊的样子、实际已是单聊 | 新增 `Settings.isGroupChat()`（按**有效**成员判断）；`ChatService` / `ChatPage` / `ChatList` 全部改用它，`Conversation.isGroup` 只留给抽屉图标 |
| 2 | 群聊中途失败或要工具审批时 `finishGroupTurn` 不执行，标题、聊天建议、记忆整理全丢 | `runGroupTurn` 改成 `try { … } finally { if (spoke) finishGroupTurn(…) }`，只要有人发过言就收尾一次 |
| 3 | 语音前台服务用 `startService` 停止，后台会被系统拒绝且异常被 `runCatching` 吞掉，通知和麦克风指示残留 | 改用 `context.stopService()`；通知栏加「结束语音」按钮，服务发 `ACTION_STOP_REQUEST` 广播，`VoiceMode` 收到后走和页面销毁一样的收尾 |
| 4 | 语音通知复用聊天直播通道，用户静音语音提示会连带关掉聊天通知 | 新增 `VOICE_LISTENING_NOTIFICATION_CHANNEL_ID`，低优先级、不振动、不显示角标 |
| 5 | 本地 VAD 在持续底噪（风扇/马路）下静音永不成立，录音无限累积、语音模式静默卡死 | 加 `maxUtteranceMs = 30s` 强制断句；阈值改为底噪自适应（`noiseFloor` 只跟更低的声音走，按每采样 0.002 回升，约 2 秒追上环境噪声） |
| 6 | 本地 VAD 等底层上传无超时，网络挂住则语音循环永久停住 | `withTimeoutOrNull(uploadTimeoutMs = 12s)`，超时就按当前文本收尾，让循环继续 |

新增字符串：`notification_channel_voice_listening`、`voice_listening_notification_text`、`voice_listening_notification_stop`（`values/` 与 `values-zh/` 各一份）。

`VoiceMode.createVoiceAsr` 的 `when` 已覆盖全部 ASR 实现，删掉了多余的 `else`（编译告警）。

---

## 十、群主是用户 + 每个成员各写各的记忆（2026-11-17）

### 10.1 群主改为用户

原来建群时把**第一个成员**当群主：会话 `assistantId` 和默认模型都取它，记忆也整理进它的库。
群主应该是用户。现在：

| 项 | 之前 | 现在 |
|---|---|---|
| 会话挂靠 | 第一个成员 | 用户当前助手（`getCurrentAssistant`），不参与发言 |
| 默认模型 | 第一个成员的模型 | 用户全局默认模型 `settings.chatModelId` |
| 预设消息 | 第一个成员的 | 不注入 |
| 记忆整理 | 第一个成员的库 | 每个成员各写各的库 |
| 抽屉分组 / 用量归属 | 第一个成员 | 用户当前助手 |

`Conversation.assistantId` 是非空列，上游模型里"会话属于某个助手"，没有"属于用户"这个选项，
所以只能挂一个不参与发言的宿主；宿主也被选进成员时照常发言。

### 10.2 记忆按记忆库分开整理

| 改动 | 文件 | 说明 |
|---|---|---|
| 消息上记发言者 | `UIMessage.speakerAssistantId`、`AgentRequest.speakerAssistantId`、`AgentLoop` | 群聊生成时写入；`memberOfMessage()` 优先按 id 认人，两人共用同一模型也不会显示「其他人」 |
| 进度按记忆库隔离 | `MemoryConsolidationEntity` / `MemoryConsolidationDAO` / `Migration_34_35` | 主键 `(conversation_id, memory_id)`；旧进度按会话所属助手补 memory_id，开了全局记忆的旧进度会失效、多整理一次。DB 34 → 35 |
| 按成员展开整理 | `MemoryConsolidator.memoryTargetsOf` | `pendingOf()` 返回一组待整理项，共用全局记忆库的合并成一份 |
| 别人的发言当背景 | `MemoryConsolidator.memoryMessagesOf` | 加「【名字】」标明出处并截到 120 字，只作背景；整理 prompt 再声明一次「既不是用户说的，也不是你说的」 |
| 允许处理群聊 | `consolidateStore` 过滤、`conversation_read` 工具 | 成员在 `groupMemberIds` 里即算属于这个记忆库 |
| 逐库排队 | `MemoryConsolidationScheduler.onTurnFinished` | 每个记忆库各自计时/立刻整理 |

代价：一条群聊消息最多触发 N 次记忆整理（N = 开了记忆的成员数）。
---

## 十一、抽屉把群聊单独分栏（2026-11-17）

### 11.1 界面

抽屉的文件夹条从 `[聊天] [文件夹…] [新建]` 变成 `[聊天] [群聊] [文件夹…] [新建]`：

- 选「聊天」= 只看普通私聊（群聊不再混在里面）
- 选「群聊」= 只看群聊（不看文件夹，群聊本来就不归文件夹）
- 新建群聊成功后自动切到「群聊」栏，能马上看到新会话

实现：`ChatDrawerVM` 新增 `showGroupChats` 状态，和文件夹筛选互斥（选任一边会清掉另一边）。

### 11.2 顺带修掉的一个隐藏问题

抽屉列表用的是轻量投影 `LightConversationEntity`，查询里**根本没读 config**，
所以列表项上 `conversation.isGroup` 恒为 false——群聊在抽屉里一直没有群图标，
也没法按群聊筛选。现在：

| 改动 | 文件 | 说明 |
|---|---|---|
| SQL 里算群聊标记 | `ConversationDAO` 的 5 个轻量查询 | `CASE WHEN config LIKE '%\"groupMemberIds\":[\"%' THEN 1 ELSE 0 END AS isGroup` |
| 投影带标记 | `LightConversationEntity.isGroup` | 由 `conversationSummaryToConversation` 写进 `Conversation.summaryIsGroup`（`@Transient`，不参与序列化） |
| `isGroup` 认出列表项 | `GroupChat.kt` | `summaryIsGroup || config 里有 ≥2 个成员` |
| 过滤放在 SQL | `GROUP_FILTER_*` + `getUnfiledConversationsOfAssistantPaging(groupFilter)` | 不用 `PagingData.filter`（整页被滤空时不会自动加载下一页，会显示空白） |

注意：判定依赖 config JSON 里非空的 `groupMemberIds`（`encodeDefaults = true`，单聊是 `[]`）。
以后如果改了 ConversationConfig 的序列化方式，这处 LIKE 要跟着改。
---

## 十二、新增助手不再带导入角色卡（2026-11-17）

`ui/pages/assistant/AssistantPage.kt` 的「新增助手」底部弹窗去掉两个「导入酒馆角色卡 (PNG / JSON)」按钮，
只留名称输入 + 取消/保存。

`AssistantImporter` 组件本身**保留未删**（`ui/pages/assistant/detail/AssistantImporter.kt`），
只是目前没有入口了——想放回助手详情页的话，加一行调用即可。
---

## 十三、设置页去掉「关于 / 使用文档 / 请求日志 / 赞助 / 分享」（2026-11-17）

整块 `item("aboutSettings")` 连同卡片标题一起删掉，相关实现也一并清理：

| 删掉的东西 | 文件 |
|---|---|
| 「关于」页 | `ui/pages/setting/SettingAboutPage.kt`（222 行） |
| 「赞助」页 | `ui/pages/setting/SettingDonatePage.kt`（169 行） |
| 「请求日志」页 | `ui/pages/log/LogPage.kt`（438 行，目录已删） |
| 请求日志采集 | `data/ai/RequestLoggingInterceptor.kt` + `DataSourceModule` 里的注册（OkHttp 少一个网络拦截器） |
| 请求日志 API | `common/.../Logging.kt` 的 `LogEntry.RequestLog`、`logRequest`、`getRequestLogs`、开关读写、`getTextLogs` |
| 群号弹窗 | `SettingPage` 里的 `QQGroup`/`QQGroupBottomSheet`/`QQ_GROUPS` |
| QQ / Discord 图标 | `ui/src/main/java/me/rerere/ui/icons/{TencentQQIcon,DiscordIcon}.kt` |
| 赞助弹窗 | 启动次数触发的那段 `AlertDialog`（`sponsorAlert*`） |

路由同步清理：`Screen.SettingAbout` / `Screen.SettingDonate` / `Screen.Log` 三个 entry 与定义。

故意留着的：
- 字符串资源（`setting_page_about*`、`documentation*`、`request_logs*`、`donate*`、`share*`、`sponsor_alert*`）——已经没人引用了，删它们要动 8 个语言文件，收益低
- `Settings.sponsorAlertDismissedAt` 和它的 preference key——已持久化的字段，删了要连带改 `SettingsFields` 与迁移测试，留着无害
---

## 十四、提示词页去掉「可用变量」（2026-11-17）

`ui/pages/assistant/detail/AssistantPromptPage.kt` 的「系统提示词」下面那块「可用变量」标签列表删掉，
只留输入框。`xx.insertAtCursor` / `Tag` / `DefaultPlaceholderProvider` 三个 import 一并清掉。

占位符**仍然生效**——`PlaceholderTransformer` 照常替换 `{{cur_date}}` 这类变量，只是界面不再提供点击插入的提示条。

---

## 十五、群聊头像 + 群聊在成员列表里可见（2026-11-17）

### 15.1 头像跟着发言人走

`ChatList` 给 `ChatMessage` 传的一直是**会话所属助手**（群聊的宿主），
所以群里每条发言都显示群主的头像和名字。现在按消息上的 `speakerAssistantId` 找出发言成员传进去，
名字和头像都跟人走；认不出是谁（老消息没有标记、成员被删）时才显示「其他人」。

### 15.2 群聊对每个成员都可见

`getUnfiledConversationsOfAssistantPaging` 原来只按 `assistant_id = 当前助手` 过滤，
群聊只挂在宿主下面，其他成员在自己的抽屉里看不到它。

现在条件放宽成「助手自己的会话 **或** 自己在这个群的 `groupMemberIds` 里」：

`sql
assistant_id = :assistantId
OR (config LIKE '%"groupMemberIds":["%' AND config LIKE '%"' || :assistantId || '"%')
`

因为是同一条会话，从任何一个成员的入口点进去都是**同一份聊天记录**。
私聊视图（`groupFilter = 2`）仍然只列非群聊会话，不受影响。
---

## 十六、角色扮演页支持导入角色卡（2026-11-17）

`AssistantRoleplayPage` 顶部加了一个「导入角色卡 (JSON)」按钮，选 `.json` 后把酒馆卡
（`chara_card_v2` / `v3`）的字段直接填进现有段落：

| 卡片字段 | 页面段落 |
|---|---|
| `name` | 角色名 |
| `description` | 角色描述 |
| `personality` | 性格 |
| `scenario` | 场景 |
| `mes_example` | 示例对话 |
| `post_history_instructions` | 作者注释 |

- 导入即把「启用角色扮演」打开，并且**同时改助手名**（`name` 既填角色名，也改成助手名）
- 角色卡里没有的段落（用户身份、风格规则、旁白样式）保持原样，不被清空
- 卡里的 `system_prompt` 不进这里——那属于「提示词」页
- 解析复用 `AssistantImporter` 的那套：顺手把 `readTavernAssistant` 抽成 internal，
  新增助手弹窗和这里共用一份解析，避免两边行为不一致
样板文件：`docs/sample-character-card.json`（苏晚 / 深夜茶室，v2 格式，可直接导入验证）。
### 16.1 修：json 角色卡在选择器里置灰选不中

原因是 `OpenDocument` 用 `application/json` 过滤，而很多系统/文件提供方把 `.json`
报成 `text/plain` 或 `application/octet-stream`，于是整类文件被置灰。

- 选择器改成 `*/*`，不再依赖系统上报的 MIME
- `readTavernAssistant` 只有 `image/png` 走图片解析，其余一律当文本读再按 JSON 解析
- JSON 解析失败时给中文提示（新增 `assistant_importer_invalid_json`）
- 按钮文案改为「导入角色卡（JSON / PNG）」——PNG 卡现在也能直接选
---

## 十七、切换助手后进入最近的会话（2026-11-17）

左侧栏切换助手原来按 `create_new_conversation_on_start` 偏好走：开着就新开一条空会话。
现在改成**直接进入这个助手最近聊过的会话**（按 `updateAt` 取最新），只有它还没有任何会话时才新建。

- 改的是 `ChatDrawer` 的 `onSelectAssistant`
- 设置里那个「启动时新建对话」仍然只管**应用启动**（`RouteActivity`），文案本来就是这么写的，不受影响
---

## 十八、去品牌化收尾 + 清理无用资源（2026-11-17）

### 18.1 可见文案里的 RikkaHub → MiaoYu / 喵语

| 位置 | 改动 |
|---|---|
| `setting_page_web_server_desc`（8 语言） | RikkaHub → MiaoYu / 喵语 |
| `assistant_page_local_tools_phone_use_enable_service`（8 语言） | 同上 |
| `setting_asr_configure_step_timestamp_desc`（8 语言） | 句中的 `rikkahub` → MiaoYu / 喵语 |
| 无障碍服务标签（`app/src/github/res`） | `RikkaHub Phone Use` → `MiaoYu Phone Use` |
| 默认 User-Agent（两处） | `RikkaHub-Android/` → `MiaoYu-Android/` |
| 导出的图片文件名（三处） | `RikkaHub_<ts>.png` → `MiaoYu_<ts>.png` |
| MCP OAuth 客户端名 | `RikkaHub` → `MiaoYu` |
| Phone Use 工具提示 | 同上 |
| mDNS 服务名 | `RikkaHub Web Server` → `MiaoYu Web Server` |
| OpenRouter 类服务的 `X-Title` 头 | `RikkaHub` → `MiaoYu` |
| 搜索服务显示名与错误文案 | `RikkaHub` → `MiaoYu` |
| 生成的 rootfs 注释 | 同上 |

### 18.2 删掉的资源

之前删页面留下的 16 个字符串，8 个语言文件各删一遍（共 128 行）：
`setting_page_{about,about_desc,documentation,documentation_desc,request_logs,request_logs_desc,donate,donate_desc,share,share_text,share_desc,no_share_app,sponsor_alert_title,sponsor_alert_desc,sponsor_alert_confirm,sponsor_alert_dismiss}`。

其余 drawable 逐个查过引用，都在用（`ic_stat_rikkahub` 是通知栏小图标，7 处引用），没有可删的。

### 18.3 刻意保留的

- 包名 `me.rerere.rikkahub`、类名 `RikkaHubApp` / `RikkaHubAPI` / `RikkaHubOptions`：改名会波及几百处引用与数据兼容，收益只是内部整洁
- 资源 key `rikkahub_provider_description`：只是 key，文案本身没有品牌字样
- `UpdateChecker` 的 User-Agent：那是给 RikkaHub 更新接口用的，改动没有意义还可能影响请求
---

## 十九、群聊发言不再重复报名字（2026-11-17）

现象：气泡上已经有「香子兰」的名字和头像，正文开头又出现一个「【香子兰】」。

原因：归属前缀只加给**别人**的发言，但模型看到历史里全是「【名字】xxx」的格式，跟着模仿，
把自己的名字也写进了正文。

两处处理：

| 位置 | 改动 |
|---|---|
| 显示 | `MessageNode.withoutSpeakerPrefix(name)`：只剥**开头**且正好是**自己名字**的「【…】」，正文中间的不动；`ChatList` 渲染时用剥离后的节点 |
| 提示词 | `buildGroupPrompt` 增加一句：自己的发言不要以「【名字】」开头，界面会显示你是谁 |

记忆不受影响：`memoryMessagesOf` 给**别人**的发言加标记，自己的发言本来就不需要前缀；
存下来的原文如果带了，也只是整理时多一个前缀，不影响判断。

已知小尾巴：气泡的「复制」拿的是原文（带前缀）；非角色扮演模式下 TTS 朗读也可能念到名字。