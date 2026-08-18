package tech.illusion.spacewhiteboard

import android.util.Log
import androidx.compose.runtime.remember
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import tech.illusion.spacewhiteboard.board.BoardDoc
import tech.illusion.spacewhiteboard.content.WhiteboardColorScheme
import tech.illusion.spacewhiteboard.content.editor.BoardEditor
import tech.illusion.spacewhiteboard.content.editor.EditorState
import tech.illusion.spacewhiteboard.content.editor.SaveStatus

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme(colorScheme = WhiteboardColorScheme) {
                // Task 17 会把 initialDoc 换成从 BoardStore 载入的结果。
                val state = remember { EditorState(BoardDoc(), initialNextId = 1L) }
                BoardEditor(
                    state = state,
                    // Task 17 接自动保存时把这里换成 BoardStore 的真实写盘状态。
                    // 现在给的是常量，但它是真的传到了指示器上，不是画死的假像素。
                    saveStatus = SaveStatus.Saved,
                    // Task 18 接 MediaStore 导出；在那之前点了也要留痕，别静默无反应。
                    onExport = { Log.i("SpaceWhiteboard", "导出 PNG 尚未接线（Task 18）") },
                )
            }
        }
    }
