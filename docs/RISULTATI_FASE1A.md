# Risultati e Criteri di Accettazione della Fase 1A (`docs/RISULTATI_FASE1A.md`)

> Questo documento traccia lo stato reale dei criteri di accettazione della **Fase 1A** di KeepApp (`it.daprile.keepapp`) sullo Xiaomi Redmi Note 11 Pro 5G + cuffie a conduzione ossea `MANKIW-X15`.

## 1. Tabella dei Criteri di Accettazione (Fase 1A)

| Criterio | Target Fase 1A | Valore Misurato | Condizioni del Test | Stato |
| :--- | :---: | :--- | :--- | :---: |
| **Errore stima cadenza** | **`< 2%`** | `+0.96%` (`209` vs `207` passi su `session_20261115_154503_arm`) in house replay; **nessun dato su corsa reale nelle 4 posizioni** | Finora misurato solo su marcia sul posto in casa (`124 s`) + prove brevi in casa (`29–253 s`) | ⏳ **APERTO** *(manca corsa reale nelle 4 posizioni)* |
| **Allineamento fase (colpo vs appoggio)** | **`< 30 ms`** | `25.71 ms` errore fase mediano su `session_20261115_154503_arm` (replay); **da verificare su corsa reale + a orecchio** | Replay su marcia sul posto in casa (`ARM`), non ancora validato su corsa su strada | ⏳ **APERTO** *(manca corsa su strada + test a orecchio)* |
| **Stabilità 60 min senza underrun né interruzioni** | **`60 min`, `0` underrun** | `0` underrun su test audio M2 (`435` campioni PLL `0.251–0.260 ms` RMS); `61 min 46 s` continui con `0` record persi in Fase 0 | M2 testato in casa per alcuni minuti; test 60 min completi svolto solo col logger Fase 0 | ⏳ **APERTO** *(manca uscita continua 60 min con app 1A)* |
| **Tempo ingresso in sosta (`-20 dB`)** | **`<= 4 s`** | Parametro impostato a `stopNoStepTimeoutSeconds = 3.5 s` (dopo test con `2.0 s` che causava false soste in camminata leggera) | Verificato empiricamente in casa in camminata (`Soste: 0` in tasca su `56 s`) | ⏳ **APERTO** *(da misurare con precisione nel replay + su strada)* |
| **Ripresa dalla sosta** | **`<= 4 passi`** | `stopResumeConsecutiveSteps = 4` passi (`~1,5–2 s`) | Verificato empiricamente nei test in casa (`Sessioni #1–#3`) | ⏳ **APERTO** *(da verificare su strada con GPS attivo)* |
| **Consumo batteria / ora** | **Monitorato (`<= 12%/h`)** | **`~7.8% / ora`** (`46% → 38%` in `61 min 46 s`) | Misurato in Fase 0 (`session_20261115_171619_arm`) con sensori `104 Hz` + audio attivo | ✅ **SUPERATO (Fase 0)** *(da riconfermare su uscita 60 min 1A)* |
| **Precisione PLL `AudioTrack.getTimestamp()` (D36)** | **`< 0.50 ms`** | **`0.251 ms`** (`310` campioni) / **`0.260 ms`** (`435` campioni) con OLS `128` campioni, `0` underrun | Misurato in casa su `MANKIW-X15` via Bluetooth A2DP (jitter grezzo HAL `2.84–2.94 ms`) | ✅ **SUPERATO** |

---

## 2. Calibrazione Latenza Bluetooth `MANKIW-X15` (D35 / M3)

### Passaggio da `248 ms` (Fase 0) a `153,4 ms ± 5,3 ms` (Milestone 3)
- **Vecchio riferimento (`248,4 ms ± 2,9 ms` in Fase 0):** il logger Fase 0 stimava l'istante di emissione dell'impulso al **ritorno di `AudioTrack.write()`** (prima che il buffer interno di `AudioTrack` + HAL fosse effettivamente consegnato al driver Bluetooth) e senza normalizzazione unificata tra `CLOCK_MONOTONIC` e `TIMEBASE_BOOTTIME`. Quel valore includeva quindi anche il buffer di scrittura lato app (~80–95 ms).
- **Nuovo riferimento (`153,4 ms ± 5,3 ms` in KeepApp M3):** l'impulso viene mappato tramite **`AudioClockEstimator` (regressione OLS su `AudioTrack.getTimestamp()` normalizzato a `TIMEBASE_BOOTTIME`)** per conoscere l'istante reale di consegna all'uscita hardware, confrontato con l'istante di cattura microfono `AudioRecord` su `TIMEBASE_BOOTTIME` (`4/5` impulsi validi filtrati con MAD: `151.1, 163.2, 155.7, 149.5 ms`, outlier iniziale `173.5 ms` scartato).
- *(Nota: il test "Batti a tempo" aveva restituito `92,1 ms ± 12,1 ms` su `14/16` tap validi a causa della fisiologica *negative mean asynchrony* umana di ~50–60 ms su click regolari; la misura oggettiva usata dal profilo `MANKIW-X15` è `153,4 ms`.)*

### Validazione a Orecchio della Latenza Calibrata
| Verifica | Procedura | Esito | Stato |
| :--- | :--- | :--- | :---: |
| **Confronto a orecchio altoparlante vs cuffia (`153,4 ms` ON / OFF)** | Emettere un click di riferimento sull'altoparlante del telefono e un click compensato sulle `MANKIW-X15` (esiti con totale applicato in ms) | `3,4 ms` → prima telefono; `38,4 ms` → prima telefono; `73,4 ms` → **1 colpo (×2)**; `78,4 ms` → **1 colpo (×2)**; `88,4 / 98,4 / 108,4 / 118,4 / 128,4 ms` → 2 colpi, sempre più distanziati all'aumentare del valore; `193,4 / 218,4 / 298,4 ms` → prima cuffia. Finestra di fusione: inferiore `38,4–73,4 ms`, superiore `78,4–88,4 ms` (**valore percettivo provvisorio `~75 ms`, relativo all'altoparlante interno**). | ⏳ **IN CORSO** *(manca misura `X` altoparlante)* |
| **Controllo loopback D35 altoparlante interno (`X`)** | Stesso metodo `AudioTrack.getTimestamp()` + `AudioRecord.getTimestamp(TIMEBASE_BOOTTIME)` forzato su `TYPE_BUILTIN_SPEAKER` (`USAGE_ASSISTANCE_SONIFICATION`) | **`X_raw = 33,0 ms`** (MAD `±0,1 ms`); con `R_mic = 4,7 ms` (`MAD ±0,5 ms`, FIFO IMU `4,2 ms`), **`X_vero = 28,3 ms`** | ✅ **MISURATO** |
| **Verifica 1d completa (stessa connessione BT)** | Test del colpo (`R_mic`) → Loopback altoparlante (`X_vero`) → Contatto 8 impulsi (`BT loopback`) → Prova a orecchio (`BT orecchio`) | `BT loopback = 125,3 ms` (`MAD ±3,9 ms`, `7/8` impulsi); Prova a orecchio: `85,3 ms` e `90,3 ms` → **1 colpo** → `BT orecchio = 113,6–118,6 ms` (**scarto `+6,7..+11,7 ms`**) | ✅ **RICONCILIATO** |

### Riconciliazione Numeri (153,4 ms vs ~75 ms a orecchio vs +62,3 ms replay `171619`)
1. **Convenzione di segno (verificata identica tra replay e app live):**
   - **Replay (`SessionReplayEngine.kt`):** `earNs = outNs + btLatencyNs` e `signedErrMs = (earNs - refFootstrikesNs) / 1e6`. **Positivo (`+`) = colpo all'orecchio DOPO l'appoggio di riferimento (in ritardo)**; negativo (`-`) = in anticipo.
   - **Scheduling (`BeatScheduler.kt`):** `targetEmissionNs = predictedFootstrikeNs - btLatencyNs - positionOffsetNs` (**sottrae** `btLatencyNs`, sia nel replay sia nell'app live via `KeepAppSessionService → KeepAppAudioEngine → BeatScheduler`).
2. **Scomposizione del `+62,3 ms` nella sessione `171619`:**
   - **Latenza BT (`153,4 ms`):** nel replay si **annulla** esattamente (`targetEmissionNs` la sottrae, `earNs = outNs + btLatencyNs` la risomma). Quindi il `+62,3 ms` del replay **non** indica che serva una compensazione BT maggiore.
   - **Ritardo di gruppo del filtro causale (`CadenceEstimator`):** il passa-banda Butterworth causale (`0,5–5,0 Hz` @ `104,05 Hz`) introduce un ritardo di fase di **`+103,3 ms` @ 90 SPM**, **`+80,5 ms` @ 120 SPM**, **`+71,5 ms` @ 150 SPM** rispetto al riferimento offline non causale (`FootstrikeReferenceDetector`, ritardo `0 ms`).
   - **Doppia sottrazione di `positionOffsetMs` (bug individuato):** `PhaseTracker.kt` sottrae già `positionOffsetNs` da `prevTimestampNs`, e `BeatScheduler.kt` lo sottrae **una seconda volta**. Nella `171619` (classificata al 78% come `WAIST`, `8 ms`), la doppia sottrazione anticipa di `2 × 8 ms = -16,0 ms`.
   - **Bilancio:** `+80,5 ms` (ritardo filtro causale ~120 SPM) `- 16,0 ms` (doppia sottrazione `WAIST`) = **`+64,5 ms`**, coerente con i **`+62,3 ms`** misurati dal replay.
   - **Ritardo FIFO sensori (D36) e lag meccanico piede→braccio:** non entrano in questo confronto replay perché `SensorEvent.timestamp` è il timestamp hardware dell'evento (non l'arrivo del batch FIFO) e `FootstrikeReferenceDetector` usa lo stesso segnale IMU.
3. **Riconciliazione finale 1d (`AudioRecord.getTimestamp` ATTIVO):**
   - `R_mic (colpo scocca) = 4,7 ms` (`MAD ±0,5 ms`), `Ritardo FIFO IMU (D36) = 4,2 ms`.
   - `X_raw (altoparlante) = 33,0 ms` (`MAD ±0,1 ms`) → **`X_vero = 33,0 - 4,7 = 28,3 ms`**.
   - `BT da loopback (8 impulsi, getTimestamp + onset 25%/20 ms) = 125,3 ms` (`MAD ±3,9 ms`, `7/8` validi).
     - *Spiegazione dei ~28 ms nella vecchia misura (`153,4 ms`):* il vecchio codice usava `SystemClock.elapsedRealtimeNanos()` al ritorno di `AudioRecord.read()` e `totalRecSamples` invece di `AudioRecord.getTimestamp(TIMEBASE_BOOTTIME)` + `recEstimator`, includendo così `~28 ms` di buffer/driver di cattura (`AudioRecord.read()`).
   - `BT da orecchio`: fusione a `85,3–90,3 ms` → `85,3 + 28,3 = 113,6 ms` (fino a `90,3 + 28,3 = 118,6 ms`), con **scarto `loopback - orecchio` di `+6,7..+11,7 ms`**.
   - *Variazione tra connessioni Bluetooth:* la prova a orecchio ha dato fusione a `~78 ms` alle 22:58 e `85,3–90,3 ms` nella procedura 1d alle 23:30 → **tolleranza attesa `±10 ms` tra una connessione Bluetooth e l'altra**.
   - *Parità `AudioTrack` verificata:* `BluetoothLatencyCalibrator` (modalità cuffie BT) e `KeepAppAudioEngine` usano esattamente la stessa configurazione (`USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`, `48 kHz`, `MONO`, `PCM_16BIT`, `MODE_STREAM`, buffer `max(minBuf, 480*2*6)`).
   - **Decisione approvata (`MANKIW-X15` & regola generale):**
     - `125,3 ms` è il valore grezzo di loopback (con `R_mic = 4,7 ms` incluso) → valore netto salvato = **`125,3 - 4,7 = 120,6 ms`** (arrotondabile a `120 ms`).
     - **Regola generale implementata in app:** per tutte le cuffie, `valore salvato = mediana loopback a contatto (≥ 8 impulsi) − R_mic del telefono` (`R_mic` misurato una volta per telefono col *Test del colpo* e salvato in `BtLatencyProfileStore`).
