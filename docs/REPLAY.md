# Guida al Replay Harness KeepApp (`docs/REPLAY.md`)

Il modulo `:replay` esegue **offline sul PC** gli stessi algoritmi di `:core-domain` (`SessionStateMachine`, `BeatScheduler`, `KeepAppConfig`) sulle sessioni binarie registrate (`sensors.bin` + `manifest.json`), confrontandoli con un rilevatore offline indipendente (`FootstrikeReferenceDetector`).

> **Regola sicurezza dati:** non indicare MAI con `--out` una cartella dentro il repository Git. I file generati (`*.csv`, `riepilogo.md`, `riepilogo.json`) vanno sempre salvati in una cartella esterna (es. `C:\temp\keepapp_replay`).

---

## 1. Esecuzione su Windows (Riga di comando PowerShell)

Imposta `JAVA_HOME` (JDK di Android Studio) e lancia `:replay:run` passando gli argomenti con `--args="..."`:

### A) Analisi di una cartella con più sessioni
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
.\gradlew.bat :replay:run --args="--dir `"C:\Users\Lorenzo\Documenti\KeepApp_Sessioni`" --out `"C:\temp\keepapp_replay`""
```

### B) Analisi di una singola sessione con override parametri (`--config`)
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
.\gradlew.bat :replay:run --args="--session `"C:\Users\Lorenzo\Documenti\KeepApp_Sessioni\session_20261115_154503_arm`" --out `"C:\temp\keepapp_replay`" --config `"C:\temp\test_config.json`""
```

---

## 2. Esecuzione da Android Studio
1. Apri la finestra **Gradle** (barra laterale destra) → `KeepApp` → `replay` → `Tasks` → `application` → `run`, oppure crea una Run Configuration **Gradle** con:
   - **Task:** `:replay:run`
   - **Arguments:** `--args="--dir \"C:\percorso\sessioni\" --out \"C:\temp\keepapp_replay\""`
2. Premi **Run**.

---

## 3. Formato `ground_truth.json` (facoltativo, accanto a `manifest.json`)
Se presente nella cartella della sessione, ha la precedenza rispetto al campo `ground_truth_steps` in `manifest.json`:

```json
{
  "steps": 200,
  "interval": "markers",
  "position": "arm",
  "activity": "in_place",
  "environment": "indoor",
  "real_stops": [[42.0, 49.5]],
  "note": "200 passi contati tra primo e ultimo marker"
}
```

- `interval`: `"markers"` (conta tra il primo e l'ultimo `MARKER` registrato) oppure `"session"` (intera sessione).
- `real_stops`: elenco facoltativo `[t_inizio_s, t_fine_s]` delle soste reali effettuate.
- Se manca sia `ground_truth.json` sia `ground_truth_steps`, il report segna `n/d` per l'errore di cadenza.

---

## 4. Codici di uscita e File Generati in `--out`
- `<session_id>.csv`: serie temporale ogni `0,5 s`.
- `riepilogo.md`: tabella completa di tutte le sessioni con parametri e hash `KeepAppConfig`.
- `riepilogo.json`: riepilogo strutturato per i test automatici e i confronti.
- **Codice di uscita:** `0` se tutte le sessioni con ground truth rispettano le soglie 1A (`|err cadenza| < 2%`, `fase mediana < 30 ms`), diverso da `0` altrimenti.

---

## 5. Convenzione di Segno Unificata (Replay, App Live, Prova a Orecchio)
Tutti e tre i componenti adottano la **stessa convenzione** (con latenza cuffia `btLatencyMs >= 0`):
- **Scheduling nell'app live (`BeatScheduler.kt`) e nel replay (`SessionReplayEngine.kt`):**
  `targetEmissionNs = predictedFootstrikeNs - btLatencyNs - positionOffsetNs`
  *(un `btLatencyMs` positivo anticipa l'emissione verso il driver Bluetooth di `btLatencyMs`).*
- **Prova a orecchio (`EarSyncTester.kt`):**
  `t_bt_emission = t_speaker_ref - totalAppliedMs`
  *(un valore positivo anticipa il click in cuffia rispetto al click sull'altoparlante interno).*
- **Errore di fase nel replay (`SessionReplayEngine.kt`):**
  `earBeatTimeNs = outNs + btLatencyNs`
  `signedPhaseErrorMs = (earBeatTimeNs - nearestRefFootstrikeNs) / 1e6`
  - **Positivo (`+`):** il colpo arriva all'orecchio **DOPO** l'appoggio di riferimento (in ritardo).
  - **Negativo (`-`):** il colpo arriva all'orecchio **PRIMA** dell'appoggio di riferimento (in anticipo).
