package com.example.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object NoiseReportSharing {
    fun intent(context: Context, file: File): Intent {
        val root = File(context.cacheDir, "noise-reports").canonicalPath + File.separator
        require(file.isFile && file.canonicalPath.startsWith(root)) { "보고서 파일만 공유할 수 있습니다." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.report-files", file)
        return forUri(context, uri)
    }

    internal fun forUri(context: Context, uri: Uri): Intent {
        require(uri.scheme == "content" && uri.authority == "${context.packageName}.report-files"
            && uri.path?.startsWith("/noise_reports/") == true) { "보고서 파일 URI가 아닙니다." }
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData("KNOK 보고서", arrayOf("application/pdf"), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
