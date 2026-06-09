package com.outfitai.app.ui.scene

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.api.Prompts
import com.outfitai.app.api.ZhipuApiService
import com.outfitai.app.databinding.FragmentSceneBinding
import com.outfitai.app.ui.settings.SettingsActivity
import io.noties.markwon.Markwon

class SceneFragment : Fragment() {

    private var _binding: FragmentSceneBinding? = null
    private val binding get() = _binding!!

    private lateinit var markwon: Markwon

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSceneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        markwon = Markwon.builder(requireContext())
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(requireContext()))
            .build()

        binding.btnGetSuggestion.setOnClickListener { getSuggestion() }
    }

    private fun getSuggestion() {
        val scene = binding.etScene.text?.toString()?.trim() ?: ""
        if (scene.isEmpty()) {
            binding.tilScene.error = "请描述您的场景需求"
            return
        }
        binding.tilScene.error = null

        val apiKey = ApiKeyManager.getApiKey(requireContext())
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        // 收集选中的风格偏好
        val stylePrefs = mutableListOf<String>()
        if (binding.chipCasual.isChecked) stylePrefs.add("休闲")
        if (binding.chipFormal.isChecked) stylePrefs.add("正式商务")
        if (binding.chipSweet.isChecked) stylePrefs.add("甜美可爱")
        if (binding.chipCool.isChecked) stylePrefs.add("酷帅街头")
        if (binding.chipElegant.isChecked) stylePrefs.add("优雅简约")
        if (binding.chipSporty.isChecked) stylePrefs.add("运动活力")

        // 隐藏键盘
        hideKeyboard()

        setLoading(true)

        val userMessage = Prompts.buildSceneUserMessage(scene, stylePrefs)

        ZhipuApiService.chatText(
            apiKey = apiKey,
            systemPrompt = Prompts.SCENE_SYSTEM,
            userText = userMessage
        ) { result ->
            // ⚠️ 防闪退：检查 Fragment 是否存活
            if (!isAdded || view == null) return@chatText

            requireActivity().runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                setLoading(false)
                result.onSuccess { content ->
                    showResult(content)
                }.onFailure { error ->
                    val errorMsg = formatApiErrorMessage(error.message ?: "未知错误")
                    Toast.makeText(requireContext(), "获取建议失败：$errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showResult(content: String) {
        binding.cardResult.visibility = View.VISIBLE
        markwon.setMarkdown(binding.tvResult, content)

        // 滚动到结果
        binding.root.post {
            (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.cardResult.top)
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnGetSuggestion.isEnabled = !loading
        binding.btnGetSuggestion.text = if (loading) "AI 思考中…" else "✨ 获取穿搭建议"
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etScene.windowToken, 0)
    }

    private fun showApiKeyDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("需要 API Key")
            .setMessage("请先在设置中配置您的智谱 API Key，才能使用 AI 功能。")
            .setPositiveButton("前往设置") { _, _ ->
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 将 API 返回的错误信息格式化为用户友好提示
     * - 智谱返回的中文错误消息（如 429/1305）已足够友好，直接展示
     * - 英文技术错误翻译为中文
     */
    private fun formatApiErrorMessage(rawMessage: String): String {
        val lower = rawMessage.lowercase()
        return when {
            // 中文消息（智谱已友好化）直接展示
            rawMessage.contains("该模型") ||
                rawMessage.contains("访问量") ||
                rawMessage.contains("稍后再试") ||
                rawMessage.contains("余额") ||
                rawMessage.contains("额度") -> rawMessage

            // 英文技术错误翻译
            lower.contains("invalid") || lower.contains("unauthorized") || lower.contains("401") -> "API Key 无效或已过期，请检查设置"
            lower.contains("timeout") || lower.contains("timed out") -> "请求超时，请检查网络后重试"
            lower.contains("network") || lower.contains("connect") ||
                lower.contains("failed to connect") -> "网络连接失败，请检查网络"

            // 兜底
            else -> rawMessage
        }
    }
}
