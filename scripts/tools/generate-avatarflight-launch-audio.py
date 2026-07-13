#!/usr/bin/env python3
"""Generate mono sampled and synthesized effects for AvatarFlight launch feedback."""

from __future__ import annotations

import argparse
import math
import shutil
import subprocess
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np


SAMPLE_RATE = 48_000
SEED = 0xA11EC
LEGACY_CHARGE_SAMPLE_COUNT = int(0.36 * SAMPLE_RATE)
LEGACY_READY_SAMPLE_COUNT = int(0.58 * SAMPLE_RATE)
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parents[2]
    / "src/main/resources/Common/Sounds/Tamework/AvatarFlight/Launch"
)
DEFAULT_WIND_SOURCE = Path.home() / "Downloads/dragon-studio-harsh-wind-515272.mp3"
DEFAULT_READY_SOURCE = (
    Path.home() / "Downloads/Polished_anime-style_#3-1783957481053.mp3"
)
DEFAULT_RELEASE_SOURCE = (
    Path.home() / "Downloads/dragon-studio-gust-of-wind-511325.mp3"
)
CHARGE_PULSE_SAMPLES = (
    ("Tamework_AvatarFlight_Launch_Charge_Pulse.ogg", 1.78, 1.02),
    ("Tamework_AvatarFlight_Launch_Charge_Pulse_B.ogg", 4.18, 1.02),
    ("Tamework_AvatarFlight_Launch_Charge_Pulse_C.ogg", 6.92, 1.02),
)


@dataclass(frozen=True)
class ReleaseSample:
    filename: str
    duration_s: float
    main_start_s: float
    pitch: float
    highpass_hz: int
    snap_gain: float
    body_gain: float
    body_boost_db: float
    fade_out_s: float
    impact_strength: float


RELEASE_SAMPLES = (
    ReleaseSample(
        filename="Tamework_AvatarFlight_Launch_Release_Partial.ogg",
        duration_s=0.72,
        main_start_s=0.20,
        pitch=1.06,
        highpass_hz=120,
        snap_gain=0.68,
        body_gain=0.52,
        body_boost_db=0.5,
        fade_out_s=0.18,
        impact_strength=0.55,
    ),
    ReleaseSample(
        filename="Tamework_AvatarFlight_Launch_Release_Mid.ogg",
        duration_s=0.90,
        main_start_s=0.13,
        pitch=1.00,
        highpass_hz=80,
        snap_gain=0.82,
        body_gain=0.82,
        body_boost_db=2.0,
        fade_out_s=0.22,
        impact_strength=0.78,
    ),
    ReleaseSample(
        filename="Tamework_AvatarFlight_Launch_Release_Full.ogg",
        duration_s=1.16,
        main_start_s=0.04,
        pitch=0.94,
        highpass_hz=55,
        snap_gain=0.96,
        body_gain=1.16,
        body_boost_db=4.0,
        fade_out_s=0.28,
        impact_strength=1.0,
    ),
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


def cancel_cue(rng: np.random.Generator) -> np.ndarray:
    duration = 0.44
    time = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
    noise = bandpass(rng.normal(0.0, 1.0, len(time)), 170.0, 5_600.0)
    envelope = np.exp(-5.2 * time) * np.clip(time / 0.018, 0.0, 1.0)
    falling = chirp(time, 520.0, 120.0) * np.exp(-7.0 * time)
    flutter = 0.72 + 0.28 * np.sin(2.0 * math.pi * (6.0 * time - 2.2 * time * time))
    output = 0.78 * noise * envelope * flutter + 0.09 * falling
    return normalize(fade(output, 0.008, 0.09), -2.0)


def release_impact(strength: float, seed_offset: int) -> np.ndarray:
    duration = 0.24
    time = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
    rng = np.random.default_rng(SEED + 0xB017 + seed_offset)

    air_noise = rng.normal(0.0, 1.0, len(time))
    pressure_noise = rng.normal(0.0, 1.0, len(time))
    attack = np.sin(
        np.clip(time / 0.0035, 0.0, 1.0) * math.pi / 2.0
    ) ** 2

    air = bandpass(air_noise, 160.0, 3_200.0)
    air *= attack * np.exp(-time / (0.040 + 0.014 * strength))

    crack = bandpass(np.roll(air_noise, 173), 600.0, 4_200.0)
    crack *= attack * np.exp(-time / 0.020)

    pressure = bandpass(pressure_noise, 48.0, 620.0)
    pressure *= attack * np.exp(-time / (0.080 + 0.025 * strength))

    start_hz = 112.0 - 14.0 * strength
    end_hz = 46.0
    frequency = end_hz + (start_hz - end_hz) * np.exp(-13.0 * time)
    phase = 2.0 * math.pi * np.cumsum(frequency) / SAMPLE_RATE
    thump_envelope = attack * np.exp(-time / (0.090 + 0.035 * strength))
    thump = (np.sin(phase) + 0.20 * np.sin(2.0 * phase)) * thump_envelope

    output = (
        (0.95 + 0.25 * strength) * air
        + 0.05 * crack
        + (0.20 + 0.15 * strength) * pressure
        + (0.28 + 0.22 * strength) * thump
    )
    return normalize(fade(output, 0.0015, 0.055), -1.0)


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


def encode_sampled_wind(ffmpeg: str, source_path: Path, ogg_path: Path,
                        start_s: float, duration_s: float) -> None:
    fade_out_start = duration_s - 0.34
    filters = ",".join((
        f"atrim=start={start_s}:duration={duration_s}",
        "asetpts=PTS-STARTPTS",
        "pan=mono|c0=0.65*c0+0.35*c1",
        "highpass=f=55:p=2",
        "lowpass=f=6500:p=2",
        "afade=t=in:st=0:d=0.22",
        f"afade=t=out:st={fade_out_start}:d=0.34",
        "loudnorm=I=-23:TP=-5:LRA=5",
    ))
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source_path),
            "-af",
            filters,
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


def encode_sampled_ready(ffmpeg: str, source_path: Path, ogg_path: Path) -> None:
    filters = ",".join((
        "atrim=start=0:duration=0.52",
        "asetpts=PTS-STARTPTS",
        "pan=mono|c0=0.5*c0+0.5*c1",
        "highpass=f=100:p=2",
        "lowpass=f=12000:p=2",
        "afade=t=in:st=0:d=0.006",
        "afade=t=out:st=0.42:d=0.10",
        "loudnorm=I=-20:TP=-4:LRA=4",
    ))
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source_path),
            "-af",
            filters,
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


def encode_sampled_release(ffmpeg: str, source_path: Path, impact_path: Path,
                           ogg_path: Path, sample: ReleaseSample) -> None:
    pitched_rate = round(SAMPLE_RATE * sample.pitch)
    restore_tempo = 1.0 / sample.pitch
    fade_out_start = sample.duration_s - sample.fade_out_s
    filters = ";".join((
        f"[0:a]pan=mono|c0=0.5*c0+0.5*c1,aresample={SAMPLE_RATE},"
        f"asetrate={pitched_rate},aresample={SAMPLE_RATE},"
        f"atempo={restore_tempo:.6f},asplit=3[main_in][snap_in][body_in]",
        f"[main_in]atrim=start={sample.main_start_s}:duration={sample.duration_s},"
        "asetpts=PTS-STARTPTS,"
        f"highpass=f={sample.highpass_hz}:p=2,lowpass=f=9500:p=2,"
        "acompressor=threshold=0.10:ratio=2.4:attack=4:release=90:makeup=1.2,"
        "afade=t=in:st=0:d=0.004,"
        f"afade=t=out:st={fade_out_start}:d={sample.fade_out_s},"
        "adelay=18[main]",
        "[snap_in]atrim=start=0.18:duration=0.24,asetpts=PTS-STARTPTS,"
        "atempo=1.6,highpass=f=650:p=2,lowpass=f=11500:p=2,"
        "acompressor=threshold=0.08:ratio=3.2:attack=1:release=35:makeup=1.35,"
        "afade=t=in:st=0:d=0.002,afade=t=out:st=0.075:d=0.075,"
        f"volume={sample.snap_gain},adelay=12[snap]",
        "[body_in]atrim=start=0.14:duration=0.52,asetpts=PTS-STARTPTS,"
        "atempo=1.35,highpass=f=45:p=2,lowpass=f=650:p=2,"
        f"equalizer=f=160:t=q:w=0.85:g={sample.body_boost_db},"
        "afade=t=in:st=0:d=0.004,afade=t=out:st=0.12:d=0.265,"
        f"volume={sample.body_gain},adelay=18[body]",
        "[1:a]atrim=start=0:duration=0.24,asetpts=PTS-STARTPTS,"
        "highpass=f=35:p=2,lowpass=f=5200:p=2,"
        f"volume={0.88 + 0.52 * sample.impact_strength:.3f}[impact]",
        "[main][snap][body][impact]amix=inputs=4:duration=longest:normalize=0,"
        "acompressor=threshold=0.15:ratio=2.0:attack=14:release=95:makeup=1.1,"
        f"atrim=start=0:duration={sample.duration_s},"
        f"afade=t=out:st={fade_out_start}:d={sample.fade_out_s},"
        "loudnorm=I=-18:TP=-3.5:LRA=4[out]",
    ))
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source_path),
            "-i",
            str(impact_path),
            "-filter_complex",
            filters,
            "-map",
            "[out]",
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


def generate(output_root: Path, ffmpeg: str, wind_source: Path,
             ready_source: Path, release_source: Path) -> None:
    if not wind_source.is_file():
        raise FileNotFoundError(f"wind source recording was not found: {wind_source}")
    if not ready_source.is_file():
        raise FileNotFoundError(f"ready source recording was not found: {ready_source}")
    if not release_source.is_file():
        raise FileNotFoundError(f"release source recording was not found: {release_source}")
    cue_rng = np.random.default_rng(SEED)
    cue_rng.normal(0.0, 1.0, LEGACY_CHARGE_SAMPLE_COUNT)
    # Preserve the random stream used by the unchanged synthesized cues.
    cue_rng.normal(0.0, 1.0, LEGACY_READY_SAMPLE_COUNT)
    cue_rng.normal(0.0, 1.0, LEGACY_READY_SAMPLE_COUNT)
    sounds = {
        "Tamework_AvatarFlight_Launch_Cancel.ogg": cancel_cue(cue_rng),
    }
    output_root.mkdir(parents=True, exist_ok=True)
    for filename, start_s, duration_s in CHARGE_PULSE_SAMPLES:
        output_path = output_root / filename
        encode_sampled_wind(ffmpeg, wind_source, output_path, start_s, duration_s)
        print(f"generated {output_path}")
    ready_output = output_root / "Tamework_AvatarFlight_Launch_Ready.ogg"
    encode_sampled_ready(ffmpeg, ready_source, ready_output)
    print(f"generated {ready_output}")
    with tempfile.TemporaryDirectory(prefix="tamework-launch-audio-") as temporary:
        temporary_root = Path(temporary)
        for index, release_sample in enumerate(RELEASE_SAMPLES):
            impact_path = temporary_root / f"release-impact-{index}.wav"
            write_wav(
                impact_path,
                release_impact(release_sample.impact_strength, index),
            )
            output_path = output_root / release_sample.filename
            encode_sampled_release(
                ffmpeg,
                release_source,
                impact_path,
                output_path,
                release_sample,
            )
            print(f"generated {output_path}")
        for filename, samples in sounds.items():
            wav_path = temporary_root / filename.replace(".ogg", ".wav")
            write_wav(wav_path, samples)
            encode_ogg(ffmpeg, wav_path, output_root / filename)
            print(f"generated {output_root / filename}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--wind-source", type=Path, default=DEFAULT_WIND_SOURCE)
    parser.add_argument("--ready-source", type=Path, default=DEFAULT_READY_SOURCE)
    parser.add_argument("--release-source", type=Path, default=DEFAULT_RELEASE_SOURCE)
    args = parser.parse_args()
    if not args.ffmpeg:
        raise SystemExit("ffmpeg was not found on PATH; pass --ffmpeg explicitly")
    generate(
        args.output_root.resolve(),
        args.ffmpeg,
        args.wind_source.resolve(),
        args.ready_source.resolve(),
        args.release_source.resolve(),
    )


if __name__ == "__main__":
    main()
