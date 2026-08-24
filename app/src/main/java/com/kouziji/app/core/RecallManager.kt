package com.kouziji.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 高精度消息自动撤回队列管理器
 */
class RecallManager(private val oneBotClient: OneBotClient) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val pendingTasks = mutableListOf<Job>()

    val recalledCount = AtomicInteger(0)
    val recallFailedCount = AtomicInteger(0)

    /**
     * 安排一条消息在指定延迟（秒）后撤回
     */
    fun scheduleRecall(messageId: Long, delaySeconds: Double) {
        if (messageId <= 0 || delaySeconds <= 0) return

        val delayMillis = (delaySeconds * 1000).toLong().coerceAtLeast(50L)
        val job = scope.launch {
            try {
                delay(delayMillis)
                val result = oneBotClient.deleteMsg(messageId)
                if (result.isSuccess) {
                    recalledCount.incrementAndGet()
                    LogManager.i("✅ 自动撤回成功 (msg_id: $messageId, 延迟: ${delaySeconds}s)")
                } else {
                    recallFailedCount.incrementAndGet()
                    LogManager.w("⚠️ 自动撤回失败 (msg_id: $messageId): ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                recallFailedCount.incrementAndGet()
                LogManager.w("⚠️ 撤回任务异常 (msg_id: $messageId): ${e.message}")
            }
        }

        synchronized(pendingTasks) {
            pendingTasks.add(job)
        }
    }

    /**
     * 清空或取消所有等待中的撤回任务
     */
    fun cancelAllPending() {
        synchronized(pendingTasks) {
            pendingTasks.forEach { it.cancel() }
            pendingTasks.clear()
        }
    }

    fun resetStats() {
        recalledCount.set(0)
        recallFailedCount.set(0)
    }
}
