package tech.illusion.spacewhiteboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import tech.illusion.spacewhiteboard.content.WhiteboardColorScheme

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme(colorScheme = WhiteboardColorScheme) {
                // 占位，Task 12 换成 BoardEditor()
                // design-style: opaque-root — materialbackground=0，根节点自绘不透明底
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WhiteboardColorScheme.fillSecondary)
                )
            }
        }
    }
