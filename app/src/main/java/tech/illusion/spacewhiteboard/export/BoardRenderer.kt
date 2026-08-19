package tech.illusion.spacewhiteboard.export

import android.content.Context
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import tech.illusion.spacewhiteboard.board.BoardDoc
import tech.illusion.spacewhiteboard.board.BoardRect
import tech.illusion.spacewhiteboard.board.ItemId
import tech.illusion.spacewhiteboard.board.Viewport
import tech.illusion.spacewhiteboard.content.editor.drawBoard
import kotlin.math.roundToInt

/**
 * 离屏渲染。导出 PNG 与（阶段 2 的）列表缩略图共用它，
 * 且它内部调用的是屏幕渲染同一个 [drawBoard] —— 保证导出图与屏幕所见不会分叉。
 *
 * 调用方在主线程之外的线程调用 [render]：一张 2x 大图的绘制/后续压缩足以造成明显卡顿
 * （见 MediaStoreExporter 与 Main.kt 的接线），因此本对象本身不做线程切换，
 * 由调用方负责已经身处 IO 线程。
 */
object BoardRenderer {

    /**
     * @param pxPerUnit 每个 board unit 渲染成多少像素。导出取 2f 保证清晰度。
     * @param marginBu  内容包围盒外扩多少 board units。
     * @param context   仅用来构造文字渲染所需的 [androidx.compose.ui.text.font.FontFamily.Resolver]
     *   （[createFontFamilyResolver] 要求一个 `Context`）——`drawBoard` 里量 `TextItem` 用
     *   固定的 `density=1f/fontScale=1f`（见 `BoardDrawing.kt` 的 `TEXT_MEASURE_DENSITY`）与固定的
     *   `layoutDirection = LayoutDirection.Ltr`（`measureTextLayout` 内部钉死，1b-2 复审修复项），
     *   都与这里传入的 `Context` 无关。字体解析器（`fontFamilyResolver`）是这条链路上唯一仍然
     *   由调用方各自构造的输入：屏幕路径读 `LocalFontFamilyResolver.current`，这里用
     *   `createFontFamilyResolver(context)`——两个实例不是同一个对象，但本应用没有声明任何自定义
     *   `FontFamily`（`TextStyle(fontSize = ...)` 从不传 `fontFamily`），两者在同一台设备上解析
     *   出的都是系统默认字体，实际不会让导出图与屏幕所见分叉。
     * @return 文档为空、或内容整体落在纸面（`[0,CANVAS_W]×[0,CANVAS_H]`）之外因而
     *   裁剪之后什么都不可见时返回 null——两种情况都是"没有可导出的内容"。
     */
    fun render(
        doc: BoardDoc,
        context: Context,
        pxPerUnit: Float = 2f,
        marginBu: Float = 40f,
        paperArgb: Int,
    ): ImageBitmap? {
        // 裁到内容包围盒而不是整张 2400x1600 逻辑画布，否则导出图大片空白。
        val raw = doc.contentBounds()?.inflate(marginBu) ?: return null

        // `contentBounds()` 由原始 item 几何算出，不夹在纸面矩形内——用户平移视口后仍能
        // 落笔在 [0,CANVAS_W]×[0,CANVAS_H] 之外（BoardDrawing.kt 的 clipToPaper 说明）。
        // drawBoard 内部永远只画 `[0, CANVAS_W] × [0, CANVAS_H]` 这一块（裁剪矩形写死在
        // board 坐标里，与调用方传入的 viewport 无关）。如果直接拿 raw 当裁切框，凡是
        // 探出纸面的那一段包围盒都会在导出图里变成一条纯 paperArgb 的空白带——那正是
        // 被裁掉的墨迹本该在的地方，而不是"纸面之外"应有的样子（纸面之外没有纸）。
        // 结论：必须先把裁切框夹在画布矩形内，导出图才不会被这种空白带污染。
        val b = BoardRect(
            left = raw.left.coerceIn(0f, Viewport.CANVAS_W),
            top = raw.top.coerceIn(0f, Viewport.CANVAS_H),
            right = raw.right.coerceIn(0f, Viewport.CANVAS_W),
            bottom = raw.bottom.coerceIn(0f, Viewport.CANVAS_H),
        )
        // 夹完之后宽/高可能变成 0（比如全部内容都画在纸面之外）：那也是"没有可见内容"，
        // 与空文档同样处理，不返回一张 1x1 的哑图。
        if (b.width <= 0f || b.height <= 0f) return null

        val wPx = (b.width * pxPerUnit).roundToInt().coerceAtLeast(1)
        val hPx = (b.height * pxPerUnit).roundToInt().coerceAtLeast(1)

        val bitmap = ImageBitmap(wPx, hPx)
        val canvas = Canvas(bitmap)

        // 复用屏幕那套变换：density 传 1，把整个缩放交给 viewport.scale = pxPerUnit。
        // drawBoard/drawLiveStroke/drawLiveShape 里的复合变换是
        // `scale(viewport.scale * density, ...)`——scale 和 density 在这个乘积里
        // 完全可交换，把 pxPerUnit 塞进 scale、density 固定传 1，和"scale 传 pxPerUnit
        // 的一半、density 传 2"效果上等价。选择前者是因为它让导出结果与设备屏幕密度
        // 彻底解耦：density 在这条路径里全程是常量 1，不会读 LocalDensity，同一份文档
        // 在任何分辨率/dpi 的机器上导出的位图完全一致，只由 pxPerUnit 这一个数决定。
        val viewport = Viewport(
            offsetX = -b.left * pxPerUnit,
            offsetY = -b.top * pxPerUnit,
            scale = pxPerUnit,
        )

        // TextMeasurer 本身不参与缩放决策——它的 defaultDensity 只在 drawBoard 内部忘记显式
        // 传 density 时才会被用到，而 drawTextItem 每次都显式传 TEXT_MEASURE_DENSITY 覆盖，
        // 这里传什么 Density 都不影响导出结果，选 Density(1f) 只是跟下面 draw() 的密度取值一致。
        // `defaultLayoutDirection = LayoutDirection.Ltr` 现在只是保底：measureTextLayout
        // 自己也钉死了 `layoutDirection = LayoutDirection.Ltr`（1b-2 复审修复项），不再依赖
        // 这里传的默认值——但仍然显式钉在这里，不留一个"万一以后 measureTextLayout 又漏传
        // 这一步，这里的默认值刚好还是对的"式的隐性耦合。
        val fontFamilyResolver = createFontFamilyResolver(context)
        val textMeasurer = TextMeasurer(
            defaultFontFamilyResolver = fontFamilyResolver,
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )

        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(wPx.toFloat(), hPx.toFloat())) {
            // 先铺纸面色，再画内容——没有内容的像素应该是纸，不是透明。
            drawRect(Color(paperArgb))
            drawBoard(
                doc, viewport, density = 1f, pathCache = mutableMapOf<ItemId, Path>(),
                textMeasurer = textMeasurer, fontFamilyResolver = fontFamilyResolver,
            )
        }
        return bitmap
    }
}
