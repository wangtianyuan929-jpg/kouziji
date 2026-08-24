package com.kouziji.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 扣字任务状态
 */
enum class EngineState(val desc: String) {
    IDLE("未启动"),
    RUNNING("运行中"),
    PAUSED("已暂停"),
    STOPPED("已停止"),
    ERROR("连接异常")
}

/**
 * 扣字引擎运行时统计信息
 */
data class EngineStats(
    val state: EngineState = EngineState.IDLE,
    val totalSent: Int = 0,
    val sendFailed: Int = 0,
    val currentIndex: Int = 0,
    val totalLines: Int = 0,
    val nextIntervalSeconds: Double = 0.0,
    val lastSentText: String = "",
    val errorMessage: String = ""
)

/**
 * 扣字核心调度引擎
 */
class KouziEngine(
    private val dictManager: DictManager,
    private val oneBotClient: OneBotClient,
    private val recallManager: RecallManager,
    private val configProvider: () -> AppConfig,
    private val onConfigUpdate: (AppConfig) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var taskJob: Job? = null

    private var state = EngineState.IDLE
    private var sentCount = AtomicInteger(0)
    private var failCount = AtomicInteger(0)
    private var currentDictLines: List<String> = emptyList()
    private var shuffledIndices: List<Int> = emptyList()
    private var pointerIndex = 0
    private var isPaused = AtomicBoolean(false)

    var onStatsChanged: ((EngineStats) -> Unit)? = null

    val currentState: EngineState get() = state

    /**
     * 开始扣字任务
     */
    @Synchronized
    fun start(fromPause: Boolean = false): Result<Unit> {
        val config = configProvider()

        if (config.targetGroupId <= 0) {
            val err = "未锁定目标群！请先在 QQ 群内 @ 目标成员一次，或手动配置目标群号。"
            LogManager.w(err)
            updateState(EngineState.ERROR, err)
            return Result.failure(IllegalStateException(err))
        }

        // 加载词库
        var dictInfo = dictManager.getDictInfo(config.selectedDictId)
        if (dictInfo == null) {
            val all = dictManager.getAllDicts()
            if (all.isNotEmpty()) {
                dictInfo = all.first()
                config.selectedDictId = dictInfo.id
                onConfigUpdate(config)
            } else {
                val created = dictManager.ensureDefaultDict()
                dictInfo = created
                if (created != null) {
                    config.selectedDictId = created.id
                    onConfigUpdate(config)
                }
            }
        }

        if (dictInfo == null) {
            val err = "当前没有可用词库，请先导入 TXT 词库！"
            LogManager.e(err)
            updateState(EngineState.ERROR, err)
            return Result.failure(IllegalStateException(err))
        }

        currentDictLines = dictManager.loadLines(dictInfo.id)
        if (currentDictLines.isEmpty()) {
            val err = "词库【${dictInfo.name}】内容为空！"
            LogManager.e(err)
            updateState(EngineState.ERROR, err)
            return Result.failure(IllegalStateException(err))
        }

        if (!fromPause) {
            sentCount.set(0)
            failCount.set(0)
            recallManager.resetStats()

            // 确定起点位置
            pointerIndex = when (config.startMode) {
                1 -> dictInfo.lastProgressIndex.coerceIn(0, currentDictLines.size - 1)
                2 -> Random.nextInt(currentDictLines.size)
                else -> 0
            }

            // 初始化随机序列
            if (config.sendMode == 1) { // 本轮不重复随机
                shuffledIndices = currentDictLines.indices.shuffled()
            }
        }

        isPaused.set(false)
        updateState(EngineState.RUNNING)
        LogManager.s("🚀 扣字任务已启动！目标群【${config.targetGroupName} (${config.targetGroupId})】词库【${dictInfo.name}】(${currentDictLines.size}句)")

        taskJob?.cancel()
        taskJob = scope.launch {
            runLoop()
        }

        return Result.success(Unit)
    }

    /**
     * 暂停任务
     */
    @Synchronized
    fun pause() {
        if (state == EngineState.RUNNING) {
            isPaused.set(true)
            taskJob?.cancel()
            updateState(EngineState.PAUSED)
            LogManager.i("⏸️ 扣字任务已暂停 (已发送: ${sentCount.get()} 条)")
        }
    }

    /**
     * 从暂停处继续
     */
    @Synchronized
    fun resume(): Result<Unit> {
        return if (state == EngineState.PAUSED) {
            start(fromPause = true)
        } else {
            start(fromPause = false)
        }
    }

    /**
     * 停止任务（紧急停止）
     */
    @Synchronized
    fun stop() {
        taskJob?.cancel()
        taskJob = null
        isPaused.set(false)
        val finalSent = sentCount.get()
        updateState(EngineState.STOPPED)
        LogManager.i("⏹️ 扣字任务已停止 (本次总计发送: $finalSent 条, 撤回: ${recallManager.recalledCount.get()} 条)")
    }

    /**
     * 核心发送主循环
     */
    private suspend fun runLoop() {
        while (scope.isActive && !isPaused.get()) {
            val config = configProvider()

            // 检查数量限制
            if (config.sendCountLimit > 0 && sentCount.get() >= config.sendCountLimit) {
                LogManager.s("🎉 已达到设定的发送数量限制 (${config.sendCountLimit} 条)，任务自动结束！")
                stop()
                break
            }

            // 获取当前要发送的文本
            val lineIndex = when (config.sendMode) {
                0 -> { // 顺序
                    val idx = pointerIndex % currentDictLines.size
                    pointerIndex++
                    idx
                }
                1 -> { // 随机（本轮不重复）
                    if (shuffledIndices.isEmpty() || pointerIndex >= shuffledIndices.size) {
                        shuffledIndices = currentDictLines.indices.shuffled()
                        pointerIndex = 0
                    }
                    val idx = shuffledIndices[pointerIndex]
                    pointerIndex++
                    idx
                }
                else -> { // 完全随机
                    Random.nextInt(currentDictLines.size)
                }
            }

            val textToSend = currentDictLines[lineIndex]

            // 保存进度
            dictManager.updateProgress(config.selectedDictId, lineIndex)

            // 计算下一次发送的间隔与随机抖动
            val actualInterval = calculateInterval(config.baseIntervalSeconds, config.jitterEnabled, config.jitterRangeSeconds)
            notifyStats(textToSend, actualInterval)

            // 发送消息
            val atUserId = if (config.atTargetEnabled && config.targetUserId > 0) config.targetUserId else null
            val sendResult = oneBotClient.sendGroupMsg(config.targetGroupId, textToSend, atUserId)

            if (sendResult.isSuccess) {
                val msgId = sendResult.getOrThrow()
                val currentCount = sentCount.incrementAndGet()
                LogManager.i("[$currentCount] 发送成功 (ID: $msgId) -> $textToSend")

                // 自动撤回处理
                if (config.autoRecallEnabled && config.recallDelaySeconds > 0) {
                    recallManager.scheduleRecall(msgId, config.recallDelaySeconds)
                }
            } else {
                val err = sendResult.exceptionOrNull()?.message ?: "未知发送错误"
                failCount.incrementAndGet()
                LogManager.e("❌ 发送失败: $err")

                if (config.failurePolicy == 1) { // 失败自动暂停
                    LogManager.w("已触发失败策略，任务自动暂停")
                    pause()
                    break
                }
            }

            notifyStats(textToSend, actualInterval)

            // 等待间隔
            val delayMillis = (actualInterval * 1000).toLong().coerceAtLeast(100L)
            delay(delayMillis)
        }
    }

    /**
     * 计算实际发送间隔（基础间隔 + 随机抖动）
     */
    private fun calculateInterval(base: Double, jitterEnabled: Boolean, jitterRange: Double): Double {
        if (!jitterEnabled || jitterRange <= 0.0) {
            return base.coerceAtLeast(0.1)
        }
        val jitter = Random.nextDouble(-jitterRange, jitterRange)
        val result = base + jitter
        return (Math.round(result * 100.0) / 100.0).coerceAtLeast(0.1)
    }

    private fun updateState(newState: EngineState, errorMsg: String = "") {
        state = newState
        notifyStats("", 0.0, errorMsg)
    }

    private fun notifyStats(lastText: String = "", nextInterval: Double = 0.0, errorMsg: String = "") {
        val stats = EngineStats(
            state = state,
            totalSent = sentCount.get(),
            sendFailed = failCount.get(),
            currentIndex = pointerIndex,
            totalLines = currentDictLines.size,
            nextIntervalSeconds = nextInterval,
            lastSentText = lastText,
            errorMessage = errorMsg
        )
        onStatsChanged?.invoke(stats)
    }
}
