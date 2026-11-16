# Proposta Tecnica — Modalità Allenamento (`TrainingPlan`) per Discussione D52

> **Stato:** Congelata dalla UI in Step A0 (`FeatureFlags.ENABLE_TRAINING_PLAN = false`) in attesa di allineamento architetturale D52 prima dell'eventuale attivazione in Fase 1B / Fase 2.

## 1. Obiettivo
Permettere al runner di scegliere tra tre modalità di guida ritmica durante una sessione:
1. **`ADAPTIVE_ANCHOR` (Ancora Elastica — default D40):** comportamento classico di KeepApp; il ritmo si aggancia alla cadenza naturale dell'utente e la sostiene nei cali temporanei.
2. **`FIXED_TARGET` (Target Fisso):** il ritmo suona a un BPM costante scelto dall'utente (`90..200 BPM`) fin dall'inizio della sessione, per allenare una cadenza specifica.
3. **`PROGRESSIVE_RAMP` (Progressione Lineare):** il ritmo parte da `rampStartBpm` e sale (o scende) linearmente fino a `rampEndBpm` nell'arco di `rampDurationMinutes` (`5..60 min`).

## 2. Regole di Interazione con l'Ancora Elastica (D40) e con i Sensori
- In `ADAPTIVE_ANCHOR`: `ElasticAnchor` guida sia il BPM target sia l'eventuale livello di rinforzo (`L3`).
- In `FIXED_TARGET` e `PROGRESSIVE_RAMP`:
  - Il BPM programmato da `TrainingPlanConfig.scheduledTargetBpm(elapsedSeconds)` **scavalca** il target calcolato da `ElasticAnchor`.
  - `CadenceEstimator`, `PositionClassifier`, `PhaseTracker` e `StopDetector` **restano comunque attivi** in background:
    - se l'utente si ferma (`ACTIVE_STOPPED`), il volume attenua comunque a `-20 dB` dopo `stopNoStepTimeoutSeconds`;
    - se l'utente mette in pausa (cuffia o UI, `PAUSED`), l'audio va in mute e il timer di progressione deve gestire l'eventuale pausa (vedi §3);
    - la posizione stimata continua a fornire `positionOffsetMs` al `BeatScheduler`, ma l'aggancio di fase ha senso solo quando la cadenza reale dell'utente è vicina al target programmato (`|cadenceSpm - targetBpm| <= soglia`), altrimenti il ritmo funge da metronomo puro.

## 3. Casi Limite da Discutere (D52)
1. **Tempo trascorso durante Pausa o Sosta nella `PROGRESSIVE_RAMP`:**
   - Attualmente `scheduledTargetBpm(elapsedSeconds)` usa `elapsedSeconds` totale della sessione. Se il runner si ferma a un semaforo per 2 minuti o mette in pausa per 3 minuti, la rampa continuerebbe ad avanzare durante la pausa.
   - **Proposta per D52:** usare `activeElapsedSeconds` (solo tempo in `ACTIVE_RUNNING`) per l'avanzamento della rampa.
2. **Sfasamento forte tra cadenza reale e target fisso:**
   - Se il runner imposta `175 BPM` ma corre a `155 SPM`, l'aggancio di fase (`PhaseTracker` → `BeatScheduler`) cercherebbe di inseguire un appoggio a frequenza diversa creando battimenti ritmici.
   - **Proposta per D52:** in `FIXED_TARGET` / `PROGRESSIVE_RAMP`, applicare la correzione di fase solo se `|cadenceSpm - scheduledBpm| <= 5 BPM`, altrimenti mantenere fase libera a tempo esatto.
3. **Livelli di intensità (`L0..L3`) con target fisso:**
   - Con `ElasticAnchor` bypassata, `AnchorState` non dovrebbe guidare i livelli `L2/L3`; serve definire se in modalità fissa/progressiva i livelli siano solo manuali o legati allo scarto tra cadenza reale e target (es. `L3` se il runner scende > 3% sotto il target programmato).
4. **Avvio immediato vs attesa primi passi:**
   - Attualmente `FIXED_TARGET` / `PROGRESSIVE_RAMP` parte subito al tap su "Avvia" (utile come metronomo guida da subito), mentre `ADAPTIVE_ANCHOR` attende i primi passi. Da confermare se è il comportamento desiderato.
