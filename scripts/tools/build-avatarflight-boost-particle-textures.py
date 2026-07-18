#!/usr/bin/env python3
"""Build 64px AvatarFlight boost particle textures from a 2x2 source sheet."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from PIL import Image


DEFAULT_OUTPUT = (
    Path(__file__).resolve().parents[2]
    / "src/main/resources/Common/Particles/Textures/Tamework/AvatarFlight/Boost"
)


@dataclass(frozen=True)
class SpriteSpec:
    filename: str
    column: int
    row: int


SPRITES = (
    SpriteSpec("Forward_Wind_Lance.png", 0, 0),
    SpriteSpec("Forward_Compression_Arc.png", 1, 0),
    SpriteSpec("Upward_Lift_Ribbon.png", 0, 1),
    SpriteSpec("Upward_Downwash_Fan.png", 1, 1),
)


def visible_bounds(image: Image.Image, alpha_threshold: int) -> tuple[int, int, int, int]:
    alpha = image.getchannel("A")
    mask = alpha.point(lambda value: 255 if value > alpha_threshold else 0)
    bounds = mask.getbbox()
    if bounds is None:
        raise ValueError("source quadrant contains no visible pixels")
    return bounds


def padded_crop(image: Image.Image, alpha_threshold: int) -> Image.Image:
    left, top, right, bottom = visible_bounds(image, alpha_threshold)
    width = right - left
    height = bottom - top
    padding = max(4, round(max(width, height) * 0.04))
    bounds = (
        max(0, left - padding),
        max(0, top - padding),
        min(image.width, right + padding),
        min(image.height, bottom + padding),
    )
    return image.crop(bounds)


def fit_sprite(image: Image.Image, size: int, inset: int) -> Image.Image:
    available = size - 2 * inset
    scale = min(available / image.width, available / image.height)
    dimensions = (
        max(1, round(image.width * scale)),
        max(1, round(image.height * scale)),
    )
    # Premultiplied alpha prevents bright RGB from bleeding into transparent edges.
    resized = image.convert("RGBa").resize(dimensions, Image.Resampling.LANCZOS)
    resized = resized.convert("RGBA")
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    position = ((size - dimensions[0]) // 2, (size - dimensions[1]) // 2)
    canvas.alpha_composite(resized, position)
    return canvas


def build(source_path: Path, output_root: Path, size: int, inset: int,
          alpha_threshold: int) -> None:
    source = Image.open(source_path).convert("RGBA")
    if source.width != source.height or source.width % 2 != 0:
        raise ValueError(f"source sheet must be an even square image: {source.size}")
    quadrant_size = source.width // 2
    output_root.mkdir(parents=True, exist_ok=True)

    for spec in SPRITES:
        left = spec.column * quadrant_size
        top = spec.row * quadrant_size
        quadrant = source.crop((left, top, left + quadrant_size, top + quadrant_size))
        sprite = fit_sprite(
            padded_crop(quadrant, alpha_threshold),
            size,
            inset,
        )
        output_path = output_root / spec.filename
        sprite.save(output_path, format="PNG", optimize=True)
        print(f"generated {output_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--size", type=int, default=64)
    parser.add_argument("--inset", type=int, default=4)
    parser.add_argument("--alpha-threshold", type=int, default=4)
    args = parser.parse_args()
    if args.size <= 0:
        raise SystemExit("--size must be positive")
    if args.inset < 0 or args.inset * 2 >= args.size:
        raise SystemExit("--inset must leave a positive drawable area")
    build(
        args.source.resolve(),
        args.output_root.resolve(),
        args.size,
        args.inset,
        args.alpha_threshold,
    )


if __name__ == "__main__":
    main()
