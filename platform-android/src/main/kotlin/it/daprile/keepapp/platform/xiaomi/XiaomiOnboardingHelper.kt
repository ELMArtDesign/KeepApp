/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.xiaomi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Helper condiviso in `:platform-android` per l'Onboarding Xiaomi / MIUI / HyperOS (F9 / D28).
 */
object XiaomiOnboardingHelper {

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
            }
        }

        return openAppDetailsSettings(context)
    }

    fun openXiaomiBatterySaverForApp(context: Context, appLabel: String = "KeepApp"): String {
        try {
            val miuiBatteryIntent = Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", appLabel)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(miuiBatteryIntent)
            return "Aperta configurazione batteria MIUI (Seleziona 'Nessuna restrizione')"
        } catch (_: Exception) {
            return openAppDetailsSettings(context)
        }
    }

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
