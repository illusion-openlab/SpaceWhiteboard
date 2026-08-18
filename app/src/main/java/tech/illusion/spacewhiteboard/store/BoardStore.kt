package tech.illusion.spacewhiteboard.store

import tech.illusion.spacewhiteboard.board.BoardCodec
import tech.illusion.spacewhiteboard.board.BoardDoc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface LoadResult {
    data class Ok(val doc: BoardDoc, val skippedLines: Int) : LoadResult
    data object Empty : LoadResult
    data class Corrupt(val backupPath: String?, val reason: String) : LoadResult
}

/**
 * 单块白板的读写。
 *
 * 写盘走"临时文件 + fsync + rename"的原子替换：进程在写入中途被杀时，
 * 目标文件要么是完整的旧版本、要么是完整的新版本，绝不会是残缺的半个文件。
 * 这条性质对本应用格外重要 —— 存档里装着用户全部笔迹。
 */
class BoardStore(private val file: File) {

    fun save(doc: BoardDoc) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(BoardCodec.encode(doc).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("atomic rename failed: " + tmp.path + " -> " + file.path)
        }
    }

    fun load(): LoadResult {
        if (!file.exists()) return LoadResult.Empty

        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            // 启动路径绝不能因为任何异常（不止 IOException）而崩溃：
            // 宁可报告一份"损坏"，也不能让整个应用起不来。
            return LoadResult.Corrupt(null, "read failed: ${e::class.java.simpleName}: ${e.message}")
        }

        return try {
            val r = BoardCodec.decode(text)
            LoadResult.Ok(r.doc, r.skippedLines)
        } catch (e: Exception) {
            // 绝不删除或覆盖原文件：静默清空等于把"损坏"升级成"永久丢失"
            val backup = File(file.parentFile, file.name + CORRUPT_SUFFIX)
            val backupPath = try {
                file.copyTo(backup, overwrite = true)
                backup.path
            } catch (io: Exception) {
                null
            }
            LoadResult.Corrupt(backupPath, "${e::class.java.simpleName}: ${e.message ?: "decode failed"}")
        }
    }

    companion object {
        const val TMP_SUFFIX = ".tmp"
        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
