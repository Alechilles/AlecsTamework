#!/usr/bin/env python3
"""Generate Tamework spawner icon overrides from model RandomAttachmentSets.

This tool expands attachment combinations declared in a model asset JSON,
generates icon paths from a template, and writes role-scoped
IconOverridesByRole entries into a TwSpawnerConfig JSON.
"""

from __future__ import annotations

import argparse
import itertools
import json
import re
import string
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Sequence, Tuple


EMPTY_OPTION_SENTINEL = "__empty__"
SAFE_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class ConfigError(Exception):
    """Raised when CLI configuration or source data is invalid."""


@dataclass(frozen=True)
class SetDefinition:
    name: str
    options: Tuple[str, ...]
    includes_empty: bool


@dataclass(frozen=True)
class OptionVisual:
    model: str | None
    texture: str | None
    weight: float | None


def load_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ConfigError(f"JSON file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ConfigError(f"Failed to parse JSON at {path}: {exc}") from exc


def load_json_from_source(asset_root: Path, source: str) -> Tuple[object, str, str]:
    """Load JSON from disk path or zip-entry syntax.

    Zip syntax:
      C:\\path\\to\\mod.zip!Server/Models/Foo.json
      relative\\mod.zip!Server/Models/Foo.json
    """
    if "!" in source:
        zip_part, inner_part = source.split("!", 1)
        zip_path = resolve_asset_path(asset_root, zip_part).resolve()
        inner_path = inner_part.replace("\\", "/").lstrip("/")
        if not inner_path:
            raise ConfigError("Zip model source is missing inner path after '!'.")
        try:
            with zipfile.ZipFile(zip_path) as archive:
                try:
                    data = archive.read(inner_path)
                except KeyError as exc:
                    raise ConfigError(
                        f"Zip entry not found: {zip_path}!{inner_path}"
                    ) from exc
        except FileNotFoundError as exc:
            raise ConfigError(f"Zip file not found: {zip_path}") from exc
        except zipfile.BadZipFile as exc:
            raise ConfigError(f"Invalid zip file: {zip_path}") from exc
        try:
            parsed = json.loads(data.decode("utf-8"))
        except UnicodeDecodeError as exc:
            raise ConfigError(
                f"Zip entry is not valid UTF-8 JSON: {zip_path}!{inner_path}"
            ) from exc
        except json.JSONDecodeError as exc:
            raise ConfigError(
                f"Failed to parse JSON at {zip_path}!{inner_path}: {exc}"
            ) from exc
        return parsed, f"{zip_path}!{inner_path}", Path(inner_path).stem

    path = resolve_asset_path(asset_root, source).resolve()
    return load_json(path), str(path), path.stem


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def slugify(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9]+", "-", value).strip("-").lower()
    return cleaned or "value"


def parse_csv_or_repeat(values: Sequence[str]) -> List[str]:
    parsed: List[str] = []
    for raw in values:
        for piece in raw.split(","):
            item = piece.strip()
            if item:
                parsed.append(item)
    return parsed


def parse_float_list(raw: str, expected_count: int, flag: str) -> Tuple[float, ...]:
    parts = [piece.strip() for piece in raw.split(",")]
    if len(parts) != expected_count:
        raise ConfigError(f"{flag} must contain {expected_count} comma-separated numbers.")
    try:
        return tuple(float(piece) for piece in parts)
    except ValueError as exc:
        raise ConfigError(f"{flag} must contain only numbers.") from exc


def resolve_asset_path(asset_root: Path, value: str) -> Path:
    candidate = Path(value)
    if candidate.is_absolute():
        return candidate
    return asset_root / value


def extract_set_definitions(
    model_json: Mapping[str, object],
    include_empty_sets: Iterable[str],
) -> List[SetDefinition]:
    raw_sets = model_json.get("RandomAttachmentSets")
    if raw_sets is None:
        raise ConfigError("Model JSON does not define RandomAttachmentSets.")
    if not isinstance(raw_sets, dict):
        raise ConfigError("RandomAttachmentSets is not a JSON object.")

    include_empty = set(include_empty_sets)
    found_set_names = set(raw_sets.keys())
    unknown_sets = sorted(include_empty.difference(found_set_names))
    if unknown_sets:
        raise ConfigError(
            "include-empty-set references unknown set(s): " + ", ".join(unknown_sets)
        )

    definitions: List[SetDefinition] = []
    for set_name, raw_options in raw_sets.items():
        if not isinstance(set_name, str):
            raise ConfigError("RandomAttachmentSets contains a non-string set key.")
        if not isinstance(raw_options, dict):
            raise ConfigError(f"Attachment set '{set_name}' is not a JSON object.")
        options: List[str] = []
        for option_name in raw_options.keys():
            if not isinstance(option_name, str):
                raise ConfigError(f"Attachment set '{set_name}' contains non-string option key.")
            options.append(option_name)
        if not options:
            raise ConfigError(f"Attachment set '{set_name}' has no options.")
        includes_empty = set_name in include_empty
        if includes_empty:
            options.append(EMPTY_OPTION_SENTINEL)
        definitions.append(SetDefinition(set_name, tuple(options), includes_empty))
    if not definitions:
        raise ConfigError("RandomAttachmentSets was present but empty.")
    return definitions


def extract_option_visuals(
    model_json: Mapping[str, object],
) -> Dict[str, Dict[str, OptionVisual]]:
    raw_sets = model_json.get("RandomAttachmentSets")
    if raw_sets is None or not isinstance(raw_sets, dict):
        return {}
    result: Dict[str, Dict[str, OptionVisual]] = {}
    for set_name, raw_options in raw_sets.items():
        if not isinstance(set_name, str) or not isinstance(raw_options, dict):
            continue
        set_result: Dict[str, OptionVisual] = {}
        for option_name, raw_value in raw_options.items():
            if not isinstance(option_name, str):
                continue
            model_value = None
            texture_value = None
            weight_value = None
            if isinstance(raw_value, dict):
                if isinstance(raw_value.get("Model"), str):
                    model_value = raw_value.get("Model")
                if isinstance(raw_value.get("Texture"), str):
                    texture_value = raw_value.get("Texture")
                if isinstance(raw_value.get("Weight"), (int, float)):
                    weight_value = float(raw_value.get("Weight"))
            set_result[option_name] = OptionVisual(
                model=model_value, texture=texture_value, weight=weight_value
            )
        result[set_name] = set_result
    return result


def discover_roles(
    roles_from_args: Sequence[str],
    spawner_json: Mapping[str, object] | None,
) -> List[str]:
    explicit = parse_csv_or_repeat(roles_from_args)
    if explicit:
        return explicit
    if spawner_json is None:
        raise ConfigError("No roles provided. Use --roles or provide --spawner-config with Allowlist.")

    allowed = spawner_json.get("AllowedRoles")
    if not isinstance(allowed, dict):
        raise ConfigError(
            "Could not derive roles from spawner config. AllowedRoles is missing or invalid."
        )
    mode = allowed.get("Mode")
    allowlist = allowed.get("Allowlist")
    if mode != "Allowlist" or not isinstance(allowlist, list):
        raise ConfigError(
            "Could not derive roles from spawner config. AllowedRoles must be Mode=Allowlist."
        )
    roles = [value for value in allowlist if isinstance(value, str) and value.strip()]
    if not roles:
        raise ConfigError("Spawner allowlist did not contain any valid role IDs.")
    return roles


def set_placeholder_entries(set_name: str, value: str, output: Dict[str, str]) -> None:
    safe_set_key = f"set_{slugify(set_name).replace('-', '_')}"
    output[safe_set_key] = value
    if SAFE_KEY_RE.match(set_name):
        output[set_name] = value


def validate_template(template: str, placeholders: Mapping[str, str]) -> None:
    fields = {
        field_name
        for _, field_name, _, _ in string.Formatter().parse(template)
        if field_name is not None and field_name != ""
    }
    missing = sorted(field for field in fields if field not in placeholders)
    if missing:
        available = ", ".join(sorted(placeholders.keys()))
        raise ConfigError(
            "Icon template references unknown field(s): "
            + ", ".join(missing)
            + f". Available: {available}"
        )


def build_combo_records(
    set_defs: Sequence[SetDefinition],
    roles: Sequence[str],
    icon_template: str,
    empty_value_token: str,
    max_combos: int,
) -> Tuple[Dict[str, List[dict]], List[dict], List[str]]:
    option_space: List[Sequence[str]] = [set_def.options for set_def in set_defs]
    estimated_combos = 1
    for options in option_space:
        estimated_combos *= len(options)
    if estimated_combos > max_combos:
        raise ConfigError(
            f"Combination count {estimated_combos} exceeds --max-combos={max_combos}. "
            "Increase --max-combos or reduce attachment options."
        )

    role_overrides: Dict[str, List[dict]] = {role: [] for role in roles}
    combo_manifest: List[dict] = []
    skipped_empty_overrides: List[str] = []

    for combo_index, option_combo in enumerate(itertools.product(*option_space), start=1):
        attachments: Dict[str, str] = {}
        set_values: Dict[str, str] = {}
        slug_parts: List[str] = []

        for set_def, selected in zip(set_defs, option_combo):
            if selected == EMPTY_OPTION_SENTINEL:
                rendered_value = empty_value_token
            else:
                rendered_value = selected
                attachments[set_def.name] = selected
            set_values[set_def.name] = rendered_value
            slug_parts.append(f"{slugify(set_def.name)}-{slugify(rendered_value)}")

        combo_slug = "__".join(slug_parts)
        common_placeholders: Dict[str, str] = {
            "combo_index": str(combo_index),
            "combo_slug": combo_slug,
        }
        for set_name, set_value in set_values.items():
            set_placeholder_entries(set_name, set_value, common_placeholders)

        if combo_index == 1:
            # Validate fields once after discovering full placeholder surface.
            sample = dict(common_placeholders)
            sample["role"] = roles[0]
            validate_template(icon_template, sample)

        icons_by_role: Dict[str, str] = {}
        for role in roles:
            role_placeholders = dict(common_placeholders)
            role_placeholders["role"] = role
            icon_path = icon_template.format(**role_placeholders)
            icons_by_role[role] = icon_path

            if attachments:
                role_overrides[role].append(
                    {
                        "Icon": icon_path,
                        "Attachments": dict(attachments),
                    }
                )

        if not attachments:
            skipped_empty_overrides.append(combo_slug)

        combo_manifest.append(
            {
                "index": combo_index,
                "comboSlug": combo_slug,
                "setValues": set_values,
                "attachments": attachments,
                "iconsByRole": icons_by_role,
            }
        )

    return role_overrides, combo_manifest, skipped_empty_overrides


def apply_overrides_to_spawner(
    spawner_json: Mapping[str, object],
    role_overrides: Mapping[str, List[dict]],
    icon_default: str | None,
) -> dict:
    output = dict(spawner_json)
    existing = output.get("IconOverridesByRole")
    if not isinstance(existing, dict):
        existing = {}
    merged = dict(existing)
    for role, overrides in role_overrides.items():
        merged[role] = overrides
    output["IconOverridesByRole"] = merged
    if icon_default is not None:
        output["IconDefault"] = icon_default
    return output


def to_common_asset_file(asset_root: Path, asset_path: str | None) -> str | None:
    if asset_path is None:
        return None
    path = Path(asset_path)
    if path.is_absolute():
        return str(path)
    return str((asset_root / "Common" / path).resolve())


def build_renderer_jobs(
    *,
    asset_root: Path,
    model_json: Mapping[str, object],
    model_source: str,
    roles: Sequence[str],
    combo_manifest: Sequence[Mapping[str, object]],
    option_visuals: Mapping[str, Mapping[str, OptionVisual]],
    renderer_name: str,
    icon_size: int,
    camera_scale: float,
    camera_rotation: Tuple[float, float, float],
    camera_translation: Tuple[float, float],
) -> dict:
    base_model = model_json.get("Model") if isinstance(model_json.get("Model"), str) else None
    base_texture = model_json.get("Texture") if isinstance(model_json.get("Texture"), str) else None

    jobs: List[dict] = []
    for combo in combo_manifest:
        combo_index = combo.get("index")
        combo_slug = combo.get("comboSlug")
        attachments = combo.get("attachments")
        set_values = combo.get("setValues")
        icons_by_role = combo.get("iconsByRole")
        if not isinstance(combo_index, int):
            continue
        if not isinstance(combo_slug, str):
            continue
        if not isinstance(attachments, dict):
            attachments = {}
        if not isinstance(set_values, dict):
            set_values = {}
        if not isinstance(icons_by_role, dict):
            continue

        for role in roles:
            icon_path = icons_by_role.get(role)
            if not isinstance(icon_path, str) or not icon_path.strip():
                continue
            output_icon_file = to_common_asset_file(asset_root, icon_path)
            selected_assets: List[dict] = []
            for set_name, option_name in attachments.items():
                if not isinstance(set_name, str) or not isinstance(option_name, str):
                    continue
                visual = option_visuals.get(set_name, {}).get(option_name)
                selected_assets.append(
                    {
                        "set": set_name,
                        "option": option_name,
                        "model": visual.model if visual else None,
                        "texture": visual.texture if visual else None,
                        "weight": visual.weight if visual else None,
                        "modelFile": to_common_asset_file(asset_root, visual.model if visual else None),
                        "textureFile": to_common_asset_file(asset_root, visual.texture if visual else None),
                    }
                )
            jobs.append(
                {
                    "id": f"{combo_slug}__role_{role}",
                    "role": role,
                    "comboIndex": combo_index,
                    "comboSlug": combo_slug,
                    "attachments": attachments,
                    "setValues": set_values,
                    "baseModel": base_model,
                    "baseTexture": base_texture,
                    "baseModelFile": to_common_asset_file(asset_root, base_model),
                    "baseTextureFile": to_common_asset_file(asset_root, base_texture),
                    "selectedOptionAssets": selected_assets,
                    "outputIcon": icon_path,
                    "outputIconFile": output_icon_file,
                }
            )

    return {
        "schema": "tamework.spawner-icon-render-jobs.v1",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "renderer": renderer_name,
        "assetRoot": str(asset_root),
        "modelSource": model_source,
        "defaults": {
            "iconSize": icon_size,
            "camera": {
                "scale": camera_scale,
                "rotation": list(camera_rotation),
                "translation": list(camera_translation),
            },
        },
        "model": {
            "baseModel": base_model,
            "baseTexture": base_texture,
            "baseModelFile": to_common_asset_file(asset_root, base_model),
            "baseTextureFile": to_common_asset_file(asset_root, base_texture),
            "randomAttachmentSets": {
                set_name: {
                    option_name: {
                        "model": option.model,
                        "texture": option.texture,
                        "weight": option.weight,
                        "modelFile": to_common_asset_file(asset_root, option.model),
                        "textureFile": to_common_asset_file(asset_root, option.texture),
                    }
                    for option_name, option in options.items()
                }
                for set_name, options in option_visuals.items()
            },
        },
        "jobCount": len(jobs),
        "jobs": jobs,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Generate TwSpawnerConfig IconOverridesByRole entries from model "
            "RandomAttachmentSets."
        )
    )
    parser.add_argument(
        "--asset-root",
        default="src/main/resources",
        help="Asset root containing Server/ and Common/ (default: src/main/resources).",
    )
    parser.add_argument(
        "--model",
        required=True,
        help=(
            "Path to model JSON containing RandomAttachmentSets. "
            "Absolute path or relative to --asset-root."
        ),
    )
    parser.add_argument(
        "--spawner-config",
        help=(
            "Path to existing TwSpawnerConfig JSON. If provided, generated overrides "
            "can be written to this config."
        ),
    )
    parser.add_argument(
        "--write-spawner",
        help=(
            "Output path for updated spawner config JSON. Defaults to "
            "<spawner-config>.generated.json when --spawner-config is set."
        ),
    )
    parser.add_argument(
        "--in-place",
        action="store_true",
        help="Write generated overrides back into --spawner-config directly.",
    )
    parser.add_argument(
        "--roles",
        action="append",
        default=[],
        help=(
            "Role ID(s) to generate IconOverridesByRole for. Can be repeated or "
            "comma-separated. If omitted, roles are derived from spawner Allowlist."
        ),
    )
    parser.add_argument(
        "--icon-template",
        required=True,
        help=(
            "Template for icon path generation. Supports {role}, {combo_index}, "
            "{combo_slug}, and per-set placeholders {set_<set_name_slug>}."
        ),
    )
    parser.add_argument(
        "--icon-default",
        help="Optional IconDefault value to set in the output spawner config.",
    )
    parser.add_argument(
        "--include-empty-set",
        action="append",
        default=[],
        help=(
            "Attachment set name(s) that should include an explicit empty state "
            "(useful for harvested/removed attachments)."
        ),
    )
    parser.add_argument(
        "--empty-value-token",
        default="none",
        help="Token used in placeholders/slugs for empty-set states (default: none).",
    )
    parser.add_argument(
        "--manifest-out",
        help=(
            "Path for manifest JSON output. Defaults to "
            ".tmp/spawner_icon_manifest_<model_stem>.json"
        ),
    )
    parser.add_argument(
        "--max-combos",
        type=int,
        default=10000,
        help="Maximum allowed combination count before aborting (default: 10000).",
    )
    parser.add_argument(
        "--renderer-jobs-out",
        help=(
            "Optional path for renderer job export JSON. Defaults to "
            ".tmp/spawner_icon_render_jobs_<model_stem>.json"
        ),
    )
    parser.add_argument(
        "--renderer-name",
        default="blockbench",
        help="Renderer label stored in jobs JSON (default: blockbench).",
    )
    parser.add_argument(
        "--icon-size",
        type=int,
        default=128,
        help="Icon output size metadata for renderer jobs (default: 128).",
    )
    parser.add_argument(
        "--camera-scale",
        type=float,
        default=1.0,
        help="Camera scale metadata for renderer jobs (default: 1.0).",
    )
    parser.add_argument(
        "--camera-rotation",
        default="22.5,45,22.5",
        help="Camera rotation metadata X,Y,Z in degrees (default: 22.5,45,22.5).",
    )
    parser.add_argument(
        "--camera-translation",
        default="0,-13.5",
        help="Camera translation metadata X,Y (default: 0,-13.5).",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    asset_root = Path(args.asset_root).resolve()
    model_json, model_source, model_stem = load_json_from_source(asset_root, args.model)
    if not isinstance(model_json, dict):
        raise ConfigError(f"Model JSON must be an object: {model_source}")

    spawner_json = None
    spawner_path: Path | None = None
    if args.spawner_config:
        spawner_path = resolve_asset_path(asset_root, args.spawner_config).resolve()
        loaded_spawner = load_json(spawner_path)
        if not isinstance(loaded_spawner, dict):
            raise ConfigError(f"Spawner config JSON must be an object: {spawner_path}")
        spawner_json = loaded_spawner

    include_empty_sets = parse_csv_or_repeat(args.include_empty_set)
    set_defs = extract_set_definitions(model_json, include_empty_sets)
    option_visuals = extract_option_visuals(model_json)
    roles = discover_roles(args.roles, spawner_json)
    camera_rotation = parse_float_list(args.camera_rotation, 3, "--camera-rotation")
    camera_translation = parse_float_list(args.camera_translation, 2, "--camera-translation")

    role_overrides, combo_manifest, skipped_empty = build_combo_records(
        set_defs=set_defs,
        roles=roles,
        icon_template=args.icon_template,
        empty_value_token=args.empty_value_token,
        max_combos=args.max_combos,
    )

    manifest_path = (
        Path(args.manifest_out).resolve()
        if args.manifest_out
        else (Path.cwd() / ".tmp" / f"spawner_icon_manifest_{model_stem}.json").resolve()
    )
    manifest_payload = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "assetRoot": str(asset_root),
        "modelPath": model_source,
        "roles": roles,
        "maxCombos": args.max_combos,
        "randomAttachmentSets": [
            {
                "set": set_def.name,
                "options": list(set_def.options),
                "includesEmptyState": set_def.includes_empty,
            }
            for set_def in set_defs
        ],
        "comboCount": len(combo_manifest),
        "overridesGeneratedByRole": {
            role: len(overrides) for role, overrides in role_overrides.items()
        },
        "skippedEmptyAttachmentCombos": skipped_empty,
        "combos": combo_manifest,
    }
    write_json(manifest_path, manifest_payload)

    output_lines = [
        f"Manifest written: {manifest_path}",
        f"Model: {model_source}",
        f"Roles: {', '.join(roles)}",
        f"Attachment combos: {len(combo_manifest)}",
    ]

    if skipped_empty:
        output_lines.append(
            "Note: Some combos had no attachments and cannot be represented as overrides. "
            "Use IconDefault for those states."
        )

    if spawner_json is not None:
        updated_spawner = apply_overrides_to_spawner(
            spawner_json=spawner_json,
            role_overrides=role_overrides,
            icon_default=args.icon_default,
        )
        if args.in_place:
            target_path = spawner_path
        elif args.write_spawner:
            target_path = resolve_asset_path(asset_root, args.write_spawner).resolve()
        else:
            assert spawner_path is not None
            target_path = spawner_path.with_suffix(".generated.json")
        write_json(target_path, updated_spawner)
        output_lines.append(f"Spawner config written: {target_path}")

    if args.renderer_jobs_out:
        renderer_jobs_path = Path(args.renderer_jobs_out).resolve()
    else:
        renderer_jobs_path = (
            Path.cwd() / ".tmp" / f"spawner_icon_render_jobs_{model_stem}.json"
        ).resolve()
    renderer_payload = build_renderer_jobs(
        asset_root=asset_root,
        model_json=model_json,
        model_source=model_source,
        roles=roles,
        combo_manifest=combo_manifest,
        option_visuals=option_visuals,
        renderer_name=args.renderer_name,
        icon_size=args.icon_size,
        camera_scale=args.camera_scale,
        camera_rotation=camera_rotation,
        camera_translation=camera_translation,
    )
    write_json(renderer_jobs_path, renderer_payload)
    output_lines.append(f"Renderer jobs written: {renderer_jobs_path}")

    print("\n".join(output_lines))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ConfigError as exc:
        print(f"ERROR: {exc}")
        raise SystemExit(2)
