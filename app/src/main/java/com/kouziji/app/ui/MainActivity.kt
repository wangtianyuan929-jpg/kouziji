package com.kouziji.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kouziji.app.KouZiApplication
import com.kouziji.app.R
import com.kouziji.app.core.DictInfo
import com.kouziji.app.core.LogManager
import com.kouziji.app.databinding.ActivityMainBinding
import com.kouziji.app.service.FloatWindowService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private val dictList = mutableListOf<DictInfo>()

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportDict(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLogs.movementMethod = ScrollingMovementMethod()

        initViews()
        loadConfigToUi()
        refreshDictList()
        setupListeners()
        setupLogListener()
    }

    private fun initViews() {
        val app = KouZiApplication.instance
        val config = app.appConfig

        binding.etHost.setText(config.napcatHttpHost)
        binding.etHttpPort.setText(config.napcatHttpPort.toString())
        binding.etCountLimit.setText(config.sendCountLimit.toString())

        when (config.sendMode) {
            1 -> binding.rbRandomNoRepeat.isChecked = true
            2 -> binding.rbRandom.isChecked = true
            else -> binding.rbOrder.isChecked = true
        }
    }

    private fun loadConfigToUi() {
        val app = KouZiApplication.instance
        val config = app.appConfig

        // 默认自动触发一次 NapCat 连接测试
        testNapCatConnection()
    }

    private fun refreshDictList() {
        val app = KouZiApplication.instance
        dictList.clear()
        dictList.addAll(app.dictManager.getAllDicts())

        val names = dictList.map { "${it.name} (${it.lineCount}句)" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spDicts.adapter = adapter

        val config = app.appConfig
        val selectedIndex = dictList.indexOfFirst { it.id == config.selectedDictId }
        if (selectedIndex >= 0) {
            binding.spDicts.setSelection(selectedIndex)
            binding.tvDictDetail.text = "总计: ${dictList[selectedIndex].lineCount} 句"
        } else if (dictList.isNotEmpty()) {
            binding.spDicts.setSelection(0)
            config.selectedDictId = dictList[0].id
            app.saveConfig()
            binding.tvDictDetail.text = "总计: ${dictList[0].lineCount} 句"
        }
    }

    private fun setupListeners() {
        val app = KouZiApplication.instance

        binding.spDicts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in dictList.indices) {
                    val selected = dictList[position]
                    app.appConfig.selectedDictId = selected.id
                    app.saveConfig()
                    binding.tvDictDetail.text = "总计: ${selected.lineCount} 句"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnToggleFloat.setOnClickListener {
            if (checkOverlayPermission()) {
                toggleFloatService()
            } else {
                requestOverlayPermission()
            }
        }

        binding.btnTestConn.setOnClickListener {
            saveUiConfig()
            testNapCatConnection()
        }

        binding.btnImportDict.setOnClickListener {
            showImportOptionsDialog()
        }

        binding.btnPreviewDict.setOnClickListener {
            previewCurrentDict()
        }

        binding.btnDeleteDict.setOnClickListener {
            deleteCurrentDict()
        }

        binding.btnClearLog.setOnClickListener {
            LogManager.clear()
            binding.tvLogs.text = ""
        }

        binding.rgSendMode.setOnCheckedChangeListener { _, checkedId ->
            app.appConfig.sendMode = when (checkedId) {
                R.id.rbRandomNoRepeat -> 1
                R.id.rbRandom -> 2
                else -> 0
            }
            app.saveConfig()
        }
    }

    private fun saveUiConfig() {
        val app = KouZiApplication.instance
        val config = app.appConfig
        config.napcatHttpHost = binding.etHost.text.toString().trim()
        config.napcatHttpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 3000
        config.sendCountLimit = binding.etCountLimit.text.toString().toIntOrNull() ?: 0
        app.saveConfig()
    }

    private fun testNapCatConnection() {
        binding.tvNapCatStatus.text = "正在检测 NapCat 连接..."
        binding.tvNapCatStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_gray))

        lifecycleScope.launch {
            val app = KouZiApplication.instance
            val res = app.oneBotClient.getLoginInfo()
            if (res.isSuccess) {
                val (userId, nickname) = res.getOrThrow()
                binding.tvNapCatStatus.text = "🟢 NapCat 已连接！QQ: $nickname ($userId)"
                binding.tvNapCatStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.primary))
                LogManager.s("NapCat 接口连接成功！当前挂机账号: $nickname ($userId)")
            } else {
                val err = res.exceptionOrNull()?.message ?: "连接失败"
                binding.tvNapCatStatus.text = "🔴 NapCat 连接失败: $err"
                binding.tvNapCatStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.danger))
                LogManager.w("NapCat 接口连接失败，请确认虚拟机中 NapCat 是否开启 3000 端口")
            }
        }
    }

    private fun showImportOptionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("导入 TXT 词库")
            .setMessage("是否对词库进行自动去重？\n(自动忽略空行，支持 UTF-8 / GBK 编码)")
            .setPositiveButton("自动去重导入") { _, _ ->
                selectFileLauncher.launch("text/plain")
            }
            .setNeutralButton("原样完整导入") { _, _ ->
                selectFileLauncher.launch("text/plain")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleImportDict(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "新导入词库.txt"
                val app = KouZiApplication.instance
                val res = app.dictManager.importDictFromStream(inputStream, fileName)
                if (res.isSuccess) {
                    val info = res.getOrThrow()
                    Toast.makeText(this, "成功导入【${info.name}】(${info.lineCount}句)", Toast.LENGTH_SHORT).show()
                    refreshDictList()
                } else {
                    Toast.makeText(this, "导入失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun previewCurrentDict() {
        val app = KouZiApplication.instance
        val config = app.appConfig
        val lines = app.dictManager.loadLines(config.selectedDictId)
        if (lines.isEmpty()) {
            Toast.makeText(this, "当前词库无内容", Toast.LENGTH_SHORT).show()
            return
        }

        val previewText = lines.take(20).joinToString("\n") + if (lines.size > 20) "\n\n... (共 ${lines.size} 句)" else ""
        AlertDialog.Builder(this)
            .setTitle("词库预览")
            .setMessage(previewText)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun deleteCurrentDict() {
        val app = KouZiApplication.instance
        val config = app.appConfig
        val dict = app.dictManager.getDictInfo(config.selectedDictId) ?: return

        AlertDialog.Builder(this)
            .setTitle("删除词库")
            .setMessage("确定要删除词库【${dict.name}】吗？")
            .setPositiveButton("删除") { _, _ ->
                app.dictManager.deleteDict(dict.id)
                refreshDictList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupLogListener() {
        LogManager.addListener { entry ->
            runOnUiThread {
                binding.tvLogs.append("[${entry.timeString}] ${entry.message}\n")
                val scrollAmount = binding.tvLogs.layout?.getLineTop(binding.tvLogs.lineCount) ?: 0
                if (scrollAmount > binding.tvLogs.height) {
                    binding.tvLogs.scrollTo(0, scrollAmount - binding.tvLogs.height)
                }
            }
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "请授予悬浮窗权限以正常开启悬浮球！", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleFloatService() {
        saveUiConfig()
        val intent = Intent(this, FloatWindowService::class.java)
        if (!isServiceRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isServiceRunning = true
            binding.btnToggleFloat.text = "关闭悬浮窗"
            binding.btnToggleFloat.setBackgroundResource(R.drawable.bg_btn_danger)
            Toast.makeText(this, "扣字悬浮球已在屏幕出现！", Toast.LENGTH_SHORT).show()
        } else {
            stopService(intent)
            isServiceRunning = false
            binding.btnToggleFloat.text = "开启悬浮窗"
            binding.btnToggleFloat.setBackgroundResource(R.drawable.bg_btn_primary)
        }
    }
}
