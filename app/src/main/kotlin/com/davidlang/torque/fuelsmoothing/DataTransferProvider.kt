package com.davidlang.torque.fuelsmoothing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

fun datatransferAllowed(uid: Int) = uid == 2000 || uid == 0

class DataTransferProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        guard()
        val path = uri.path ?: ""
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        when {
            path == "/export" && mode == "r" -> {
                Thread({ exportTo(writeEnd) }, "datatransfer-export").start()
                return readEnd
            }
            path == "/import-status" && mode == "r" -> {
                Thread({ statusTo(writeEnd) }, "datatransfer-status").start()
                return readEnd
            }
            path == "/import" && mode == "w" -> {
                Thread({ importFrom(readEnd) }, "datatransfer-import").start()
                return writeEnd
            }
            else -> {
                readEnd.close()
                writeEnd.close()
                throw IllegalArgumentException(ALLOWED)
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        guard()
        throw UnsupportedOperationException("datatransfer: query is not supported")
    }

    override fun getType(uri: Uri): String? {
        guard()
        throw IllegalArgumentException(ALLOWED)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        guard()
        throw UnsupportedOperationException("datatransfer: insert is not supported")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        guard()
        throw UnsupportedOperationException("datatransfer: delete is not supported")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        guard()
        throw UnsupportedOperationException("datatransfer: update is not supported")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        guard()
        throw IllegalArgumentException(ALLOWED)
    }

    private fun guard() {
        if (!datatransferAllowed(Binder.getCallingUid())) {
            throw SecurityException("datatransfer: adb shell only")
        }
    }

    private fun exportTo(writeEnd: ParcelFileDescriptor) {
        val out = FileOutputStream(writeEnd.fileDescriptor)
        try {
            val ctx = context ?: throw IllegalStateException("datatransfer: provider has no context")
            val dataDir = ctx.filesDir.parentFile ?: throw IllegalStateException("datatransfer: no data dir")
            DataTransfer.exportSnapshot(
                dataDir = dataDir,
                noBackupFilesDir = ctx.noBackupFilesDir,
                out = out,
                info = BackupManifest.Info(
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    buildType = BuildConfig.BUILD_TYPE,
                    androidSdkInt = Build.VERSION.SDK_INT,
                ),
                recording = PluginService.recording,
            )
            out.flush()
            writeEnd.close()
        } catch (t: Throwable) {
            val msg = reason(t)
            Log.e(TAG, msg, t)
            runCatching { writeEnd.closeWithError(msg) }
        }
    }

    private fun statusTo(writeEnd: ParcelFileDescriptor) {
        val out = FileOutputStream(writeEnd.fileDescriptor)
        try {
            val ctx = context ?: throw IllegalStateException("datatransfer: provider has no context")
            val file = File(ctx.noBackupFilesDir, "datatransfer/last-import.json")
            val text = if (file.isFile) file.readText() else "{\"result\":\"none\"}"
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            writeEnd.close()
        } catch (t: Throwable) {
            val msg = reason(t)
            Log.e(TAG, msg, t)
            runCatching { writeEnd.closeWithError(msg) }
        }
    }

    private fun importFrom(readEnd: ParcelFileDescriptor) {
        val input = FileInputStream(readEnd.fileDescriptor)
        try {
            val ctx = context ?: throw IllegalStateException("datatransfer: provider has no context")
            val dataDir = ctx.filesDir.parentFile ?: throw IllegalStateException("datatransfer: no data dir")
            val status = File(ctx.noBackupFilesDir, "datatransfer/last-import.json")
            val outcome = if (PluginService.recording) {
                DataTransfer.importReplace(dataDir, input, true, status)
            } else {
                ctx.stopService(Intent(ctx, PluginService::class.java))
                ProfileStore.DataLock.lock()
                try {
                    DataTransfer.importReplace(dataDir, input, PluginService.recording, status)
                } finally {
                    ProfileStore.DataLock.unlock()
                }
            }
            if (!outcome.ok) {
                val msg = "datatransfer: ${outcome.reason ?: "import refused"}"
                Log.e(TAG, msg)
                readEnd.closeWithError(msg)
                return
            }
            readEnd.close()
            Process.killProcess(Process.myPid())
        } catch (t: Throwable) {
            val msg = reason(t)
            Log.e(TAG, msg, t)
            runCatching { readEnd.closeWithError(msg) }
        }
    }

    private fun reason(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return if (msg.startsWith("datatransfer:")) msg else "datatransfer: $msg"
    }

    private companion object {
        const val TAG = "DataTransfer"
        const val ALLOWED = "datatransfer: allowed URIs are /export (mode r), /import (mode w), /import-status (mode r)"
    }
}
