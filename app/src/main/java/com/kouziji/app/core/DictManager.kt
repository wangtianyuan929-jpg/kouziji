package com.kouziji.app.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.UUID

/**
 * 词库元数据
 */
data class DictInfo(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var lineCount: Int = 0,
    val createTime: Long = System.currentTimeMillis(),
    var lastProgressIndex: Int = 0
)

/**
 * 词库管理器
 */
class DictManager(private val context: Context) {
    private val dictDir = File(context.filesDir, "dicts").apply { if (!exists()) mkdirs() }
    private val metaFile = File(dictDir, "meta.json")
    private val gson = Gson()
    private val dictList = mutableListOf<DictInfo>()

    init {
        loadMetadata()
    }

    private fun loadMetadata() {
        dictList.clear()
        if (metaFile.exists()) {
            try {
                val json = metaFile.readText(Charsets.UTF_8)
                val type = object : TypeToken<List<DictInfo>>() {}.type
                val list: List<DictInfo>? = gson.fromJson(json, type)
                if (list != null) {
                    dictList.addAll(list)
                }
            } catch (e: Exception) {
                LogManager.e("加载词库元数据失败: ${e.message}")
            }
        }
    }

    private fun saveMetadata() {
        try {
            val json = gson.toJson(dictList)
            metaFile.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            LogManager.e("保存词库元数据失败: ${e.message}")
        }
    }

    fun getAllDicts(): List<DictInfo> = dictList.toList()

    fun getDictInfo(id: String): DictInfo? = dictList.find { it.id == id }

    /**
     * 智能探测编码并读取所有非空行
     */
    fun importDictFromStream(
        inputStream: InputStream,
        dictName: String,
        deduplicate: Boolean = false
    ): Result<DictInfo> {
        return try {
            val bytes = inputStream.readBytes()
            val charset = detectCharset(bytes)
            
            val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), charset))
            val lines = mutableListOf<String>()
            val seen = if (deduplicate) mutableSetOf<String>() else null

            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (seen == null || seen.add(trimmed)) {
                        lines.add(trimmed)
                    }
                }
            }

            if (lines.isEmpty()) {
                return Result.failure(IllegalArgumentException("词库文件内容为空或无有效行"))
            }

            val dictInfo = DictInfo(
                name = dictName.replace(".txt", "").trim(),
                lineCount = lines.size
            )

            // 保存实际词库内容（统一转为标准 UTF-8 存储）
            val targetFile = File(dictDir, "${dictInfo.id}.txt")
            targetFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)

            dictList.add(dictInfo)
            saveMetadata()
            LogManager.s("成功导入词库【${dictInfo.name}】，共 ${dictInfo.lineCount} 行 (编码: ${charset.name()})")
            Result.success(dictInfo)
        } catch (e: Exception) {
            LogManager.e("导入词库失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 自动探测编码（UTF-8, GBK, GB2312, UTF-16, ISO-8859-1）
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }

        // 尝试 UTF-8 校验
        if (isValidUtf8(bytes)) {
            return Charsets.UTF_8
        }

        // 中文常见 GBK / GB2312
        return try {
            Charset.forName("GBK")
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b in 0x00..0x7F -> i += 1
                b in 0xC2..0xDF -> {
                    if (i + 1 >= bytes.size) return false
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= bytes.size) return false
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    if ((bytes[i + 2].toInt() and 0xC0) != 0x80) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= bytes.size) return false
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    if ((bytes[i + 2].toInt() and 0xC0) != 0x80) return false
                    if ((bytes[i + 3].toInt() and 0xC0) != 0x80) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }

    /**
     * 读取指定词库的所有行
     */
    fun loadLines(dictId: String): List<String> {
        val file = File(dictDir, "$dictId.txt")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } catch (e: Exception) {
            LogManager.e("读取词库行内容失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 更新词库上次发送进度
     */
    fun updateProgress(dictId: String, index: Int) {
        val info = getDictInfo(dictId)
        if (info != null) {
            info.lastProgressIndex = index
            saveMetadata()
        }
    }

    /**
     * 重命名词库
     */
    fun renameDict(id: String, newName: String): Boolean {
        val info = getDictInfo(id) ?: return false
        info.name = newName.trim()
        saveMetadata()
        return true
    }

    /**
     * 删除词库
     */
    fun deleteDict(id: String): Boolean {
        val info = getDictInfo(id) ?: return false
        val file = File(dictDir, "$id.txt")
        if (file.exists()) file.delete()
        dictList.remove(info)
        saveMetadata()
        LogManager.i("已删除词库【${info.name}】")
        return true
    }

    /**
     * 创建默认测试词库（初次启动无词库时自动生成）
     */
    fun ensureDefaultDict(): DictInfo? {
        if (dictList.isNotEmpty()) return dictList.first()
        val defaultLines = listOf(
            "扣字软件运行正常，请准备就绪",
            "打字速度决定输出效率，请开始你的表演",
            "顺风说骚话，逆风讲道理，这就是扣字艺术",
            "手速跟不上思维，请加大力度",
            "词库加载完毕，随时可以发起进攻",
            "精准定位目标，全自动节奏掌控",
            "节奏抖动已就绪，保持高频输出",
            "秒级自动撤回，不留一丝痕迹"
        )
        val defaultInfo = DictInfo(
            name = "默认示例词库",
            lineCount = defaultLines.size
        )
        val targetFile = File(dictDir, "${defaultInfo.id}.txt")
        targetFile.writeText(defaultLines.joinToString("\n"), Charsets.UTF_8)
        dictList.add(defaultInfo)
        saveMetadata()
        return defaultInfo
    }
}
