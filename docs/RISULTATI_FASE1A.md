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
| **Controllo loopback D35 altoparlante interno (`X`)** | Stesso metodo `AudioTrack.getTimestamp()` + `AudioRecord` su `TIMEBASE_BOOTTIME` forzato su `TYPE_BUILTIN_SPEAKER` (`USAGE_ASSISTANCE_SONIFICATION`) | **Mediana `47,0 ms`** (media `46,9 ms`, dev. std `0,6 ms`, **`8/8` impulsi** rilevati) | ✅ **MISURATO** |

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
3. **Calcolo `BT_vero = 78 + X` e proposta motivata:**
   - Con **`X = 47,0 ms`** (media `46,9 ms`, dev. std `0,6 ms`, `8/8` impulsi) e valore percettivo a orecchio **`78 ms ± 5 ms`**:
     - **`BT_vero = 78 + 47,0 = 125,0 ms`** (intervallo `120–130 ms`).
     - Confronto con calibrazione a contatto (`153,4 ms` su `4/5` impulsi): scarto residuo di **`28,4 ms`**.
     - *Nota fisica (`R_mic`):* sia `X = 47,0 ms` sia la calibrazione a contatto (`153,4 ms`) includono la latenza di cattura del microfono `AudioRecord` (`R_mic`), assente nell'uso reale in cuffia; per questo `78 + X = 125,0 ms` è direttamente confrontabile con il loopback a contatto `153,4 ms`, mentre il ritardo puro d'uscita BT (senza microfono) è inferiore di `R_mic`.
   - **Proposta per la calibrazione da usare (in attesa di conferma di Adriano e Claude):**
     - **Non modificare ancora** `153,4 ms` salvati senza approvazione.
     - Prima di decidere definitivamente, eseguire la nuova **calibrazione a contatto a 8 impulsi** su `MANKIW-X15` per verificare se su 8 impulsi il valore resta `153,4 ms` o converge verso `~125–140 ms`; il valore proposto dalla riconciliazione a orecchio + loopback `X` è **`125,0 ms`** (`78 + 47,0 ms`).
     - In parallelo, in Fase A3 vanno corretti il ritardo di gruppo del filtro causale (`~72–80 ms`) e la doppia sottrazione di `positionOffsetNs`, che dominano l'errore di fase nei replay.
