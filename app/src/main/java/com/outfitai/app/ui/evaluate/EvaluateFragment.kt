package com.outfitai.app.ui.evaluate

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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.outfitai.app.PhotoHelper
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.api.Prompts
import com.outfitai.app.api.ZhipuApiService
import com.outfitai.app.databinding.FragmentEvaluateBinding
import com.outfitai.app.ui.settings.SettingsActivity
import io.noties.markwon.Markwon

class EvaluateFragment : Fragment() {

    private var _binding: FragmentEvaluateBinding? = null
    private val binding get() = _binding!!

    private var currentBitmap: Bitmap? = null
    private var photoUri: Uri? = null
    private lateinit var markwon: Markwon

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
                val bitmap = PhotoHelper.loadBitmapFromUri(requireContext(), uri)
                if (bitmap != null) {
                    setImage(bitmap)
                }
            }
        }
    }

    // 相册选择结果
    private val selectPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val bitmap = PhotoHelper.loadBitmapFromUri(requireContext(), uri)
                if (bitmap != null) {
                    setImage(bitmap)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEvaluateBinding.inflate(inflater, container, false)
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
        binding.btnAnalyze.setOnClickListener { analyzeOutfit() }
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
        binding.cardResult.visibility = View.GONE
    }

    private fun analyzeOutfit() {
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
            systemPrompt = Prompts.EVALUATE_SYSTEM,
            userText = Prompts.EVALUATE_USER,
            bitmap = bitmap,
            visionModel = com.outfitai.app.api.ApiKeyManager.getVisionModel(requireContext())
        ) { result ->
            // ⚠️ 关键：必须检查 Fragment 是否还存活，防止异步回调返回时已销毁导致闪退
            if (!isAdded || view == null) return@chatWithImage

            requireActivity().runOnUiThread {
                // 再次检查，runOnUiThread 延迟期间可能又销毁了
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
        binding.cardResult.visibility = View.VISIBLE
        markwon.setMarkdown(binding.tvResult, content)

        // 滚动到结果区域
        binding.root.post {
            (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.cardResult.top)
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !loading
        binding.btnTakePhoto.isEnabled = !loading
        binding.btnSelectPhoto.isEnabled = !loading
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
