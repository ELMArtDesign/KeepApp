#!/usr/bin/env python3
"""
Pipeline offline su PC (Step C1/C3) per generare frasi vocali con Kokoro-82M:
  24 kHz -> taglio silenzi -> 48 kHz -> filtro ricetrasmittente (leggero/marcato)
  -> loudnorm (-16 LUFS, TP <= -1 dBTP) -> Ogg Opus mono ~32 kbit/s + manifest.json (sha256, sintetica=true).
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf


FILTER_CHAINS = {
    "leggero": "aresample=48000:resampler=soxr,highpass=f=260,lowpass=f=4200,acompressor=threshold=-18dB:ratio=2.5:attack=5:release=60",
    "marcato": "aresample=48000:resampler=soxr,highpass=f=380,lowpass=f=3000,acompressor=threshold=-22dB:ratio=4.0:attack=3:release=50,asoftclip=type=tanh",
}


def check_dependencies() -> None:
    if shutil.which("ffmpeg") is None:
        sys.exit("Errore: ffmpeg non trovato nel PATH. Installalo su WSL2 con: sudo apt install ffmpeg")


def trim_silence(audio: np.ndarray, sr: int, threshold_db: float = -45.0, pad_ms: float = 25.0) -> np.ndarray:
    if audio.size == 0:
        return audio
    amp_thresh = 10.0 ** (threshold_db / 20.0)
    idx = np.where(np.abs(audio) >= amp_thresh)[0]
    if idx.size == 0:
        return audio
    pad = int(sr * pad_ms / 1000.0)
    start = max(0, int(idx[0]) - pad)
    end = min(audio.shape[0], int(idx[-1]) + pad + 1)
    trimmed = audio[start:end].copy()
    fade = min(int(sr * 0.005), trimmed.shape[0] // 4)
    if fade > 1:
        ramp = np.linspace(0.0, 1.0, fade, dtype=trimmed.dtype)
        trimmed[:fade] *= ramp
        trimmed[-fade:] *= ramp[::-1]
    return trimmed


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def encode_to_opus(raw_wav: Path, out_ogg: Path, filter_name: str) -> None:
    dsp = FILTER_CHAINS[filter_name]
    af = f"{dsp},loudnorm=I=-16:TP=-1.0:LRA=7"
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(raw_wav),
        "-af", af,
        "-ac", "1",
        "-ar", "48000",
        "-c:a", "libopus",
        "-b:a", "32k",
        "-vbr", "on",
        "-application", "voip",
        str(out_ogg),
    ]
    subprocess.run(cmd, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Generatore pack vocali Kokoro-82M -> Ogg Opus 48 kHz")
    parser.add_argument("--phrases", required=True, help="Percorso JSON con le frasi")
    parser.add_argument("--out", required=True, help="Cartella di output (FUORI dal repository Git)")
    parser.add_argument("--voices", nargs="+", default=["if_sara", "im_nicola"])
    parser.add_argument("--filters", nargs="+", default=["leggero", "marcato"])
    args = parser.parse_args()

    check_dependencies()

    repo_root = Path(__file__).resolve().parents[2]
    out_dir = Path(args.out).resolve()
    if repo_root in out_dir.parents or out_dir == repo_root:
        sys.exit(f"RIFIUTATO: --out non può stare dentro il repository ({repo_root}).")

    out_dir.mkdir(parents=True, exist_ok=True)
    with open(args.phrases, "r", encoding="utf-8") as f:
        phrases_doc = json.load(f)

    try:
        from kokoro import KPipeline
    except ImportError as exc:
        sys.exit(f"Errore import kokoro ({exc}). Installa in WSL2: pip install kokoro>=0.9.4 soundfile")

    pipeline = KPipeline(lang_code="i")
    entries = []
    listening_lines = [
        "# Elenco Ascolto Campioni C3 (Kokoro-82M)",
        "",
        "| # | Voce | Filtro | ID Frase | Testo | File |",
        "| :---: | :--- | :--- | :--- | :--- | :--- |",
    ]

    with tempfile.TemporaryDirectory() as tmp_str:
        tmp_dir = Path(tmp_str)
        counter = 0
        for voice in args.voices:
            for flt in args.filters:
                if flt not in FILTER_CHAINS:
                    sys.exit(f"Filtro non valido: {flt}")
                sub_dir = out_dir / voice / flt
                sub_dir.mkdir(parents=True, exist_ok=True)
                for item in phrases_doc["phrases"]:
                    pid = item["id"]
                    text = item["text"]
                    chunks = []
                    for _, _, audio_chunk in pipeline(text, voice=voice, speed=1.05):
                        chunks.append(np.asarray(audio_chunk, dtype=np.float32))
                    if not chunks:
                        continue
                    full_audio = trim_silence(np.concatenate(chunks), sr=24_000)
                    wav_path = tmp_dir / f"{voice}_{flt}_{pid}.wav"
                    sf.write(str(wav_path), full_audio, 24_000)

                    ogg_rel = f"{voice}/{flt}/{pid}.ogg"
                    ogg_path = out_dir / ogg_rel
                    encode_to_opus(wav_path, ogg_path, flt)

                    counter += 1
                    entries.append({
                        "id": pid,
                        "voice": voice,
                        "filter": flt,
                        "text": text,
                        "file": ogg_rel,
                        "sample_rate_hz": 48000,
                        "channels": 1,
                        "codec": "ogg_opus",
                        "target_lufs": -16.0,
                        "max_true_peak_dbtp": -1.0,
                        "sintetica": True,
                        "sha256": sha256_file(ogg_path),
                    })
                    listening_lines.append(f"| {counter} | `{voice}` | `{flt}` | `{pid}` | {text} | `{ogg_rel}` |")

    manifest = {
        "pack_id": phrases_doc.get("pack_id", "keepapp_voice_pack"),
        "generator": "Kokoro-82M (Apache 2.0)",
        "sintetica": True,
        "count": len(entries),
        "entries": entries,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / "ascolto_c3.md").write_text("\n".join(listening_lines) + "\n", encoding="utf-8")
    print(f"Generati {len(entries)} campioni Ogg Opus in: {out_dir}")


if __name__ == "__main__":
    main()
