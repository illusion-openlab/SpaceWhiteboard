package tech.illusion.spacewhiteboard.content.editor

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import tech.illusion.spacewhiteboard.board.*

private const val TAG = "SpaceWhiteboard"

/** 采样距离过滤，board units。手停住时驱动层仍持续下发事件，不过滤会塞进几千个重复点。
 *  初值 2.0 是保守起步值，Task 19 用真机实测数据校准。 */
private const val MIN_STEP = 2.0f

@Composable
fun CanvasSurface(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val pathCache = remember { mutableMapOf<ItemId, Path>() }

    Canvas(
        modifier = modifier
            .background(Color(0xFFFFFFFF))   // 纸面色，Task 15 换成主题里的 fillTertiary
            .pointerInput(state.tool, state.colorArgb, state.widthDp) {
                // 累加游标，屏幕像素空间。
                // 只在 onDragStart 读一次绝对 offset，之后全靠累加 dragAmount ——
                // PICO 的注视+捏合交互里，拖拽中重读绝对坐标反映的是几乎不动的注视点，不是手的位移。
                var cursorPx = Offset.Zero

                detectSpatialDragGesture(
                    context = context,
                    onDragStart = { offset, _ ->
                        if (state.tool != Tool.Pen) return@detectSpatialDragGesture
                        // offset 是 com.pico.spatial.ui.geometry.Offset3D（带 z），
                        // 这里只取 x/y 落回 2D 屏幕像素坐标。
                        cursorPx = Offset(offset.x, offset.y)
                        state.livePoints = listOf(state.viewport.toBoard(offset.x / density, offset.y / density))
                    },
                    onDragEnd = { _ ->
                        if (state.tool != Tool.Pen) return@detectSpatialDragGesture
                        val pts = state.livePoints
                        state.livePoints = emptyList()
                        if (pts.isNotEmpty()) {
                            state.commit(AddItem(Stroke(state.newId(), state.colorArgb, state.widthDp, pts)))
                        }
                    },
                    onDragCancel = { _ -> state.livePoints = emptyList() },
                ) { value ->
                    if (state.tool != Tool.Pen || state.livePoints.isEmpty()) return@detectSpatialDragGesture
                    cursorPx = Offset(cursorPx.x + value.dragAmount.x, cursorPx.y + value.dragAmount.y)
                    val p = state.viewport.toBoard(cursorPx.x / density, cursorPx.y / density)
                    if (p.distanceTo(state.livePoints.last()) > MIN_STEP) {
                        state.livePoints = state.livePoints + p
                    }
                }
            },
    ) {
        drawBoard(state.doc, state.viewport, density, pathCache)
        drawLiveStroke(state.livePoints, state.colorArgb, state.widthDp, state.viewport, density)
    }
}
