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
     * 解析 AI 返回的 JSON，拆分成三个区域展示
     */
    private fun parseAndShowResult(content: String) {
        try {
            // 提取 JSON 代码块（AI 可能包裹在 ```json ... ``` 中）
            val jsonStr = extractJson(content)
            val jsonObj = gson.fromJson(jsonStr, JsonObject::class.java)

            val jewelry = jsonObj.get("jewelry")?.asString ?: content
            val shoes = jsonObj.get("shoes")?.asString ?: ""
            val hairstyle = jsonObj.get("hairstyle")?.asString ?: ""

            binding.tvJewelry.text = jewelry
            binding.tvShoes.text = shoes
            binding.tvHairstyle.text = hairstyle
            binding.layoutResults.visibility = View.VISIBLE

            // 滚动到结果
            binding.root.post {
                (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.layoutResults.top)
            }
        } catch (e: Exception) {
            // JSON 解析失败时，把完整内容展示在首饰区域
            binding.tvJewelry.text = content
            binding.tvShoes.text = "（请查看上方完整建议）"
            binding.tvHairstyle.text = "（请查看上方完整建议）"
            binding.layoutResults.visibility = View.VISIBLE
        }
    }

    /**
     * 从 AI 返回内容中提取 JSON 字符串
     */
    private fun extractJson(content: String): String {
        val jsonBlockRegex = Regex("```json\\s*([\\s\\S]*?)\\s*```")
        val match = jsonBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        // 直接尝试找花括号范围
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return content.substring(start, end + 1)
        }
        return content
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
     * 将 API 返回的技术错误信息转换为用户友好的提示
     */
    private fun formatApiErrorMessage(rawMessage: String): String {
        val lower = rawMessage.lowercase()
        return when {
            lower.contains("invalid") || lower.contains("unauthorized") || lower.contains("401") -> "API Key 无效或已过期，请检查设置"
            lower.contains("quota") || lower.contains("rate") || lower.contains("429") -> "API 调用频率超限，请稍后再试"
            lower.contains("timeout") || lower.contains("timed out") -> "请求超时，请检查网络后重试"
            lower.contains("network") || lower.contains("connect") -> "网络连接失败，请检查网络"
            else -> "服务异常，请重试"
        }
    }
}
