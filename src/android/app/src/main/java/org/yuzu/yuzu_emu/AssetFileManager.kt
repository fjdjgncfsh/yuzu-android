package org.yuzu.yuzu_emu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AssetFileManager(private val context: Context) {

    fun copyFoldersFromAssets() {
        val foldersToCopy = arrayOf("01007EF00011E000", "0100A250097F0000")
        val destinationParentDir = context.getExternalFilesDir("load")

        foldersToCopy.forEach { sourceFolderName ->
            val destinationDir = File(destinationParentDir, sourceFolderName)

            if (!destinationDir.exists()) {
                try {
                    destinationDir.mkdirs() // 创建目标文件夹
                    copyAssetFolder(sourceFolderName, destinationDir)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyAssetFolder(assetFolder: String, destinationDir: File) {
        context.assets.list(assetFolder)?.forEach { assetItem ->
            val assetPath = "$assetFolder/$assetItem"
            val destinationFile = File(destinationDir, assetItem)

            if (context.assets.list(assetPath)?.isNotEmpty() == true) {
                // 如果是子文件夹，递归调用 copyAssetFolder
                destinationFile.mkdirs()
                copyAssetFolder(assetPath, destinationFile)
            } else {
                // 如果是文件，复制文件
                context.assets.open(assetPath).use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        val buffer = ByteArray(1024)
                        var length: Int
                        while (inputStream.read(buffer).also { length = it } > 0) {
                            outputStream.write(buffer, 0, length)
                        }
                    }
                }
            }
        }
    }
}