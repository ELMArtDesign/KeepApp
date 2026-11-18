# Protocollo di Raccolta Dati su Strada — Chiusura Fase 0 (`docs/PROTOCOLLO_DATI.md`)

> **Destinatario:** Adriano D'Aprile
> **Applicazione da usare sul telefono:** **Moventure Logger (Fase 0)** (`it.daprile.keepapp.logger`, modulo `:logger`)
> **Scopo:** Raccogliere i 4 tracciati reali su strada necessari per sbloccare la taratura finale degli algoritmi della **Fase 1A** (decisioni D33 e D34).

---

## 📋 Prima di uscire: Controllo rapido (1 minuto)

1. Verifica di aver già eseguito i **3 passaggi Xiaomi / HyperOS** (scheda `3. Media3 & Xiaomi (M4)` nell'app Logger):
   * **Avvio automatico in background:** Attivo.
   * **Risparmio batteria:** *"Nessuna restrizione"*.
   * **Lucchetto nelle App Recenti:** App bloccata col lucchetto 🔒.
2. Accendi e collega le tue cuffie a conduzione ossea **`MANKIW-X15`** via Bluetooth.
3. Assicurati di avere almeno il **50% di batteria** e **500 MB liberi** sul telefono.

---

## 🏃 Test 1 — Conteggio di riferimento dei 200 passi (`D33c`) + Tasti Cuffie (`D34`)
**Durata:** ~2–3 minuti | **Obiettivo:** Misurare l'errore esatto dello Step Detector e dell'algoritmo di cadenza (obiettivo errore `< 2%`).

1. Apri **Moventure Logger (Fase 0)** nella scheda **`1. Sessione`**.
2. Imposta:
   * **Posizione:** `Tasca` (o `Braccio (Fascia)`)
   * **Attività:** `Camminata` (e poi ripeti una seconda volta con `Corsa`)
   * **3. Registra Microfono:** `ON`
   * **4. Metronomo in Sessione:** `OFF`
   * **5. Test sopravvivenza pura (D33d):** `OFF` (così i tasti delle cuffie sono attivi!)
3. Premi **`▶ AVVIA SESSIONE DI REGISTRAZIONE`**, metti il telefono nella posizione scelta e **resta fermo per 5 secondi**.
4. **Prova dei tasti cuffia (`D34`):**
   * Premi **1 volta** il tasto sulle cuffie Mankiw (sentirai 1 beep). Aspetta 3 secondi.
   * Premi **1 volta** di nuovo il tasto sulle cuffie Mankiw (così registriamo sia `PAUSE 127` sia `PLAY 126` o `85`). Aspetta 3 secondi.
   * Premi **2 volte rapide** il tasto sulle cuffie Mankiw (sentirai 2 beep, codice `87`). Aspetta 3 secondi.
   * Premi **3 volte rapide** il tasto sulle cuffie Mankiw (così verifichiamo se invia `PREVIOUS 88` o 3 tocchi!). Aspetta 5 secondi da fermo.
5. **Parti e conta a mente esattamente 200 passi** (ogni appoggio del piede destro o sinistro = 1 passo: `1, 2, 3, ... 200`):
   * Appena fai il **passo numero 1**, premi **1 volta** il tasto della cuffia (Marker di inizio dei 200 passi).
   * Cammina (o corri) a passo regolare contando fino a **200**.
   * Appena poggi il piede del **passo numero 200**, premi **1 volta** il tasto della cuffia (Marker di fine dei 200 passi) e **fermati per 5 secondi**.
6. Premi **`⏹ FERMA E SALVA SESSIONE`**.

---

## 📱 Test 2 — Camminata e Corsa nelle 4 Posizioni (`D33b`)
**Durata:** ~3 minuti per posizione (totale ~25 minuti) | **Obiettivo:** Tarare il classificatore di posizione (`PositionClassifier`) e la scelta dell'armonica della cadenza.

Registra **8 brevi sessioni da 3 minuti ciascuna a schermo spento** (4 posizioni × 2 attività):

| # | Posizione selezionata nell'app | Attività selezionata | Durata a schermo spento | Cosa fare |
| :---: | :--- | :--- | :---: | :--- |
| **2.1** | `Braccio (Fascia)` | `Camminata` | **3 min** | Cammina normalmente, fai 1 sosta di 15 s a metà |
| **2.2** | `Braccio (Fascia)` | `Corsa` | **3 min** | Corri a ritmo costante, fai 1 sosta di 15 s a metà |
| **2.3** | `Mano` | `Camminata` | **3 min** | Cammina tenendo il telefono in mano in modo naturale |
| **2.4** | `Mano` | `Corsa` | **3 min** | Corri tenendo il telefono in mano |
| **2.5** | `Vita (Marsupio)` | `Camminata` | **3 min** | Cammina col telefono nel marsupio / cintura |
| **2.6** | `Vita (Marsupio)` | `Corsa` | **3 min** | Corri col telefono nel marsupio / cintura |
| **2.7** | `Tasca` | `Camminata` | **3 min** | Cammina col telefono in tasca dei pantaloni |
| **2.8** | `Tasca` | `Corsa` | **3 min** | Corri col telefono in tasca dei pantaloni |

> 💡 **Consiglio pratico:** In ognuna di queste 8 sessioni da 3 minuti, dopo circa 1 minuto e mezzo **fermati completamente al semaforo o sul posto per 15 secondi** e poi riparti: così ogni sessione ci fornisce anche un test reale per il rilevatore di soste (`StopDetector`)!

---

## 🛡️ Test 3 — Sopravvivenza in Background SENZA MediaSession / SENZA AudioTrack (`D33d` — Scenario Spotify)
**Durata:** 30–60 minuti a schermo spento | **Obiettivo:** Verificare che HyperOS non chiuda il servizio quando Moventure non riproduce audio proprio (scenario in cui l'utente ascolta Spotify).

1. Nella scheda **`1. Sessione`** di Moventure Logger:
   * Disattiva **`3. Registra Microfono`** (`OFF`).
   * Disattiva **`4. Metronomo in Sessione`** (`OFF`).
   * **ATTIVA l'interruttore `5. Test sopravvivenza pura (D33d — Zero MediaSession / AudioTrack)` (`ON`)**.
2. *(Opzionale ma consigliato)* Avvia una playlist su **Spotify** nelle cuffie Mankiw.
3. Premi **`▶ AVVIA SESSIONE DI REGISTRAZIONE`**, **spegni lo schermo** e tieni il telefono in tasca o al braccio per **almeno 30–60 minuti** senza mai accendere lo schermo.
4. Alla fine riaccendi lo schermo e premi **`⏹ FERMA E SALVA SESSIONE`**.

---

## ⏱️ Test 4 — Corsa/Uscita completa di 60 Minuti a Schermo Spento (`D33a`)
**Durata:** 60 minuti continui a schermo spento | **Obiettivo:** Verificare che non ci sia alcun buco `HEARTBEAT > 15 s` su un'ora intera e misurare il consumo reale di batteria e la temperatura.

1. Nella scheda **`1. Sessione`** imposta la tua posizione preferita, `Corsa` (o camminata veloce), `5. Test sopravvivenza pura = OFF`.

---

## 🎯 Protocollo Taratura T1–T3 (App Moventure — Fase 1A)

Per le uscite di taratura **T1** e **T3** si usa direttamente **Moventure (`it.daprile.keepapp`)**, scheda **`Live`**, senza modificare file a mano.

### Convenzione Tasti Cuffia in Raccolta Dati (`T1`)
1. Prima di avviare la sessione, attiva l'interruttore **`Raccolta dati T1 (1 tocco = Marker)`** nella scheda **`Live`**.
   *(In questa modalità, `1 tocco` sulle `MANKIW-X15` registra un marker invece di mettere in pausa.)*
2. Avvia **`AVVIA SESSIONE LIVE`** e segui la sequenza dei **4 marker**:
   - **1° tocco cuffia** → **Inizio conteggio** (es. conta solo i passi del piede destro fino a `100` = `200` passi totali).
   - **2° tocco cuffia** → **Inizio sosta reale** (fermati sul posto per ~10 secondi).
   - **3° tocco cuffia** → **Fine sosta reale** (riprendi a camminare/correre continuando il conteggio).
   - **4° tocco cuffia** → **Fine conteggio**.
   *(Se registri solo **2 marker**, l'app imposterà `interval = "markers"` senza soste; con **4 marker** imposterà automaticamente sia `interval = "markers"` sia `real_stops = [[t2, t3]]`.)*
3. Premi **`FERMA SESSIONE LIVE`**: si apre automaticamente la schermata **`Dati di riferimento`**:
   - Inserisci **Passi contati** (es. `200`), **Posizione** (`Polso` / `Braccio` / `Mano` / `Vita` / `Tasca`), **Attività** (`Camminata` / `Corsa`), **Ambiente** (`Aperto` / `Chiuso`) ed eventuali **Note**.
   - Premi **`Salva ground_truth.json`**: l'app salva il file direttamente nella cartella della sessione accanto a `sensors.bin` e `manifest.json`.

### Matrice Uscite T1 (10 + 10 micro-sessioni da 3–5 minuti)
- **Priorità di taratura (`A10`):** **`Polso` (`WRIST`)** è la posizione principale usata da Adriano e ha priorità. Ordine consigliato: **1) `Polso – Camminata`**, **2) `Polso – Corsa`**, poi tutte le altre.
- **Gruppo TARATURA (Giorno 1):** 10 sessioni `{Polso, Braccio, Mano, Vita, Tasca} × {Camminata, Corsa}`, ciascuna con 1 sosta reale di ~10 s.
- **Gruppo VERIFICA (Altro giorno o percorso):** le stesse 10 sessioni ripetute in condizioni indipendenti (usate solo per validazione finale in `ReplayRegressionTest`, mai per tarare i parametri).
2. *(Per non riempire inutilmente lo spazio con 60 minuti di file WAV da 350 MB, puoi lasciare `3. Registra Microfono = OFF` oppure tenerlo `ON` se hai almeno 1 GB libero).*
3. Premi **`▶ AVVIA SESSIONE DI REGISTRAZIONE`**, **spegni subito lo schermo** e fai la tua sessione di **60 minuti**.
4. Al termine premi **`⏹ FERMA E SALVA SESSIONE`**.

---

## 💾 Come esportare le sessioni e passarle al PC

1. Nella scheda **`1. Sessione`** di Moventure Logger, scorri in basso fino a **`Sessioni salvate in memoria locale`**.
2. Su ciascuna sessione registrata, tocca il pulsante verde **`💾 Salva .ZIP in Download`**.
3. Collega il telefono al PC con il cavo USB, apri la cartella **`Download/Moventure_Exports`** del telefono e copia i file `.zip` in una cartella sul tuo PC **FUORI dal repository Git** (ad esempio `C:\Users\Lorenzo\Desktop\SessioniMoventure\`).
4. **ATTENZIONE PRIVACY:** Non copiare mai i file `.zip`, `.bin` o `.wav` dentro `C:\Users\Lorenzo\Desktop\KeepApp\` (anche se `.gitignore` li blocca già automaticamente).
