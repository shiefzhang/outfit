package com.outfitai.app.ui.settings

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.outfitai.app.R
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Key 显示状态：false = 脱敏，true = 完整显示
    private var isKeyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置顶部导航栏返回按钮
        binding.toolbarSettings.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupModelSelection()
        refreshKeyStatus()

        binding.btnSave.setOnClickListener { saveApiKey() }
        binding.btnOpenWebsite.setOnClickListener { openZhipuWebsite() }
        binding.tvKeyAction.setOnClickListener { toggleInputCard(true) }
        binding.btnCopyKey.setOnClickListener { copyApiKey() }
        binding.btnDeleteKey.setOnClickListener { confirmDeleteKey() }
        binding.btnToggleKeyVisibility.setOnClickListener { toggleKeyVisibility() }
    }

    // ==================== 模型选择 ====================

    private fun setupModelSelection() {
        val rg = binding.rgVisionModels
        rg.removeAllViews()

        val options = ApiKeyManager.VISION_MODEL_OPTIONS
        val currentModel = ApiKeyManager.getVisionModel(this)

        options.forEachIndexed { index, model ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = buildModelDisplayText(model)
                textSize = 14f
                setTextColor(if (model.isFree) Color.parseColor("#2E7D32") else Color.parseColor("#333333"))
                setPadding(16, 24, 16, 24)
                if (model.modelId == currentModel) isChecked = true
            }
            rg.addView(rb)

            // 分割线（最后一个不加）
            if (index < options.size - 1) {
                View(this).apply {
                    layoutParams = RadioGroup.LayoutParams(
                        RadioGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(16, 0, 16, 0) }
                    setBackgroundColor(Color.parseColor("#E0E0E0"))
                }.also { rg.addView(it) }
            }
        }

        rg.setOnCheckedChangeListener { _, checkedId ->
            val checkedIndex = rg.indexOfChild(rg.findViewById(checkedId))
            if (checkedIndex in options.indices) {
                val selected = options[checkedIndex]
                ApiKeyManager.saveVisionModel(this, selected.modelId)
                binding.tvModelDesc.text = selected.description
                val freeTip = if (selected.isFree) "🆓 当前模型免费" else "⚠️ 当前模型按量计费，请确保账户余额充足"
                Toast.makeText(this, freeTip, Toast.LENGTH_SHORT).show()
            }
        }

        // 初始化描述
        val current = options.find { it.modelId == currentModel }
        binding.tvModelDesc.text = current?.description ?: ""
    }

    private fun buildModelDisplayText(model: ApiKeyManager.VisionModel): String {
        val freeTag = if (model.isFree) "  🆓免费" else ""
        return "${model.displayName}$freeTag\n${model.modelId}"
    }

    // ==================== API Key 状态 ====================

    private fun refreshKeyStatus() {
        val apiKey = ApiKeyManager.getApiKey(this)
        isKeyVisible = false // 每次刷新重置为脱敏模式

        if (apiKey.isBlank()) {
            binding.viewStatusDot.setBackgroundResource(android.R.drawable.presence_invisible)
            binding.tvKeyStatus.text = "未配置 API Key"
            binding.tvKeyStatus.setTextColor(0xFF666666.toInt())
            binding.tvMaskedKey.visibility = View.GONE
            binding.btnToggleKeyVisibility.visibility = View.GONE
            binding.layoutKeyActions.visibility = View.GONE
            binding.tvKeyAction.text = "去配置"
            toggleInputCard(true)
        } else {
            binding.viewStatusDot.setBackgroundResource(android.R.drawable.presence_online)
            binding.tvKeyStatus.text = "API Key 已配置"
            binding.tvKeyStatus.setTextColor(0xFF2E7D32.toInt())
            updateKeyDisplay(apiKey)
            binding.tvMaskedKey.visibility = View.VISIBLE
            binding.btnToggleKeyVisibility.visibility = View.VISIBLE
            binding.layoutKeyActions.visibility = View.VISIBLE
            binding.tvKeyAction.text = "编辑"
            binding.btnToggleKeyVisibility.text = "👁 显示完整 Key"
            toggleInputCard(false)
            binding.etApiKey.setText(apiKey)
        }
    }

    /**
     * 更新 Key 显示内容（脱敏或完整）
     */
    private fun updateKeyDisplay(apiKey: String) {
        if (isKeyVisible) {
            // 完整显示：自动换行，最多3行，中间省略超长部分
            binding.tvMaskedKey.ellipsize = TextUtils.TruncateAt.MIDDLE
            binding.tvMaskedKey.maxLines = 3
            binding.tvMaskedKey.text = apiKey
        } else {
            // 脱敏显示：前8位****后4位
            binding.tvMaskedKey.maxLines = 1
            binding.tvMaskedKey.ellipsize = null
            binding.tvMaskedKey.text = maskApiKey(apiKey)
        }
    }

    /** 切换 Key 显示/隐藏 */
    private fun toggleKeyVisibility() {
        val apiKey = ApiKeyManager.getApiKey(this)
        if (apiKey.isBlank()) return

        isKeyVisible = !isKeyVisible
        if (isKeyVisible) {
            binding.btnToggleKeyVisibility.text = "🙈 隐藏 Key"
        } else {
            binding.btnToggleKeyVisibility.text = "👁 显示完整 Key"
        }
        updateKeyDisplay(apiKey)
    }

    private fun maskApiKey(key: String): String {
        return if (key.length <= 12) "****"
        else key.substring(0, 8) + "****" + key.takeLast(4)
    }

    private fun toggleInputCard(show: Boolean) {
        binding.cardKeyInput.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSave.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun saveApiKey() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isBlank()) {
            binding.tilApiKey.error = "API Key 不能为空"
            return
        }
        binding.tilApiKey.error = null
        ApiKeyManager.saveApiKey(this, apiKey)
        Toast.makeText(this, "✅ API Key 已保存", Toast.LENGTH_SHORT).show()
        refreshKeyStatus()
    }

    private fun copyApiKey() {
        val apiKey = ApiKeyManager.getApiKey(this)
        if (apiKey.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("zhipu_api_key", apiKey))
        Toast.makeText(this, "📋 API Key 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteKey() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除已保存的 API Key 吗？删除后需要重新配置才能使用 AI 功能。")
            .setPositiveButton("删除") { _, _ ->
                ApiKeyManager.saveApiKey(this, "")
                binding.etApiKey.setText("")
                Toast.makeText(this, "🗑️ API Key 已删除", Toast.LENGTH_SHORT).show()
                refreshKeyStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openZhipuWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bigmodel.cn/invite?icode=jh%2FYRDISbzHFHr4LKFV91wZ3c5owLmCCcMQXWcJRS8E%3D")))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
