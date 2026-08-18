package tech.illusion.spacewhiteboard

import androidx.compose.runtime.remember
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import tech.illusion.spacewhiteboard.board.BoardDoc
import tech.illusion.spacewhiteboard.content.WhiteboardColorScheme
import tech.illusion.spacewhiteboard.content.editor.BoardEditor
import tech.illusion.spacewhiteboard.content.editor.EditorState

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme(colorScheme = WhiteboardColorScheme) {
                // Task 17 会把 initialDoc 换成从 BoardStore 载入的结果。
                val state = remember { EditorState(BoardDoc(), initialNextId = 1L) }
                BoardEditor(state)
            }
        }
    }
