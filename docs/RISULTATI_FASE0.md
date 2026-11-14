# Risultati Sperimentali della Fase 0 (`docs/RISULTATI_FASE0.md`)

> Questo documento raccoglie i risultati oggettivi misurati con **KeepApp Logger (Fase 0)** sullo smartphone di riferimento, senza alcun dato personale o coordinata geografica privata.

## 1. Ambiente di Test
* **Dispositivo:** Xiaomi Redmi Note 11 Pro 5G (`2201116SG` / `veux`)
* **Versione Android / ROM:** Android 13 (`TKQ1.221114.001`) — HyperOS `1.0.12.0.TKCMIXM`
* **Cuffie Bluetooth:** Mankiw (Conduzione ossea)

---

## 2. Tabella Riepilogativa dei Test (Da compilare a ogni Milestone)

| Test / Verifica | Milestone | Stato | Valore Misurato / Esito | Note Tecniche |
| :--- | :---: | :---: | :--- | :--- |
| **Presenza Barometro Hardware** | M1 | ✅ Verificato | **ASSENTE** | Confermato sul modello `veux` (`2201116SG`). Salite da calcolare con quota GPS + Copernicus |
| **Base Temporale `SensorEvent.timestamp`** | M1 | ✅ Verificato | **Allineata (`elapsedRealtimeNanos`)** | `CLOCK_BOOTTIME` condiviso con il clock monotonico di sistema |
| **Frequenza Reale Accelerometro (~100 Hz)** | M1 | ✅ Superato | **`104.0 Hz` stabili** | Misurato a schermo spento (`27.748` record in `01:46`) |
| **Frequenza Reale Giroscopio (~100 Hz)** | M1 | ✅ Superato | **`104.0 Hz` stabili** | Sincronizzato allo stesso clock dell'accelerometro (`104.0 Hz`) |
| **Frequenza Reale Magnetometro (~50 Hz)** | M1 | ✅ Superato | **`50.0 Hz` stabili** | Esattamente 50.0 Hz continui |
| **Step Detector & GPS Nativo (`GPS_PROVIDER`)** | M1 | ✅ Superato | **160 passi / 88 fix GPS in 106 s** | `maxReportLatencyUs = 0` funziona senza ritardi a schermo spento |
| **Test Camminata a Schermo Spento (M1)** | M1 | ✅ Superato | **11 Heartbeat in 01:46 (Zero buchi)** | Batteria 84% (`32.4 °C`), `ThermalStatus = 0` |
| **Microfono `BUILTIN_MIC` + Cuffie in `A2DP`** | M2 | ⏳ In test | *Pronto in M2* | Verificare assenza di switch a `TYPE_BLUETOOTH_SCO` (7) |
| **Deriva Clock Audio (`AUDIO_SYNC`)** | M2 | ⏳ In test | *Pronto in M2* | Deriva in ms tra clock audio 48 kHz e `elapsedRealtimeNanos` |
| **Tasti AVRCP Cuffie Mankiw (`keyCode`)** | M2 | ⏳ In test | *Pronto in M2* | Quali `keyCode` invia 1 click e 2 click |
| **Jitter e Underrun Metronomo (`AudioTrack`)** | M3 | ⏳ Da eseguire | *In attesa di M3* | Target jitter $\le 10\text{–}15\text{ ms}$, underrun = 0 |
| **Calibrazione BT: Batti a Tempo vs Contatto** | M3 | ⏳ Da eseguire | *In attesa di M3* | Offset medio (ms) e deviazione standard (ms) |
| **Qualità Time-Stretch `Media3` (0,85x – 1,15x)** | M4 | ⏳ Da eseguire | *In attesa di M4* | Voto 1–5 e impatto CPU |
| **Sidechain EQ con `DynamicsProcessing`** | M4 | ⏳ Da eseguire | *In attesa di M4* | Attenuazione 300 Hz – 3 kHz udibile senza glitch |
| **Ducking Dinamico con Spotify** | M4 | ⏳ Da eseguire | *In attesa di M4* | Abbassamento volume vs Pausa indesiderata |
| **Corsa 60 min a Schermo Spento (HyperOS)** | M5 | ⏳ Da eseguire | *In attesa di M5* | Criterio finale: nessun buco `HEARTBEAT` > 15 s |
