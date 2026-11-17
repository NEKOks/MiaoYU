package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import me.rerere.rikkahub.R

/**
 * 桌面上的应用外观：名字 + 图标。
 *
 * Android 不允许运行时改启动器的 label/icon，所以这里用 `activity-alias`：
 * manifest 里为每套外观声明一个别名（各自带 label 和 icon），切换时启用选中的那个、禁用其余。
 * [alias] 必须和 AndroidManifest.xml 里 `.alias.XXX` 的后缀一致。
 */
enum class AppAppearance(
    val alias: String,
    val displayName: String,
    @DrawableRes val iconRes: Int,
) {
    MIAO("Default", "喵语", R.mipmap.ic_launcher),
    CAT_BOX("CatBox", "猫匣", R.mipmap.ic_launcher_catbox),
    FLUFF("Fluff", "绒", R.mipmap.ic_launcher_fluff),
    STAR("Star", "星语", R.mipmap.ic_launcher_star),
}

object AppAppearanceStore {
    private const val PREFS_NAME = "app_appearance"
    private const val KEY_SELECTED = "selected"

    fun selected(context: Context): AppAppearance {
        val raw = prefs(context).getString(KEY_SELECTED, null) ?: return AppAppearance.MIAO
        return AppAppearance.entries.firstOrNull { it.name == raw } ?: AppAppearance.MIAO
    }

    /**
     * 立即切换桌面外观。
     *
     * 先启用新的再禁用旧的，避免中间出现"桌面上没有图标"的瞬间。
     * 用 [PackageManager.DONT_KILL_APP] 才不会把当前进程杀掉。
     */
    fun select(context: Context, option: AppAppearance) {
        prefs(context).edit().putString(KEY_SELECTED, option.name).apply()
        apply(context, option)
    }

    /** 启动时对一次账：用户清过数据、或者旧版本装过别的别名时，这里会纠正回来。 */
    fun applySelection(context: Context) {
        apply(context, selected(context))
    }

    private fun apply(context: Context, option: AppAppearance) {
        val packageManager = context.packageManager
        setEnabled(context, packageManager, option, true)
        AppAppearance.entries.filter { it != option }.forEach { other ->
            setEnabled(context, packageManager, other, false)
        }
    }

    private fun setEnabled(
        context: Context,
        packageManager: PackageManager,
        option: AppAppearance,
        enabled: Boolean,
    ) {
        val component = ComponentName(context, "${context.packageName}.alias.${option.alias}")
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
