package cn.edu.bjtu.mis.data.exporting

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SavedScheduleExport(
    val uri: Uri,
    val displayName: String,
    val location: String,
)

object ScheduleExportStorage {
    private val FileTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.CHINA)

    fun needsLegacyWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    suspend fun save(
        context: Context,
        document: ScheduleExportDocument,
        format: ScheduleExportFormat,
    ): SavedScheduleExport = withContext(Dispatchers.IO) {
        val displayName = exportFileName(document, format)
        when (format) {
            ScheduleExportFormat.Pdf -> savePdf(context, document, displayName)
            ScheduleExportFormat.Png -> savePng(context, document, displayName)
        }
    }

    fun sharePdf(context: Context, saved: SavedScheduleExport) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ScheduleExportFormat.Pdf.mimeType
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享 PDF").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun savePdf(
        context: Context,
        document: ScheduleExportDocument,
        displayName: String,
    ): SavedScheduleExport =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePdfToMediaStore(context, document, displayName)
        } else {
            savePdfToLegacyDownloads(context, document, displayName)
        }

    private fun savePng(
        context: Context,
        document: ScheduleExportDocument,
        displayName: String,
    ): SavedScheduleExport =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePngToMediaStore(context, document, displayName)
        } else {
            savePngToLegacyPictures(context, document, displayName)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePdfToMediaStore(
        context: Context,
        document: ScheduleExportDocument,
        displayName: String,
    ): SavedScheduleExport {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, ScheduleExportFormat.Pdf.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BJTU MIS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                ScheduleExportRenderer.writePdf(document, output)
            } ?: throw IOException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedScheduleExport(uri, displayName, "Downloads/BJTU MIS/$displayName")
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePngToMediaStore(
        context: Context,
        document: ScheduleExportDocument,
        displayName: String,
    ): SavedScheduleExport {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, ScheduleExportFormat.Png.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建图库文件")
        try {
            val bitmap = ScheduleExportRenderer.renderPng(document)
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(BitmapCompressFormat, 100, output)
            } ?: throw IOException("无法写入图库文件")
            bitmap.recycle()
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedScheduleExport(uri, displayName, "Pictures/$displayName")
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun savePdfToLegacyDownloads(
        context: Context,
        document: ScheduleExportDocument,
        displayName: String,
    ): SavedScheduleExport {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("BJTU MIS")
            .apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        target.outputStream().use { output -> ScheduleExportRenderer.writePdf(document, output) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return SavedScheduleExport(uri, target.name, target.absolutePath)
    }

    @Suppress("DEPRECATION")
    private fun savePngToLegacyPictures(
        context: Context,
        document: ScheduleExportDocument,
        displayName: String,
    ): SavedScheduleExport {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        val bitmap = ScheduleExportRenderer.renderPng(document)
        target.outputStream().use { output -> bitmap.compress(BitmapCompressFormat, 100, output) }
        bitmap.recycle()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(ScheduleExportFormat.Png.mimeType), null)
        return SavedScheduleExport(Uri.fromFile(target), target.name, target.absolutePath)
    }

    private fun exportFileName(document: ScheduleExportDocument, format: ScheduleExportFormat): String {
        val base = document.title
            .replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("""\s+"""), "_")
            .trim('_', '.')
            .take(64)
            .ifBlank { "BJTU_MIS_Export" }
        return "${base}_${document.generatedAt.format(FileTimestampFormatter)}.${format.extension}"
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        dir.mkdirs()
        val baseName = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = dir.resolve(displayName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) "($index)" else "($index).$extension"
            candidate = dir.resolve("${baseName}_$suffix")
            index += 1
        }
        return candidate
    }

    private val BitmapCompressFormat = android.graphics.Bitmap.CompressFormat.PNG
}
