package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BoardEditor(state: EditorState) {
    // Task 15 会在这里加上工具栏；现在先只有画布
    Box(modifier = Modifier.fillMaxSize()) {
        CanvasSurface(state, Modifier.fillMaxSize())
    }
}
