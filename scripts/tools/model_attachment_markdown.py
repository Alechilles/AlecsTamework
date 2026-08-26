#!/usr/bin/env python3
"""Create a Markdown report for a Hytale ModelAsset's random attachments."""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


DEFAULT_COLUMNS = (
    "attachment-set",
    "set-display",
    "attachment",
    "attachment-display",
    "weight",
    "chance",
)
COLUMN_DEFINITIONS = {
    "attachment-set": ("Attachment Set", "---"),
    "set-display": ("Set Display", "---"),
    "attachment": ("Attachment", "---"),
    "attachment-display": ("Attachment Display", "---"),
    "weight": ("Weight", "---:"),
    "chance": ("Chance", "---:"),
}


class ReportError(Exception):
    """Raised when an input asset cannot produce a reliable report."""


@dataclass(frozen=True)
class DisplayCandidate:
    match_weight: int
    priority: int
    config_id: str
    entry_id: str
    set_label: str
    value_label: str


def load_json_object(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ReportError(f"JSON file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ReportError(f"Failed to parse JSON at {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ReportError(f"Expected a JSON object at {path}.")
    return value


def find_asset_layout(model_path: Path) -> tuple[Path, Path | None]:
    for candidate in (model_path.parent, *model_path.parents):
        if candidate.name.casefold() == "models" and candidate.parent.name.casefold() == "server":
            return candidate, candidate.parent.parent
    return model_path.parent, None


def find_batch_layout(batch_root: Path) -> tuple[Path, Path]:
    root = batch_root.resolve()
    if root.name.casefold() == "models" and root.parent.name.casefold() == "server":
        models_root = root
        mod_root = root.parent.parent
    else:
        models_root = root / "Server" / "Models"
        mod_root = root
    if not models_root.is_dir():
        raise ReportError(f"Server/Models directory not found: {models_root}")
    return models_root, mod_root


def default_hytale_roots() -> list[Path]:
    roots: list[Path] = []
    system = platform.system()
    if system == "Windows" and os.environ.get("APPDATA"):
        roots.append(Path(os.environ["APPDATA"]) / "Hytale")
    elif system == "Darwin":
        roots.append(Path.home() / "Library" / "Application Support" / "Hytale")
    return roots


def inferred_hytale_root(source_path: Path) -> Path | None:
    resolved = source_path.resolve()
    for candidate in (resolved, *resolved.parents):
        if candidate.name.casefold() == "hytale":
            return candidate
    return None


def install_model_candidates(
    hytale_root: Path,
    source_path: Path | None = None,
) -> list[Path]:
    patchlines: list[str] = []
    if source_path is not None:
        try:
            relative_parts = (
                source_path.resolve().relative_to(hytale_root.resolve()).parts
            )
        except ValueError:
            relative_parts = ()
        if len(relative_parts) >= 2 and relative_parts[0].casefold() == "data":
            patchlines.append(relative_parts[1])
        elif relative_parts and relative_parts[0].casefold() == "userdata":
            patchlines.append("release")
    for patchline in ("release", "pre-release"):
        if patchline not in patchlines:
            patchlines.append(patchline)
    install_root = hytale_root / "install"
    if install_root.is_dir():
        for child in sorted(install_root.iterdir(), key=lambda path: path.name.casefold()):
            if child.is_dir() and child.name not in patchlines:
                patchlines.append(child.name)
    return [
        install_root
        / patchline
        / "package"
        / "game"
        / "latest"
        / "Assets"
        / "Server"
        / "Models"
        for patchline in patchlines
    ]


def normalize_base_game_models(path: Path) -> Path:
    root = path.expanduser().resolve()
    candidates = [root]
    if root.name.casefold() == "server":
        candidates.append(root / "Models")
    candidates.extend(
        (
            root / "Server" / "Models",
            root / "Assets" / "Server" / "Models",
            root / "package" / "game" / "latest" / "Assets" / "Server" / "Models",
        )
    )
    candidates.extend(
        install_model_candidates(root.parent if root.name.casefold() == "install" else root)
    )
    for candidate in candidates:
        if (
            candidate.is_dir()
            and candidate.name.casefold() == "models"
            and candidate.parent.name.casefold() == "server"
        ):
            return candidate
    raise ReportError(f"Base-game Server/Models directory not found under: {root}")


def find_base_game_models(
    source_path: Path | None = None,
    manual_path: Path | None = None,
) -> Path | None:
    if manual_path is not None:
        return normalize_base_game_models(manual_path)
    roots: list[Path] = []
    inferred = inferred_hytale_root(source_path) if source_path is not None else None
    if inferred is not None:
        roots.append(inferred)
    for root in default_hytale_roots():
        resolved = root.resolve()
        if resolved not in roots:
            roots.append(resolved)
    for root in roots:
        for candidate in install_model_candidates(root, source_path):
            if candidate.is_dir():
                return candidate.resolve()
    return None


def add_index_key(index: dict[str, list[Path]], key: str, path: Path) -> None:
    normalized = key.replace("\\", "/").strip("/").casefold()
    if normalized and path not in index.setdefault(normalized, []):
        index[normalized].append(path)


def build_asset_index(files: Iterable[Path], root_by_file: Mapping[Path, Path]) -> dict[str, list[Path]]:
    index: dict[str, list[Path]] = {}
    for path in files:
        add_index_key(index, path.stem, path)
        root = root_by_file[path]
        add_index_key(index, path.relative_to(root).with_suffix("").as_posix(), path)
    return index


def normalize_parent_id(parent_id: str) -> str:
    normalized = parent_id.replace("\\", "/").strip().strip("/")
    if ":" in normalized:
        normalized = normalized.split(":", 1)[1]
    for prefix in ("Server/Models/", "Server/Tamework/AttachmentDisplays/"):
        if normalized.casefold().startswith(prefix.casefold()):
            normalized = normalized[len(prefix) :]
            break
    return normalized.removesuffix(".json")


def resolve_parent_path(parent_id: str, index: Mapping[str, list[Path]], child_path: Path) -> Path:
    normalized = normalize_parent_id(parent_id)
    candidates = list(index.get(normalized.casefold(), ()))
    if not candidates:
        candidates = list(index.get(Path(normalized).name.casefold(), ()))
    if not candidates:
        raise ReportError(f"Parent asset '{parent_id}' referenced by {child_path} was not found.")
    if len(candidates) > 1:
        same_directory = [path for path in candidates if path.parent == child_path.parent]
        if len(same_directory) == 1:
            return same_directory[0]
        choices = ", ".join(str(path) for path in candidates)
        raise ReportError(f"Parent asset '{parent_id}' is ambiguous: {choices}")
    return candidates[0]


def resolve_inheritance(
    path: Path,
    index: Mapping[str, list[Path]],
    inherited_keys: Sequence[str] | None = None,
    stack: tuple[Path, ...] = (),
) -> dict:
    resolved_path = path.resolve()
    if resolved_path in stack:
        chain = " -> ".join(str(item) for item in (*stack, resolved_path))
        raise ReportError(f"Asset inheritance cycle: {chain}")

    raw = load_json_object(resolved_path)
    effective: dict = {}
    parent_id = raw.get("Parent")
    if parent_id is not None:
        if not isinstance(parent_id, str) or not parent_id.strip():
            raise ReportError(f"Parent must be a non-empty string at {resolved_path}.")
        parent_path = resolve_parent_path(parent_id, index, resolved_path)
        parent = resolve_inheritance(parent_path, index, inherited_keys, (*stack, resolved_path))
        if inherited_keys is None:
            effective.update(parent)
        else:
            for key in inherited_keys:
                if key in parent:
                    effective[key] = parent[key]
    effective.update(raw)
    return effective


def display_directories(mod_root: Path | None, extra_roots: Sequence[Path]) -> list[Path]:
    roots = ([mod_root] if mod_root is not None else []) + list(extra_roots)
    directories: list[Path] = []
    for raw_root in roots:
        root = raw_root.resolve()
        if root.name.casefold() == "attachmentdisplays":
            directory = root
        else:
            directory = root / "Server" / "Tamework" / "AttachmentDisplays"
        if directory.is_dir() and directory not in directories:
            directories.append(directory)
    return directories


def model_directories(primary_root: Path, extra_roots: Sequence[Path]) -> list[Path]:
    directories = [primary_root.resolve()]
    for raw_root in extra_roots:
        root = raw_root.resolve()
        directory = root if root.name.casefold() == "models" else root / "Server" / "Models"
        if not directory.is_dir():
            raise ReportError(f"Model root not found: {directory}")
        if directory not in directories:
            directories.append(directory)
    return directories


def load_display_configs(directories: Sequence[Path]) -> list[tuple[str, dict]]:
    files = sorted(
        (path.resolve() for directory in directories for path in directory.rglob("*.json")),
        key=lambda path: str(path).casefold(),
    )
    root_by_file = {
        path: next(directory for directory in directories if path.is_relative_to(directory))
        for path in files
    }
    index = build_asset_index(files, root_by_file)
    return [
        (
            path.stem,
            resolve_inheritance(path, index, ("Enabled", "Priority", "Entries")),
        )
        for path in files
    ]


def string_list(value: object) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ReportError("Attachment display filters must be arrays of strings.")
    return value


def matches_any(candidates: object, actual: str | None) -> bool:
    if actual is None or not actual.strip():
        return False
    return any(candidate.strip().casefold() == actual.strip().casefold() for candidate in string_list(candidates))


def namespace_of(asset_id: str | None) -> str | None:
    if asset_id is None or ":" not in asset_id:
        return None
    namespace, _ = asset_id.split(":", 1)
    return namespace or None


def match_weight(applies_to: Mapping[str, object], role_id: str | None, model_id: str | None) -> int | None:
    if matches_any(applies_to.get("ModelIds"), model_id):
        return 5
    if matches_any(applies_to.get("RoleIds"), role_id):
        return 4
    if matches_any(applies_to.get("ModelNamespaces"), namespace_of(model_id)):
        return 3
    if matches_any(applies_to.get("RoleNamespaces"), namespace_of(role_id)):
        return 2
    filters = ("RoleIds", "ModelIds", "RoleNamespaces", "ModelNamespaces")
    return 1 if all(not string_list(applies_to.get(key)) for key in filters) else None


def case_insensitive_get(values: Mapping[str, object], key: str) -> object | None:
    direct = values.get(key)
    if direct is not None:
        return direct
    folded = key.casefold()
    for candidate_key, value in values.items():
        if candidate_key.casefold() == folded:
            return value
    return None


def resolve_display(
    configs: Sequence[tuple[str, dict]],
    role_id: str | None,
    model_id: str | None,
    set_id: str,
    attachment_id: str,
) -> tuple[str, str]:
    candidates: list[DisplayCandidate] = []
    for config_id, config in configs:
        enabled = config.get("Enabled", True)
        if not isinstance(enabled, bool):
            raise ReportError(f"Enabled must be a boolean in attachment display {config_id}.")
        if not enabled:
            continue
        priority = config.get("Priority", 0)
        if not isinstance(priority, int) or isinstance(priority, bool):
            raise ReportError(f"Priority must be an integer in attachment display {config_id}.")
        entries = config.get("Entries", [])
        if not isinstance(entries, list):
            raise ReportError(f"Entries must be an array in attachment display {config_id}.")
        for entry in entries:
            if not isinstance(entry, dict):
                raise ReportError(f"Entries must contain objects in attachment display {config_id}.")
            applies_to = entry.get("AppliesTo", {})
            sets = entry.get("Sets", {})
            if not isinstance(applies_to, dict) or not isinstance(sets, dict):
                raise ReportError(f"AppliesTo and Sets must be objects in attachment display {config_id}.")
            weight = match_weight(applies_to, role_id, model_id)
            set_display = case_insensitive_get(sets, set_id)
            if weight is None or set_display is None:
                continue
            if not isinstance(set_display, dict):
                raise ReportError(f"Display set {set_id} must be an object in {config_id}.")
            values = set_display.get("Values", {})
            if not isinstance(values, dict):
                raise ReportError(f"Values for display set {set_id} must be an object in {config_id}.")
            set_label = set_display.get("Label") or set_id
            value_label = case_insensitive_get(values, attachment_id) or attachment_id
            if not isinstance(set_label, str) or not isinstance(value_label, str):
                raise ReportError(f"Attachment display labels must be strings in {config_id}.")
            candidates.append(
                DisplayCandidate(
                    weight,
                    priority,
                    config_id,
                    str(entry.get("Id") or ""),
                    set_label,
                    value_label,
                )
            )
    if not candidates:
        return set_id, attachment_id
    winner = min(
        candidates,
        key=lambda item: (
            -item.match_weight,
            -item.priority,
            item.config_id.casefold(),
            item.entry_id.casefold(),
        ),
    )
    return winner.set_label, winner.value_label


def attachment_sets(model: Mapping[str, object], model_path: Path) -> dict[str, dict[str, float]]:
    raw_sets = model.get("RandomAttachmentSets", {})
    if raw_sets is None:
        return {}
    if not isinstance(raw_sets, dict):
        raise ReportError(f"RandomAttachmentSets must be an object at {model_path}.")
    parsed: dict[str, dict[str, float]] = {}
    for set_id, raw_options in raw_sets.items():
        if not isinstance(set_id, str) or not isinstance(raw_options, dict):
            raise ReportError(f"RandomAttachmentSets must map names to objects at {model_path}.")
        options: dict[str, float] = {}
        for attachment_id, raw_attachment in raw_options.items():
            if not isinstance(attachment_id, str) or not isinstance(raw_attachment, dict):
                raise ReportError(f"Attachment options must map names to objects in set {set_id}.")
            weight = raw_attachment.get("Weight", 1)
            if not isinstance(weight, (int, float)) or isinstance(weight, bool) or not math.isfinite(weight):
                raise ReportError(f"Weight for {set_id}.{attachment_id} must be a finite number.")
            if weight < 0:
                raise ReportError(f"Weight for {set_id}.{attachment_id} cannot be negative.")
            options[attachment_id] = float(weight)
        if options and sum(options.values()) <= 0:
            raise ReportError(f"Attachment set {set_id} must have a positive total weight.")
        parsed[set_id] = options
    return parsed


def markdown_text(
    sets: Mapping[str, Mapping[str, float]],
    configs: Sequence[tuple[str, dict]],
    role_id: str | None,
    model_id: str,
    columns: Sequence[str] = DEFAULT_COLUMNS,
) -> str:
    selected_columns = normalize_columns(columns)
    lines = [
        "| "
        + " | ".join(COLUMN_DEFINITIONS[key][0] for key in selected_columns)
        + " |",
        "| "
        + " | ".join(COLUMN_DEFINITIONS[key][1] for key in selected_columns)
        + " |",
    ]
    for set_id in sorted(sets, key=str.casefold):
        options = sets[set_id]
        total_weight = sum(options.values())
        for attachment_id in sorted(options, key=str.casefold):
            weight = options[attachment_id]
            set_label, value_label = resolve_display(
                configs, role_id, model_id, set_id, attachment_id
            )
            chance = weight / total_weight * 100
            values = {
                "attachment-set": set_id,
                "set-display": set_label,
                "attachment": attachment_id,
                "attachment-display": value_label,
                "weight": format_number(weight),
                "chance": f"{chance:.1f}%",
            }
            lines.append(
                "| "
                + " | ".join(
                    escape_markdown(values[key]) for key in selected_columns
                )
                + " |"
            )
    return "\n".join(lines) + "\n"


def normalize_columns(columns: str | Sequence[str] | None) -> tuple[str, ...]:
    if columns is None:
        return DEFAULT_COLUMNS
    if isinstance(columns, str):
        selected = tuple(piece.strip() for piece in columns.split(",") if piece.strip())
    else:
        selected = tuple(columns)
    if not selected:
        raise ReportError("Select at least one report column.")
    unknown = [key for key in selected if key not in COLUMN_DEFINITIONS]
    if unknown:
        raise ReportError(f"Unknown report column: {unknown[0]}")
    if len(set(selected)) != len(selected):
        raise ReportError("Report columns cannot contain duplicates.")
    return selected


def format_number(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".")


def escape_markdown(value: str) -> str:
    return str(value).replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a Markdown table of ModelAsset attachment weights, chances, and Tamework displays."
    )
    parser.add_argument(
        "model_asset",
        nargs="?",
        type=Path,
        help="Path to one ModelAsset JSON file.",
    )
    parser.add_argument(
        "--batch-root",
        type=Path,
        help="Create one report for every ModelAsset under a mod root or Server/Models directory.",
    )
    parser.add_argument(
        "--display-root",
        action="append",
        default=[],
        type=Path,
        help="Extra mod root or AttachmentDisplays directory to scan. Repeat as needed.",
    )
    parser.add_argument(
        "--model-root",
        action="append",
        default=[],
        type=Path,
        help="Extra mod root or Server/Models directory for inherited parents. Repeat as needed.",
    )
    parser.add_argument(
        "--base-game-models",
        type=Path,
        help="Custom Hytale install root or base-game Server/Models directory. Auto-detected by default.",
    )
    parser.add_argument("--model-id", help="Model asset ID used for display matching. Defaults to the filename.")
    parser.add_argument("--role-id", help="Optional NPC role ID used for display matching.")
    parser.add_argument(
        "--columns",
        help="Comma-separated columns in display order. Defaults to all columns.",
    )
    parser.add_argument("--output", type=Path, help="Write the table to this file instead of stdout.")
    args = parser.parse_args(argv)
    if (args.model_asset is None) == (args.batch_root is None):
        parser.error("provide either model_asset or --batch-root")
    return args


def prepare_model_index(
    primary_root: Path,
    extra_roots: Sequence[Path],
) -> tuple[list[Path], dict[str, list[Path]]]:
    model_roots = model_directories(primary_root, extra_roots)
    model_files = sorted(
        (path.resolve() for root in model_roots for path in root.rglob("*.json")),
        key=lambda path: str(path).casefold(),
    )
    root_by_file = {
        path: next(root for root in model_roots if path.is_relative_to(root))
        for path in model_files
    }
    return model_files, build_asset_index(model_files, root_by_file)


def run_single(args: argparse.Namespace, columns: Sequence[str]) -> str:
    model_path = args.model_asset.resolve()
    models_root, mod_root = find_asset_layout(model_path)
    base_game_models = find_base_game_models(
        model_path,
        getattr(args, "base_game_models", None),
    )
    model_roots = list(args.model_root)
    if base_game_models is not None:
        model_roots.append(base_game_models)
    _, model_index = prepare_model_index(models_root, model_roots)
    model = resolve_inheritance(model_path, model_index)
    configs = load_display_configs(display_directories(mod_root, args.display_root))
    sets = attachment_sets(model, model_path)
    return markdown_text(
        sets,
        configs,
        args.role_id,
        args.model_id or model_path.stem,
        columns,
    )


def run_batch(args: argparse.Namespace, columns: Sequence[str]) -> str:
    models_root, mod_root = find_batch_layout(args.batch_root)
    base_game_models = find_base_game_models(
        args.batch_root,
        getattr(args, "base_game_models", None),
    )
    model_roots = list(args.model_root)
    if base_game_models is not None:
        model_roots.append(base_game_models)
    _, model_index = prepare_model_index(models_root, model_roots)
    discovered = sorted(
        (path.resolve() for path in models_root.rglob("*.json")),
        key=lambda path: str(path).casefold(),
    )
    configs = load_display_configs(display_directories(mod_root, args.display_root))
    sections: list[str] = []
    for model_path in discovered:
        model = resolve_inheritance(model_path, model_index)
        sets = attachment_sets(model, model_path)
        if not any(sets.values()):
            continue
        table = markdown_text(
            sets,
            configs,
            args.role_id,
            model_path.stem,
            columns,
        ).rstrip()
        sections.append(f"## {escape_markdown(model_path.stem)}\n\n{table}")

    reported = len(sections)
    lines = [
        "# Model Attachment Report",
        "",
        f"Discovered: {len(discovered)} | Reported: {reported} | Omitted: {len(discovered) - reported}",
    ]
    if sections:
        lines.extend(("", "\n\n".join(sections)))
    return "\n".join(lines) + "\n"


def run(args: argparse.Namespace) -> str:
    columns = normalize_columns(getattr(args, "columns", None))
    if getattr(args, "batch_root", None) is not None:
        return run_batch(args, columns)
    return run_single(args, columns)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        report = run(args)
        if args.output is None:
            sys.stdout.write(report)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(report, encoding="utf-8")
        return 0
    except (OSError, ReportError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
