# Pipeline Vocale Kokoro-82M (`tools/voice/` — Step C1 & C3)

Pipeline **offline su PC (WSL2)** per generare i pacchetti vocali pre-generati dell'app in **Ogg Opus mono 48 kHz ~32 kbit/s** con **Kokoro-82M** (Apache 2.0) + `espeak-ng` (usato **solo sul PC**, mai incluso nell'APK Android).

## 1. Installazione su Windows + WSL2 (Ubuntu)

1. Apri PowerShell come Amministratore su Windows e installa WSL2 Ubuntu (se non già presente):
   ```powershell
   wsl --install -d Ubuntu
   ```
2. Dentro il terminale **Ubuntu (WSL2)** installa le dipendenze di sistema (`espeak-ng` e `ffmpeg`):
   ```bash
   sudo apt update
   sudo apt install -y espeak-ng ffmpeg python3-venv python3-pip
   ```
3. Crea l'ambiente Python e installa Kokoro-82M + elaborazione audio:
   ```bash
   python3 -m venv ~/.venvs/keepapp-voice
   source ~/.venvs/keepapp-voice/bin/activate
   pip install --upgrade pip
   pip install "kokoro>=0.9.4" soundfile numpy scipy
   ```

## 2. Generazione dei 40 campioni di ascolto C3 (FUORI dal repository)

I 40 campioni (`10 frasi × 2 voci [if_sara, im_nicola] × 2 filtri [leggero, marcato]`) vanno sempre esportati **fuori** dal repository Git (es. `/mnt/c/temp/keepapp_voice_c3`):

```bash
source ~/.venvs/keepapp-voice/bin/activate
cd /mnt/c/Users/Lorenzo/Desktop/KeepApp/tools/voice
python generate_voice_pack.py \
  --phrases phrases_c3.json \
  --out /mnt/c/temp/keepapp_voice_c3 \
  --voices if_sara im_nicola \
  --filters leggero marcato
```

## 3. Specifiche DSP della Pipeline (C1)
1. **Sintesi:** Kokoro-82M (`lang_code='i'`, voci italiane `if_sara` e `im_nicola`) @ `24.000 Hz`.
2. **Taglio silenzi:** rimozione dei silenzi iniziali/finali sotto `-45 dBFS` con margine di sicurezza `25 ms` + fade `5 ms`.
3. **Ricampionamento:** `24.000 Hz → 48.000 Hz` tramite `ffmpeg` (`aresample=resampler=soxr`).
4. **Filtro ricetrasmittente:**
   - `leggero`: banda `260 Hz – 4.200 Hz`, leggera compressione dinamica (`acompressor`).
   - `marcato`: banda `380 Hz – 3.000 Hz`, compressione più stretta + saturazione morbida (`acompressor` + `tanh`).
5. **Loudness e picco:** normalizzazione EBU R128 / `ffmpeg loudnorm` a **`-16 LUFS`** con true-peak **`<= -1,0 dBTP`**.
6. **Codifica finale:** Ogg Opus mono (`libopus -ac 1 -b:a 32k -ar 48000`).
7. **Output:** file `.ogg`, `manifest.json` (con `sha256` e `"sintetica": true`) e `ascolto_c3.md` per la valutazione di Adriano.
