package com.kouziji.app

import android.app.Application
import com.kouziji.app.core.AppConfig
import com.kouziji.app.core.DictManager
import com.kouziji.app.core.KouziEngine
import com.kouziji.app.core.OneBotClient
import com.kouziji.app.core.RecallManager

class KouZiApplication : Application() {

    lateinit var appConfig: AppConfig
        private set

    lateinit var dictManager: DictManager
        private set

    lateinit var oneBotClient: OneBotClient
        private set

    lateinit var recallManager: RecallManager
        private set

    lateinit var kouziEngine: KouziEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        appConfig = AppConfig.load(this)
        dictManager = DictManager(this)
        dictManager.ensureDefaultDict()

        oneBotClient = OneBotClient { appConfig }
        recallManager = RecallManager(oneBotClient)

        kouziEngine = KouziEngine(
            dictManager = dictManager,
            oneBotClient = oneBotClient,
            recallManager = recallManager,
            configProvider = { appConfig },
            onConfigUpdate = { saveConfig() }
        )

        // 目标自动捕获事件
        oneBotClient.onTargetCaptured = { groupId, userId, groupName, userName ->
            appConfig.targetGroupId = groupId
            appConfig.targetUserId = userId
            appConfig.targetGroupName = groupName
            appConfig.targetUserName = userName
            saveConfig()
        }

        // 启动 WebSocket 实时事件流
        oneBotClient.startWebSocket()
    }

    fun saveConfig() {
        AppConfig.save(this, appConfig)
    }

    companion object {
        lateinit var instance: KouZiApplication
            private set
    }
}
