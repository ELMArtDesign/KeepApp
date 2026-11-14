# Risultati Sperimentali della Fase 0 (`docs/RISULTATI_FASE0.md`)

> Questo documento raccoglie i risultati oggettivi misurati con **KeepApp Logger (Fase 0)** sullo smartphone di riferimento, senza alcun dato personale o coordinata geografica privata.

## 1. Ambiente di Test
* **Dispositivo:** Xiaomi Redmi Note 11 Pro 5G (`2201116SG` / `veux`)
* **Versione Android / ROM:** Android 13 (`TKQ1.221114.001`) — HyperOS `1.0.12.0.TKCMIXM`
* **Cuffie Bluetooth:** Mankiw (`MANKIW-X15` — Conduzione ossea)

---

## 2. Tabella Riepilogativa dei Test (M1 – M5)

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
| **Qualità Time-Stretch `Media3` (0,85x – 1,15x)** | M4 | ✅ Superato | **Voto qualità: `5 ★ / 5`** | Algoritmi Sonic di ExoPlayer a tonalità preservata validati tra `0.85x` e `1.15x` con impatto CPU minimo |
| **Sidechain EQ con `DynamicsProcessing`** | M4 | ✅ Superato | **`Udibile: SÌ (Chiaro)` — Zero errori** | Attenuazione selettiva 300 Hz – 3 kHz (-12 dB con rampa 200 ms) agganciata all'`audioSessionId` di ExoPlayer (offload disattivato) |
| **Ducking Dinamico con Spotify** | M4 | ✅ Superato | **`Si è abbassato (Ducking OK)`** | Richiesta dinamica `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` + rilascio a fine frase: Spotify abbassa il volume senza mettersi in pausa |
| **Onboarding Xiaomi / HyperOS (F9)** | M4 | ✅ Superato | **Apertura Autostart + Risparmio Batteria OK** | Nessun uso di `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| **Esportazione `.zip` & Script Analisi PC (`F10/F11`)** | M5 | ✅ Completato | **`MediaStore.Downloads` + `tools/read_session.py`** | Script validato su sessione reale estratta dal dispositivo |
| **Corsa 60 min a Schermo Spento (HyperOS)** | M5 | ⏳ Pronto per prova su strada | *Pronto per la corsa di 60 min di Adriano* | Criterio: nessun buco `HEARTBEAT` > 15 s |
