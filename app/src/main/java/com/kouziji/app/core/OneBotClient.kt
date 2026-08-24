package com.kouziji.app.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OneBot 11 协议客户端（HTTP API + WebSocket 实时事件流）
 */
class OneBotClient(private val configProvider: () -> AppConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var scope = CoroutineScope(Dispatchers.IO + Job())
    private var reconnectJob: Job? = null

    var onTargetCaptured: ((groupId: Long, userId: Long, groupName: String, userName: String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    private val httpBaseUrl: String
        get() {
            val c = configProvider()
            return "http://${c.napcatHttpHost}:${c.napcatHttpPort}"
        }

    private val wsUrl: String
        get() {
            val c = configProvider()
            return "ws://${c.napcatHttpHost}:${c.napcatWsPort}"
        }

    /**
     * 发送 HTTP POST 请求到 OneBot 11 API
     */
    private suspend fun postApi(endpoint: String, params: Any = JsonObject()): Result<JsonObject> {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = if (params is String) params else gson.toJson(params)
                val requestBuilder = Request.Builder()
                    .url("$httpBaseUrl/$endpoint")
                    .post(jsonBody.toRequestBody(jsonMediaType))

                val token = configProvider().napcatToken
                if (token.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val respStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $respStr"))
                }

                val json = JsonParser.parseString(respStr).asJsonObject
                val status = json.get("status")?.asString
                if (status == "ok" || status == "async") {
                    Result.success(json)
                } else {
                    val retcode = json.get("retcode")?.asInt ?: -1
                    val msg = json.get("msg")?.asString ?: json.get("wording")?.asString ?: "未知错误"
                    Result.failure(Exception("OneBot API 错误 ($retcode): $msg"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 智能自动探测 NapCat 地址（无需用户手动输入 IP）
     */
    suspend fun autoDiscoverAndConnect(): Result<Pair<Long, String>> {
        return withContext(Dispatchers.IO) {
            val candidateHosts = mutableSetOf<String>()
            val c = configProvider()
            if (c.napcatHttpHost.isNotBlank()) candidateHosts.add(c.napcatHttpHost)
            candidateHosts.add("127.0.0.1")
            candidateHosts.add("localhost")
            candidateHosts.add("10.0.2.2")
            candidateHosts.add("10.0.2.15")

            // 动态收集手机本机所有网络接口的 IPv4 (Wi-Fi, 移动网络, 虚拟局域网等)
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            candidateHosts.add(ip)
                            // 同时加入该网段常见网关和广播
                            val prefix = ip.substringBeforeLast(".")
                            candidateHosts.add("$prefix.1")
                            candidateHosts.add("$prefix.2")
                            candidateHosts.add("$prefix.15")
                        }
                    }
                }
            } catch (e: Exception) {}

            for (host in candidateHosts) {
                try {
                    val url = "http://$host:${c.napcatHttpPort}/get_login_info"
                    val requestBuilder = Request.Builder().url(url).post("{}".toRequestBody(jsonMediaType))
                    if (c.napcatToken.isNotBlank()) {
                        requestBuilder.addHeader("Authorization", "Bearer ${c.napcatToken}")
                    }
                    val resp = client.newCall(requestBuilder.build()).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JsonParser.parseString(body).asJsonObject
                        if (json.get("status")?.asString == "ok") {
                            val data = json.getAsJsonObject("data")
                            val userId = data.get("user_id").asLong
                            val nickname = data.get("nickname").asString
                            
                            // 自动更新配置中的有效 Host
                            c.napcatHttpHost = host
                            LogManager.s("🎉 智能探针命中 NapCat 通道: $host:${c.napcatHttpPort} (QQ: $nickname)")
                            return@withContext Result.success(Pair(userId, nickname))
                        }
                    }
                } catch (e: Exception) {
                    // 尝试下一个候选地址
                }
            }
            Result.failure(Exception("自动探测完成，未发现已在线的 NapCat 服务"))
        }
    }

    /**
     * 获取登录 QQ 信息
     */
    suspend fun getLoginInfo(): Result<Pair<Long, String>> {
        val res = postApi("get_login_info")
        if (res.isSuccess) {
            return res.mapCatching { json ->
                val data = json.getAsJsonObject("data")
                val userId = data.get("user_id").asLong
                val nickname = data.get("nickname").asString
                Pair(userId, nickname)
            }
        }
        // 如果当前配置的 host 失败，自动尝试全网段智能探针
        return autoDiscoverAndConnect()
    }

    /**
     * 发送群消息（返回 message_id）
     */
    suspend fun sendGroupMsg(groupId: Long, text: String, atUserId: Long? = null): Result<Long> {
        val params = JsonObject().apply {
            addProperty("group_id", groupId)
            if (atUserId != null && atUserId > 0) {
                // 携带 @ 消息
                addProperty("message", "[CQ:at,qq=$atUserId] $text")
            } else {
                addProperty("message", text)
            }
        }

        val res = postApi("send_group_msg", params)
        return res.mapCatching { json ->
            val data = json.get("data")
            if (data != null && data.isJsonObject) {
                data.asJsonObject.get("message_id").asLong
            } else if (data != null && data.isJsonPrimitive) {
                data.asLong
            } else {
                json.get("message_id")?.asLong ?: 0L
            }
        }
    }

    /**
     * 撤回消息
     */
    suspend fun deleteMsg(messageId: Long): Result<Boolean> {
        val params = JsonObject().apply {
            addProperty("message_id", messageId)
        }
        val res = postApi("delete_msg", params)
        return res.map { true }
    }

    /**
     * 获取群信息与成员昵称
     */
    suspend fun getGroupMemberNickname(groupId: Long, userId: Long): String {
        val params = JsonObject().apply {
            addProperty("group_id", groupId)
            addProperty("user_id", userId)
            addProperty("no_cache", true)
        }
        val res = postApi("get_group_member_info", params)
        return res.mapCatching { json ->
            val data = json.getAsJsonObject("data")
            val card = data.get("card")?.asString
            if (!card.isNullOrBlank()) card else (data.get("nickname")?.asString ?: userId.toString())
        }.getOrDefault(userId.toString())
    }

    suspend fun getGroupName(groupId: Long): String {
        val params = JsonObject().apply {
            addProperty("group_id", groupId)
            addProperty("no_cache", true)
        }
        val res = postApi("get_group_info", params)
        return res.mapCatching { json ->
            val data = json.getAsJsonObject("data")
            data.get("group_name")?.asString ?: groupId.toString()
        }.getOrDefault(groupId.toString())
    }

    /**
     * 启动 WebSocket 实时事件监听（用于识别群内 @ 动作自动锁定目标）
     */
    fun startWebSocket() {
        stopWebSocket()
        val requestBuilder = Request.Builder().url(wsUrl)
        val token = configProvider().napcatToken
        if (token.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                LogManager.s("WebSocket 已连接到 NapCat ($wsUrl)")
                onConnectionStateChanged?.invoke(true, "已连接")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleWebSocketEvent(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                LogManager.w("WebSocket 连接断开: ${t.message}")
                onConnectionStateChanged?.invoke(false, "连接断开: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                LogManager.w("WebSocket 已关闭 ($code: $reason)")
                onConnectionStateChanged?.invoke(false, "已关闭")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            if (!isConnected && isActive) {
                LogManager.i("正在尝试重连 NapCat WebSocket...")
                startWebSocket()
            }
        }
    }

    fun stopWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal close")
        webSocket = null
        isConnected = false
    }

    /**
     * 解析 OneBot 事件：当检测到在群里 @ 了某人时，自动锁定该群和目标
     */
    private fun handleWebSocketEvent(jsonStr: String) {
        try {
            val json = JsonParser.parseString(jsonStr).asJsonObject
            val postType = json.get("post_type")?.asString
            if (postType == "message") {
                val messageType = json.get("message_type")?.asString
                if (messageType == "group") {
                    val groupId = json.get("group_id")?.asLong ?: 0L
                    val sender = json.getAsJsonObject("sender")
                    val senderId = sender?.get("user_id")?.asLong ?: 0L
                    
                    // 检查消息中的 @ 段或 CQ 码
                    var atTargetId: Long? = null
                    
                    val rawMessage = json.get("raw_message")?.asString ?: ""
                    val cqAtRegex = """\[CQ:at,qq=(\d+)\]""".toRegex()
                    val match = cqAtRegex.find(rawMessage)
                    if (match != null) {
                        atTargetId = match.groupValues[1].toLongOrNull()
                    }

                    // 兼容 array 消息格式
                    val messageArr = json.get("message")
                    if (atTargetId == null && messageArr != null && messageArr.isJsonArray) {
                        for (elem in messageArr.asJsonArray) {
                            val obj = elem.asJsonObject
                            if (obj.get("type")?.asString == "at") {
                                val data = obj.getAsJsonObject("data")
                                val qqStr = data.get("qq")?.asString
                                if (qqStr != null && qqStr != "all") {
                                    atTargetId = qqStr.toLongOrNull()
                                    if (atTargetId != null) break
                                }
                            }
                        }
                    }

                    if (groupId > 0 && atTargetId != null && atTargetId > 0) {
                        scope.launch {
                            val groupName = getGroupName(groupId)
                            val userName = getGroupMemberNickname(groupId, atTargetId)
                            LogManager.s("🎯 检测到群内 @ 动作！自动锁定目标群【$groupName ($groupId)】目标成员【$userName ($atTargetId)】")
                            onTargetCaptured?.invoke(groupId, atTargetId, groupName, userName)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略非事件 json 解析
        }
    }
}
