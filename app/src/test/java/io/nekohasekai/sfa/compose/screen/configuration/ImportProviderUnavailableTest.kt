package io.nekohasekai.sfa.compose.screen.configuration

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ImportProviderUnavailableTest {
    class UnavailableProvider : ContentProvider() {
        var file: File? = null
        var opens = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("profile.json")) }
        override fun getType(uri: Uri): String = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            opens++
            return file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        }
    }
    private fun testContext(): Context {
        val base = RuntimeEnvironment.getApplication()
        val original = base.resources
        val resources = object : Resources(original.assets, original.displayMetrics, original.configuration) {
            override fun getString(id: Int): String = when (id) {
                io.nekohasekai.sfa.R.string.error_import_file_unavailable -> "file-unavailable"
                io.nekohasekai.sfa.R.string.error_import_snapshot_mismatch -> "snapshot-mismatch"
                else -> "test-string"
            }
        }
        return object : ContextWrapper(base) { override fun getResources(): Resources = resources }
    }
    @Test fun unavailableProviderMustNotProduceConfirmableImport() = runBlocking {
        val context: Context = testContext()
        val provider = UnavailableProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.unavailable"; exported = false })
        ShadowContentResolver.registerProviderInternal("test.unavailable", provider)
        val result = ProfileImportHandler(context).parseUri(Uri.parse("content://test.unavailable/profile.json"))
        assertTrue("No captured file must yield an error, not an import confirmation: $result", result is ProfileImportHandler.UriParseResult.Error)
        assertEquals("file-unavailable", (result as ProfileImportHandler.UriParseResult.Error).message)
        assertEquals(1, provider.opens)
    }
    @Test fun snapshotContainsTheBytesSeenBeforeProviderChanges() = runBlocking {
        val context: Context = testContext()
        val file = File(context.cacheDir, "import-snapshot-test.json")
        val original = "{\"outbounds\":[]}".toByteArray()
        file.writeBytes(original)
        val provider = UnavailableProvider().apply { this.file = file }
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.available"; exported = false })
        ShadowContentResolver.registerProviderInternal("test.available", provider)
        val handler = ProfileImportHandler(context)
        val result = handler.parseUri(Uri.parse("content://test.available/profile.json"))
        assertTrue("Expected original JSON to be classified: $result", result is ProfileImportHandler.UriParseResult.Success)
        result as ProfileImportHandler.UriParseResult.Success
        file.writeText("{\"outbounds\":[{\"type\":\"direct\"}]}")
        assertArrayEquals(original, result.snapshot.bytes)
        assertEquals(1, provider.opens)
        result.snapshot.bytes[0] = 'x'.code.toByte()
        val changed = handler.importFromSnapshot(result.snapshot, result.name)
        assertTrue(changed is ProfileImportHandler.ImportResult.Error)
        assertEquals("snapshot-mismatch", (changed as ProfileImportHandler.ImportResult.Error).message)
        assertEquals(1, provider.opens)
    }

}
