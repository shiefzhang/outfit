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
import com.outfitai.app.PhotoHelper
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.api.Prompts
import com.outfitai.app.api.ZhipuApiService
import com.outfitai.app.data.HistoryManager
import com.outfitai.app.data.OutfitRecord
import com.outfitai.app.databinding.FragmentDetailBinding
import com.outfitai.app.ui.settings.SettingsActivity
import io.noties.markwon.Markwon

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private var currentBitmap: Bitmap? = null
    private var photoUri: Uri? = null
    private var currentAiContent: String = ""
    private lateinit var markwon: Markwon

    // 9 个配饰分类
    private val allCategories = listOf(
        "发型", "化妆", "耳环", "包包", "戒指", "腕部装饰", "项链", "围巾", "鞋子"
    )

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
                photoUri = uri
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

        markwon = Markwon.builder(requireContext())
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(requireContext()))
            .build()

        binding.btnTakePhoto.setOnClickListener { checkCameraAndLaunch() }
        binding.btnSelectPhoto.setOnClickListener { launchGallery() }
        binding.btnAnalyze.setOnClickListener { analyzeDetail() }

        // 默认全选（在布局中设置 checked=true 即可）
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
        binding.cardResult.visibility = View.GONE
    }

    private fun getSelectedCategories(): List<String> {
        val selected = mutableListOf<String>()
        if (binding.chipHair.isChecked) selected.add("发型")
        if (binding.chipMakeup.isChecked) selected.add("化妆")
        if (binding.chipEarrings.isChecked) selected.add("耳环")
        if (binding.chipBag.isChecked) selected.add("包包")
        if (binding.chipRing.isChecked) selected.add("戒指")
        if (binding.chipWrist.isChecked) selected.add("腕部装饰")
        if (binding.chipNecklace.isChecked) selected.add("项链")
        if (binding.chipScarf.isChecked) selected.add("围巾")
        if (binding.chipShoes.isChecked) selected.add("鞋子")
        return selected
    }

    private fun analyzeDetail() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(requireContext(), "请先选择或拍摄穿搭照片", Toast.LENGTH_SHORT).show()
            return
        }

        val categories = getSelectedCategories()
        if (categories.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个需要建议的分类", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = ApiKeyManager.getApiKey(requireContext())
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        setLoading(true)

        // 隐藏旧的结果
        binding.layoutResults.visibility = View.GONE
        binding.cardResult.visibility = View.GONE

        val userText = Prompts.buildDetailUserMessage(categories)

        ZhipuApiService.chatWithImage(
            apiKey = apiKey,
            systemPrompt = Prompts.DETAIL_SYSTEM,
            userText = userText,
            bitmap = bitmap,
            visionModel = com.outfitai.app.api.ApiKeyManager.getVisionModel(requireContext())
        ) { result ->
            // ⚠️ 防闪退：检查 Fragment 是否存活
            if (!isAdded || view == null) return@chatWithImage

            requireActivity().runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                setLoading(false)
                result.onSuccess { content ->
                    showResult(content)
                }.onFailure { error ->
                    val errorMsg = formatApiErrorMessage(error.message ?: "未知错误")
                    Toast.makeText(requireContext(), "分析失败：$errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showResult(content: String) {
        currentAiContent = content
        binding.layoutResults.visibility = View.VISIBLE
        binding.cardResult.visibility = View.VISIBLE
        markwon.setMarkdown(binding.tvResult, content)

        // 设置保存按钮
        binding.btnSave.setOnClickListener { saveRecord() }

        binding.root.post {
            (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.cardResult.top)
        }
    }

    private fun saveRecord() {
        if (currentAiContent.isBlank()) return

        val categories = getSelectedCategories()
        val userInput = "选中分类：${categories.joinToString("、")}"
        val permanentPath = if (photoUri != null) HistoryManager.savePhotoPermanent(requireContext(), photoUri!!) else ""
        val imagePath = if (permanentPath.isNotBlank()) permanentPath else (photoUri?.toString() ?: "")
        val thumbPath = if (imagePath.isNotBlank()) HistoryManager.saveThumbnail(requireContext(), imagePath) else ""

        val record = OutfitRecord(
            type = "detail",
            typeName = "配饰建议",
            userInput = userInput,
            aiContent = currentAiContent,
            imagePath = imagePath,
            thumbPath = thumbPath
        )
        HistoryManager.addRecord(requireContext(), record)
        Toast.makeText(requireContext(), "✅ 已保存到穿搭历史", Toast.LENGTH_SHORT).show()
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !loading
        binding.btnTakePhoto.isEnabled = !loading
        binding.btnSelectPhoto.isEnabled = !loading
        binding.chipGroupAccessories.isEnabled = !loading
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
     */
    private fun formatApiErrorMessage(rawMessage: String): String {
        val lower = rawMessage.lowercase()
        return when {
            rawMessage.contains("该模型") ||
                rawMessage.contains("访问量") ||
                rawMessage.contains("稍后再试") ||
                rawMessage.contains("余额") ||
                rawMessage.contains("额度") -> rawMessage

            lower.contains("invalid") || lower.contains("unauthorized") || lower.contains("401") -> "API Key 无效或已过期，请检查设置"
            lower.contains("timeout") || lower.contains("timed out") -> "请求超时，请检查网络后重试"
            lower.contains("network") || lower.contains("connect") ||
                lower.contains("failed to connect") -> "网络连接失败，请检查网络"

            else -> rawMessage
        }
    }
}
