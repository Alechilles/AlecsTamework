#!/usr/bin/env python3
"""Generate original mono wind effects for AvatarFlight launch feedback."""

from __future__ import annotations

import argparse
import math
import shutil
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 48_000
SEED = 0xA11EC
LEGACY_CHARGE_SAMPLE_COUNT = int(0.36 * SAMPLE_RATE)
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parents[2]
    / "src/main/resources/Common/Sounds/Tamework/AvatarFlight/Launch"
)


def lowpass(samples: np.ndarray, cutoff_hz: float) -> np.ndarray:
    coefficient = math.exp(-2.0 * math.pi * cutoff_hz / SAMPLE_RATE)
    output = np.empty_like(samples)
    state = 0.0
    feed = 1.0 - coefficient
    for index, sample in enumerate(samples):
        state = feed * float(sample) + coefficient * state
        output[index] = state
    return output


def highpass(samples: np.ndarray, cutoff_hz: float) -> np.ndarray:
    return samples - lowpass(samples, cutoff_hz)


def bandpass(samples: np.ndarray, low_hz: float, high_hz: float) -> np.ndarray:
    return highpass(lowpass(samples, high_hz), low_hz)


def chirp(time: np.ndarray, start_hz: float, end_hz: float) -> np.ndarray:
    duration = max(float(time[-1]), 1.0 / SAMPLE_RATE)
    frequency = start_hz + (end_hz - start_hz) * (time / duration)
    phase = 2.0 * math.pi * np.cumsum(frequency) / SAMPLE_RATE
    return np.sin(phase)


def fade(samples: np.ndarray, fade_in_s: float, fade_out_s: float) -> np.ndarray:
    output = samples.copy()
    fade_in = min(len(output), int(fade_in_s * SAMPLE_RATE))
    fade_out = min(len(output), int(fade_out_s * SAMPLE_RATE))
    if fade_in > 0:
        output[:fade_in] *= np.sin(np.linspace(0.0, math.pi / 2.0, fade_in)) ** 2
    if fade_out > 0:
        output[-fade_out:] *= np.cos(np.linspace(0.0, math.pi / 2.0, fade_out)) ** 2
    return output


def normalize(samples: np.ndarray, peak_db: float = -1.0) -> np.ndarray:
    samples = samples - float(np.mean(samples))
    peak = float(np.max(np.abs(samples)))
    if peak <= 1e-9:
        return samples
    target = 10.0 ** (peak_db / 20.0)
    return np.tanh(samples * (target / peak) * 1.12) / math.tanh(1.12)


def charge_pulse(rng: np.random.Generator) -> np.ndarray:
    duration = 0.72
    time = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
    noise = rng.normal(0.0, 1.0, len(time))
    shifted = np.roll(noise, int(0.021 * SAMPLE_RATE))
    air = highpass(lowpass(lowpass(noise, 2_400.0), 2_400.0), 180.0)
    body = highpass(lowpass(lowpass(shifted, 820.0), 820.0), 65.0)
    rumble = highpass(lowpass(np.roll(noise, int(0.047 * SAMPLE_RATE)), 340.0), 32.0)

    progress = np.clip(time / duration, 0.0, 1.0)
    attack = np.sin(np.clip(time / 0.18, 0.0, 1.0) * math.pi / 2.0) ** 2
    release = np.sin(np.clip((duration - time) / 0.28, 0.0, 1.0) * math.pi / 2.0) ** 2
    envelope = attack * release

    turbulence_noise = lowpass(rng.normal(0.0, 1.0, len(time)), 4.2)
    turbulence_scale = max(float(np.std(turbulence_noise)), 1e-9)
    turbulence = np.clip(0.86 + 0.16 * turbulence_noise / turbulence_scale, 0.58, 1.14)
    swirl = 0.90 + 0.10 * np.sin(2.0 * math.pi * (1.3 * time + 0.85 * time * time))
    breath = 0.018 * chirp(time, 145.0, 215.0) * envelope ** 1.4

    wind = (0.28 + 0.18 * progress) * air + 0.78 * body + 0.24 * rumble
    output = wind * envelope * turbulence * swirl + breath
    return normalize(fade(output, 0.07, 0.18), -4.5)


def ready_cue(rng: np.random.Generator) -> np.ndarray:
    duration = 0.58
    time = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
    noise = bandpass(rng.normal(0.0, 1.0, len(time)), 240.0, 8_500.0)
    intake = np.clip(time / 0.16, 0.0, 1.0) * np.exp(-3.1 * time)
    ring_envelope = np.clip(time / 0.035, 0.0, 1.0) * np.exp(-4.8 * time)
    ring = (
        0.48 * chirp(time, 690.0, 930.0)
        + 0.24 * chirp(time, 1_120.0, 1_380.0)
    ) * ring_envelope
    pressure = lowpass(rng.normal(0.0, 1.0, len(time)), 480.0) * np.exp(-5.2 * time)
    output = 0.54 * noise * intake + 0.22 * pressure + 0.25 * ring
    return normalize(fade(output, 0.012, 0.11), -1.5)


def cancel_cue(rng: np.random.Generator) -> np.ndarray:
    duration = 0.44
    time = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
    noise = bandpass(rng.normal(0.0, 1.0, len(time)), 170.0, 5_600.0)
    envelope = np.exp(-5.2 * time) * np.clip(time / 0.018, 0.0, 1.0)
    falling = chirp(time, 520.0, 120.0) * np.exp(-7.0 * time)
    flutter = 0.72 + 0.28 * np.sin(2.0 * math.pi * (6.0 * time - 2.2 * time * time))
    output = 0.78 * noise * envelope * flutter + 0.09 * falling
    return normalize(fade(output, 0.008, 0.09), -2.0)


def release_burst(rng: np.random.Generator, strength: float, duration: float) -> np.ndarray:
    time = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
    noise = rng.normal(0.0, 1.0, len(time))
    air = bandpass(noise, 240.0, 10_500.0)
    body = bandpass(noise, 65.0, 1_850.0)
    gust_envelope = np.clip(time / 0.028, 0.0, 1.0) * np.exp(-(2.8 + 0.5 / strength) * time)
    gust = (0.78 * air + (0.32 + 0.22 * strength) * body) * gust_envelope

    suction_center = 0.075 + 0.025 * strength
    suction_width = 0.045 + 0.018 * strength
    suction_envelope = np.exp(-0.5 * ((time - suction_center) / suction_width) ** 2)
    suction = bandpass(np.roll(noise, int(0.027 * SAMPLE_RATE)), 380.0, 6_200.0) * suction_envelope

    snap_envelope = np.exp(-55.0 * np.maximum(0.0, time - 0.105)) * (time >= 0.105)
    snap = highpass(noise, 2_600.0) * snap_envelope

    low_frequency = 92.0 - 46.0 * np.clip(time / duration, 0.0, 1.0)
    low_phase = 2.0 * math.pi * np.cumsum(low_frequency) / SAMPLE_RATE
    thump = np.sin(low_phase) * np.exp(-5.4 * np.maximum(0.0, time - 0.09)) * (time >= 0.09)

    shimmer = chirp(time, 430.0 + 90.0 * strength, 980.0 + 240.0 * strength)
    shimmer *= np.exp(-4.2 * time) * np.clip(time / 0.05, 0.0, 1.0)

    output = (
        0.44 * suction
        + (0.62 + 0.16 * strength) * gust
        + (0.08 + 0.14 * strength) * snap
        + (0.10 + 0.21 * strength) * thump
        + (0.025 + 0.035 * strength) * shimmer
    )
    return normalize(fade(output, 0.01, 0.16 + 0.08 * strength), -0.8)


def write_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32_767.0).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm.tobytes())


def encode_ogg(ffmpeg: str, wav_path: Path, ogg_path: Path) -> None:
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(wav_path),
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            "-c:a",
            "libvorbis",
            "-q:a",
            "6",
            "-serial_offset",
            "1",
            str(ogg_path),
        ],
        check=True,
    )
    normalize_ogg_serial(ogg_path, 0xA11EC)


def normalize_ogg_serial(path: Path, serial: int) -> None:
    """Replace FFmpeg's random Ogg stream serial and repair each page checksum."""
    data = bytearray(path.read_bytes())
    crc_table = ogg_crc_table()
    offset = 0
    while offset < len(data):
        if data[offset:offset + 4] != b"OggS" or offset + 27 > len(data):
            raise ValueError(f"invalid Ogg page at byte {offset}: {path}")
        segment_count = data[offset + 26]
        header_end = offset + 27 + segment_count
        if header_end > len(data):
            raise ValueError(f"truncated Ogg page header at byte {offset}: {path}")
        page_end = header_end + sum(data[offset + 27:header_end])
        if page_end > len(data):
            raise ValueError(f"truncated Ogg page body at byte {offset}: {path}")
        data[offset + 14:offset + 18] = serial.to_bytes(4, "little", signed=False)
        data[offset + 22:offset + 26] = b"\x00\x00\x00\x00"
        checksum = 0
        for value in data[offset:page_end]:
            checksum = ((checksum << 8) & 0xFFFFFFFF) ^ crc_table[((checksum >> 24) & 0xFF) ^ value]
        data[offset + 22:offset + 26] = checksum.to_bytes(4, "little", signed=False)
        offset = page_end
    path.write_bytes(data)


def ogg_crc_table() -> list[int]:
    polynomial = 0x04C11DB7
    table: list[int] = []
    for value in range(256):
        remainder = value << 24
        for _ in range(8):
            if remainder & 0x80000000:
                remainder = ((remainder << 1) ^ polynomial) & 0xFFFFFFFF
            else:
                remainder = (remainder << 1) & 0xFFFFFFFF
        table.append(remainder)
    return table


def generate(output_root: Path, ffmpeg: str) -> None:
    cue_rng = np.random.default_rng(SEED)
    cue_rng.normal(0.0, 1.0, LEGACY_CHARGE_SAMPLE_COUNT)
    sounds = {
        "Tamework_AvatarFlight_Launch_Charge_Pulse.ogg": charge_pulse(
            np.random.default_rng(SEED)
        ),
        "Tamework_AvatarFlight_Launch_Ready.ogg": ready_cue(cue_rng),
        "Tamework_AvatarFlight_Launch_Cancel.ogg": cancel_cue(cue_rng),
        "Tamework_AvatarFlight_Launch_Release_Partial.ogg": release_burst(
            cue_rng, 0.48, 0.72
        ),
        "Tamework_AvatarFlight_Launch_Release_Mid.ogg": release_burst(
            cue_rng, 0.72, 0.90
        ),
        "Tamework_AvatarFlight_Launch_Release_Full.ogg": release_burst(
            cue_rng, 1.0, 1.16
        ),
    }
    output_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tamework-launch-audio-") as temporary:
        temporary_root = Path(temporary)
        for filename, samples in sounds.items():
            wav_path = temporary_root / filename.replace(".ogg", ".wav")
            write_wav(wav_path, samples)
            encode_ogg(ffmpeg, wav_path, output_root / filename)
            print(f"generated {output_root / filename}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    args = parser.parse_args()
    if not args.ffmpeg:
        raise SystemExit("ffmpeg was not found on PATH; pass --ffmpeg explicitly")
    generate(args.output_root.resolve(), args.ffmpeg)


if __name__ == "__main__":
    main()
