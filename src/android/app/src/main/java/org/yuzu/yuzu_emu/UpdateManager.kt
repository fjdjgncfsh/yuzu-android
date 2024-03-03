package org.yuzu.yuzu_emu

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import android.app.ProgressDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.security.NoSuchAlgorithmException
import java.io.InputStream

object UpdateManager {
    private val client = OkHttpClient()

    fun checkAndInstallUpdate(context: Context) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) {
            val updateInfo = getUpdateInfoFromServer()
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName

            // 比较版本号，如果需要更新才显示更新对话框
            if (isUpdateAvailable(currentVersion, updateInfo.versionName)) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(context, updateInfo, currentVersion)
                }
            }
        }
    }

    private suspend fun getUpdateInfoFromServer(): UpdateInfo {
        val request = Request.Builder()
            .url("http://mkoc.cn/update.php")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }

            val inputStream = response.body?.byteStream()

            if (inputStream != null) {
                val responseBody = inputStream.bufferedReader().use { it.readText() }
                JSONObject(responseBody).let { json ->
                    if (
                        json.has("title") &&
                        json.has("content") &&
                        json.has("versionName") &&
                        json.has("downloadUrl") &&
                        json.has("md5Hash")
                    ) {
                        UpdateInfo(
                            title = json.getString("title"),
                            content = json.getString("content"),
                            versionName = json.getString("versionName"),
                            downloadUrl = json.getString("downloadUrl"),
                            hashValue = json.getString("md5Hash")
                        )
                    } else {
                        UpdateInfo("", "", "", "", "")
                    }
                }
            } else {
                UpdateInfo("", "", "", "", "")
            }
        } catch (e: IOException) {
            UpdateInfo("", "", "", "", "")
        } catch (e: JSONException) {
            UpdateInfo("", "", "", "", "")
        }
    }

    private fun showUpdateDialog(
        context: Context,
        updateInfo: UpdateInfo,
        currentVersion: String
    ) {
        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle(updateInfo.title)
            .setMessage(updateInfo.content)

        val downloadDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkFileName = "yuzu${updateInfo.versionName}.apk"
        val apkFilePath = File(downloadDirectory, apkFileName).absolutePath

        val isApkValid = isApkIntegrityValid(apkFilePath, updateInfo.hashValue)

        if (isApkValid) {
            dialogBuilder.setPositiveButton("安装") { _, _ ->
                installUpdate(context, apkFilePath)
            }
        } else {
            dialogBuilder.setPositiveButton("更新") { _, _ ->
                val progressDialog = createProgressDialog(context)
                downloadAndInstallUpdate(
                    context,
                    updateInfo.downloadUrl,
                    progressDialog,
                    updateInfo.versionName,
                    apkFilePath
                )
            }
        }

        dialogBuilder.setNegativeButton("稍后") { _, _ ->
            // 用户选择稍后更新或安装
        }.show()
    }

    private fun createProgressDialog(context: Context): ProgressDialog {
        val progressDialog = ProgressDialog(context)
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setTitle("下载中")
        progressDialog.setMessage("请稍候...")
        progressDialog.isIndeterminate = false
        progressDialog.setCancelable(false)
        progressDialog.max = 100
        return progressDialog
    }

    private fun downloadAndInstallUpdate(
        context: Context,
        downloadUrl: String,
        progressDialog: ProgressDialog,
        versionName: String,
        apkFilePath: String,
        retryCount: Int = 0
    ) {
        // 创建下载请求
        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        progressDialog.show()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showErrorMessageDialog(context, "下载失败，请检查网络连接")
                    // 下载失败时，尝试再次下载
                    downloadAndInstallUpdate(
                        context,
                        downloadUrl,
                        progressDialog,
                        versionName,
                        apkFilePath,
                        retryCount + 1
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                var inputStream: InputStream? = null
                try {
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected response code: ${response.code}")
                    }

                    inputStream = response.body?.byteStream()

                    if (inputStream != null) {
                        File(apkFilePath).outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                            progressDialog.dismiss()
                            installUpdate(context, apkFilePath)
                        }
                    } else {
                        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                            progressDialog.dismiss()
                            showErrorMessageDialog(context, "下载失败，无法获取输入流")
                        }
                    }
                } catch (e: IOException) {
                    (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.dismiss()
                        showErrorMessageDialog(context, "下载失败，复制文件时出错")
                    }
                } finally {
                    inputStream?.close()
                }
            }
        })
    }

    private fun showErrorMessageDialog(context: Context, message: String) {
        AlertDialog.Builder(context)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun isApkIntegrityValid(apkFilePath: String, expectedHash: String): Boolean {
        val file = File(apkFilePath)
        if (!file.exists()) {
            return false
        }

        return try {
            val calculatedHash = calculateMD5Hash(file)
            calculatedHash == expectedHash
        } catch (e: IOException) {
            false
        } catch (e: NoSuchAlgorithmException) {
            false
        }
    }

    private fun calculateMD5Hash(file: File): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        file.inputStream().use { fis ->
            val byteArray = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(byteArray).also { bytesRead = it } != -1) {
                messageDigest.update(byteArray, 0, bytesRead)
            }
        }
        val hashBytes = messageDigest.digest()

        val hexStringBuilder = StringBuilder(2 * hashBytes.size)
        for (hashByte in hashBytes) {
            val hex = Integer.toHexString(0xff and hashByte.toInt())
            if (hex.length == 1) {
                hexStringBuilder.append('0')
            }
            hexStringBuilder.append(hex)
        }
        return hexStringBuilder.toString()
    }

    private fun installUpdate(context: Context, apkFilePath: String) {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            File(apkFilePath)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (installIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(installIntent)
        } else {
            showErrorMessageDialog(context, "没有找到可用的应用程序来安装更新")
        }
    }

    data class UpdateInfo(
        val title: String,
        val content: String,
        val versionName: String,
        val downloadUrl: String,
        val hashValue: String
    )

    private fun isUpdateAvailable(
        currentVersion: String,
        latestVersion: String
    ): Boolean {
        return compareSemanticVersion(currentVersion, latestVersion) < 0
    }

    private fun compareSemanticVersion(
        currentVersion: String,
        otherVersion: String
    ): Int {
        val currentParts = currentVersion.split('.')
        val otherParts = otherVersion.split('.')

        val currentMajor = currentParts.getOrNull(0)?.toIntOrNull() ?: 0
        val otherMajor = otherParts.getOrNull(0)?.toIntOrNull() ?: 0

        val currentMinor = currentParts.getOrNull(1)?.toIntOrNull() ?: 0
        val otherMinor = otherParts.getOrNull(1)?.toIntOrNull() ?: 0

        val currentPatch = currentParts.getOrNull(2)?.toIntOrNull() ?: 0
        val otherPatch = otherParts.getOrNull(2)?.toIntOrNull() ?: 0

        if (currentMajor != otherMajor) {
            return currentMajor.compareTo(otherMajor)
        }
        if (currentMinor != otherMinor) {
            return currentMinor.compareTo(otherMinor)
        }
        return currentPatch.compareTo(otherPatch)
    }
}
