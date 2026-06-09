package com.outfitai.app.ui.detail

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.outfitai.app.PhotoHelper
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.api.Prompts
import com.outfitai.app.api.ZhipuApiService
import com.outfitai.app.databinding.FragmentDetailBinding
import com.outfitai.app.ui.settings.SettingsActivity

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private var currentBitmap: Bitmap? = null
    private var photoUri: Uri? = null
    private val gson = Gson()

    // 相机权限申请
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    // 拍照结果
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            photoUri?.let { uri ->
                PhotoHelper.loadBitmapFromUri(requireContext(), uri)?.let { setImage(it) }
            }
        }
    }

    // 相册选择结果
    private val selectPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                PhotoHelper.loadBitmapFromUri(requireContext(), uri)?.let { setImage(it) }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnTakePhoto.setOnClickListener { checkCameraAndLaunch() }
        binding.btnSelectPhoto.setOnClickListener { launchGallery() }
        binding.btnAnalyze.setOnClickListener { analyzeDetail() }
    }

    private fun checkCameraAndLaunch() {
        when {
            PhotoHelper.hasCameraPermission(requireContext()) -> launchCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(requireContext(), "请授予相机权限以拍照", Toast.LENGTH_LONG).show()
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val pair = PhotoHelper.createTakePhotoIntent(requireContext())
        if (pair != null) {
            photoUri = pair.second
            takePictureLauncher.launch(pair.first)
        }
    }

    private fun launchGallery() {
        selectPhotoLauncher.launch(PhotoHelper.createSelectPhotoIntent())
    }

    private fun setImage(bitmap: Bitmap) {
        currentBitmap = bitmap
        binding.ivOutfit.setImageBitmap(bitmap)
        binding.tvImageHint.visibility = View.GONE
        binding.layoutResults.visibility = View.GONE
    }

    private fun analyzeDetail() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(requireContext(), "请先选择或拍摄穿搭照片", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = ApiKeyManager.getApiKey(requireContext())
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        setLoading(true)

        ZhipuApiService.chatWithImage(
            apiKey = apiKey,
            systemPrompt = Prompts.DETAIL_SYSTEM,
            userText = Prompts.DETAIL_USER,
            bitmap = bitmap,
            visionModel = com.outfitai.app.api.ApiKeyManager.getVisionModel(requireContext())
        ) { result ->
            // ⚠️ 防闪退：检查 Fragment 是否存活
            if (!isAdded || view == null) return@chatWithImage

            requireActivity().runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                setLoading(false)
                result.onSuccess { content ->
                    parseAndShowResult(content)
                }.onFailure { error ->
                    val errorMsg = formatApiErrorMessage(error.message ?: "未知错误")
                    Toast.makeText(requireContext(), "分析失败：$errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 解析 AI 返回的内容，拆分成三个区域展示
     * 支持多种格式：标准 JSON、带换行符的 JSON、或按关键词分段
     */
    private fun parseAndShowResult(content: String) {
        // 先尝试 JSON 解析
        val jsonResult = tryParseJson(content)
        if (jsonResult != null) {
            binding.tvJewelry.text = jsonResult["jewelry"]
            binding.tvShoes.text = jsonResult["shoes"]
            binding.tvHairstyle.text = jsonResult["hairstyle"]
            binding.layoutResults.visibility = View.VISIBLE

            binding.root.post {
                (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.layoutResults.top)
            }
            return
        }

        // JSON 失败，尝试分段提取
        val sections = extractSections(content)
        if (sections != null) {
            binding.tvJewelry.text = sections["jewelry"]
            binding.tvShoes.text = sections["shoes"]
            binding.tvHairstyle.text = sections["hairstyle"]
            binding.layoutResults.visibility = View.VISIBLE

            binding.root.post {
                (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.layoutResults.top)
            }
            return
        }

        // 全部失败：把内容放到首饰区，其他给提示
        binding.tvJewelry.text = content
        binding.tvShoes.text = "（请查看上方完整建议）"
        binding.tvHairstyle.text = "（请查看上方完整建议）"
        binding.layoutResults.visibility = View.VISIBLE
    }

    /**
     * 尝试解析 JSON，包括修复常见格式问题
     */
    private fun tryParseJson(content: String): Map<String, String>? {
        val jsonStr = extractJsonBlock(content) ?: return null

        // 尝试修复未转义的换行符（JSON 字符串值中的实际换行）
        val fixedJson = fixUnescapedNewlines(jsonStr)

        return try {
            val jsonObj = gson.fromJson(fixedJson, JsonObject::class.java)
            val jewelry = jsonObj.get("jewelry")?.asString
            val shoes = jsonObj.get("shoes")?.asString
            val hairstyle = jsonObj.get("hairstyle")?.asString

            if (jewelry != null) {
                mapOf(
                    "jewelry" to jewelry,
                    "shoes" to (shoes ?: ""),
                    "hairstyle" to (hairstyle ?: "")
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 提取 JSON 代码块（```json ... ```）或花括号范围
     */
    private fun extractJsonBlock(content: String): String? {
        // 先尝试 ```json ... ``` 块
        val jsonBlockRegex = Regex("```json\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val match = jsonBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 再尝试花括号范围
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return content.substring(start, end + 1)
        }

        return null
    }

    /**
     * 修复 JSON 字符串值中未转义的换行符
     * 例如："jewelry": "文本1
     * 文本2"  →  "jewelry": "文本1\n文本2"
     */
    private fun fixUnescapedNewlines(json: String): String {
        val sb = StringBuilder()
        var inString = false
        var escape = false

        for (ch in json) {
            if (escape) {
                sb.append(ch)
                escape = false
                continue
            }
            if (ch == '\\' && inString) {
                sb.append(ch)
                escape = true
                continue
            }
            if (ch == '"' && !escape) {
                inString = !inString
                sb.append(ch)
                continue
            }
            if (inString && (ch == '\n' || ch == '\r')) {
                // 字符串内的换行替换为 \n
                sb.append("\\n")
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * 当 JSON 解析失败时，通过关键词分段提取
     * 匹配 **jewelry** / **shoes** / **hairstyle** 等标题后的内容
     */
    private fun extractSections(content: String): Map<String, String>? {
        // 匹配 Markdown 标题或 JSON 键模式，提取三段内容
        val patterns = mapOf(
            "jewelry" to Regex(
                "(?:\\*\\*jewelry(?:（首饰）)?\\*\\*|首饰|jewelry)[：:\\s]*(.*?)(?=\\n\\s*\\*\\*(?:shoes|鞋子|hairstyle|发型)|\\n\\s*(?:鞋子|shoes|发型|hairstyle)\\b)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ),
            "shoes" to Regex(
                "(?:\\*\\*shoes(?:（鞋子）)?\\*\\*|鞋子|shoes)[：:\\s]*(.*?)(?=\\n\\s*\\*\\*(?:hairstyle|发型|jewelry|首饰)|\\n\\s*(?:发型|hairstyle|首饰|jewelry)\\b)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ),
            "hairstyle" to Regex(
                "(?:\\*\\*hairstyle(?:（发型）)?\\*\\*|发型|hairstyle)[：:\\s]*(.*?)(?=\\n\\s*\\*\\*(?:jewelry|首饰|shoes|鞋子)|\\n\\s*(?:首饰|jewelry|鞋子|shoes)\\b|\$)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
        )

        val result = mutableMapOf<String, String>()
        for ((key, regex) in patterns) {
            val match = regex.find(content)
            if (match != null) {
                result[key] = match.groupValues[1].trim()
            }
        }

        return if (result.size >= 2) result else null // 至少匹配到2段才认为有效
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !loading
        binding.btnTakePhoto.isEnabled = !loading
        binding.btnSelectPhoto.isEnabled = !loading
        binding.btnAnalyze.text = if (loading) "AI 分析中…" else "💎 获取配饰建议"
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
