# Debito Tecnico — Identificativi Interni (`docs/DEBITO_TECNICO_NOMI.md`)

In seguito alla rinomina visibile dell'applicazione in **Moventure** (nome visibile, icona, risorse `@string/app_name`, cartella `Download/Moventure_Exports` e documentazione), restano intenzionalmente invariati gli identificativi interni per non introdurre regressioni durante la Fase 1A:

- **Package Kotlin / namespace Gradle:** `it.daprile.keepapp.*` (`:core-domain`, `:platform-android`, `:app`, `:logger`, `:replay`)
- **Nomi di classi principali:** `KeepAppConfig`, `KeepAppAudioEngine`, `KeepAppSessionService`, `KeepAppMainScreen`, `KeepAppUiStateHolder`
- **Nomi di thread interni, channel ID e SharedPreferences:** `"KeepApp-AudioEngine"`, `"KeepApp-Sensors"`, `"KeepApp-FileWriter"`, `"keepapp_live_session"`, `"keepapp_bt_latency_profiles"`, `"keepapp_session_history"`
- **Header binario `sensors.bin` (retrocompatibilità D45):** magic ASCII `"KEEPAPP0"` (invariato per compatibilità con tutte le sessioni registrate in Fase 0 e 1A).
- **`applicationId` (già migrato):** `it.daprile.moventure` (app principale) e `it.daprile.moventure.logger` (Logger).

Questi nomi interni saranno valutati per eventuale refactoring dopo la chiusura della Fase 1A / 1B.
