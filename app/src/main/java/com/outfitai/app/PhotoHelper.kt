package com.outfitai.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * 相机与图片选择相关工具方法
 */
object PhotoHelper {

    const val REQUEST_CAMERA_PERMISSION = 100
    const val REQUEST_TAKE_PHOTO = 101
    const val REQUEST_SELECT_PHOTO = 102

    private var currentPhotoUri: Uri? = null

    /**
     * 检查是否有相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有存储读取权限
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 创建拍照 Intent 并返回用于存储照片的 Uri
     */
    fun createTakePhotoIntent(context: Context): Pair<Intent, Uri>? {
        return try {
            val photoFile = createImageFile(context)
            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            currentPhotoUri = photoUri

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            Pair(intent, photoUri)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 创建选择图片的 Intent
     */
    fun createSelectPhotoIntent(): Intent {
        return Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
    }

    /**
     * 从 Uri 加载 Bitmap（自动处理旋转问题）
     */
    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // 修正图片旋转
            val rotatedBitmap = fixBitmapRotation(context, uri, bitmap)
            rotatedBitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 修正 EXIF 旋转信息
     */
    private fun fixBitmapRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val exif = try {
            ExifInterface(inputStream)
        } catch (e: Exception) {
            inputStream.close()
            return bitmap
        }
        inputStream.close()

        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 创建临时图片文件
     */
    @Throws(IOException::class)
    private fun createImageFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.externalCacheDir ?: context.cacheDir
        return File.createTempFile("OUTFIT_${timeStamp}_", ".jpg", storageDir)
    }
}
