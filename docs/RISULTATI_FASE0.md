# Risultati Sperimentali della Fase 0 (`docs/RISULTATI_FASE0.md`)

> Questo documento raccoglie i risultati oggettivi misurati con **KeepApp Logger (Fase 0)** sullo smartphone di riferimento, senza alcun dato personale o coordinata geografica privata.

## 1. Ambiente di Test
* **Dispositivo:** Xiaomi Redmi Note 11 Pro 5G
* **Versione Android / ROM:** Android 13 (`TKQ1.221114.001`) — HyperOS `1.0.12.0.TKCMIXM`
* **Cuffie Bluetooth:** Mankiw (Conduzione ossea)

---

## 2. Tabella Riepilogativa dei Test (Da compilare a ogni Milestone)

| Test / Verifica | Milestone | Stato | Valore Misurato / Esito | Note Tecniche |
| :--- | :---: | :---: | :--- | :--- |
| **Presenza Barometro Hardware** | M1 | ⏳ Da eseguire | *In attesa di M1* | Verificare da tab "Info Dispositivo" |
| **Base Temporale `SensorEvent.timestamp`** | M1 | ⏳ Da eseguire | *In attesa di M1* | Verificare allineamento con `elapsedRealtimeNanos` |
| **Frequenza Reale Accelerometro (~100 Hz)** | M1 | ⏳ Da eseguire | *In attesa di M1* | Target: 95 – 105 Hz continui a schermo spento |
| **Frequenza Reale Giroscopio (~100 Hz)** | M1 | ⏳ Da eseguire | *In attesa di M1* | Target: 95 – 105 Hz continui a schermo spento |
| **Test 10 min Camminata a Schermo Spento** | M1 | ⏳ Da eseguire | *In attesa di M1* | Zero buchi `HEARTBEAT` > 15 s |
| **Microfono `BUILTIN_MIC` + Cuffie in `A2DP`** | M2 | ⏳ Da eseguire | *In attesa di M2* | Verificare assenza di switch a `TYPE_BLUETOOTH_SCO` (7) |
| **Deriva Clock Audio (`AUDIO_SYNC`)** | M2 | ⏳ Da eseguire | *In attesa di M2* | Ppm di deriva tra clock audio e clock di sistema |
| **Tasti AVRCP Cuffie Mankiw (`keyCode`)** | M2 | ⏳ Da eseguire | *In attesa di M2* | Quali `keyCode` invia 1 click e 2 click |
| **Jitter e Underrun Metronomo (`AudioTrack`)** | M3 | ⏳ Da eseguire | *In attesa di M3* | Target jitter $\le 10\text{–}15\text{ ms}$, underrun = 0 |
| **Calibrazione BT: Batti a Tempo vs Contatto** | M3 | ⏳ Da eseguire | *In attesa di M3* | Offset medio (ms) e deviazione standard (ms) |
| **Qualità Time-Stretch `Media3` (0,85x – 1,15x)** | M4 | ⏳ Da eseguire | *In attesa di M4* | Voto 1–5 e impatto CPU |
| **Sidechain EQ con `DynamicsProcessing`** | M4 | ⏳ Da eseguire | *In attesa di M4* | Attenuazione 300 Hz – 3 kHz udibile senza glitch |
| **Ducking Dinamico con Spotify** | M4 | ⏳ Da eseguire | *In attesa di M4* | Abbassamento volume vs Pausa indesiderata |
| **Corsa 60 min a Schermo Spento (HyperOS)** | M5 | ⏳ Da eseguire | *In attesa di M5* | Criterio finale: nessun buco `HEARTBEAT` > 15 s |
