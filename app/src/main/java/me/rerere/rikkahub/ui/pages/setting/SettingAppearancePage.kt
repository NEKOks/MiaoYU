package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.AppAppearance
import me.rerere.rikkahub.utils.AppAppearanceStore
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingAppearancePage() {
    val context = LocalContext.current
    // 选中项存在 SharedPreferences 里，切换时同步覆盖本地状态即可
    var selected by remember { mutableStateOf(AppAppearanceStore.selected(context)) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("外观")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    title = { Text("应用名称与桌面图标") },
                ) {
                    AppAppearance.entries.forEach { option ->
                        item(
                            onClick = {
                                AppAppearanceStore.select(context, option)
                                selected = option
                            },
                            leadingContent = {
                                // 自适应图标（adaptive-icon）不能直接喂给 painterResource，
                                // 这里用「底色 + 猫矢量图」自己拼一个缩略图，效果和桌面图标一致
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(option.previewBackground()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(option.previewCat()),
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            },
                            headlineContent = { Text(option.displayName) },
                            trailingContent = if (selected == option) {
                                {
                                    Icon(
                                        HugeIcons.Tick01,
                                        contentDescription = null
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }

            item {
                Text(
                    text = "切换后桌面图标会立刻变化。部分桌面不会自动刷新，把图标从桌面移除再重新拖出来即可。" +
                        "名称和图标只能从这几套里选，Android 不允许运行时任意修改启动器图标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

/** 缩略图底色，和 mipmap-anydpi-v26 里那几套自适应图标的 background 保持一致 */
private fun AppAppearance.previewBackground(): Color = when (this) {
    AppAppearance.MIAO -> Color(0xFFFFF0EC)
    AppAppearance.CAT_BOX -> Color(0xFF2B2B33)
    AppAppearance.FLUFF -> Color(0xFFF7D9D9)
    AppAppearance.STAR -> Color(0xFF1E2438)
}

/** 深色底用浅猫，浅色底用深猫 */
private fun AppAppearance.previewCat(): Int = when (this) {
    AppAppearance.MIAO, AppAppearance.FLUFF -> R.drawable.ic_cat_foreground
    AppAppearance.CAT_BOX, AppAppearance.STAR -> R.drawable.ic_cat_foreground_light
}
