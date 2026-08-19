package tech.illusion.spacewhiteboard.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * 写入系统相册的 `Pictures/SpaceWhiteboard/`。
 *
 * scoped storage 下应用向 `MediaStore` 插入自己创建的图片**不需要任何运行时权限**——
 * 不为此申请 `WRITE_EXTERNAL_STORAGE` 或 `READ_MEDIA_*`。这是本类存在的第二个理由
 * （第一个是把导出目的地这段与 `BoardRenderer` 的纯渲染逻辑分开）。
 */
object MediaStoreExporter {

    // Environment.DIRECTORY_PICTURES 是平台字段而非 Kotlin 编译期常量，不能声明成 const val。
    val RELATIVE_DIR = Environment.DIRECTORY_PICTURES + "/SpaceWhiteboard"

    /**
     * @param displayName 不含扩展名的文件名。调用方需保证跨快速多次导出不重名
     *   （Main.kt 用 `"whiteboard-" + System.currentTimeMillis()`）。
     * @return 写入成功后的内容 Uri；任何一步失败都返回 null，并且不会在相册里
     *   留下一条指向半成品/空文件的记录。
     */
    fun exportPng(context: Context, bitmap: ImageBitmap, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            // 写入过程中这条目对其它应用（包括系统相册本身）不可见，写完才发布——
            // 保证外部永远看不到一张打不开的半成品图。
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val opened = resolver.openOutputStream(uri)?.use { out ->
                bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // openOutputStream 返回 null，或者 compress 本身报告失败：两种都算写入未完成，
            // 删掉刚插入的行，不留下一条打不开的破图。
            if (opened != true) {
                resolver.delete(uri, null, null)
                return null
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
