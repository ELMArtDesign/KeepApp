# Risultati Sperimentali della Fase 0 (`docs/RISULTATI_FASE0.md`)

> Questo documento raccoglie i risultati oggettivi misurati con **KeepApp Logger (Fase 0)** sullo smartphone di riferimento, senza alcun dato personale o coordinata geografica privata.

## 1. Ambiente di Test
* **Dispositivo:** Xiaomi Redmi Note 11 Pro 5G (`2201116SG` / `veux`)
* **Versione Android / ROM:** Android 13 (`TKQ1.221114.001`) — HyperOS `1.0.12.0.TKCMIXM`
* **Cuffie Bluetooth:** Mankiw (`MANKIW-X15` — Conduzione ossea)

---

## 2. Tabella Riepilogativa dei Test (Da compilare a ogni Milestone)

| Test / Verifica | Milestone | Stato | Valore Misurato / Esito | Note Tecniche |
| :--- | :---: | :---: | :--- | :--- |
| **Presenza Barometro Hardware** | M1 | ✅ Verificato | **ASSENTE** | Confermato sul modello `veux` (`2201116SG`). Salite da calcolare con quota GPS + Copernicus |
| **Base Temporale `SensorEvent.timestamp`** | M1 | ✅ Verificato | **Allineata (`elapsedRealtimeNanos`)** | `CLOCK_BOOTTIME` condiviso con il clock monotonico di sistema |
| **Frequenza Reale Accelerometro (~100 Hz)** | M1 | ✅ Superato | **`104.0 Hz` stabili** | Misurato a schermo spento (`27.748` record in `01:46`) |
| **Frequenza Reale Giroscopio (~100 Hz)** | M1 | ✅ Superato | **`104.0–104.1 Hz` stabili** | Sincronizzato allo stesso clock dell'accelerometro |
| **Frequenza Reale Magnetometro (~50 Hz)** | M1 | ✅ Superato | **`50.0 Hz` stabili** | Esattamente 50.0 Hz continui |
| **Step Detector & GPS Nativo (`GPS_PROVIDER`)** | M1 | ✅ Superato | **160 passi / 99 fix GPS** | `maxReportLatencyUs = 0` funziona senza ritardi a schermo spento |
| **Test Camminata a Schermo Spento (M1)** | M1 | ✅ Superato | **11 Heartbeat in 01:46 (Zero buchi)** | Batteria 84–85% (`31.5–32.4 °C`), `ThermalStatus = 0` |
| **Microfono `BUILTIN_MIC` + Cuffie in `A2DP`** | M2 | ✅ Superato | **`BUILTIN_MIC` + `MANKIW-X15` in `BLUETOOTH_A2DP`** | Zero switch a SCO/HFP durante registrazione WAV 48 kHz (`104.6 s`) |
| **Deriva Clock Audio (`AUDIO_SYNC`)** | M2 | ✅ Superato | **`+0.02 ms` su `104.6 s` (99 sync)** | Deriva praticamente nulla (< 0.2 ppm) tra ADC audio e `elapsedRealtimeNanos` |
| **Tasti AVRCP Cuffie Mankiw (`keyCode`)** | M2 | ✅ Superato | **Doppio click HW = `KEYCODE_MEDIA_NEXT (87)`** | Le `MANKIW-X15` inviano direttamente `KEYCODE_MEDIA_NEXT (87)` al doppio click |
| **Jitter e Underrun Metronomo (`AudioTrack`)** | M3 | ✅ Superato | **`Underrun: 0` \| `Jitter RMS: 3.04 ms` \| `Max: 10.58 ms`** | Su 129 click a 160 BPM (`WRITE_BLOCKING` a 48 kHz), ampiamente entro il target $\le 10\text{–}15\text{ ms}$ |
| **Calibrazione BT: Batti a Tempo vs Contatto** | M3 | ✅ Superato | **Contatto: `248.4 ms (±2.9 ms, 4/5)`** vs **Tap: `66.3 / -3.4 ms (±44–62 ms)`** | Il metodo "A contatto" funziona sulle cuffie ossee `MANKIW-X15` con precisione $\pm 2.9\text{ ms}$, mentre il tap umano anticipa il ritmo con alta dispersione |
| **Qualità Time-Stretch `Media3` (0,85x – 1,15x)** | M4 | ⏳ In test | *Pronto in M4* | Voto 1–5 e impatto CPU |
| **Sidechain EQ con `DynamicsProcessing`** | M4 | ⏳ In test | *Pronto in M4* | Attenuazione 300 Hz – 3 kHz udibile senza glitch |
| **Ducking Dinamico con Spotify** | M4 | ⏳ In test | *Pronto in M4* | Abbassamento volume vs Pausa indesiderata |
| **Onboarding Xiaomi / HyperOS** | M4 | ⏳ In test | *Pronto in M4* | Apertura Autostart + Risparmio batteria senza `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| **Corsa 60 min a Schermo Spento (HyperOS)** | M5 | ⏳ Da eseguire | *In attesa di M5* | Criterio finale: nessun buco `HEARTBEAT` > 15 s |
