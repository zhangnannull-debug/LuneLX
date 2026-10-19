package com.lunelx.music.ui.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import com.lunelx.music.tools.SettingsManager
import com.lunelx.music.ui.screens.lx.LxScriptManagerScreen
import com.lunelx.music.ui.theme.LuneTheme

/**
 * 「设置 → 音源」的宿主 Activity。
 *
 * 音源脚本管理（导入 / 启用 / 删除 / 在线总开关）原来挂在主界面里，
 * 现在收进设置页，主界面不再有「在线音源」入口。
 */
class LxScriptManagerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsManager.getInstance(this)
        enableEdgeToEdge()
        setContent {
            val targetDarkTheme = when (settings.themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            LuneTheme(
                darkTheme = targetDarkTheme,
                useCustomColors = settings.useCustomColors,
                customColorPalette = settings.customColorPalette,
                useAmoledPitchBlack = settings.useAmoledPitchBlack,
            ) {
                LxScriptManagerScreen(onBack = { finish() })
            }
        }
    }
}
