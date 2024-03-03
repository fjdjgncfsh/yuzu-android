package org.yuzu.yuzu_emu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

class FileDownloader(private val context: Context) {
    companion object {
        private const val PROD_KEYS_URL = "http://mkoc.cn/prod.keys"
        private const val GPU_DRIVERS_URL = "http://mkoc.cn/turnip-24.1.0.adpkg_R17-v2.zip"

        // 替换为你预期的哈希值
        private const val PROD_KEYS_HASH = "4ed853d4a52e6b9b9e11954f155ecb8a"
        private const val GPU_DRIVERS_HASH = "dbdb8d8fe6d6a310be79ad93b7d038ec"
    }

    fun checkAndDownloadFiles() {
        // 检查并下载 prod.keys 文件
        val prodKeysFile = getOrCreateFile(
            "prod.keys",
            PROD_KEYS_URL,
            "keys",
            PROD_KEYS_HASH
        )

        // 检查并下载 GPU 驱动文件
        val gpuDriversFile = getOrCreateFile(
            "turnip-24.1.0.adpkg_R17-v2.zip",
            GPU_DRIVERS_URL,
            "gpu_drivers",
            GPU_DRIVERS_HASH
        )
    }

    private fun getOrCreateFile(
        fileName: String,
        downloadUrl: String,
        directoryName: String,
        expectedHash: String
    ): File {
        val externalFilesDir = context.getExternalFilesDir(null)
        val targetDir = File(externalFilesDir, directoryName)
        val outputFile = File(targetDir, fileName)

        if (!outputFile.exists()) {
            // 使用协程在后台执行下载任务
            GlobalScope.launch(Dispatchers.IO) {
                downloadFile(downloadUrl, outputFile, expectedHash)
            }
        } else {
            // 校验文件哈希值
            val actualHash = calculateMD5(outputFile)
            if (actualHash != expectedHash) {
                println("$fileName 文件哈希值不匹配，重新下载.")
                outputFile.delete()
                GlobalScope.launch(Dispatchers.IO) {
                    downloadFile(downloadUrl, outputFile, expectedHash)
                }
            } else {
                println("$fileName 文件已存在.")
            }
        }

        return outputFile
    }

    private fun downloadFile(urlString: String, outputFile: File, expectedHash: String) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connect()

            val input = connection.getInputStream()
            val output = FileOutputStream(outputFile)

            val buffer = ByteArray(1024)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                output.write(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }

            output.close()
            input.close()

            // 校验下载后的文件哈希值
            val actualHash = calculateMD5(outputFile)
            if (actualHash != expectedHash) {
                println("下载文件 ${outputFile.name} 时出错: 哈希值不匹配.")
                outputFile.delete()
            } else {
                println("文件 ${outputFile.name} 下载成功.")
            }
        } catch (e: Exception) {
            println("下载文件 ${outputFile.name} 时出错: ${e.message}")
        }
    }

    private fun calculateMD5(file: File): String {
        return file.inputStream().buffered().use { input ->
            val md = MessageDigest.getInstance("MD5")
            val byteArray = ByteArray(1024)
            var bytesCount: Int
            while (input.read(byteArray).also { bytesCount = it } != -1) {
                md.update(byteArray, 0, bytesCount)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
