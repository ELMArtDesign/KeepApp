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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Helper per la procedura guidata di Onboarding Xiaomi / MIUI / HyperOS (F9 / D28).
 *
 * Rispetta il vincolo F9:
 * - NON usa mai il permesso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 * - Apre direttamente la schermata proprietaria Xiaomi per l'Avvio Automatico
 *   (`com.miui.securitycenter` / `AutoStartManagementActivity`) con fallback automatico
 *   sulla schermata delle Impostazioni dell'App (`ACTION_APPLICATION_DETAILS_SETTINGS`).
 * - Offre anche l'apertura diretta del pannello "Risparmio batteria app" di MIUI/HyperOS.
 */
object XiaomiOnboardingHelper {

    /**
     * Tenta di aprire la schermata "Avvio automatico" di Xiaomi (MIUI / HyperOS).
     * Se l'Intent proprietario non è disponibile, apre la schermata Impostazioni dell'App.
     * Restituisce una descrizione dell'azione eseguita.
     */
    fun openXiaomiAutostartOrAppSettings(context: Context): String {
        val xiaomiIntents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent("miui.intent.action.OP_AUTO_START").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )

        for (intent in xiaomiIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Aperta schermata Avvio Automatico Xiaomi (SecurityCenter)"
            } catch (_: Exception) {
                // Prova il successivo
            }
        }

        return openAppDetailsSettings(context)
    }

    /**
     * Tenta di aprire direttamente la schermata "Risparmio batteria" di MIUI/HyperOS per questa app
     * (dove l'utente può selezionare "Nessuna restrizione"), con fallback su Impostazioni App.
     */
    fun openXiaomiBatterySaverForApp(context: Context): String {
        try {
            val miuiBatteryIntent = Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(miuiBatteryIntent)
            return "Aperta configurazione batteria MIUI (Seleziona 'Nessuna restrizione')"
        } catch (_: Exception) {
            return openAppDetailsSettings(context)
        }
    }

    /**
     * Apre la pagina standard di sistema delle informazioni sull'app (`ACTION_APPLICATION_DETAILS_SETTINGS`).
     */
    fun openAppDetailsSettings(context: Context): String {
        return try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            "Aperta schermata Informazioni App (Impostazioni)"
        } catch (e: Exception) {
            "Impossibile aprire le impostazioni: ${e.message}"
        }
    }
}
