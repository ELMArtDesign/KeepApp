# Checklist UI KeepApp (`docs/CHECKLIST_UI.md` — Step B)

Documento di riferimento per l'architettura UI di `:app` dopo la suddivisione di `MainActivity.kt` in schermate dedicate (`it.daprile.keepapp.ui`).

## 1. Struttura dei File UI (`app/src/main/kotlin/it/daprile/keepapp/`)
- `MainActivity.kt` (< 150 righe): shell di navigazione (`Scaffold` + `TabRow` su 4 schede) e gestione permessi runtime.
- `ui/KeepAppUiStateHolder.kt`: state holder che coordina `KeepAppAudioEngine`, `BluetoothLatencyCalibrator`, `EarSyncTester`, `BtLatencyProfileStore`, `SessionHistoryStore` e scrittura di `ground_truth.json`.
- `ui/LiveSessionTab.kt`: stato corsa live, toggle **"Raccolta dati T1 (1 tocco = Marker)"**, pulsante `Segna Marker (n/4)`, controlli guida sonora, telemetria BT/PLL e apertura automatica di `GroundTruthDialog` a fine sessione.
- `ui/TrainingPlanCard.kt`: scheda Modalità Allenamento estratta per mantenere `LiveSessionTab.kt` < 350 righe.
- `ui/GroundTruthDialog.kt`: form **"Dati di riferimento"** a fine sessione (`passi contati`, `posizione`, `attività`, `ambiente`, `note`), derivazione automatica di `interval` e `real_stops` dai marker e salvataggio diretto di `ground_truth.json`.
- `ui/SessionHistoryTab.kt`: elenco sessioni completate (`SummaryCard` + grafico `CadenceTraceChart`), aggiornamento e svuotamento storico.
- `ui/AudioEngineTab.kt`: scheda **"Test audio"** per prove manuali su `Pattern Ritmico` / `Metronomo Semplice`, livelli `L1..L4`, BPM target, volume e telemetria `AudioEngine`.
- `ui/BluetoothCalibrationTab.kt`: scheda **"Calibrazione"** (stato dispositivo attivo, 1. Calibrazione a contatto 8 impulsi con salvataggio automatico `mediana − R_mic`, 2. Batti a tempo, *Test del colpo* `R_mic`, *Verifica altoparlante interno* `X`, override manuale).
- `ui/CalibrationResultCards.kt`: card risultato calibrazione e riepilogo confronto latenze 1d estratte per mantenere `BluetoothCalibrationTab.kt` < 380 righe.
- `ui/EarSyncSection.kt`: sezione **"Prova a orecchio"** (compensazione automatica `BT_salvato − X_vero` a `Fine = 0 ms`, regolazione fine `±150 ms`, esiti ascolto, esportazione completa negli appunti o in `Download/`).

## 2. Checklist di Verifica Rapida (Manuale)
- [ ] **Navigazione:** le 4 schede (`Live`, `Storico`, `Test audio`, `Calibrazione`) commutano senza crash né perdita di stato.
- [ ] **Avvio/Arresto mutuo:** avviare `Sessione Live` ferma eventuale `Audio Manuale`, `Calibrator` ed `EarSyncTester` (e viceversa).
- [ ] **Calibrazione automatica (`1`):** *Test del colpo* salva `R_mic`; *Contatto 8 impulsi* salva automaticamente `(mediana − R_mic)` sul profilo delle cuffie attive; *Prova a orecchio* a `Fine = 0 ms` applica `(BT_salvato − X_vero)` producendo **1 colpo**.
- [ ] **Raccolta T1 (`3`):** con `Raccolta dati T1` attivo, 4 tocchi cuffia registrano i 4 marker senza mettere in pausa; allo stop si apre `Dati di riferimento` e scrive `ground_truth.json` nella cartella della sessione.
