package tech.illusion.spacewhiteboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.illusion.spacewhiteboard.board.BoardDoc
import tech.illusion.spacewhiteboard.content.PAPER_ARGB
import tech.illusion.spacewhiteboard.content.WhiteboardColorScheme
import tech.illusion.spacewhiteboard.content.editor.AutoSave
import tech.illusion.spacewhiteboard.content.editor.BoardEditor
import tech.illusion.spacewhiteboard.content.editor.EditorState
import tech.illusion.spacewhiteboard.content.editor.SaveStatus
import tech.illusion.spacewhiteboard.export.BoardRenderer
import tech.illusion.spacewhiteboard.export.MediaStoreExporter
import tech.illusion.spacewhiteboard.store.BoardStore
import tech.illusion.spacewhiteboard.store.LoadResult
import java.io.File

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme(colorScheme = WhiteboardColorScheme) {
                val context = LocalContext.current
                // PicoTheme 已经在其 content 外层套了一层 SnackbarHost（design-6.0.0 源码
                // `PicoTheme.kt`：`TextSelectionAndToolbarProvider { SnackbarHost(content) }`），
                // 这里读到的就是那个宿主提供的状态，不需要再手动包一层 SnackbarHost。
                val snackbarHostState = LocalSnackbarHostState.current
                // colorScheme.error 是一次 Composable 读取，导出协程跑在合成之外，必须
                // 提前在这里取出普通的 Color 值，不能在 launch{} 里再读 PicoTheme.colorScheme。
                val exportFailureColor = PicoTheme.colorScheme.error
                // 导出要经历渲染 + PNG 压缩两步 IO/CPU 工作，绑定到组合作用域的协程作用域，
                // 这样即便触发导出后用户很快离开这个组合，协程仍能正常收尾/取消。
                val exportScope = rememberCoroutineScope()
                val boardFile = remember {
                    File(context.filesDir, "projects/default/boards/main.wbd")
                }
                val store = remember { BoardStore(boardFile) }

                // 启动时载入一次即可：LoadResult 决定了 EditorState 的初始文档与 nextId，
                // 之后的读写全部走 AutoSave（写）与内存中的 EditorState（读），不会再调用 load()。
                val loaded = remember { store.load() }
                val state = remember {
                    when (loaded) {
                        // BoardCodec.decode 按出现顺序把已恢复的条目分配 ItemId(1..n)（Task 11），
                        // 所以 n+1 一定是未被占用的新 id。这里不能写死 1L——写死会让新建条目
                        // 复用某个已恢复条目的 id，AddItem.revertFrom 按 id 删除时会连坐删错对象。
                        is LoadResult.Ok -> EditorState(loaded.doc, initialNextId = loaded.doc.items.size + 1L)
                        is LoadResult.Empty -> EditorState(BoardDoc(), initialNextId = 1L)
                        // 损坏时给一块新白板，但绝不假装什么都没发生——见下方常驻提示条。
                        is LoadResult.Corrupt -> EditorState(BoardDoc(), initialNextId = 1L)
                    }
                }

                // 初始态是 Idle（契约 §2.1「不显示」）：应用刚起来时既没存过也没失败过，
                // 用 Saved 顶替会撒谎——其实这份文档还没被写过一次盘。
                var saveStatus by remember { mutableStateOf(SaveStatus.Idle) }
                AutoSave(state = state, store = store) { saveStatus = it }

                // 存档部分损坏（skippedLines > 0）时的常驻提示文案。只在这里算一次而不是在
                // DegradedLoadNotice 内部算，是因为 Corrupt 与「Ok 但部分行被跳过」是两种不同的
                // LoadResult 分支，各自的文案输入不同（备份路径 vs 跳过行数），但共享同一条
                // 常驻横条视觉——见下方 DegradedLoadNotice 的说明。
                val partialLoadMessage = (loaded as? LoadResult.Ok)?.takeIf { it.skippedLines > 0 }
                    ?.let { ok ->
                        // 契约没有为这种情况定义文案（§4 只给了「存档整体损坏」一条），这里
                        // 遵循同一套写法：先说清楚发生了什么，再说清楚继续操作会有什么后果——
                        // 用户必须在动笔之前就知道这份文档已经不完整，而不是等保存之后才发现。
                        "上次的白板文件部分内容无法读取，已跳过 ${ok.skippedLines} 处无法解析的" +
                            "记录，继续绘制并保存将只保留已成功载入的内容。"
                    }

                Box(Modifier.fillMaxSize()) {
                    BoardEditor(
                        state = state,
                        saveStatus = saveStatus,
                        onExport = {
                            exportScope.launch {
                                // 渲染（Canvas 绘制）与 PNG 压缩都挪到 IO 线程：一张 2x 的
                                // 大图，这两步合起来足以让主线程明显卡顿一下。
                                val bitmap = withContext(Dispatchers.IO) {
                                    BoardRenderer.render(state.doc, context = context, paperArgb = PAPER_ARGB)
                                }
                                if (bitmap == null) {
                                    // 空文档：不是失败，是没什么可导出的，不用 error 语气。
                                    snackbarHostState.show(message = "白板是空的，没有可导出的内容")
                                    return@launch
                                }
                                // 毫秒时间戳单独用可能在两次快速点击间撞车（同一毫秒内两次导出
                                // 会覆盖同一个 MediaStore 条目）；再叠一段 nanoTime 的低位做去重。
                                val displayName = "whiteboard-${System.currentTimeMillis()}" +
                                    "-${System.nanoTime() % 1_000_000L}"
                                val uri = withContext(Dispatchers.IO) {
                                    MediaStoreExporter.exportPng(context, bitmap, displayName)
                                }
                                if (uri != null) {
                                    snackbarHostState.show(message = "已导出到相册")
                                } else {
                                    // 契约 §4 文案；用 error 角色着色区分于"成功"的普通提示。
                                    snackbarHostState.show(
                                        message = "导出失败，请重试",
                                        backgroundColor = exportFailureColor,
                                    )
                                }
                            }
                        },
                    )

                    // 存档损坏 / 部分损坏：用户有权知道自己的东西没了多少、以及备份在哪。
                    // 这条常驻提示条不是 Snackbar——不会自己消失，只要本次会话还在这个状态
                    // 就一直显示。两种触发条件互斥（Corrupt 与 Ok 是同一个 sealed interface
                    // 的不同分支），最多同时出现一条。
                    when (loaded) {
                        is LoadResult.Corrupt -> DegradedLoadNotice(corruptMessage(loaded, boardFile))
                        is LoadResult.Ok -> partialLoadMessage?.let { DegradedLoadNotice(it) }
                        is LoadResult.Empty -> Unit
                    }
                }
            }
        }
    }

/**
 * `LoadResult.Corrupt` 分支的文案（存档整体损坏，无法解码）。契约 §11 歧义 #4 与 §4 文案表
 * 给的是 Snackbar 文案（转瞬即逝、也不含备份路径），但 Task 17 明确要求"持久、非 toast、
 * 必须点名备份路径"——用户的全部作品可能已经丢失，静默给一块空白白板是最糟的处理方式。
 */
private fun corruptMessage(corrupt: LoadResult.Corrupt, originalFile: File): String =
    if (corrupt.backupPath != null) {
        "上次的白板文件无法读取，已另存备份，当前是一块新白板。备份路径：${corrupt.backupPath}"
    } else {
        // 连备份都没能写成功（比如磁盘已满）：原文件此刻还在原地，没被覆盖或删除——但仅仅是
        // "此刻"：AutoSave 的 ON_PAUSE 强制 flush 现在已经守卫了 state.revision == 0
        // （见 content/editor/AutoSave.kt），本次会话不碰一笔画就不会覆盖它，原文件才能真的
        // 保住，而不是"再撑一次 pause 就没了"。把原路径告诉用户比什么都不说要好。
        "上次的白板文件无法读取，备份也未能保存，当前是一块新白板。原文件路径：${originalFile.path}"
    }

/**
 * 存档退化（整体损坏 / `BoardCodec.decode` 部分行被跳过）时的常驻提示条，两种情况共用同一条
 * 横条视觉，只是文案不同（由调用方算好传入）。
 *
 * 没有关闭按钮：契约的元素清单里没有这个控件，加一个新的可交互元素超出本任务范围；
 * "常驻"在这里就是字面意思——贯穿本次会话，直到应用重启（下次若能正常载入就不会再出现）。
 */
@Composable
private fun DegradedLoadNotice(message: String) {
    val scheme = PicoTheme.colorScheme
    val typography = PicoTheme.typography
    // 没有用 Modifier.align(...)：这是个独立的顶层 @Composable，不在外层 BoxScope 的
    // lambda receiver 里，align 这个 BoxScope 扩展在这里不可用。直接 fillMaxWidth() 已经
    // 能让这条横条贴在外层 Box 的顶部、撑满宽度——外层 Box 的默认 contentAlignment 就是
    // TopStart，配合 fillMaxWidth 视觉上等价于"顶部通栏"，不需要再显式对齐。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.error)
                .border(1.dp, scheme.dividerLine, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                text = message,
                color = scheme.labelPrimary,
                style = typography.bodyMedium,
            )
        }
    }
}
