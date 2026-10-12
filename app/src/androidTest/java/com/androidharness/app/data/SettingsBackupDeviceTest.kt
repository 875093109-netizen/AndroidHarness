package com.androidharness.app.data

import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.llm.ProviderType
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as HarnessApp).container

    @Test fun credentialsRoundTripThroughAndroidStorageAndEncryption() = runBlocking {
        val provider = container.providers.add("Backup device test", ProviderType.OPENAI_COMPAT,
            "https://example.com/v1", "test-model", "backup-fixture-key")
        val file = File(context.cacheDir, "settings-backup-device-test.hbackup")
        try {
            val backup = SettingsBackup(container)
            for (includeKeys in listOf(false, true)) {
                val password = "123456789101".toCharArray()
                backup.exportTo(Uri.fromFile(file), password, includeKeys)
                assertTrue(password.all { it == '\u0000' })
                assertFalse(file.readText(Charsets.ISO_8859_1).contains("backup-fixture-key"))
                val restored = backup.read(Uri.fromFile(file), "123456789101".toCharArray())
                val connection = restored.providers.single { it.config.id == provider.id }
                assertEquals(provider, connection.config)
                assertEquals(if (includeKeys) "backup-fixture-key" else null, connection.apiKey)
            }
        } finally {
            container.providers.delete(provider.id)
            file.delete()
        }
    }

    @Test fun credentialsRoundTripThroughContentDestination() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val provider = container.providers.add("Custom endpoint backup test", ProviderType.OPENAI_COMPAT,
            "http://my_server:11434/v1", "test-model", "backup-content-fixture-key")
        var destination: Uri? = null
        try {
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "backup-device-${System.nanoTime()}.hbackup")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AndroidHarnessBackupTests")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
            destination = uri
            val backup = SettingsBackup(container)
            backup.exportTo(uri, "abc123".toCharArray(), includeKeys = true)
            val restored = backup.read(uri, "abc123".toCharArray())
            val connection = restored.providers.single { it.config.id == provider.id }
            assertEquals(provider, connection.config)
            assertEquals("backup-content-fixture-key", connection.apiKey)
        } finally {
            destination?.let { context.contentResolver.delete(it, null, null) }
            container.providers.delete(provider.id)
        }
    }

    @Test fun blockedExportExplainsSettingPreservesDestinationAndCanBeRetried() = runBlocking {
        val original = container.settings.settings.first().maxContextTokens
        val file = File(context.cacheDir, "settings-backup-retry-test.hbackup")
        try {
            file.writeText("existing destination")
            container.settings.setMaxContextTokens(-1)
            val password = "123456789101".toCharArray()
            val backup = SettingsBackup(container)
            val error = try {
                backup.exportTo(Uri.fromFile(file), password, includeKeys = true)
                throw AssertionError("Invalid model limit should block export")
            } catch (e: SettingsBackupValidationException) { e }
            assertTrue(settingsBackupExportError(error).contains("Run limits"))
            assertTrue(password.all { it == '\u0000' })
            assertEquals("existing destination", file.readText())
            container.settings.setMaxContextTokens(original)
            backup.exportTo(Uri.fromFile(file), "123456789101".toCharArray(), includeKeys = true)
            assertEquals(original, backup.read(Uri.fromFile(file), "123456789101".toCharArray()).settings.maxContextTokens)
        } finally {
            container.settings.setMaxContextTokens(original)
            file.delete()
        }
    }
}
