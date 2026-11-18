# Moventure (ex KeepApp) — Adaptive Rhythm Runner

[![Moventure CI](https://github.com/ELMArtDesign/KeepApp/actions/workflows/ci.yml/badge.svg)](https://github.com/ELMArtDesign/KeepApp/actions/workflows/ci.yml)

> **Stato del progetto:** `Fase 0 – Strumento diagnostico e di acquisizione interno`  
> **Piattaforma:** Android (`minSdk 26`, `targetSdk 36`) — Scritto in Kotlin + Jetpack Compose  
> **Licenza:** [Apache License 2.0](LICENSE)

---

## ⚠️ Avvertenza Importante (Disclaimer)

**Questo software NON è un dispositivo medico, NON fornisce diagnosi o consigli sanitari e NON è un sistema di sicurezza o di allerta garantito.**  
L'applicazione **Moventure Logger (`it.daprile.keepapp.logger`)** è uno strumento tecnico interno di Fase 0 progettato esclusivamente per registrare i dati grezzi dei sensori inerziali (IMU), del GPS e del banco di prova audio su smartphone Android durante sessioni di camminata e corsa, al fine di calibrare gli algoritmi del progetto *Moventure (Adaptive Rhythm Runner)*.

Durante qualsiasi attività su strada o all'aperto, l'utente è l'unico responsabile della propria attenzione e sicurezza.

---

## 🎯 Scopo della Fase 0

Prima di sviluppare gli algoritmi definitivi di cadenza adattiva, fase del passo e guida vocale, questa applicazione verifica sul campo (in particolare su hardware **Xiaomi Redmi Note 11 Pro 5G** con **HyperOS / MIUI** e cuffie Bluetooth a conduzione ossea):

1. **F1 – Info Dispositivo:** Censimento completo dei sensori hardware (risoluzione, frequenza massima, wake-up, presenza barometro) e dei dispositivi di input/output audio.
2. **F2 & F3 – Registrazione Sensori e Sopravvivenza Background:** Acquisizione continua a schermo spento in un *Foreground Service* (Accelerometro ~100 Hz, Giroscopio ~100 Hz, Magnetometro ~50 Hz, Step Detector, GPS puro `LocationManager` 1 Hz, stato termico e `HEARTBEAT` ogni 10 secondi) salvata nel formato binario ad alte prestazioni `sensors.bin` + `manifest.json` (vedi [docs/FORMAT.md](docs/FORMAT.md)).
3. **F4 – Marker Cuffie Bluetooth:** Intercettazione dei tasti multimediali (AVRCP) delle cuffie durante la corsa per marcare eventi reali (incroci, veicoli) nel tracciato.
4. **F5 & F6 – Metronomo Sample-Accurate e Calibrazione Latenza BT:** Verifica del jitter di `AudioTrack` in modalità `WRITE_BLOCKING` e confronto tra calibrazione "Batti a tempo" e calibrazione "A contatto meccanico".
5. **F7 & F8 – Test Media3, Sidechain EQ (`DynamicsProcessing`) e Ducking Spotify:** Verifica della variazione di velocità a tonalità preservata (0,85x – 1,15x), dell'attenuazione selettiva 300 Hz – 3 kHz e della convivenza con Spotify.
6. **F9 – Onboarding Xiaomi (MIUI / HyperOS):** Guida alla configurazione di *Avvio Automatico*, *Nessuna restrizione batteria* e blocco nei Recenti per sessioni di 60+ minuti a schermo spento.
7. **F10 & F11 – Esportazione `.zip` e Analisi Python sul PC:** Esportazione locale delle sessioni e script di validazione scientifica `tools/read_session.py`.

---

## 🔒 Privacy e Sicurezza dei Dati

* **Nessun permesso `INTERNET`:** L'app di Fase 0 non richiede il permesso di accesso alla rete e non può trasmettere alcun dato all'esterno.
* **Archiviazione 100% locale:** Tutte le sessioni vengono salvate esclusivamente nella memoria privata dell'app sul telefono e possono essere cancellate in qualsiasi momento tramite il pulsante **"Cancella tutte le sessioni"**.
* **Nessun dato personale nel repository:** Il file `.gitignore` blocca il caricamento su Git di qualsiasi sessione registrata (`.bin`, `.wav`, `.zip`).

---

## 🛠️ Struttura dei Moduli

* **`:core-domain`**: Modulo in **Kotlin puro** (zero dipendenze dal framework Android), contenente la specifica binaria dei record a 32 byte (`BinaryRecord`) e i modelli di metadati condivisi, pronto per un futuro utilizzo multipiattaforma (Kotlin Multiplatform).
* **`:app`**: Applicazione Android in Jetpack Compose contenente il `LoggerForegroundService`, lo scrittore asincrono su thread dedicato (`SessionFileWriter`), il raccoglitore hardware (`DeviceInfoCollector`) e le schermate di test.

---

## 🚀 Come Compilare e Installare (Guida passo-passo per Android Studio)

1. Avvia **Android Studio** sul tuo PC Windows 10.
2. Clicca su **Open** e seleziona la cartella `C:\Users\Lorenzo\Desktop\KeepApp`.
3. Attendi che Android Studio completi il **Gradle Sync** automatico (in basso a destra vedrai la barra di avanzamento; alla prima apertura scaricherà il wrapper Gradle e le dipendenze AndroidX).
4. Collega il tuo **Xiaomi Redmi Note 11 Pro 5G** al PC con il cavo USB (assicurati che nelle *Opzioni Sviluppatore* del telefono siano attivi **Debug USB** e **Installa via USB**).
5. In alto in Android Studio, verifica che sia selezionato `app` accanto al tuo dispositivo Xiaomi e premi il pulsante verde **Run (▶)**.
6. L'app **Moventure Logger** si avvierà automaticamente sul telefono.
