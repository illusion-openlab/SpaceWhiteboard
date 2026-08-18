package tech.illusion.spacewhiteboard.content

import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.design.ColorScheme
import com.pico.spatial.ui.design.defaultColorScheme

/**
 * 固定配色，不用 `PicoTheme` 的系统自适应默认方案。
 *
 * 依据：模板默认方案在真机上跑出来是"没有主题色的通用浅色 UI"（主按钮中性深灰、无强调色），
 * 工作区已复现两次（SpaceLianliankan、Overwinter）。固定值同时保证视觉标识不随房间环境光漂移。
 *
 * 取值来源：.spatialsdk/design-contract.md §8（颜色 token 表），全部 16 个角色均为确切值，
 * 不落回 SDK 默认值。
 */
val WhiteboardColorScheme: ColorScheme = defaultColorScheme(
    fillPrimary = Color(0xFF2F6BEA),
    fillSecondary = Color(0xFF101826),
    fillTertiary = Color(0xFFF6F3EC),
    fillLight = Color(0x26FFFFFF),
    labelPrimary = Color(0xFFEDF1F7),
    labelPrimaryLight = Color(0xFF16202E),
    labelSecondary = Color(0xB8EDF1F7),
    labelTertiary = Color(0x7AEDF1F7),
    labelQuaternary = Color(0x4DEDF1F7),
    lightenHover = Color(0x14FFFFFF),
    lightenPressed = Color(0x24FFFFFF),
    error = Color(0xFFE5484D),
    alert = Color(0xFFE9A23B),
    passable = Color(0xFF3DBE8B),
    interaction = Color(0xFF6699FF),
    dividerLine = Color(0x24FFFFFF),
)
