# Specifica del Formato Dati di Sessione (`docs/FORMAT.md`)

Questo documento definisce in modo formale e bit-accurate il formato dei file generati da **Moventure Logger (Fase 0)**.

Ogni sessione di registrazione viene salvata in una cartella dedicata con nome:
```text
session_YYYYMMDD_HHMMSS_<posizione>/
```
dove `<posizione>` è uno tra: `arm` (braccio), `hand` (mano), `waist` (vita/marsupio), `pocket` (tasca).

All'interno della cartella vengono generati tre file:
1. `manifest.json` — Metadati della sessione, informazioni sul dispositivo, elenco sensori e dispositivi audio.
2. `sensors.bin` — Flusso binario sequenziale Little-Endian contenente l'intestazione (32 byte) seguita da record a dimensione fissa di **32 byte ciascuno**.
3. `mic_raw.wav` *(da Milestone 2)* — File audio RIFF/WAVE PCM mono, 48.000 Hz, 16-bit signed integer (`int16`).

---

## 1. Base Temporale (`elapsedRealtimeNanos`)

Tutti i timestamp salvati in `sensors.bin` utilizzano come riferimento l'orologio monotonico di sistema Android **`SystemClock.elapsedRealtimeNanos()`** (tempo trascorso dall'avvio del sistema in nanosecondi, incluso il tempo trascorso in deep sleep a schermo spento).

All'avvio della sessione:
* Nel `manifest.json` vengono registrati sia `unix_start_ms` (`System.currentTimeMillis()`) sia `monotonic_start_ns` (`SystemClock.elapsedRealtimeNanos()`).
* Alla ricezione dei primi eventi dai sensori inerziali (`SensorEvent.timestamp`), l'app misura la differenza $|\text{SensorEvent.timestamp} - \text{SystemClock.elapsedRealtimeNanos()}|$ e registra nel `manifest.json` il campo booleano `sensor_timestamp_is_elapsed_realtime` e lo scarto iniziale in millisecondi `sensor_clock_offset_ms`. Sul Redmi Note 11 Pro 5G (Android 13), `SensorEvent.timestamp` utilizza la medesima base `CLOCK_BOOTTIME` di `elapsedRealtimeNanos()`.

---

## 2. Struttura Binaria di `sensors.bin`

Tutti i valori numerici multi-byte sono codificati in formato **Little-Endian (`<` in Python `struct`)**.

### 2.1 Intestazione del File (Header — esattamente 32 byte)

I primi 32 byte del file `sensors.bin` costituiscono l'header:

| Offset (Byte) | Tipo | Dimensione | Nome Campo | Valore / Descrizione |
| :--- | :--- | :--- | :--- | :--- |
| `0..7` | `char[8]` | 8 byte | `magic` | Stringa ASCII fissa: `"KEEPAPP0"` (`4B 45 45 50 41 50 50 30`) |
| `8..9` | `uint16` | 2 byte | `version` | Versione del formato binario: `1` (`0x0001`) |
| `10..11` | `uint16` | 2 byte | `record_size` | Dimensione di ogni record in byte: `32` (`0x0020`) |
| `12..15` | `uint32` | 4 byte | `flags` | Bitmask opzioni sessione (`bit 0`: barometro presente, `bit 1`: audio attivo) |
| `16..23` | `int64` | 8 byte | `monotonic_start_ns` | Valore di `elapsedRealtimeNanos` all'apertura del file |
| `24..31` | `int64` | 8 byte | `unix_start_ms` | Timestamp UTC Unix in millisecondi all'apertura del file |

---

### 2.2 Struttura dei Record Dati (32 byte fissi per record)

Dopo i primi 32 byte di intestazione, il file contiene una sequenza di $N$ record da **32 byte** ciascuno.
Poiché `32` è un multiplo esatto di 8, il file non può mai contenere record parziali se troncato a multipli di 32 byte.

Layout comune di ogni record da 32 byte:

| Offset (Byte) | Tipo | Dimensione | Nome Campo | Descrizione |
| :--- | :--- | :--- | :--- | :--- |
| `0..7` | `int64` (`<q`) | 8 byte | `timestamp_ns` | Timestamp monotonico in nanosecondi (`elapsedRealtimeNanos`) |
| `8` | `uint8` (`<B`) | 1 byte | `record_type` | Codice identificativo del tipo di record (vedi tabella sotto) |
| `9` | `uint8` (`<B`) | 1 byte | `flags` | Flag specifici del record (es. `SensorEvent.accuracy` o sottotipo) |
| `10..11` | `uint16` (`<H`) | 2 byte | `reserved` | Padding di allineamento a 32-bit (o codice extra a 16-bit) |
| `12..31` | Payload | **20 byte** | `payload` | 5 valori `float32` (`<5f`), oppure `double`+`double`+`float32` (`<ddf`), oppure `int64`+`int64`+`int32` (`<qqi`) a seconda di `record_type` |

---

### 2.3 Tabella dei Tipi di Record (`record_type`) e Codifica del Payload (20 byte)

| Codice (`uint8`) | Nome Tipo | Formato Payload (`struct` Python) | `flags` (`byte 9`) | `reserved` (`byte 10..11`) | Significato dei 20 byte di Payload (`byte 12..31`) |
| :---: | :--- | :---: | :--- | :--- | :--- |
| **`1`** | `ACCEL` | `<5f` (5 × `float32`) | `accuracy` (0..3) | `0` | `f0`: $a_x$ (m/s²), `f1`: $a_y$ (m/s²), `f2`: $a_z$ (m/s²), `f3`: $\|a\|$, `f4`: `0.0` |
| **`2`** | `GYRO` | `<5f` (5 × `float32`) | `accuracy` (0..3) | `0` | `f0`: $\omega_x$ (rad/s), `f1`: $\omega_y$ (rad/s), `f2`: $\omega_z$ (rad/s), `f3`: $\|\omega\|$, `f4`: `0.0` |
| **`3`** | `MAG` | `<5f` (5 × `float32`) | `accuracy` (0..3) | `0` | `f0`: $B_x$ ($\mu$T), `f1`: $B_y$ ($\mu$T), `f2`: $B_z$ ($\mu$T), `f3`: $\|B\|$, `f4`: `0.0` |
| **`4`** | `BARO` | `<5f` (5 × `float32`) | `accuracy` (0..3) | `0` | `f0`: pressione (hPa), `f1..f4`: `0.0` |
| **`5`** | `STEP` | `<5f` (5 × `float32`) | `1` = detector | `0` | `f0`: contatore passi cumulativo della sessione, `f1..f4`: `0.0` |
| **`6`** | `GPS_A` | `<ddf` (2 × `float64` + 1 × `float32`) | `1` = fix valido | satelliti (se disp.) | `d0` (8B): **Latitudine** (gradi decimali `Double`), `d1` (8B): **Longitudine** (gradi decimali `Double`), `f2` (4B): **Quota** (metri s.l.m. `Float`) |
| **`7`** | `GPS_B` | `<5f` (5 × `float32`) | bitmask hasSpeed/Bearing | `0` | Emesso insieme a `GPS_A`: `f0`: velocità (m/s), `f1`: rotta/bearing (gradi 0..360), `f2`: accuratezza orizzontale (m), `f3`: accuratezza verticale (m), `f4`: scarto `(location.elapsedRealtimeNanos - now) / 1e9` (s) |
| **`8`** | `MARKER` | `<5f` (5 × `float32`) | `1` = evento (1 click), `2` = incrocio (2 click), `3` = pulsante UI | `keyCode` Android grezzo | `f0`: indice progressivo marker, `f1`: `keyCode` (float), `f2`: numero tocchi ravvicinati, `f3..f4`: `0.0` |
| **`9`** | `METRONOME_CLICK` | `<qqi` (`int64`, `int64`, `int32`) | BPM corrente | `0` | `q0` (8B): indice campione scritto, `q1` (8B): `estimatedOutputNanoTime` da `AudioTrack.getTimestamp`, `i2` (4B): conteggio `underrunCount` |
| **`10`** | `AUDIO_SYNC` | `<qqi` (`int64`, `int64`, `int32`) | `1` = timestamp valido | `0` | `q0` (8B): `framePosition` da `AudioRecord.getTimestamp`, `q1` (8B): `nanoTime` da `AudioRecord.getTimestamp`, `i2` (4B): frame totali letti dal thread |
| **`11`** | `HEARTBEAT` | `<5f` (5 × `float32`) | stato servizio | `0` | Emesso ogni 10 s: `f0`: numero sequenziale heartbeat, `f1`: secondi trascorsi dall'inizio, `f2`: frequenza media ACCEL (Hz) negli ultimi 10 s, `f3`: frequenza media GYRO (Hz) negli ultimi 10 s, `f4`: numero fix GPS totali |
| **`12`** | `THERMAL` | `<5f` (5 × `float32`) | `thermalStatus` (0..6) | `0` | Emesso ogni 10 s: `f0`: livello batteria (0..100 %), `f1`: temperatura batteria (°C), `f2`: `thermalStatus` Android (`PowerManager`), `f3`: `getThermalHeadroom(10s)` (`NaN` se < API 30), `f4`: `0.0` |
| **`13`** | `ROUTE_CHANGE` | `<5f` (5 × `float32`) | `audioMode` (`0`=NORMAL, `2`=IN_CALL, `3`=IN_COMM) | motivo evento | `f0`: tipo dispositivo input attivo (`AudioDeviceInfo.TYPE_*`), `f1`: tipo dispositivo output primario (es. `8` = `TYPE_BLUETOOTH_A2DP`, `7` = `TYPE_BLUETOOTH_SCO`), `f2`: flag `isBluetoothScoOn` (`0.0` o `1.0`), `f3`: flag `isA2dpActive` (`0.0` o `1.0`), `f4`: numero dispositivi collegati |
