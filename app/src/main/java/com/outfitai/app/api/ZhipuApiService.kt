package com.outfitai.app.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 智谱 GLM-4V 大模型 API 调用服务
 * 文档：https://open.bigmodel.cn/dev/api/vision-model/glm-4v
 */
object ZhipuApiService {

    private const val TAG = "ZhipuAPI"
    private const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val IMAGE_GEN_URL = "https://open.bigmodel.cn/api/paas/v4/images/generations"
    private const val MODEL_TEXT = "glm-4-flash"        // 纯文本请求使用 Flash

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------- 数据类 ----------

    data class ChatRequest(
        val model: String,
        val messages: List<Map<String, Any>>,
        val temperature: Double = 0.7,
        val max_tokens: Int = 2048
    )

    data class ChatResponse(
        val choices: List<Choice>?,
        val error: ApiError?
    )

    data class Choice(
        val message: MessageContent
    )

    data class MessageContent(
        val content: String
    )

    data class ApiError(
        val message: String,
        val code: String?
    )

    // ---------- 图片生成 ----------
    data class ImageGenResponse(
        val data: List<ImageData>?,
        val error: ApiError?
    )
    data class ImageData(
        val url: String?
    )

    // ---------- 核心调用 ----------

    /**
     * 带图片的请求（用于功能1 穿搭评估 / 功能3 配饰建议）
     * @param visionModel 视觉模型ID，由调用方从 ApiKeyManager 获取
     *
     * ⚠️ 注意：GLM-4V-Flash 不支持 system role，系统提示词需拼入 user message 的 text 中
     */
    fun chatWithImage(
        apiKey: String,
        systemPrompt: String,
        userText: String,
        bitmap: Bitmap,
        visionModel: String = ApiKeyManager.DEFAULT_VISION_MODEL,
        callback: (Result<String>) -> Unit
    ) {
        val base64Image = bitmapToBase64(bitmap)

        // GLM-4V-Flash 不支持 system role，将 systemPrompt 拼到 userText 前面
        val combinedText = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userText" else userText

        val contentParts = listOf(
            mapOf("type" to "image_url", "image_url" to mapOf("url" to base64Image)),
            mapOf("type" to "text", "text" to combinedText)
        )

        val messages = listOf(
            mapOf("role" to "user", "content" to contentParts)
        )

        val requestBody = mapOf(
            "model" to visionModel,
            "messages" to messages,
            "temperature" to 0.7,
            "max_tokens" to 2048
        )

        executeRequest(apiKey, requestBody, callback)
    }

    /**
     * 纯文本请求（用于功能2 场景穿搭建议）
     */
    fun chatText(
        apiKey: String,
        systemPrompt: String,
        userText: String,
        callback: (Result<String>) -> Unit
    ) {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userText)
        )

        val requestBody = mapOf(
            "model" to MODEL_TEXT,
            "messages" to messages,
            "temperature" to 0.8,
            "max_tokens" to 2048
        )

        executeRequest(apiKey, requestBody, callback)
    }

    /**
     * 图片生成（CogView）
     * @param prompt 英文图像描述
     * @param callback 返回生成的图片 URL
     */
    fun generateImage(
        apiKey: String,
        prompt: String,
        callback: (Result<String>) -> Unit
    ) {
        val requestBody = mapOf(
            "model" to "cogview-3-flash",
            "prompt" to prompt
        )
        val jsonBody = gson.toJson(requestBody)
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        Log.d(TAG, ">>> 图片生成 prompt 长度=${prompt.length}")

        val request = Request.Builder()
            .url(IMAGE_GEN_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ 图片生成网络请求失败: ${e.message}")
                callback(Result.failure(Exception("图片生成请求失败：${e.message}")))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<<< 图片生成 HTTP ${response.code}")

                if (!response.isSuccessful) {
                    try {
                        val errResp = gson.fromJson(responseBody, Map::class.java)
                        val err = errResp["error"] as? Map<*, *>
                        val msg = err?.get("message") as? String ?: "图片生成失败 (${response.code})"
                        callback(Result.failure(Exception(msg)))
                    } catch (e: Exception) {
                        callback(Result.failure(Exception("图片生成失败 (${response.code})")))
                    }
                    return
                }

                try {
                    val imgResp = gson.fromJson(responseBody, ImageGenResponse::class.java)
                    val url = imgResp.data?.firstOrNull()?.url
                    if (url != null) {
                        Log.d(TAG, "✅ 图片生成成功，URL长度=${url.length}")
                        callback(Result.success(url))
                    } else {
                        callback(Result.failure(Exception("图片生成返回数据为空")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析图片生成响应失败: ${e.message}")
                    callback(Result.failure(Exception("解析图片生成响应失败：${e.message}")))
                }
            }
        })
    }

    // ---------- 私有方法 ----------

    private fun executeRequest(
        apiKey: String,
        requestBodyMap: Map<String, Any>,
        callback: (Result<String>) -> Unit
    ) {
        val jsonBody = gson.toJson(requestBodyMap)
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        // Debug：打印请求模型和 Key 前缀
        val modelName = requestBodyMap["model"] as? String ?: "?"
        val keyPreview = if (apiKey.length > 8) apiKey.take(8) + "****" else "****"
        Log.d(TAG, ">>> 请求 model=$modelName, key=$keyPreview")

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ 网络请求失败: ${e.message}")
                callback(Result.failure(Exception("网络请求失败：${e.message}")))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<<< HTTP ${response.code}")

                if (!response.isSuccessful) {
                    // Debug：打印原始错误响应
                    Log.e(TAG, "❌ 错误响应(${response.code}): $responseBody")
                    // 尝试解析错误信息
                    try {
                        val errorResponse = gson.fromJson(responseBody, Map::class.java)
                        val error = errorResponse["error"] as? Map<*, *>
                        val msg = error?.get("message") as? String ?: "请求失败 (${response.code})"
                        callback(Result.failure(Exception(msg)))
                    } catch (e: Exception) {
                        callback(Result.failure(Exception("请求失败 (${response.code})")))
                    }
                    return
                }

                // Debug：打印成功响应摘要
                Log.d(TAG, "✅ 响应成功，body长度=${responseBody.length}")

                try {
                    val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                    val content = chatResponse.choices?.firstOrNull()?.message?.content
                    if (content != null) {
                        Log.d(TAG, "📝 内容长度=${content.length}")
                        callback(Result.success(content))
                    } else {
                        Log.w(TAG, "⚠️ 响应中没有 content")
                        callback(Result.failure(Exception("AI 返回内容为空")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析响应失败: ${e.message}")
                    callback(Result.failure(Exception("解析响应失败：${e.message}")))
                }
            }
        })
    }

    /**
     * Bitmap 转 Base64 字符串（带智能压缩）
     *
     * 压缩策略：
     * 1. 缩放：长边限制 800px（穿搭分析够用）
     * 2. JPEG 质量从 75 开始，逐步降低直到 ≤ 500KB
     * 3. 最终兜底质量 40，确保不会太大
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Step 1: 缩放 —— 长边限制 800px
        val maxDimension = 800
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // Step 2: 渐进式 JPEG 压缩 —— 目标 ≤ 500KB
        val targetSizeBytes = 500 * 1024
        var quality = 75

        // 先尝试质量 75
        var result = compressToJpeg(scaledBitmap, quality)
        if (result.size <= targetSizeBytes) {
            return Base64.encodeToString(result, Base64.NO_WRAP)
        }

        // 超了就逐步降质量：60 → 50 → 40
        for (q in intArrayOf(60, 50, 40)) {
            quality = q
            result = compressToJpeg(scaledBitmap, quality)
            if (result.size <= targetSizeBytes) {
                return Base64.encodeToString(result, Base64.NO_WRAP)
            }
        }

        // 兜底：质量 40 的结果直接返回
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    /**
     * 将 Bitmap 按指定 JPEG 质量编码为字节数组
     */
    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }
}
