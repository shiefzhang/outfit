package com.outfitai.app.api

import android.content.Context
import android.content.SharedPreferences

/**
 * 统一管理 API Key 的存储与读取
 */
object ApiKeyManager {

    private const val PREF_NAME = "outfit_ai_prefs"
    private const val KEY_API_KEY = "zhipu_api_key"
    private const val KEY_VISION_MODEL = "zhipu_vision_model"

    // 所有可选视觉模型
    val VISION_MODEL_OPTIONS = listOf(
        VisionModel("glm-4.6v-flash",         "GLM-4.6V-Flash",          true,  "🆓 免费，速度快，适合日常穿搭分析"),
        VisionModel("glm-4v-flash",            "GLM-4V-Flash",            true,  "🆓 免费，经典视觉模型，兼容性好"),
        VisionModel("glm-4.1v-thinking-flash", "GLM-4.1V-Thinking-Flash", true,  "🆓 免费，支持思考链推理，分析更深入"),
        VisionModel("glm-4.6v-flashx",         "GLM-4.6V-FlashX",         false, "轻量高速版，响应快，性价比高"),
        VisionModel("glm-4.6v",                "GLM-4.6V",                false, "高性能版，图像理解能力更强"),
        VisionModel("glm-5v-turbo",            "GLM-5V-Turbo",            false, "最新旗舰视觉模型，效果最佳"),
    )

    data class VisionModel(
        val modelId: String,    // API 调用的 model 字段值
        val displayName: String,  // 显示名称
        val isFree: Boolean,      // 是否免费
        val description: String    // 简要说明
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ========== API Key ==========

    fun saveApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    // ========== 视觉模型选择 ==========

    fun saveVisionModel(context: Context, modelId: String) {
        getPrefs(context).edit().putString(KEY_VISION_MODEL, modelId).apply()
    }

    fun getVisionModel(context: Context): String {
        val saved = getPrefs(context).getString(KEY_VISION_MODEL, "") ?: ""
        return if (VISION_MODEL_OPTIONS.any { it.modelId == saved }) saved else DEFAULT_VISION_MODEL
    }

    fun getVisionModelDisplayName(context: Context): String {
        val modelId = getVisionModel(context)
        return VISION_MODEL_OPTIONS.find { it.modelId == modelId }?.displayName ?: modelId
    }

    fun isCurrentModelFree(context: Context): Boolean {
        val modelId = getVisionModel(context)
        return VISION_MODEL_OPTIONS.find { it.modelId == modelId }?.isFree ?: false
    }

    const val DEFAULT_VISION_MODEL = "glm-4.6v-flash"
}
