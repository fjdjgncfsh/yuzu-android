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
        private const val GPU_DRIVERS_URL = "http://pan.94cto.com/index/index/down/shorturl/cmuxt"
        private const val NEW_DRIVER_1_URL = "http://pan.94cto.com/index/index/down/shorturl/zk43r"
        private const val NEW_DRIVER_2_URL = "http://pan.94cto.com/index/index/down/shorturl/khpxm"

        private const val PROD_KEYS_HASH = "4ed853d4a52e6b9b9e11954f155ecb8a"
        private const val GPU_DRIVERS_HASH = "dbdb8d8fe6d6a310be79ad93b7d038ec"
        private const val NEW_DRIVER_1_HASH = "fa6ad835ae20ea02a5d667e2953ab0be"
        private const val NEW_DRIVER_2_HASH = "1a9eaa252da47557d32e54f27cd8ab5d"
    }

    fun checkAndDownloadFiles() {
        val prodKeysFile = getOrCreateFile(
            "prod.keys",
            PROD_KEYS_URL,
            "keys",
            PROD_KEYS_HASH
        )

        val gpuDriversFile = getOrCreateFile(
            "turnip-24.1.0.R17.zip",
            GPU_DRIVERS_URL,
            "gpu_drivers",
            GPU_DRIVERS_HASH
        )

        val newDriver1File = getOrCreateFile(
            "turnip-24.1.0.R16.zip",
            NEW_DRIVER_1_URL,
            "gpu_drivers",
            NEW_DRIVER_1_HASH
        )

        val newDriver2File = getOrCreateFile(
            "turnip-24.0.0.R15.zip",
            NEW_DRIVER_2_URL,
            "new_drivers",
            NEW_DRIVER_2_HASH
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
            GlobalScope.launch(Dispatchers.IO) {
                downloadFile(downloadUrl, outputFile, expectedHash)
            }
        } else {
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
