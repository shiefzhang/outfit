package com.outfitai.app.ui.scene

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.api.Prompts
import com.outfitai.app.api.ZhipuApiService
import com.outfitai.app.data.HistoryManager
import com.outfitai.app.data.OutfitRecord
import com.outfitai.app.databinding.FragmentSceneBinding
import com.outfitai.app.ui.settings.SettingsActivity
import io.noties.markwon.Markwon

class SceneFragment : Fragment() {

    private var _binding: FragmentSceneBinding? = null
    private val binding get() = _binding!!

    private lateinit var markwon: Markwon
    private var currentAiContent: String = ""
    private var currentSceneText: String = ""
    private var currentVisualUrl: String = ""

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

        // 收集选中的风格偏好（遍历所有分组 ChipGroup）
        val stylePrefs = mutableListOf<String>()
        val chipGroups = listOf(
            binding.chipGroupDaily,
            binding.chipGroupGentle,
            binding.chipGroupWork,
            binding.chipGroupCool
        )
        for (group in chipGroups) {
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                if (chip.isChecked) {
                    stylePrefs.add(chip.text.toString())
                }
            }
        }

        // 隐藏键盘
        hideKeyboard()

        currentSceneText = scene

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
        currentAiContent = content

        // 将 "场景解读" 替换为用户输入的场景文字
        val sceneText = if (currentSceneText.isNotBlank()) currentSceneText else "场景解读"
        val modifiedContent = content.replace("## 🌟 场景解读", "## 📝 场景：${sceneText}")

        binding.cardResult.visibility = View.VISIBLE
        markwon.setMarkdown(binding.tvResult, modifiedContent)

        // 设置保存按钮
        binding.btnSave.setOnClickListener { saveRecord() }
        // 设置效果图生成按钮
        binding.btnVisualize.setOnClickListener { generateVisual() }
        binding.btnVisualize.isEnabled = true
        binding.btnVisualize.text = "🎨 生成效果图"
        binding.cardVisual.visibility = View.GONE
        currentVisualUrl = ""

        // 滚动到结果
        binding.root.post {
            (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.cardResult.top)
        }
    }

    private fun saveRecord() {
        if (currentAiContent.isBlank()) return

        // 保存时也替换"场景解读"为用户输入的文字
        val sceneText = if (currentSceneText.isNotBlank()) currentSceneText else "场景解读"
        val savedContent = currentAiContent.replace("## 🌟 场景解读", "## 📝 场景：${sceneText}")

        val record = OutfitRecord(
            type = "scene",
            typeName = "场景建议",
            userInput = "场景：$currentSceneText",
            aiContent = savedContent,
            imagePath = currentVisualUrl,
            thumbPath = if (currentVisualUrl.isNotBlank()) {
                HistoryManager.saveThumbnailFromUrl(requireContext(), currentVisualUrl)
            } else ""
        )
        HistoryManager.addRecord(requireContext(), record)
        Toast.makeText(requireContext(), "✅ 已保存到穿搭历史", Toast.LENGTH_SHORT).show()
    }

    private fun generateVisual() {
        val apiKey = ApiKeyManager.getApiKey(requireContext())
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        val sceneText = if (currentSceneText.isNotBlank()) currentSceneText else "场景解读"

        // 显示加载状态
        binding.cardVisual.visibility = View.VISIBLE
        binding.progressVisual.visibility = View.VISIBLE
        binding.ivVisual.visibility = View.GONE
        binding.btnVisualize.isEnabled = false
        binding.btnVisualize.text = "生成中…"

        // Step 1: 用 AI 生成详细的英文图像描述
        val userPrompt = "以下是一个穿搭场景描述和AI给出的穿搭建议。请基于此生成一段英文图像描述，用于AI绘图工具生成效果图。要求描述人物的整体穿搭风格、服装款式、颜色、配饰等细节。" +
                "\n\n场景：$sceneText" +
                "\n\nAI穿搭建议：\n$currentAiContent" +
                "\n\n请用英文输出，100-200词，包含场景、人物穿搭细节、整体风格、摄影风格描述。"

        ZhipuApiService.chatText(
            apiKey = apiKey,
            systemPrompt = "",
            userText = userPrompt
        ) { result1 ->
            if (!isAdded) return@chatText

            result1.onSuccess { imagePrompt ->
                Log.d("Visualize", "✅ 图像描述生成成功，长度=${imagePrompt.length}")

                // Step 2: 调用 CogView 生成图片
                ZhipuApiService.generateImage(apiKey, imagePrompt) { result2 ->
                    if (!isAdded) return@generateImage

                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread

                        binding.progressVisual.visibility = View.GONE
                        binding.btnVisualize.isEnabled = true
                        binding.btnVisualize.text = "🎨 重新生成"

                        result2.onSuccess { imageUrl ->
                            currentVisualUrl = imageUrl
                            binding.ivVisual.visibility = View.VISIBLE
                            com.bumptech.glide.Glide.with(binding.ivVisual.context)
                                .load(imageUrl)
                                .placeholder(android.R.color.transparent)
                                .error(android.R.color.transparent)
                                .into(binding.ivVisual)
                        }.onFailure { error ->
                            val errorMsg = formatApiErrorMessage(error.message ?: "未知错误")
                            Toast.makeText(requireContext(), "效果图生成失败：$errorMsg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.onFailure { error ->
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progressVisual.visibility = View.GONE
                    binding.btnVisualize.isEnabled = true
                    binding.btnVisualize.text = "🎨 生成效果图"
                    val errorMsg = formatApiErrorMessage(error.message ?: "未知错误")
                    Toast.makeText(requireContext(), "分析失败：$errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnGetSuggestion.isEnabled = !loading
        binding.btnGetSuggestion.text = if (loading) "AI 思考中…" else "✨ 获取穿搭建议"
        if (loading) {
            binding.cardVisual.visibility = View.GONE
        }
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
