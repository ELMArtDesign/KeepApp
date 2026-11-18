# Discussione D52 — Modalità Allenamento e Rapporto con l'Ancora Elastica (`docs/D52_DOMANDE.md`)

> **Stato:** Bozza di discussione (solo testo, nessun codice). Da discutere insieme tra Adriano, Claude e Antigravity **dopo** il completamento di **T3** e prima di sbloccare la Fase 1B.

---

## 1. Contesto Attuale
In Fase 1A sono attivi solo i parametri base dell'**Ancora Elastica (D40)** con `FeatureFlags.ENABLE_TRAINING_PLAN = false` (`FeatureFlags.ENABLE_VOICE_COACH_TTS = false`).
Nel codice esistono già tre modalità concettuali in `TrainingPlanConfig`:
1. **`ADAPTIVE_ANCHOR` (Ancora Elastica):** aggancio naturale sui primi `24 s` stabili, rinforzo su cali brevi (`< 20 s`), adattamento lento su cali prolungati (`> 60 s`, max `-1 BPM / 10 s`, limite `±15%`).
2. **`FIXED_TARGET` (Target Fisso):** il runner sceglie un BPM fisso (es. `165 BPM`).
3. **`PROGRESSIVE_RAMP` (Rampa / Progressione):** progressione lineare da `BPM_start` a `BPM_end` in `N` minuti.

---

## 2. Domande Aperte per D52

### A) Rapporto tra Modalità Allenamento e Ancora Elastica
1. In **`FIXED_TARGET`**, se il runner rallenta molto rispetto al target fisso (es. stanchezza o semaforo), l'app:
   - mantiene rigidamente il target fisso fino alla sosta, oppure
   - attiva comunque il **rinforzo ritmico** (D40) o un'ancora elastica di sicurezza?
2. In **`PROGRESSIVE_RAMP`**, cosa succede in caso di **sosta** (`StopDetector` / semaforo)?
   - Il timer della rampa si **mette in pausa** durante la sosta reale e la pausa manuale, oppure continua a scorrere?
   - All'uscita da una sosta `< 2 min`, riprende esattamente dal BPM raggiunto prima della sosta?
3. L'aggancio iniziale (`WARMING_UP`, 24 s):
   - Deve valere **solo** per `ADAPTIVE_ANCHOR`, oppure anche `FIXED_TARGET` / `PROGRESSIVE_RAMP` dovrebbero partire solo dopo i primi passi stabili (invece che a ritmo fisso da fermo)?

### B) Gestione delle Variazioni e Comandi in Corsa
4. **Regolazione manuale al volo:** in `ADAPTIVE_ANCHOR` o `FIXED_TARGET`, serve poter alzare/abbassare il target di `±2` / `±5 BPM` (dalla UI o da un gesto)?
5. **Doppio tocco sulle cuffie Bluetooth (D44):**
   - Confermiamo di riservare il doppio tocco (`KEYCODE_MEDIA_NEXT` o doppio click entro `450 ms`) al **recap vocale on-demand** della 1B (es. *"Cadenza 164, obiettivo 165, 22 minuti"*), oppure vogliamo associarvi un'azione diversa?
   - Per cuffie come le `MANKIW-X15` (dove il doppio tocco invia direttamente `KEYCODE_MEDIA_NEXT` senza ritardare il singolo tocco), il recap vocale può funzionare a **zero latenza sul singolo tocco**.

### C) Proposta Sintetica da Valutare Dopo T3
- **Default in Fase 1B:** mantenere `ADAPTIVE_ANCHOR` come modalità predefinita.
- **Soste in `FIXED_TARGET` e `PROGRESSIVE_RAMP`:** congelare timer rampa e attenuare a `-20 dB` in sosta esattamente come in `ADAPTIVE_ANCHOR`, evitando che il metronomo continui a "correre" mentre l'utente è fermo.
- **Doppio tocco = Recap vocale:** usare i mattoni Kokoro pre-generati (Step C) con composizione breve (es. 2–3 secondi max) e attenuazione temporanea del ritmo (`ducking`).
