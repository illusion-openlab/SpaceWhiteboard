package tech.illusion.spacewhiteboard.board

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 画布坐标系（board units）中的一个点。
 * 刻意不用 androidx.compose.ui.geometry.Offset —— board 包必须零 Compose 依赖才能跑 JVM 单测。
 */
data class Pt(val x: Float, val y: Float) {
    fun distanceTo(o: Pt): Float = hypot(x - o.x, y - o.y)
}

/** 归一化的轴对齐矩形：left <= right 且 top <= bottom 恒成立。 */
data class BoardRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun union(o: BoardRect) = BoardRect(
        min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom),
    )

    fun inflate(d: Float) = BoardRect(left - d, top - d, right + d, bottom + d)

    fun contains(p: Pt) = p.x >= left && p.x <= right && p.y >= top && p.y <= bottom

    companion object {
        /**
         * 从任意两点构造并自动归一化。
         * 这是"从右下往左上拖也能得到正确矩形/椭圆"的唯一保证点 —— 形状最容易写错的地方。
         */
        fun of(a: Pt, b: Pt) = BoardRect(
            min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y),
        )
    }
}
