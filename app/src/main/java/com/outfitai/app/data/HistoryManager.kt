package com.outfitai.app.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 穿搭记录数据类
 */
data class OutfitRecord(
    val id: Long = System.currentTimeMillis(),
    val type: String,           // "evaluate" | "scene" | "detail"
    val typeName: String,       // 中文类型名
    val userInput: String,      // 用户输入/场景描述/选中分类
    val aiContent: String,      // AI 返回的建议内容
    val imagePath: String = "", // 原始图片路径（仅 evaluate/detail 有）
    val thumbPath: String = "", // 缩略图路径（保存时预先生成）
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(timestamp))
        }

    val summary: String
        get() {
            val text = aiContent ?: return ""
            return if (text.length > 80) text.take(80) + "…" else text
        }
}

/**
 * 穿搭历史管理器（SharedPreferences + JSON 存储）
 */
object HistoryManager {

    private const val PREF_NAME = "outfit_history"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 200

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getRecords(context: Context): List<OutfitRecord> {
        val json = getPrefs(context).getString(KEY_RECORDS, "[]") ?: "[]"
        val type = object : TypeToken<List<OutfitRecord>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecord(context: Context, record: OutfitRecord) {
        val records = getRecords(context).toMutableList()
        records.add(0, record) // 新记录插到最前面
        // 限制最大条数
        val trimmed = if (records.size > MAX_RECORDS) records.take(MAX_RECORDS) else records
        val json = gson.toJson(trimmed)
        getPrefs(context).edit().putString(KEY_RECORDS, json).apply()
    }

    /**
     * 从图片文件路径生成 120px 长边缩略图并保存到缓存目录
     * 内部已做全面异常处理，不会抛出任何异常
     * @return 缩略图文件路径，失败时返回空字符串
     */
    fun saveThumbnail(context: Context, imagePath: String): String {
        return try {
            if (imagePath.isBlank()) return ""

            val original = try {
                BitmapFactory.decodeFile(imagePath)
            } catch (e: Exception) {
                null
            }
            if (original == null) return ""

            // 缩放到 120px 长边
            val size = 120
            val scale = size.toFloat() / maxOf(original.width, original.height).coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), true)

            if (original !== thumb) original.recycle()

            // 保存到缓存目录
            val cacheDir = File(context.cacheDir, "thumbnails")
            cacheDir.mkdirs()
            val thumbFile = File(cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(thumbFile).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            thumb.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 将拍照的临时图片复制到应用私有目录（永久存储）
     * 先尝试二进制复制，失败则通过 Bitmap 方式转存
     * @return 永久文件路径，失败时返回空字符串
     */
    fun savePhotoPermanent(context: Context, imageUri: Uri): String {
        val photosDir = File(context.filesDir, "history_photos")
        photosDir.mkdirs()
        val targetFile = File(photosDir, "photo_${System.currentTimeMillis()}.jpg")

        // 方式一：直接二进制复制
        try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
                if (targetFile.exists() && targetFile.length() > 0) {
                    return targetFile.absolutePath
                }
            }
        } catch (_: Exception) {}

        // 方式二：通过 Bitmap 转存（兼容更多 URI 类型）
        return try {
            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return ""
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return ""

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()

            if (targetFile.exists() && targetFile.length() > 0) {
                targetFile.absolutePath
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun deleteRecord(context: Context, recordId: Long) {
        val records = getRecords(context).toMutableList()
        records.removeAll { it.id == recordId }
        val json = gson.toJson(records)
        getPrefs(context).edit().putString(KEY_RECORDS, json).apply()
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_RECORDS).apply()
    }
}
