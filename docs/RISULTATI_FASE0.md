# Risultati Sperimentali della Fase 0 (`docs/RISULTATI_FASE0.md`)

> Questo documento raccoglie i risultati oggettivi misurati con **KeepApp Logger (Fase 0)** sullo smartphone di riferimento e analizzati tramite `tools/read_session.py`, senza alcun dato personale o coordinata geografica privata.

## 1. Ambiente di Test
* **Dispositivo:** Xiaomi Redmi Note 11 Pro 5G (`2201116SG` / `veux`)
* **Versione Android / ROM:** Android 13 (`TKQ1.221114.001`) — HyperOS `1.0.12.0.TKCMIXM`
* **Cuffie Bluetooth:** Mankiw (`MANKIW-X15` — Conduzione ossea)

---

## 2. Tabella Riepilogativa dei Test (M1 – M5)

| Test / Verifica | Milestone | Stato | Valore Misurato / Esito | Note Tecniche |
| :--- | :---: | :---: | :--- | :--- |
| **Presenza Barometro Hardware** | M1 | ✅ Verificato | **ASSENTE** | Confermato sul modello `veux` (`2201116SG`). Salite da calcolare con quota GPS + Copernicus |
| **Base Temporale `SensorEvent.timestamp`** | M1 | ✅ Verificato | **`True` (`elapsedRealtimeNanos`)** | `CLOCK_BOOTTIME` condiviso con il clock monotonico di sistema (offset iniziale `-24.47 ms`) |
| **Frequenza Reale Accelerometro (~100 Hz)** | M1/M5 | ✅ Superato | **`104.05 Hz` (Mediana `104.05 Hz`, Jitter dt `±0.00 ms`)** | Misurato a schermo spento su `11.007` campioni |
| **Frequenza Reale Giroscopio (~100 Hz)** | M1/M5 | ✅ Superato | **`104.05 Hz` (Mediana `104.05 Hz`, Jitter dt `±0.00 ms`)** | Sincronizzato allo stesso clock dell'accelerometro (`11.001` campioni) |
| **Frequenza Reale Magnetometro (~50 Hz)** | M1/M5 | ✅ Superato | **`50.00 Hz` (Mediana `50.00 Hz`, Jitter dt `±0.00 ms`)** | Esattamente 50.00 Hz continui (`5.289` campioni) |
| **Step Detector & GPS Nativo (`GPS_PROVIDER`)** | M1 | ✅ Superato | **160 passi / 99 fix GPS** | `maxReportLatencyUs = 0` funziona senza batching a schermo spento |
| **Test Background a Schermo Spento** | M1/M2 | ✅ Superato | **11 Heartbeat in 103.5 s (Max $\Delta t = 10.56\text{ s}$, Zero buchi $> 15\text{ s}$)** | Batteria 84–87% (`31.5–32.4 °C`), `ThermalStatus = 0` |
| **Microfono `BUILTIN_MIC` + Cuffie in `A2DP`** | M2 | ✅ Superato | **`InputType=15 (BUILTIN_MIC)` + `OutputType=8 (BLUETOOTH_A2DP)`** | `A2DP=True`, `SCO=False` costanti per tutta la sessione con WAV 48 kHz attivo |
| **Deriva Clock Audio (`AUDIO_SYNC`)** | M2/M5 | ✅ Superato | **`+0.017 ms` su `103.5 s` (`+0.16 ppm`, 99 sync)** | Deriva praticamente nulla tra ADC audio 48 kHz e `elapsedRealtimeNanos` |
| **Tasti AVRCP Cuffie Mankiw (`keyCode`)** | M2/M5 | ✅ Superato | **1 tocco = `keyCode 127 (MEDIA_PAUSE)` \| 2 tocchi = `keyCode 87 (MEDIA_NEXT)`** | Entrambi i pattern intercettati e distinti correttamente in `MARKER` |
| **Jitter e Underrun Metronomo (`AudioTrack`)** | M3 | ✅ Superato | **`Underrun: 0` \| `Jitter RMS: 3.04 ms` \| `Max: 10.58 ms`** | Su 129 click a 160 BPM (`WRITE_BLOCKING` a 48 kHz), ampiamente entro il target $\le 10\text{–}15\text{ ms}$ |
| **Calibrazione BT: Batti a Tempo vs Contatto** | M3 | ✅ Superato | **Contatto: `248.4 ms (±2.9 ms, 4/5)`** vs **Tap: `66.3 / -3.4 ms (±44–62 ms)`** | Il metodo "A contatto" funziona sulle cuffie ossee `MANKIW-X15` con precisione $\pm 2.9\text{ ms}$, mentre il tap umano anticipa il ritmo con alta dispersione |
| **Qualità Time-Stretch `Media3` (0,85x – 1,15x)** | M4 | ✅ Superato | **Voto qualità: `5 ★ / 5`** | Algoritmo Sonic di ExoPlayer a tonalità preservata validato tra `0.85x` e `1.15x` |
| **Sidechain EQ con `DynamicsProcessing`** | M4 | ✅ Superato | **`Udibile: SÌ (Chiaro)` — Zero errori** | Attenuazione selettiva 300 Hz – 3 kHz (-12 dB con rampa 200 ms) agganciata all'`audioSessionId` di ExoPlayer (offload disattivato) |
| **Ducking Dinamico con Spotify** | M4 | ✅ Superato | **`Si è abbassato (Ducking OK)`** | Richiesta dinamica `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` + rilascio a fine frase: Spotify abbassa il volume senza mettersi in pausa |
| **Onboarding Xiaomi / HyperOS (F9)** | M4 | ✅ Superato | **Apertura Autostart + Risparmio Batteria OK** | Nessun uso di `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| **Esportazione `.zip` & Script Analisi PC (`F10/F11`)** | M5 | ✅ Superato | **`MediaStore.Downloads` + `tools/read_session.py`** | Validato su `session_20261114_223509_arm` con generazione grafico `PNG` |
| **Corsa 60 min a Schermo Spento (HyperOS)** | M5 | ⏳ Pronto per prova su strada | *Da completare durante l'uscita da 60 min di Adriano* | Criterio: nessun buco `HEARTBEAT` > 15 s |
