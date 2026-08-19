package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.illusion.spacewhiteboard.store.BoardStore

/** debounce 窗口：连续操作合并成一次写盘，见 [AutoSave] 顶部文档。 */
private const val DEBOUNCE_MS = 500L

/**
 * 自动保存。两条路径：
 *
 *  1) [EditorState.revision] 变化后 debounce [DEBOUNCE_MS] 写盘——合并连续操作
 *     （连画十笔不写十次盘）。`LaunchedEffect(state.revision)` 的取消语义正是要的
 *     debounce：revision 再次变化时旧协程被取消在 `delay` 上，只有最后一次变化能走到
 *     写盘。**不用** `withFrameNanos`——它在 `DefaultWindowContainer` 里只触发一次就死；
 *     也不手搓计时器。
 *
 *  2) `ON_PAUSE` / `ON_STOP` 立即强制写盘，不等 debounce。头显场景下，摘下头显、切走
 *     App 极其频繁，这条路径比手机上重要得多。
 *
 * 最坏丢失量 = debounce 窗口内的操作（<=500ms，最多一笔）——这是本任务接受的权衡，
 * 前提是路径 2 覆盖了「正常摘下/切走」的全部普通场景，只有真正的崩溃/被杀才会丢。
 *
 * **两条路径可能重叠**：debounce 的写盘跑在 IO 线程，尚未完成时若 `ON_PAUSE` 恰好触发，
 * 主线程会试图对同一个文件发起第二次 `BoardStore.save()`。`BoardStore.save()` 用固定的
 * `file.name + ".tmp"` 作为临时文件名（Task 11，未改动、也不允许改动），两次并发调用会
 * 写向同一个临时文件——后写入的一路会截断/覆盖前一路尚未 `fsync`/`rename` 的内容，破坏
 * "临时文件+原子替换"这条不变式所依赖的前提（同一时刻只有一个写者）。用一把跨两条路径
 * 共享的锁 [saveLock] 串行化即可：保存本身很快（几 KB 的文本 + 一次 fsync），
 * 让 `ON_PAUSE` 在极端情况下多等这一次写盘完成，比冒着损坏存档的风险划算得多。
 */
@Composable
fun AutoSave(state: EditorState, store: BoardStore, onStatus: (SaveStatus) -> Unit) {

    // 两条路径共享同一把锁，防止 debounce 写盘与 onPause/onStop 强制写盘并发访问
    // BoardStore 内部同一个固定命名的临时文件。见上方类注释。
    val saveLock = remember { Any() }

    LaunchedEffect(state.revision, state, store) {
        // revision == 0：刚从磁盘载入（或全新空文档），还没有发生过任何用户操作，
        // 不应该在启动瞬间就触发一次多余的写盘（也让初始状态保持 Idle，契约 §2.1「不显示」）。
        if (state.revision == 0) return@LaunchedEffect
        delay(DEBOUNCE_MS)
        val doc = state.doc
        onStatus(SaveStatus.Saving)
        val result = withContext(Dispatchers.IO) {
            runCatching { synchronized(saveLock) { store.save(doc) } }
        }
        onStatus(if (result.isSuccess) SaveStatus.Saved else SaveStatus.Failed)
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                // 故意同步执行（不切协程/不 launch 到别的线程）：onPause/onStop 之后系统
                // 随时可能冻结甚至杀掉进程，没有"稍后再补写"的时间窗口。存盘本身很小很快
                // （几 KB 文本 + 一次 fsync），在生命周期回调里短暂阻塞是这里唯一能保证
                // "写完了才离开"的写法。synchronized 同一把 [saveLock]：若此刻 debounce
                // 那一路正在 IO 线程里写，这里会等它写完，而不是并发抢同一个临时文件。
                val doc = state.doc
                onStatus(SaveStatus.Saving)
                val result = runCatching { synchronized(saveLock) { store.save(doc) } }
                onStatus(if (result.isSuccess) SaveStatus.Saved else SaveStatus.Failed)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
