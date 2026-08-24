package com.kouziji.app.core

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * 扣字软件全局配置持久化管理
 */
data class AppConfig(
    var napcatHttpHost: String = "127.0.0.1",
    var napcatHttpPort: Int = 3000,
    var napcatWsPort: Int = 3001,
    var napcatToken: String = "",
    
    // 词库设置
    var selectedDictId: String = "",
    
    // 发送模式: 0-顺序发送, 1-随机发送(本轮不重复), 2-完全随机
    var sendMode: Int = 0,
    
    // 发送数量限制: 0 表示全部发送，>0 表示发送指定数量
    var sendCountLimit: Int = 0,
    
    // 起点模式: 0-从头开始, 1-从上次进度继续, 2-随机起点
    var startMode: Int = 0,
    
    // 发送间隔（秒，支持小数）
    var baseIntervalSeconds: Double = 3.0,
    
    // 随机抖动
    var jitterEnabled: Boolean = true,
    var jitterRangeSeconds: Double = 0.5,
    
    // 自动撤回
    var autoRecallEnabled: Boolean = false,
    var recallDelaySeconds: Double = 2.0,
    
    // @ 目标开关
    var atTargetEnabled: Boolean = true,
    
    // 锁定目标
    var targetGroupId: Long = 0L,
    var targetUserId: Long = 0L,
    var targetGroupName: String = "",
    var targetUserName: String = "",
    
    // 失败重试策略: 0-继续下一条, 1-自动暂停
    var failurePolicy: Int = 0
) {
    companion object {
        private const val PREFS_NAME = "kouziji_config"
        private const val KEY_CONFIG_JSON = "config_json"
        private val gson = Gson()

        fun load(context: Context): AppConfig {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_CONFIG_JSON, null)
            return if (json != null) {
                try {
                    gson.fromJson(json, AppConfig::class.java) ?: AppConfig()
                } catch (e: Exception) {
                    AppConfig()
                }
            } else {
                AppConfig()
            }
        }

        fun save(context: Context, config: AppConfig) {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_CONFIG_JSON, gson.toJson(config)).apply()
        }
    }
}
