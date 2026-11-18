# Risultati Sperimentali della Fase 0 (`docs/RISULTATI_FASE0.md`)

> Questo documento raccoglie i risultati oggettivi misurati con **Moventure Logger (Fase 0)** sullo smartphone di riferimento e analizzati tramite `tools/read_session.py` / ispezione binaria, senza alcun dato personale o coordinata geografica privata.

## 1. Ambiente di Test
* **Dispositivo:** Xiaomi Redmi Note 11 Pro 5G (`2201116SG` / `veux`)
* **Versione Android / ROM:** Android 13 (`TKQ1.221114.001`) — HyperOS `1.0.12.0.TKCMIXM`
* **Cuffie Bluetooth:** Mankiw (`MANKIW-X15` — Conduzione ossea)

---

## 2. Tabella Riepilogativa dei Test Infrastrutturali (M1 – M5)

| Test / Verifica | Milestone | Stato | Valore Misurato / Esito | Note Tecniche |
| :--- | :---: | :---: | :--- | :--- |
| **Presenza Barometro Hardware** | M1 | ✅ Verificato | **ASSENTE** | Confermato sul modello `veux` (`2201116SG`). Salite da calcolare con quota GPS + Copernicus |
| **Base Temporale `SensorEvent.timestamp`** | M1 | ✅ Verificato | **`True` (`elapsedRealtimeNanos`)** | `CLOCK_BOOTTIME` condiviso con il clock monotonico di sistema (`-18` a `-45 ms`) |
| **Frequenza Reale Accelerometro (~100 Hz)** | M1/M5 | ✅ Superato | **`104.06 Hz` (Mediana `104.05 Hz`, `max_gap 9.6 ms`, `0` gap > 50 ms)** | Confermato fino a `385.685` campioni continui a schermo spento |
| **Frequenza Reale Giroscopio (~100 Hz)** | M1/M5 | ✅ Superato | **`104.05 Hz` (Mediana `104.05 Hz`, Jitter dt `±0.00 ms`)** | Sincronizzato allo stesso clock dell'accelerometro |
| **Frequenza Reale Magnetometro (~50 Hz)** | M1/M5 | ✅ Superato | **`50.00 Hz` (Mediana `50.00 Hz`, Jitter dt `±0.00 ms`)** | Esattamente `50.00 Hz` continui |
| **Microfono `BUILTIN_MIC` + Cuffie in `A2DP`** | M2 | ✅ Superato | **`InputType=15 (BUILTIN_MIC)` + `OutputType=8 (BLUETOOTH_A2DP)`** | `A2DP=True`, `SCO=False` costanti per tutta la sessione |
| **Onboarding Xiaomi / HyperOS (F9)** | M4 | ✅ Superato | **Apertura Autostart + Risparmio Batteria OK** | Nessun uso di `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| **Esportazione `.zip` & Script Analisi PC (`F10/F11`)** | M5 | ✅ Superato | **`MediaStore.Downloads` + `tools/read_session.py`** | Validato su `session_20261114_223509_arm` e sessioni del 15/11/2026 |

---

## 3. Sessioni di Validazione Raccolte il 15/11/2026 (in casa)

| Sessione | Data | Durata | Condizioni | Dati Misurati | Criterio / Esito |
| :--- | :---: | :---: | :--- | :--- | :--- |
| **`session_20261115_154503_arm`** *(200 passi sul posto)* | 15/11/2026 | `124 s` | **In casa**, braccio (fascia), marcia sul posto | **Marker esatto: `200/200` passi** · Replay `:core-domain`: `209` vs `207` totali (`+0.96%`), errore fase mediano `25.71 ms` | ✅ **Superato** *(sul posto; resta aperto il test in camminata/corsa reale)* |
| **`session_20261115_161238_arm`** *(Test tasti cuffie `MANKIW-X15`)* | 15/11/2026 | `76,82 s` | **In casa**, cuffie `MANKIW-X15` collegate | `19.897` record (`0` persi), `3` soli eventi `TYPE_HEADSET_KEY (8)` ricevuti: `keyCode 127 (MEDIA_PAUSE)`, `126 (MEDIA_PLAY)`, `127 (MEDIA_PAUSE)` | ⚠️ **Parziale / Nota HW** *(vedi §4 sotto)* |
| **`session_20261115_161832_arm_puresurvival`** *(Test sopravvivenza pura)* | 15/11/2026 | **`3.370,88 s` (~56 min 11 s)** | **In casa**, schermo spento, `pure_survival_mode = true` (nessun audio attivo) | `877.429` record scritti, **`0` persi** · Accelerometro `350.750` campioni @ `104.06 Hz`, **`max_gap = 9.6 ms`**, `0` gap > 50 ms | ✅ **Superato** *(HyperOS non killa né rallenta il `ForegroundService`)* |
| **`session_20261115_171619_arm`** *(Sopravvivenza standard ~60 min)* | 15/11/2026 | **`3.706,44 s` (~61 min 46 s)** | **In casa**, schermo spento, `pure_survival_mode = false` (audio attivo), batteria `46% → 38%` | `964.808` record scritti, **`0` persi** · Accelerometro `385.685` campioni @ `104.06 Hz`, **`max_gap = 9.6 ms`**, `0` gap > 50 ms · Consumo **`8%` (~`7,8%/h`)** | ✅ **Superato** |

---

## 4. Verifica Log Tasti Cuffie `MANKIW-X15` (Test del 15/11/2026)
La prima tabella preliminare di Fase 0 riportava `keyCode 87 (MEDIA_NEXT)` per la doppia pressione. Il test dedicato **`session_20261115_161238_arm`** del 15/11/2026 ha chiarito cosa arriva realmente dal firmware delle **`MANKIW-X15`**:
- Pressione **singola**: arriva regolarmente al telefono via AVRCP alternando `keyCode 127 (KEYCODE_MEDIA_PAUSE)` e `keyCode 126 (KEYCODE_MEDIA_PLAY)` (3 eventi registrati in `sensors.bin` di tipo `8`, con `seq` `0x7F01`, `0x7E01`, `0x7F01`).
- Pressione **doppia rapida** e **tripla rapida**: **non arrivano al telefono** (nessun `keyCode 87` né altri `KeyEvent` ricevuti), perché il firmware delle `MANKIW-X15` li intercetta internamente: **2 pressioni rapide = richiamata ultimo numero** (HFP), **3 pressioni rapide = assistente vocale** (Siri/Google Assistant).
- **Azione implementata (Correzione A1):**
  - Il doppio tocco resta **senza funzione** in Fase 1A (D44 lo riserva al recap vocale della 1B).
  - Supporto sia per `KEYCODE_MEDIA_NEXT (87)` sia per `2` click singoli entro `headsetDoubleTapWindowMs = 450 ms`.
  - Flag `supportsDoubleTap` nel profilo cuffie (`BtLatencyProfileStore`, default `false`): per le `MANKIW-X15` oggi il singolo tocco è **immediato senza attesa di `450 ms`**; se una cuffia invia un doppio tocco valido, il flag si attiva automaticamente.

---

## 5. Prove Fase 0 Ancora APERTE (Bloccate da Meteo — Da Completare su Strada)
- ⏳ **APERTO — Camminata e corsa all'aperto nelle 4 posizioni:** `braccio (ARM)`, `tasca (POCKET)`, `vita/marsupio (WAIST)`, `mano (HAND)`.
- ⏳ **APERTO — `200` passi contati a mano in camminata reale** (percorso lineare all'aperto, non sul posto).
- ⏳ **APERTO — `200` passi contati a mano in corsa reale** (percorso lineare all'aperto, non sul posto).
