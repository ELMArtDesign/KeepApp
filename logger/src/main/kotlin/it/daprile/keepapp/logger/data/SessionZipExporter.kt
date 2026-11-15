/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.logger.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Esportatore delle sessioni di registrazione come archivio `.zip` (F10 — Milestone 5).
 *
 * Offre due modalità:
 * 1. Salvataggio diretto nella cartella pubblica `Download/KeepApp_Exports/` dello smartphone
 *    tramite `MediaStore.Downloads` (nessun permesso di archiviazione necessario su Android 10+).
 * 2. Condivisione tramite il selettore di sistema (`Intent.ACTION_SEND` + `FileProvider`).
 *
 * Oltre ai file della sessione (`manifest.json`, `sensors.bin`, `mic_raw.wav`), include
 * nell'archivio `.zip` anche i file di esito `headphones_calibration.json` (F6) e
 * `m4_audio_tests.json` (F7/F8) se presenti.
 */
object SessionZipExporter {

    /**
     * Esporta la cartella `sessionDir` in un file `.zip` dentro `Download/KeepApp_Exports/`.
     * Restituisce un messaggio con il percorso e la dimensione del file salvato.
     */
    fun exportToDownloads(context: Context, sessionDir: File): String {
        val zipFileName = "${sessionDir.name}.zip"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/KeepApp_Exports"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return "Errore: impossibile creare voce in MediaStore.Downloads"

                var bytesWritten = 0L
                resolver.openOutputStream(uri)?.use { os ->
                    writeSessionToZipStream(context, sessionDir, os)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    bytesWritten = pfd.statSize
                }
                val sizeKb = bytesWritten / 1024L
                "✅ Salvato in Download/KeepApp_Exports/$zipFileName ($sizeKb KB)"
            } else {
                @Suppress("DEPRECATION")
                val pubDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "KeepApp_Exports"
                )
                if (!pubDir.exists()) pubDir.mkdirs()
                val outFile = File(pubDir, zipFileName)
                FileOutputStream(outFile).use { fos ->
                    writeSessionToZipStream(context, sessionDir, fos)
                }
                val sizeKb = outFile.length() / 1024L
                "✅ Salvato in Download/KeepApp_Exports/$zipFileName ($sizeKb KB)"
            }
        } catch (e: Exception) {
            "❌ Errore esportazione: ${e.message}"
        }
    }

    /**
     * Crea un file `.zip` nella cache temporanea e avvia il menu di condivisione di Android.
     */
    fun shareSessionZip(context: Context, sessionDir: File): String {
        return try {
            val exportsDir = File(context.cacheDir, "exports")
            if (!exportsDir.exists()) exportsDir.mkdirs()

            // Pulisce vecchi zip temporanei in cache
            exportsDir.listFiles()?.forEach { it.delete() }

            val zipFile = File(exportsDir, "${sessionDir.name}.zip")
            FileOutputStream(zipFile).use { fos ->
                writeSessionToZipStream(context, sessionDir, fos)
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, zipFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "KeepApp Fase 0 — ${sessionDir.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Esporta / Condividi ${zipFile.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            "📤 Menu condivisione aperto per ${zipFile.name} (${zipFile.length() / 1024L} KB)"
        } catch (e: Exception) {
            "❌ Errore condivisione: ${e.message}"
        }
    }

    private fun writeSessionToZipStream(
        context: Context,
        sessionDir: File,
        rawOut: OutputStream
    ) {
        ZipOutputStream(BufferedOutputStream(rawOut, 65_536)).use { zos ->
            val filesInSession = sessionDir.listFiles().orEmpty()
            val buffer = ByteArray(65_536)

            for (file in filesInSession) {
                if (!file.isFile) continue
                val entryName = "${sessionDir.name}/${file.name}"
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { fis ->
                    BufferedInputStream(fis, 65_536).use { bis ->
                        var read = bis.read(buffer)
                        while (read != -1) {
                            zos.write(buffer, 0, read)
                            read = bis.read(buffer)
                        }
                    }
                }
                zos.closeEntry()
            }

            // Aggiungi anche i risultati dei test F6 (calibrazione BT) e F7/F8 (Media3/Spotify) se presenti
            val extraFiles = listOf(
                File(context.filesDir, "headphones_calibration.json"),
                File(context.filesDir, "m4_audio_tests.json")
            )
            for (extra in extraFiles) {
                if (extra.exists() && extra.isFile) {
                    val entryName = "${sessionDir.name}/${extra.name}"
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(extra).use { fis ->
                        var read = fis.read(buffer)
                        while (read != -1) {
                            zos.write(buffer, 0, read)
                            read = fis.read(buffer)
                        }
                    }
                    zos.closeEntry()
                }
            }
            zos.flush()
        }
    }
}
