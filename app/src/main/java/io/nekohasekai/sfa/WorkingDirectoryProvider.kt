package io.nekohasekai.sfa

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File

class WorkingDirectoryProvider : DocumentsProvider() {
    companion object {
        private const val ROOT_ID = "working_directory"
        private const val ROOT_DOC_ID = "root"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }

    private val baseDir: File
        get() = context?.getExternalFilesDir(null)?.canonicalFile
            ?: error("工作目录不可用")

    override fun onCreate(): Boolean = context?.getExternalFilesDir(null) != null

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(DocumentsContract.Root.COLUMN_SUMMARY, context!!.getString(R.string.working_directory))
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId)
        if (!file.exists()) throw IllegalArgumentException("文件不存在")
        includeFile(result, documentId, file)
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        if (!parent.isDirectory) return result
        parent.listFiles()?.forEach { file -> includeFile(result, getDocIdForFile(file), file) }
        return result
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        require(file.exists()) { "文件不存在" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        requireValidName(displayName)
        val parent = getFileForDocId(parentDocumentId)
        require(parent.isDirectory) { "父目录不存在" }
        val file = File(parent, displayName).canonicalFile
        require(isWithinBase(file)) { "非法路径" }
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            require(file.mkdirs() || file.isDirectory) { "无法创建目录" }
        } else {
            require(file.createNewFile()) { "文件已存在" }
        }
        return getDocIdForFile(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        require(file != baseDir) { "不能删除根目录" }
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireValidName(displayName)
        val file = getFileForDocId(documentId)
        require(file != baseDir) { "不能重命名根目录" }
        val newFile = File(file.parentFile, displayName).canonicalFile
        require(isWithinBase(newFile)) { "非法路径" }
        require(file.renameTo(newFile)) { "重命名失败" }
        return getDocIdForFile(newFile)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = getFileForDocId(parentDocumentId)
        val child = getFileForDocId(documentId)
        return isWithin(parent, child)
    }

    private fun getFileForDocId(documentId: String): File {
        if (documentId == ROOT_DOC_ID) return baseDir
        requireValidDocumentId(documentId)
        val file = File(baseDir, documentId).canonicalFile
        require(isWithinBase(file)) { "非法文档路径" }
        return file
    }

    private fun getDocIdForFile(file: File): String {
        val canonical = file.canonicalFile
        require(isWithinBase(canonical)) { "文件超出工作目录" }
        if (canonical == baseDir) return ROOT_DOC_ID
        return canonical.relativeTo(baseDir).path.replace(File.separatorChar, '/')
    }

    private fun isWithinBase(file: File): Boolean = isWithin(baseDir, file)

    private fun isWithin(parent: File, child: File): Boolean {
        val p = parent.canonicalFile
        val c = child.canonicalFile
        return c == p || c.path.startsWith(p.path + File.separator)
    }

    private fun requireValidDocumentId(id: String) {
        require(id.isNotBlank() && !id.startsWith('/') && !id.contains('\\') && !id.split('/').contains("..")) { "非法文档 ID" }
    }

    private fun requireValidName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) { "非法文件名" }
    }

    private fun includeFile(result: MatrixCursor, documentId: String, file: File) {
        var flags = 0
        if (file.isDirectory) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        else flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        if (file.parentFile?.canWrite() == true && file != baseDir) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }
        val mimeType = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file)
        result.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
        }
    }

    private fun getMimeType(file: File): String = file.extension.lowercase().let {
        if (it.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) ?: "application/octet-stream"
        else "application/octet-stream"
    }
}
