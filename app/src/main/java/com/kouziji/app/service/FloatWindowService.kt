package com.kouziji.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kouziji.app.KouZiApplication
import com.kouziji.app.R
import com.kouziji.app.core.EngineState
import com.kouziji.app.core.EngineStats
import com.kouziji.app.core.LogManager
import com.kouziji.app.ui.MainActivity
import kotlin.math.abs

class FloatWindowService : Service() {

    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var floatBallView: View? = null
    private var floatPanelView: View? = null

    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var isPanelShowing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            startForegroundNotification()
            initLayoutParams()
            createFloatBall()

            // 监听引擎状态变化刷新 UI
            val app = KouZiApplication.instance
            app.kouziEngine.onStatsChanged = { stats ->
                mainHandler.post {
                    updatePanelStats(stats)
                }
            }
            LogManager.s("悬浮窗服务启动成功！")
        } catch (e: Exception) {
            LogManager.e("悬浮窗启动异常: ${e.message}")
            Toast.makeText(this, "悬浮窗启动异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startForegroundNotification() {
        try {
            val channelId = "kouziji_float_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "扣字悬浮窗服务",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("扣字神器已就绪")
                .setContentText("悬浮窗运行中，随时可展开控制")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

            ServiceCompat.startForeground(this, 1001, notification, serviceType)
        } catch (e: Exception) {
            LogManager.w("前台服务通知设置警告: ${e.message}")
        }
    }

    private fun initLayoutParams() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 悬浮球 LayoutParams
        ballParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 350
        }

        // 控制面板 LayoutParams
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
    }

    private fun createFloatBall() {
        val wm = windowManager ?: return
        try {
            val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_KouZiJi)
            val inflater = LayoutInflater.from(contextThemeWrapper)
            floatBallView = inflater.inflate(R.layout.layout_float_ball, null)

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            floatBallView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = ballParams.x
                        initialY = ballParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        ballParams.x = (initialX + dx).toInt()
                        ballParams.y = (initialY + dy).toInt()
                        try {
                            wm.updateViewLayout(floatBallView, ballParams)
                        } catch (e: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            showPanel()
                        }
                        true
                    }
                    else -> false
                }
            }

            wm.addView(floatBallView, ballParams)
        } catch (e: Exception) {
            LogManager.e("添加悬浮球失败: ${e.message}")
            Toast.makeText(this, "添加悬浮球失败: 请确保已允许悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun createFloatPanel() {
        if (floatPanelView != null) return
        try {
            val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_KouZiJi)
            val inflater = LayoutInflater.from(contextThemeWrapper)
            floatPanelView = inflater.inflate(R.layout.layout_float_panel, null)

            val app = KouZiApplication.instance
            val config = app.appConfig

            // 绑定关闭/折叠按钮
            floatPanelView?.findViewById<ImageView>(R.id.btnCollapse)?.setOnClickListener {
                hidePanel()
            }

            // 绑定调参输入框与复选框
            val etInterval = floatPanelView?.findViewById<EditText>(R.id.etInterval)
            val cbJitter = floatPanelView?.findViewById<CheckBox>(R.id.cbJitter)
            val etJitter = floatPanelView?.findViewById<EditText>(R.id.etJitter)
            val cbAutoRecall = floatPanelView?.findViewById<CheckBox>(R.id.cbAutoRecall)
            val etRecallDelay = floatPanelView?.findViewById<EditText>(R.id.etRecallDelay)
            val cbAtTarget = floatPanelView?.findViewById<CheckBox>(R.id.cbAtTarget)

            etInterval?.setText(config.baseIntervalSeconds.toString())
            cbJitter?.isChecked = config.jitterEnabled
            etJitter?.setText(config.jitterRangeSeconds.toString())
            cbAutoRecall?.isChecked = config.autoRecallEnabled
            etRecallDelay?.setText(config.recallDelaySeconds.toString())
            cbAtTarget?.isChecked = config.atTargetEnabled

            // 按钮操作
            val btnStartResume = floatPanelView?.findViewById<Button>(R.id.btnStartResume)
            val btnPause = floatPanelView?.findViewById<Button>(R.id.btnPause)
            val btnStopEmergency = floatPanelView?.findViewById<Button>(R.id.btnStopEmergency)

            btnStartResume?.setOnClickListener {
                syncParamsFromUi()
                val state = app.kouziEngine.currentState
                if (state == EngineState.PAUSED) {
                    app.kouziEngine.resume()
                } else {
                    val res = app.kouziEngine.start()
                    if (res.isFailure) {
                        Toast.makeText(this, res.exceptionOrNull()?.message ?: "启动失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            btnPause?.setOnClickListener {
                app.kouziEngine.pause()
            }

            btnStopEmergency?.setOnClickListener {
                app.kouziEngine.stop()
                Toast.makeText(this, "扣字已紧急停止！", Toast.LENGTH_SHORT).show()
            }

            // 标题栏拖拽移动面板
            val header = floatPanelView?.findViewById<View>(R.id.panelHeader)
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            header?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = panelParams.x
                        initialY = panelParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panelParams.x = (initialX + (event.rawX - initialTouchX)).toInt()
                        panelParams.y = (initialY + (event.rawY - initialTouchY)).toInt()
                        if (isPanelShowing && floatPanelView != null) {
                            try {
                                windowManager?.updateViewLayout(floatPanelView, panelParams)
                            } catch (e: Exception) {}
                        }
                        true
                    }
                    else -> false
                }
            }
        } catch (e: Exception) {
            LogManager.e("创建控制面板失败: ${e.message}")
            Toast.makeText(this, "创建面板异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun syncParamsFromUi() {
        val app = KouZiApplication.instance
        val config = app.appConfig
        floatPanelView?.let { panel ->
            val etInterval = panel.findViewById<EditText>(R.id.etInterval)
            val cbJitter = panel.findViewById<CheckBox>(R.id.cbJitter)
            val etJitter = panel.findViewById<EditText>(R.id.etJitter)
            val cbAutoRecall = panel.findViewById<CheckBox>(R.id.cbAutoRecall)
            val etRecallDelay = panel.findViewById<EditText>(R.id.etRecallDelay)
            val cbAtTarget = panel.findViewById<CheckBox>(R.id.cbAtTarget)

            config.baseIntervalSeconds = etInterval.text.toString().toDoubleOrNull() ?: 3.0
            config.jitterEnabled = cbJitter.isChecked
            config.jitterRangeSeconds = etJitter.text.toString().toDoubleOrNull() ?: 0.5
            config.autoRecallEnabled = cbAutoRecall.isChecked
            config.recallDelaySeconds = etRecallDelay.text.toString().toDoubleOrNull() ?: 2.0
            config.atTargetEnabled = cbAtTarget.isChecked
            app.saveConfig()
        }
    }

    private fun updatePanelStats(stats: EngineStats) {
        val app = KouZiApplication.instance
        val config = app.appConfig
        floatPanelView?.let { panel ->
            val tvEngineState = panel.findViewById<TextView>(R.id.tvEngineState)
            val tvTargetInfo = panel.findViewById<TextView>(R.id.tvTargetInfo)
            val tvDictInfo = panel.findViewById<TextView>(R.id.tvDictInfo)
            val tvSentStats = panel.findViewById<TextView>(R.id.tvSentStats)

            tvEngineState.text = stats.state.desc
            tvEngineState.setTextColor(
                when (stats.state) {
                    EngineState.RUNNING -> ContextCompat.getColor(this, R.color.primary)
                    EngineState.PAUSED -> ContextCompat.getColor(this, R.color.warning)
                    EngineState.ERROR -> ContextCompat.getColor(this, R.color.danger)
                    else -> ContextCompat.getColor(this, R.color.text_gray)
                }
            )

            // 目标信息
            if (config.targetGroupId > 0) {
                val groupText = if (config.targetGroupName.isNotBlank()) config.targetGroupName else "${config.targetGroupId}"
                val userText = if (config.targetUserName.isNotBlank()) config.targetUserName else "${config.targetUserId}"
                tvTargetInfo.text = "🎯 群: $groupText | 目标: $userText"
            } else {
                tvTargetInfo.text = "🎯 目标: 未锁定 (在群内@目标即可)"
            }

            // 词库信息
            val dict = app.dictManager.getDictInfo(config.selectedDictId) ?: app.dictManager.getAllDicts().firstOrNull()
            if (dict != null) {
                tvDictInfo.text = "📚 词库: ${dict.name} (${stats.currentIndex}/${dict.lineCount})"
            }

            // 发送统计
            val recalled = app.recallManager.recalledCount.get()
            val intervalStr = if (stats.nextIntervalSeconds > 0) "${stats.nextIntervalSeconds}s" else "${config.baseIntervalSeconds}s"
            tvSentStats.text = "📊 已发: ${stats.totalSent} | 撤回: $recalled | 间隔: $intervalStr"
        }
    }

    private fun showPanel() {
        val wm = windowManager ?: return
        if (!isPanelShowing) {
            createFloatPanel()
            syncParamsFromUi()
            updatePanelStats(EngineStats(state = KouZiApplication.instance.kouziEngine.currentState))
            panelParams.x = ballParams.x.coerceAtMost(300)
            panelParams.y = ballParams.y.coerceAtMost(600)
            try {
                wm.addView(floatPanelView, panelParams)
                floatBallView?.visibility = View.GONE
                isPanelShowing = true
            } catch (e: Exception) {
                LogManager.e("展开面板失败: ${e.message}")
            }
        }
    }

    private fun hidePanel() {
        val wm = windowManager ?: return
        if (isPanelShowing && floatPanelView != null) {
            syncParamsFromUi()
            try {
                wm.removeView(floatPanelView)
            } catch (e: Exception) {}
            floatBallView?.visibility = View.VISIBLE
            isPanelShowing = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val wm = windowManager
        if (isPanelShowing && floatPanelView != null) {
            try { wm?.removeView(floatPanelView) } catch (e: Exception) {}
        }
        if (floatBallView != null) {
            try { wm?.removeView(floatBallView) } catch (e: Exception) {}
        }
        KouZiApplication.instance.kouziEngine.stop()
    }
}
