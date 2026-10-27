package com.xbatch.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.util.Locale

/** Optional read-only access to the user's existing per-account Downloads folder. */
object ExistingFolder {
    private fun key(username: String) = "folder:" + username.lowercase(Locale.ROOT)
    fun save(context: Context, username: String, uri: Uri) {
        val id = DocumentsContract.getTreeDocumentId(uri)
        require(uri.authority == "com.android.externalstorage.documents" &&
            id.equals("primary:Download/$username", ignoreCase = true)) {
            "请选择手机内部存储 Download/$username 文件夹"
        }
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit().putString(key(username), uri.toString()).apply()
    }
    fun tree(context: Context, username: String): Uri? =
        context.getSharedPreferences("folders", Context.MODE_PRIVATE).getString(key(username), null)?.let(Uri::parse)

    fun find(context: Context, username: String, matches: (String) -> Boolean): List<Uri> {
        val tree = tree(context, username) ?: return emptyList()
        val resolver = context.contentResolver
        try {
            val directoryId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
            val result = mutableListOf<Uri>()
            val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?: throw IOException("无法读取已关联的图片文件夹，请重新关联")
            cursor.use {
                while (it.moveToNext()) if (matches(it.getString(1) ?: "")) {
                    result.add(DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)))
                }
            }
            return result
        } catch (e: Exception) {
            throw IOException("无法读取已关联的图片文件夹，请重新关联后重试", e)
        }
    }
}
