#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright 2026 Adriano D'Aprile
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
KeepApp — Fase 0: Script di Lettura e Analisi Sessione (F11)

Legge una sessione esportata da KeepApp Logger (cartella o file .zip situati
IN UNA CARTELLA ESTERNA AL REPOSITORY GIT) e produce:
1. Report diagnostico testuale completo:
   - Frequenza reale misurata di Accelerometro, Giroscopio e Magnetometro.
   - Verifica degli intervalli HEARTBEAT (segnalando eventuali buchi > 15 s).
   - Analisi della deriva del clock audio da AUDIO_SYNC (ms e ppm).
   - Elenco degli eventi ROUTE_CHANGE (verifica tenuta A2DP vs SCO/HFP).
   - Elenco dei MARKER (tasti cuffie Bluetooth / UI) e dei METRONOME_CLICK.
2. Grafico multi-pannello Matplotlib (salvato anche come PNG nella cartella della sessione):
   - Modulo e assi dell'accelerazione con sovrapposti i MARKER e i METRONOME_CLICK.
   - Intervalli HEARTBEAT (con soglia 15 s) e temperatura batteria (THERMAL).
   - Deriva del clock audio (AUDIO_SYNC) e stato A2DP (ROUTE_CHANGE).

Uso su Windows (PowerShell / CMD):
    python tools/read_session.py "C:\\Percorso\\Esterno\\session_20261114_223509_arm.zip"
oppure su una cartella estratta:
    python tools/read_session.py "C:\\Percorso\\Esterno\\session_20261114_223509_arm"
"""

import argparse
import json
import os
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

import numpy as np
import matplotlib.pyplot as plt
from scipy import signal

# Costanti del formato binario (docs/FORMAT.md)
MAGIC_EXPECTED = b"KEEPAPP0"
HEADER_SIZE = 32
RECORD_SIZE = 32

TYPE_ACCEL = 1
TYPE_GYRO = 2
TYPE_MAG = 3
TYPE_BARO = 4
TYPE_STEP = 5
TYPE_GPS_A = 6
TYPE_GPS_B = 7
TYPE_MARKER = 8
TYPE_METRONOME_CLICK = 9
TYPE_AUDIO_SYNC = 10
TYPE_HEARTBEAT = 11
TYPE_THERMAL = 12
TYPE_ROUTE_CHANGE = 13


def parse_sensors_bin(bin_path: Path):
    """Legge l'header da 32 byte e tutti i record da 32 byte di sensors.bin."""
    raw = bin_path.read_bytes()
    if len(raw) < HEADER_SIZE:
        raise ValueError(f"File {bin_path} troppo corto ({len(raw)} byte).")

    magic, version, rec_size, flags, mono_start_ns, unix_start_ms = struct.unpack(
        "<8sHHIqq", raw[:HEADER_SIZE]
    )
    if magic != MAGIC_EXPECTED:
        raise ValueError(f"Magic number non valido: {magic!r} (atteso {MAGIC_EXPECTED!r})")
    if rec_size != RECORD_SIZE:
        raise ValueError(f"Dimensione record non supportata: {rec_size} (atteso {RECORD_SIZE})")

    body = raw[HEADER_SIZE:]
    num_records = len(body) // RECORD_SIZE

    # Usa NumPy structured array per decodificare istantaneamente anche milioni di record
    dtype_common = np.dtype([
        ("ts_ns", "<i8"),
        ("rtype", "<u1"),
        ("flags", "<u1"),
        ("reserved", "<u2"),
        ("f0", "<f4"),
        ("f1", "<f4"),
        ("f2", "<f4"),
        ("f3", "<f4"),
        ("f4", "<f4"),
    ])
    records = np.frombuffer(body[: num_records * RECORD_SIZE], dtype=dtype_common)

    # Vista alternativa con payload <qqi (int64, int64, int32) per AUDIO_SYNC e METRONOME_CLICK
    dtype_qqi = np.dtype([
        ("ts_ns", "<i8"),
        ("rtype", "<u1"),
        ("flags", "<u1"),
        ("reserved", "<u2"),
        ("q0", "<i8"),
        ("q1", "<i8"),
        ("i2", "<i4"),
    ])
    records_qqi = np.frombuffer(body[: num_records * RECORD_SIZE], dtype=dtype_qqi)

    # Vista alternativa con payload <ddf (double, double, float) per GPS_A
    dtype_gps_a = np.dtype([
        ("ts_ns", "<i8"),
        ("rtype", "<u1"),
        ("flags", "<u1"),
        ("reserved", "<u2"),
        ("lat", "<f8"),
        ("lon", "<f8"),
        ("alt", "<f4"),
    ])
    records_gps_a = np.frombuffer(body[: num_records * RECORD_SIZE], dtype=dtype_gps_a)

    header_info = {
        "version": version,
        "flags": flags,
        "monotonic_start_ns": mono_start_ns,
        "unix_start_ms": unix_start_ms,
        "num_records": num_records,
    }
    return header_info, records, records_qqi, records_gps_a


def compute_sensor_rate(ts_sec: np.ndarray):
    """Calcola frequenza media (Hz), mediana e deviazione standard degli intervalli dt."""
    if len(ts_sec) < 2:
        return 0.0, 0.0, 0.0
    duration = ts_sec[-1] - ts_sec[0]
    mean_hz = (len(ts_sec) - 1) / duration if duration > 0 else 0.0
    dt_ms = np.diff(ts_sec) * 1000.0
    median_hz = 1000.0 / np.median(dt_ms) if np.median(dt_ms) > 0 else 0.0
    std_dt_ms = float(np.std(dt_ms))
    return float(mean_hz), float(median_hz), std_dt_ms


def analyze_and_plot(session_dir: Path, output_dir: Path, no_show: bool = False):
    manifest_path = session_dir / "manifest.json"
    bin_path = session_dir / "sensors.bin"

    if not bin_path.exists():
        raise FileNotFoundError(f"File sensors.bin non trovato in {session_dir}")

    manifest = {}
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    header, rec, rec_qqi, rec_gps = parse_sensors_bin(bin_path)
    t0_ns = header["monotonic_start_ns"]

    print("=" * 74)
    print(f" KEEPAPP FASE 0 — REPORT ANALISI SESSIONE: {session_dir.name}")
    print("=" * 74)
    if manifest:
        dev = manifest.get("device", {})
        print(f" Dispositivo : {dev.get('manufacturer')} {dev.get('model')} ({dev.get('device')}) - Android {dev.get('android_release')}")
        print(f" Barometro   : {'PRESENTE' if dev.get('has_barometer') else 'ASSENTE'}")
        print(f" Posizione   : {manifest.get('position_label')} ({manifest.get('position_code')})")
        print(f" Attività    : {manifest.get('activity_label')} ({manifest.get('activity_code')})")
        print(f" Cuffie      : {manifest.get('headphones_name')}")
        clk = manifest.get("clock_verification", {})
        print(f" Base Clock  : SensorEvent == elapsedRealtimeNanos -> {clk.get('sensor_timestamp_is_elapsed_realtime')} (offset iniziale: {clk.get('sensor_clock_offset_ms', 0.0):.2f} ms)")

    print(f" Record tot. : {header['num_records']} record da 32 byte ({bin_path.stat().st_size / 1024:.1f} KB)")

    # Estrazione per tipo
    accel = rec[rec["rtype"] == TYPE_ACCEL]
    gyro = rec[rec["rtype"] == TYPE_GYRO]
    mag = rec[rec["rtype"] == TYPE_MAG]
    steps = rec[rec["rtype"] == TYPE_STEP]
    gps_a = rec_gps[rec["rtype"] == TYPE_GPS_A]
    markers = rec[rec["rtype"] == TYPE_MARKER]
    clicks = rec_qqi[rec["rtype"] == TYPE_METRONOME_CLICK]
    audio_sync = rec_qqi[rec["rtype"] == TYPE_AUDIO_SYNC]
    heartbeats = rec[rec["rtype"] == TYPE_HEARTBEAT]
    thermals = rec[rec["rtype"] == TYPE_THERMAL]
    routes = rec[rec["rtype"] == TYPE_ROUTE_CHANGE]

    # 1. Frequenze reali sensori IMU
    print("-" * 74)
    print(" 1. FREQUENZA REALE SENSORI INERZIALI (IMU)")
    for name, arr in [("ACCEL", accel), ("GYRO ", gyro), ("MAG  ", mag)]:
        t_s = (arr["ts_ns"] - t0_ns) * 1e-9
        mean_hz, med_hz, std_dt = compute_sensor_rate(t_s)
        print(f"   - {name}: {len(arr):7d} campioni | Media: {mean_hz:6.2f} Hz | Mediana: {med_hz:6.2f} Hz | Jitter dt: ±{std_dt:.2f} ms")

    print(f"   - STEP : {len(steps):7d} passi rilevati da TYPE_STEP_DETECTOR")
    print(f"   - GPS  : {len(gps_a):7d} fix satellitari GPS_PROVIDER")

    # 2. Analisi HEARTBEAT (criterio accettazione: nessun buco > 15 s)
    print("-" * 74)
    print(" 2. VERIFICA CONTINUITÀ BACKGROUND (HEARTBEAT OGNI 10 s)")
    hb_t = (heartbeats["ts_ns"] - t0_ns) * 1e-9
    if len(hb_t) >= 2:
        hb_dt = np.diff(hb_t)
        max_gap = float(np.max(hb_dt))
        gaps_over_15 = np.sum(hb_dt > 15.0)
        status_hb = "SUPERATO (Zero buchi > 15 s)" if gaps_over_15 == 0 else f"ATTENZIONE ({gaps_over_15} buchi > 15 s!)"
        print(f"   - Heartbeat registrati : {len(hb_t)} (Durata coperta: {hb_t[-1] / 60.0:.2f} min)")
        print(f"   - Intervallo massimo   : {max_gap:.2f} s | Esito: {status_hb}")
    else:
        hb_dt = np.array([])
        print(f"   - Heartbeat registrati : {len(hb_t)} (sessione breve < 10 s)")

    # 3. Analisi Deriva Clock Audio (AUDIO_SYNC)
    print("-" * 74)
    print(" 3. DERIVA DEL CLOCK AUDIO (AUDIO_SYNC 48.000 Hz)")
    sync_t = (audio_sync["ts_ns"] - t0_ns) * 1e-9
    drift_ms_series = np.zeros_like(sync_t)
    if len(audio_sync) >= 2:
        frames = audio_sync["q0"].astype(np.float64)
        hw_ns = audio_sync["q1"].astype(np.float64)
        expected_elapsed_s = (frames - frames[0]) / 48000.0
        actual_elapsed_s = (hw_ns - hw_ns[0]) * 1e-9
        drift_ms_series = (expected_elapsed_s - actual_elapsed_s) * 1000.0
        total_dur = actual_elapsed_s[-1] if actual_elapsed_s[-1] > 0 else 1.0
        ppm = (drift_ms_series[-1] * 1000.0) / total_dur
        print(f"   - Record AUDIO_SYNC    : {len(audio_sync)} (su {total_dur:.1f} s)")
        print(f"   - Deriva finale        : {drift_ms_series[-1]:+.3f} ms ({ppm:+.2f} ppm)")
    else:
        print("   - Nessun record AUDIO_SYNC o sessione troppo breve.")

    # 4. Analisi ROUTE_CHANGE (A2DP vs SCO)
    print("-" * 74)
    print(" 4. EVENTI DI ROUTING AUDIO (ROUTE_CHANGE — Tenuta A2DP)")
    for idx, r in enumerate(routes):
        rt_s = (r["ts_ns"] - t0_ns) * 1e-9
        in_type = int(r["f0"])
        out_type = int(r["f1"])
        is_sco = int(r["f2"]) == 1
        is_a2dp = int(r["f3"]) == 1
        print(f"   - [{rt_s:7.2f} s] InputType={in_type} (15=BUILTIN_MIC) | OutputType={out_type} (8=A2DP, 7=SCO) | A2DP={is_a2dp} | SCO={is_sco}")

    # 5. Marker e Click Metronomo
    print("-" * 74)
    print(f" 5. MARKER ({len(markers)}) & METRONOME_CLICK ({len(clicks)})")
    for m in markers:
        mt_s = (m["ts_ns"] - t0_ns) * 1e-9
        mtype = int(m["flags"])
        keycode = int(m["reserved"])
        presses = int(m["f2"])
        label = {1: "1x Evento", 2: "2x Incrocio", 3: "UI Pulsante"}.get(mtype, f"Tipo {mtype}")
        print(f"   - MARKER a {mt_s:7.2f} s -> {label} | keyCode={keycode} | pressioni={presses}")

    if len(clicks) >= 2:
        click_out_s = (clicks["q1"] - t0_ns) * 1e-9
        click_dt_ms = np.diff(click_out_s) * 1000.0
        bpm_val = int(clicks["flags"][0])
        ideal_ms = 60000.0 / bpm_val if bpm_val > 0 else 375.0
        jitter_arr = np.abs(click_dt_ms - ideal_ms)
        valid_j = jitter_arr[jitter_arr < 150.0]
        rms_j = float(np.sqrt(np.mean(valid_j ** 2))) if len(valid_j) > 0 else 0.0
        underruns_final = int(clicks["i2"][-1])
        print(f"   - METRONOMO ({bpm_val} BPM): {len(clicks)} click | Jitter RMS: {rms_j:.2f} ms | Underrun: {underruns_final}")

    print("=" * 74)

    # === GENERAZIONE GRAFICO MULTI-PANNELLO ===
    fig, axes = plt.subplots(3, 1, figsize=(13, 9), sharex=False)
    fig.suptitle(f"KeepApp Fase 0 — Sessione: {session_dir.name}", fontsize=13, fontweight="bold")

    # Pannello 1: Accelerazione + Filtro Passa-Alto |a| + Marker + Click Metronomo
    ax1 = axes[0]
    if len(accel) > 0:
        acc_t = (accel["ts_ns"] - t0_ns) * 1e-9
        acc_mag = accel["f3"]
        ax1.plot(acc_t, acc_mag, label="|a| Modulo Accelerazione (m/s²)", color="#1565C0", linewidth=0.9, alpha=0.85)
        if len(acc_mag) > 30:
            # Rimozione gravità con passa-alto Butterworth a 0.5 Hz (come previsto in D21)
            sos = signal.butter(2, 0.5, btype="highpass", fs=104.0, output="sos")
            acc_hp = signal.sosfiltfilt(sos, acc_mag)
            ax1.plot(acc_t, acc_hp, label="|a| senza gravità (Passa-Alto 0.5 Hz)", color="#2E7D32", linewidth=0.8, alpha=0.75)

    for i, m in enumerate(markers):
        mt_s = (m["ts_ns"] - t0_ns) * 1e-9
        ax1.axvline(mt_s, color="#C62828", linestyle="--", linewidth=1.4, label="MARKER (Cuffia/UI)" if i == 0 else None)

    for i, c in enumerate(clicks):
        ct_s = (c["q1"] - t0_ns) * 1e-9
        ax1.axvline(ct_s, color="#FF8F00", linestyle=":", linewidth=0.7, alpha=0.6, label="METRONOME_CLICK" if i == 0 else None)

    ax1.set_title("1. Accelerazione Inerziale (IMU ~104 Hz), Marker e Click Metronomo")
    ax1.set_xlabel("Tempo dall'avvio (s)")
    ax1.set_ylabel("m/s²")
    ax1.grid(True, alpha=0.3)
    ax1.legend(loc="upper right", fontsize=8)

    # Pannello 2: Intervalli HEARTBEAT e Temperatura Batteria
    ax2 = axes[1]
    if len(hb_t) >= 2:
        ax2.plot(hb_t[1:], hb_dt, marker="o", markersize=4, color="#283593", label="Δt Heartbeat (target ~10 s)")
        ax2.axhline(15.0, color="#C62828", linestyle="--", linewidth=1.2, label="Soglia Max Accettabile (15 s)")
    ax2.set_title("2. Continuità Foreground Service a Schermo Spento (Intervalli HEARTBEAT)")
    ax2.set_xlabel("Tempo dall'avvio (s)")
    ax2.set_ylabel("Intervallo Δt (s)")
    ax2.set_ylim(0, max(20.0, float(np.max(hb_dt)) + 3.0 if len(hb_dt) > 0 else 20.0))
    ax2.grid(True, alpha=0.3)
    ax2.legend(loc="upper left", fontsize=8)

    if len(thermals) > 0:
        ax2_temp = ax2.twinx()
        th_t = (thermals["ts_ns"] - t0_ns) * 1e-9
        ax2_temp.plot(th_t, thermals["f1"], color="#E65100", linestyle="-.", linewidth=1.1, label="Temp. Batteria (°C)")
        ax2_temp.set_ylabel("Temperatura (°C)", color="#E65100")
        ax2_temp.legend(loc="upper right", fontsize=8)

    # Pannello 3: Deriva Clock Audio (AUDIO_SYNC) e Eventi ROUTE_CHANGE
    ax3 = axes[2]
    if len(sync_t) >= 2:
        ax3.plot(sync_t, drift_ms_series, color="#00695C", linewidth=1.2, label="Deriva Clock Audio vs BootTime (ms)")
    for i, r in enumerate(routes):
        rt_s = (r["ts_ns"] - t0_ns) * 1e-9
        is_a2dp = int(r["f3"]) == 1
        col = "#2E7D32" if is_a2dp else "#C62828"
        ax3.axvline(rt_s, color=col, linestyle="--", linewidth=1.3, label=f"ROUTE_CHANGE (A2DP={is_a2dp})" if i == 0 else None)

    ax3.set_title("3. Stabilità Clock Audio 48 kHz (AUDIO_SYNC) e Routing Bluetooth (ROUTE_CHANGE)")
    ax3.set_xlabel("Tempo dall'avvio (s)")
    ax3.set_ylabel("Deriva Clock (ms)")
    ax3.grid(True, alpha=0.3)
    ax3.legend(loc="upper right", fontsize=8)

    plt.tight_layout()
    report_png = output_dir / f"report_{session_dir.name}.png"
    plt.savefig(report_png, dpi=140)
    print(f"\n[OK] Grafico salvato in: {report_png}")

    if not no_show:
        plt.show()


def main():
    parser = argparse.ArgumentParser(
        description="Legge e grafica una sessione di KeepApp Logger (Fase 0) da una cartella o file .zip esterno."
    )
    parser.add_argument(
        "session_path",
        type=str,
        help="Percorso alla cartella session_YYYYMMDD_HHMMSS_<pos> oppure al file .zip esportato (fuori dal repository Git)"
    )
    parser.add_argument(
        "--no-show",
        action="store_true",
        help="Salva solo il grafico PNG senza aprire la finestra interattiva Matplotlib"
    )
    args = parser.parse_args()

    target = Path(args.session_path).resolve()
    if not target.exists():
        print(f"Errore: il percorso specificato non esiste: {target}", file=sys.stderr)
        sys.exit(1)

    if target.is_file() and target.suffix.lower() == ".zip":
        with tempfile.TemporaryDirectory(prefix="keepapp_session_") as tmp_dir:
            tmp_path = Path(tmp_dir)
            with zipfile.ZipFile(target, "r") as zf:
                zf.extractall(tmp_path)
            subfolders = [p for p in tmp_path.iterdir() if p.is_dir()]
            session_folder = subfolders[0] if subfolders else tmp_path
            analyze_and_plot(session_folder, output_dir=target.parent, no_show=args.no_show)
    elif target.is_dir():
        analyze_and_plot(target, output_dir=target, no_show=args.no_show)
    else:
        print("Errore: specifica una cartella di sessione o un archivio .zip.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
