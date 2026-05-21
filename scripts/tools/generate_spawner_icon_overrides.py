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
import os
import re
import string
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Sequence, Tuple


EMPTY_OPTION_SENTINEL = "__empty__"
SAFE_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
ENV_VAR_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


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


@dataclass(frozen=True)
class BatchSource:
    models_root: str
    common_roots: Tuple[str, ...]

    def as_report(self) -> dict:
        output = {"modelsRoot": self.models_root}
        if self.common_roots:
            output["commonRoots"] = list(self.common_roots)
        return output


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


def parse_string_list_field(raw: object, field_name: str) -> List[str]:
    if raw is None:
        return []
    if isinstance(raw, str):
        return parse_csv_or_repeat([raw])
    if isinstance(raw, list):
        parsed: List[str] = []
        for item in raw:
            if not isinstance(item, str):
                raise ConfigError(f"{field_name} must contain only strings.")
            parsed.extend(parse_csv_or_repeat([item]))
        return parsed
    raise ConfigError(f"{field_name} must be a string or array of strings.")


def parse_string_list_mapping(raw: object, field_name: str) -> Dict[str, List[str]]:
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise ConfigError(f"{field_name} must be an object mapping set names to strings or string arrays.")
    parsed: Dict[str, List[str]] = {}
    for key, value in raw.items():
        if not isinstance(key, str) or not key.strip():
            raise ConfigError(f"{field_name} keys must be non-empty strings.")
        parsed[key] = parse_string_list_field(value, f"{field_name}.{key}")
    return parsed


def parse_float_list(raw: str, expected_count: int, flag: str) -> Tuple[float, ...]:
    parts = [piece.strip() for piece in raw.split(",")]
    if len(parts) != expected_count:
        raise ConfigError(f"{flag} must contain {expected_count} comma-separated numbers.")
    try:
        return tuple(float(piece) for piece in parts)
    except ValueError as exc:
        raise ConfigError(f"{flag} must contain only numbers.") from exc


def parse_float_field(raw: object, expected_count: int, field_name: str) -> Tuple[float, ...]:
    if isinstance(raw, str):
        return parse_float_list(raw, expected_count, field_name)
    if isinstance(raw, list):
        if len(raw) != expected_count:
            raise ConfigError(f"{field_name} must contain {expected_count} numbers.")
        try:
            return tuple(float(piece) for piece in raw)
        except ValueError as exc:
            raise ConfigError(f"{field_name} must contain only numbers.") from exc
        except TypeError as exc:
            raise ConfigError(f"{field_name} must contain only numbers.") from exc
    raise ConfigError(f"{field_name} must be a comma-separated string or number array.")


def parse_bool_field(raw: object, field_name: str) -> bool:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, str):
        normalized = raw.strip().lower()
        if normalized in {"true", "1", "yes", "y", "on"}:
            return True
        if normalized in {"false", "0", "no", "n", "off"}:
            return False
    raise ConfigError(f"{field_name} must be a boolean.")


def resolve_asset_path(asset_root: Path, value: str) -> Path:
    candidate = Path(value)
    if candidate.is_absolute():
        return candidate
    return asset_root / value


def expand_path_variables(value: str, manifest_dir: Path) -> str:
    variables = dict(os.environ)
    variables["MANIFEST_DIR"] = str(manifest_dir)

    def replace(match: re.Match[str]) -> str:
        name = match.group(1)
        if name not in variables:
            raise ConfigError(f"Path references undefined variable ${{{name}}}.")
        return variables[name]

    return ENV_VAR_RE.sub(replace, value)


def resolve_manifest_root_path(manifest_dir: Path, raw_value: str) -> str:
    value = expand_path_variables(raw_value, manifest_dir)
    if "!" in value:
        zip_part, inner_part = value.split("!", 1)
        zip_path = Path(zip_part)
        if not zip_path.is_absolute():
            zip_path = manifest_dir / zip_path
        inner_path = inner_part.replace("\\", "/").strip("/")
        return f"{zip_path.resolve()}!{inner_path}" if inner_path else str(zip_path.resolve())

    path = Path(value)
    if not path.is_absolute():
        path = manifest_dir / path
    return str(path.resolve())


def join_manifest_model_path(models_root: str, model_path: str) -> str:
    clean_model = model_path.replace("\\", "/").lstrip("/")
    if not clean_model:
        raise ConfigError("Batch manifest entry model path cannot be empty.")
    if "!" in clean_model:
        return clean_model
    if "!" in models_root:
        zip_part, inner_part = models_root.split("!", 1)
        clean_inner = inner_part.replace("\\", "/").strip("/")
        joined_inner = f"{clean_inner}/{clean_model}" if clean_inner else clean_model
        return f"{zip_part}!{joined_inner}"
    return str((Path(models_root) / Path(clean_model)).resolve())


def infer_common_root_from_models_root(models_root: str) -> str | None:
    if "!" in models_root:
        zip_part, inner_part = models_root.split("!", 1)
        clean_inner = inner_part.replace("\\", "/").strip("/")
        if clean_inner.endswith("Server/Models"):
            prefix = clean_inner[: -len("Server/Models")].rstrip("/")
            common_inner = f"{prefix}/Common" if prefix else "Common"
            return f"{zip_part}!{common_inner}"
        return None

    path = Path(models_root)
    if len(path.parts) >= 2 and path.parts[-2:] == ("Server", "Models"):
        return str((path.parent.parent / "Common").resolve())
    return None


def extract_zip_asset(common_root: str, asset_path: str, extract_root: Path) -> str:
    zip_part, inner_part = common_root.split("!", 1)
    zip_path = Path(zip_part)
    inner_root = inner_part.replace("\\", "/").strip("/")
    clean_asset = asset_path.replace("\\", "/").lstrip("/")
    if not clean_asset or clean_asset.startswith("../") or "/../" in clean_asset:
        raise ConfigError(f"Unsafe zip asset path: {asset_path}")
    zip_entry = f"{inner_root}/{clean_asset}" if inner_root else clean_asset
    output_path = extract_root / Path(*clean_asset.split("/"))

    try:
        with zipfile.ZipFile(zip_path) as archive:
            try:
                data = archive.read(zip_entry)
            except KeyError as exc:
                raise ConfigError(f"Zip asset not found: {zip_path}!{zip_entry}") from exc
    except FileNotFoundError as exc:
        raise ConfigError(f"Zip file not found: {zip_path}") from exc
    except zipfile.BadZipFile as exc:
        raise ConfigError(f"Invalid zip file: {zip_path}") from exc

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(data)
    return str(output_path.resolve())


def resolve_common_asset_file(
    asset_root: Path,
    common_root: str | None,
    asset_path: str | None,
    extract_root: Path | None = None,
) -> str | None:
    if asset_path is None:
        return None
    path = Path(asset_path)
    if path.is_absolute():
        return str(path)
    if common_root is None:
        return to_common_asset_file(asset_root, asset_path)
    if "!" in common_root:
        if extract_root is None:
            raise ConfigError("Zip-backed commonRoot requires an extraction directory.")
        return extract_zip_asset(common_root, asset_path, extract_root)
    return str((Path(common_root) / path).resolve())


def resolve_common_asset_file_from_roots(
    asset_root: Path,
    common_roots: Sequence[str],
    asset_path: str | None,
    extract_root: Path | None = None,
) -> str | None:
    if asset_path is None:
        return None
    if not common_roots:
        return to_common_asset_file(asset_root, asset_path)

    errors: List[str] = []
    for common_root in common_roots:
        try:
            resolved = resolve_common_asset_file(asset_root, common_root, asset_path, extract_root)
        except ConfigError as exc:
            errors.append(str(exc))
            continue
        if resolved is None:
            return None
        if Path(resolved).is_file():
            return resolved
        errors.append(f"Common asset not found: {resolved}")

    raise ConfigError(
        f"Could not resolve Common asset '{asset_path}' from configured roots. "
        + " | ".join(errors)
    )


def read_required_mapping(raw: Mapping[str, object], key: str, context: str) -> Mapping[str, object]:
    value = raw.get(key)
    if not isinstance(value, dict):
        raise ConfigError(f"{context} must define object '{key}'.")
    return value


def get_string(raw: Mapping[str, object], key: str, context: str, default: str | None = None) -> str:
    value = raw.get(key)
    if value is None:
        if default is not None:
            return default
        raise ConfigError(f"{context} must define string '{key}'.")
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{context} field '{key}' must be a non-empty string.")
    return value


def extract_set_definitions(
    model_json: Mapping[str, object],
    include_empty_sets: Iterable[str],
) -> List[SetDefinition]:
    raw_sets = model_json.get("RandomAttachmentSets")
    if raw_sets is None:
        include_empty = set(include_empty_sets)
        if include_empty:
            raise ConfigError(
                "include-empty-set cannot be used because model JSON does not define RandomAttachmentSets."
            )
        return []
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
    return definitions


def filter_set_definitions(
    set_defs: Sequence[SetDefinition],
    keep_sets: Sequence[str],
    context: str,
) -> List[SetDefinition]:
    if not keep_sets:
        return list(set_defs)

    by_name = {set_def.name: set_def for set_def in set_defs}
    unknown = [set_name for set_name in keep_sets if set_name not in by_name]
    if unknown:
        raise ConfigError(
            f"{context} keepAttachmentSets references unknown set(s): "
            + ", ".join(unknown)
            + f". Available: {', '.join(by_name.keys())}"
        )

    seen = set()
    filtered: List[SetDefinition] = []
    for set_name in keep_sets:
        if set_name in seen:
            continue
        seen.add(set_name)
        filtered.append(by_name[set_name])
    if not filtered:
        raise ConfigError(f"{context} keepAttachmentSets did not select any sets.")
    return filtered


def exclude_set_options(
    set_defs: Sequence[SetDefinition],
    exclude_options: Mapping[str, Sequence[str]],
    context: str,
) -> List[SetDefinition]:
    if not exclude_options:
        return list(set_defs)

    by_name = {set_def.name: set_def for set_def in set_defs}
    unknown_sets = sorted(set(exclude_options.keys()).difference(by_name.keys()))
    if unknown_sets:
        raise ConfigError(
            f"{context} excludeAttachmentOptions references unknown set(s): "
            + ", ".join(unknown_sets)
        )

    filtered: List[SetDefinition] = []
    for set_def in set_defs:
        excluded = set(exclude_options.get(set_def.name, []))
        unknown_options = sorted(excluded.difference(set_def.options))
        if unknown_options:
            raise ConfigError(
                f"{context} excludeAttachmentOptions.{set_def.name} references unknown option(s): "
                + ", ".join(unknown_options)
            )
        options = tuple(option for option in set_def.options if option not in excluded)
        if not options:
            raise ConfigError(
                f"{context} excludeAttachmentOptions removed every option from set '{set_def.name}'."
            )
        filtered.append(
            SetDefinition(
                name=set_def.name,
                options=options,
                includes_empty=set_def.includes_empty and EMPTY_OPTION_SENTINEL in options,
            )
        )
    return filtered


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
    icon_role_for_paths: str | None = None,
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

        combo_slug = "__".join(slug_parts) if slug_parts else "base"
        common_placeholders: Dict[str, str] = {
            "combo_index": str(combo_index),
            "combo_slug": combo_slug,
        }
        for set_name, set_value in set_values.items():
            set_placeholder_entries(set_name, set_value, common_placeholders)

        if combo_index == 1:
            # Validate fields once after discovering full placeholder surface.
            sample = dict(common_placeholders)
            sample["role"] = icon_role_for_paths or roles[0]
            validate_template(icon_template, sample)

        icons_by_role: Dict[str, str] = {}
        for role in roles:
            role_placeholders = dict(common_placeholders)
            role_placeholders["role"] = icon_role_for_paths or role
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


def build_icon_override_group(roles: Sequence[str], combo_manifest: Sequence[Mapping[str, object]]) -> dict:
    overrides: List[dict] = []
    icon_default = None
    for combo in combo_manifest:
        attachments = combo.get("attachments")
        icons_by_role = combo.get("iconsByRole")
        if not isinstance(attachments, dict):
            continue
        if not isinstance(icons_by_role, dict):
            continue
        icon = None
        for role in roles:
            candidate = icons_by_role.get(role)
            if isinstance(candidate, str) and candidate.strip():
                icon = candidate
                break
        if icon is None:
            continue
        if not attachments:
            if icon_default is None:
                icon_default = icon
            continue
        overrides.append(
            {
                "Icon": icon,
                "Attachments": dict(attachments),
            }
        )
    group = {
        "Roles": list(roles),
        "Overrides": overrides,
    }
    if icon_default is not None:
        group["IconDefault"] = icon_default
    return group


def icon_override_group_role_key(group: Mapping[str, object]) -> Tuple[str, ...]:
    roles = group.get("Roles")
    if not isinstance(roles, list):
        return ()
    normalized_roles = sorted(
        {
            role.strip().lower()
            for role in roles
            if isinstance(role, str) and role.strip()
        }
    )
    return tuple(normalized_roles)


def icon_override_attachment_predicate(override: Mapping[str, object]) -> Dict[str, str]:
    attachments = override.get("Attachments")
    if not isinstance(attachments, dict):
        return {}
    return {
        str(key): str(value)
        for key, value in attachments.items()
        if str(key).strip() and str(value).strip()
    }


def icon_override_predicates_overlap(first: Mapping[str, str], second: Mapping[str, str]) -> bool:
    if not first or not second:
        return False
    for key in set(first).intersection(second):
        if first[key] != second[key]:
            return False
    return True


def validate_icon_override_group_predicates(role_key: Tuple[str, ...], overrides: Sequence[object]) -> None:
    predicates: List[Tuple[int, Dict[str, str]]] = []
    for index, override in enumerate(overrides):
        if not isinstance(override, Mapping):
            continue
        predicate = icon_override_attachment_predicate(override)
        if not predicate:
            continue
        for existing_index, existing_predicate in predicates:
            if icon_override_predicates_overlap(existing_predicate, predicate):
                roles = ", ".join(role_key)
                raise ConfigError(
                    "overlapping IconOverrideGroups for roles "
                    f"{roles}: overrides {existing_index} and {index} can match the same captured NPC. "
                    "Generate full attachment combinations or split them into separate role groups."
                )
        predicates.append((index, predicate))


def merged_icon_override_group_overrides(
    role_key: Tuple[str, ...],
    existing_overrides: object,
    incoming_overrides: object,
) -> List[dict] | None:
    if not isinstance(existing_overrides, list) or not isinstance(incoming_overrides, list):
        return None
    merged = list(existing_overrides) + list(incoming_overrides)
    validate_icon_override_group_predicates(role_key, merged)
    return merged


def append_or_merge_icon_override_group(groups: List[dict], group: Mapping[str, object]) -> None:
    role_key = icon_override_group_role_key(group)
    if not role_key:
        return

    for existing in groups:
        if icon_override_group_role_key(existing) != role_key:
            continue

        existing_overrides = existing.get("Overrides")
        incoming_overrides = group.get("Overrides")
        merged_overrides = merged_icon_override_group_overrides(role_key, existing_overrides, incoming_overrides)
        if merged_overrides is not None:
            existing["Overrides"] = merged_overrides
        if not existing.get("IconDefault") and isinstance(group.get("IconDefault"), str):
            existing["IconDefault"] = group["IconDefault"]
        return

    incoming_overrides = group.get("Overrides")
    if isinstance(incoming_overrides, list):
        validate_icon_override_group_predicates(role_key, incoming_overrides)
    groups.append(dict(group))


def replace_or_append_icon_override_group(groups: List[dict], group: Mapping[str, object]) -> None:
    role_key = icon_override_group_role_key(group)
    if not role_key:
        return
    replacement = dict(group)
    replacement_overrides = replacement.get("Overrides")
    if isinstance(replacement_overrides, list):
        validate_icon_override_group_predicates(role_key, replacement_overrides)
    for index, existing in enumerate(groups):
        if icon_override_group_role_key(existing) == role_key:
            groups[index] = replacement
            return
    groups.append(replacement)


def apply_overrides_to_spawner(
    spawner_json: Mapping[str, object],
    role_overrides: Mapping[str, List[dict]],
    icon_override_groups: Sequence[Mapping[str, object]] = (),
    icon_default: str | None = None,
    replace_icon_overrides: bool = False,
) -> dict:
    output = dict(spawner_json)
    if replace_icon_overrides:
        existing = {}
        existing_groups = []
    else:
        existing = output.get("IconOverridesByRole")
        if not isinstance(existing, dict):
            existing = {}
        raw_groups = output.get("IconOverrideGroups")
        existing_groups = list(raw_groups) if isinstance(raw_groups, list) else []
    merged = dict(existing)
    for role, overrides in role_overrides.items():
        merged[role] = overrides
    if merged or replace_icon_overrides or "IconOverridesByRole" in output:
        output["IconOverridesByRole"] = merged
    else:
        output.pop("IconOverridesByRole", None)
    groups = list(existing_groups)
    for group in icon_override_groups:
        if isinstance(group, Mapping):
            replace_or_append_icon_override_group(groups, group)
    if groups or replace_icon_overrides:
        output["IconOverrideGroups"] = groups
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
    common_asset_roots: Sequence[str] = (),
    common_extract_root: Path | None = None,
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
    camera_auto_frame: bool,
    camera_auto_frame_padding: int,
    camera_auto_frame_max_attempts: int,
) -> dict:
    base_model = model_json.get("Model") if isinstance(model_json.get("Model"), str) else None
    base_texture = model_json.get("Texture") if isinstance(model_json.get("Texture"), str) else None
    source_asset_cache: Dict[str | None, str | None] = {}

    def source_asset_file(asset_path: str | None) -> str | None:
        if asset_path in source_asset_cache:
            return source_asset_cache[asset_path]
        resolved = resolve_common_asset_file_from_roots(
            asset_root,
            common_asset_roots,
            asset_path,
            common_extract_root,
        )
        source_asset_cache[asset_path] = resolved
        return resolved

    jobs: List[dict] = []
    jobs_by_output_path: set[str] = set()
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
            output_key = output_icon_file or icon_path
            if output_key in jobs_by_output_path:
                continue
            jobs_by_output_path.add(output_key)
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
                        "modelFile": source_asset_file(visual.model if visual else None),
                        "textureFile": source_asset_file(visual.texture if visual else None),
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
                    "baseModelFile": source_asset_file(base_model),
                    "baseTextureFile": source_asset_file(base_texture),
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
        "sourceCommonRoots": list(common_asset_roots),
        "sourceAssetExtractRoot": str(common_extract_root.resolve()) if common_extract_root else None,
        "modelSource": model_source,
        "defaults": {
            "iconSize": icon_size,
            "camera": {
                "scale": camera_scale,
                "rotation": list(camera_rotation),
                "translation": list(camera_translation),
                "autoFrame": camera_auto_frame,
                "autoFramePadding": camera_auto_frame_padding,
                "autoFrameMaxAttempts": camera_auto_frame_max_attempts,
            },
        },
        "model": {
            "baseModel": base_model,
            "baseTexture": base_texture,
            "baseModelFile": source_asset_file(base_model),
            "baseTextureFile": source_asset_file(base_texture),
            "randomAttachmentSets": {
                set_name: {
                    option_name: {
                        "model": option.model,
                        "texture": option.texture,
                        "weight": option.weight,
                        "modelFile": source_asset_file(option.model),
                        "textureFile": source_asset_file(option.texture),
                    }
                    for option_name, option in options.items()
                }
                for set_name, options in option_visuals.items()
            },
        },
        "jobCount": len(jobs),
        "jobs": jobs,
    }


def combine_renderer_payloads(
    *,
    asset_root: Path,
    renderer_name: str,
    icon_size: int,
    camera_scale: float,
    camera_rotation: Tuple[float, float, float],
    camera_translation: Tuple[float, float],
    camera_auto_frame: bool,
    camera_auto_frame_padding: int,
    camera_auto_frame_max_attempts: int,
    payloads: Sequence[Mapping[str, object]],
) -> dict:
    jobs: List[dict] = []
    model_sources: List[str] = []
    source_common_roots: List[str] = []
    source_asset_extract_roots: List[str] = []
    models: List[object] = []
    for payload in payloads:
        model_source = payload.get("modelSource")
        if isinstance(model_source, str):
            model_sources.append(model_source)
        payload_common_roots = payload.get("sourceCommonRoots")
        if isinstance(payload_common_roots, list):
            source_common_roots.extend(
                root for root in payload_common_roots if isinstance(root, str)
            )
        source_asset_extract_root = payload.get("sourceAssetExtractRoot")
        if isinstance(source_asset_extract_root, str):
            source_asset_extract_roots.append(source_asset_extract_root)
        model = payload.get("model")
        if model is not None:
            models.append(model)
        payload_jobs = payload.get("jobs")
        if isinstance(payload_jobs, list):
            jobs.extend(job for job in payload_jobs if isinstance(job, dict))

    first_model_source = model_sources[0] if model_sources else None
    return {
        "schema": "tamework.spawner-icon-render-jobs.v1",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "renderer": renderer_name,
        "assetRoot": str(asset_root),
        "modelSource": first_model_source,
        "modelSources": model_sources,
        "sourceCommonRoots": source_common_roots,
        "sourceAssetExtractRoots": source_asset_extract_roots,
        "defaults": {
            "iconSize": icon_size,
            "camera": {
                "scale": camera_scale,
                "rotation": list(camera_rotation),
                "translation": list(camera_translation),
                "autoFrame": camera_auto_frame,
                "autoFramePadding": camera_auto_frame_padding,
                "autoFrameMaxAttempts": camera_auto_frame_max_attempts,
            },
        },
        "model": models[0] if len(models) == 1 else None,
        "models": models,
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
        "--batch-manifest",
        help=(
            "Optional batch manifest JSON with shared sources/defaults and entry list. "
            "When set, --model and --icon-template are read from the manifest."
        ),
    )
    parser.add_argument(
        "--model",
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
        "--replace-icon-overrides",
        action="store_true",
        help=(
            "Replace generated icon override sections instead of preserving "
            "IconOverridesByRole roles or IconOverrideGroups from the existing config."
        ),
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
        "--icon-override-mode",
        default="byRole",
        choices=["byRole", "group"],
        help="Override output mode: byRole writes IconOverridesByRole; group writes IconOverrideGroups.",
    )
    parser.add_argument(
        "--icon-role",
        help=(
            "Canonical role placeholder value for shared group icon paths. "
            "Defaults to the first generated role."
        ),
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
    parser.add_argument(
        "--camera-auto-frame",
        action="store_true",
        help="Enable screenshot alpha based auto-framing in renderer jobs.",
    )
    parser.add_argument(
        "--camera-auto-frame-padding",
        type=int,
        default=4,
        help="Minimum transparent pixel padding for auto-framed icons (default: 4).",
    )
    parser.add_argument(
        "--camera-auto-frame-max-attempts",
        type=int,
        default=6,
        help="Maximum screenshot/zoom attempts for auto-framed icons (default: 6).",
    )
    return parser.parse_args()


def run_single_model(args: argparse.Namespace, asset_root: Path) -> int:
    if not args.model:
        raise ConfigError("--model is required unless --batch-manifest is provided.")
    if not args.icon_template:
        raise ConfigError("--icon-template is required unless --batch-manifest is provided.")

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
    icon_override_mode = normalize_icon_override_mode(args.icon_override_mode, "Single model")
    icon_role = args.icon_role.strip() if isinstance(args.icon_role, str) and args.icon_role.strip() else roles[0]

    role_overrides, combo_manifest, skipped_empty = build_combo_records(
        set_defs=set_defs,
        roles=roles,
        icon_template=args.icon_template,
        empty_value_token=args.empty_value_token,
        max_combos=args.max_combos,
        icon_role_for_paths=icon_role if icon_override_mode == "group" else None,
    )
    icon_override_groups = []
    if icon_override_mode == "group":
        icon_override_groups.append(build_icon_override_group(roles, combo_manifest))
        role_overrides = {}

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
        "iconOverrideMode": icon_override_mode,
        "iconRole": icon_role if icon_override_mode == "group" else None,
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
            "Note: Some combos had no attachments. Group mode writes the first one as "
            "IconOverrideGroups[].IconDefault; byRole mode still needs a separate default."
        )

    if spawner_json is not None:
        updated_spawner = apply_overrides_to_spawner(
            spawner_json=spawner_json,
            role_overrides=role_overrides,
            icon_override_groups=icon_override_groups,
            icon_default=args.icon_default,
            replace_icon_overrides=args.replace_icon_overrides,
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
        camera_auto_frame=args.camera_auto_frame,
        camera_auto_frame_padding=max(0, args.camera_auto_frame_padding),
        camera_auto_frame_max_attempts=max(1, args.camera_auto_frame_max_attempts),
    )
    write_json(renderer_jobs_path, renderer_payload)
    output_lines.append(f"Renderer jobs written: {renderer_jobs_path}")

    print("\n".join(output_lines))
    return 0


def resolve_batch_sources(
    batch_manifest: Mapping[str, object],
    manifest_dir: Path,
) -> Dict[str, BatchSource]:
    raw_sources = read_required_mapping(batch_manifest, "sources", "Batch manifest")
    sources: Dict[str, BatchSource] = {}
    for source_id, raw_source in raw_sources.items():
        if not isinstance(source_id, str) or not source_id.strip():
            raise ConfigError("Batch manifest sources must use non-empty string IDs.")
        if isinstance(raw_source, str):
            models_root = raw_source
            raw_common_roots: List[str] = []
        elif isinstance(raw_source, dict):
            models_root = get_string(raw_source, "modelsRoot", f"Source '{source_id}'")
            raw_common_root = raw_source.get("commonRoot")
            raw_common_roots = parse_string_list_field(
                raw_source.get("commonRoots"),
                f"Source '{source_id}' commonRoots",
            )
            if raw_common_root is not None and (
                not isinstance(raw_common_root, str) or not raw_common_root.strip()
            ):
                raise ConfigError(f"Source '{source_id}' field 'commonRoot' must be a non-empty string.")
            if raw_common_root is not None:
                raw_common_roots.insert(0, raw_common_root)
        else:
            raise ConfigError(f"Source '{source_id}' must be a string or object.")
        resolved_models_root = resolve_manifest_root_path(manifest_dir, models_root)
        if raw_common_roots:
            resolved_common_roots = tuple(
                resolve_manifest_root_path(manifest_dir, common_root)
                for common_root in raw_common_roots
            )
        else:
            inferred_common_root = infer_common_root_from_models_root(resolved_models_root)
            resolved_common_roots = (
                (inferred_common_root,) if inferred_common_root is not None else tuple()
            )
        sources[source_id] = BatchSource(
            models_root=resolved_models_root,
            common_roots=resolved_common_roots,
        )
    if not sources:
        raise ConfigError("Batch manifest must define at least one source.")
    return sources


def read_batch_entries(batch_manifest: Mapping[str, object]) -> List[Mapping[str, object]]:
    raw_entries = batch_manifest.get("entries")
    if not isinstance(raw_entries, list) or not raw_entries:
        raise ConfigError("Batch manifest must define a non-empty entries array.")
    entries: List[Mapping[str, object]] = []
    for index, raw_entry in enumerate(raw_entries, start=1):
        if not isinstance(raw_entry, dict):
            raise ConfigError(f"Batch manifest entry #{index} must be an object.")
        entries.append(raw_entry)
    return entries


def batch_value(
    entry: Mapping[str, object],
    defaults: Mapping[str, object],
    key: str,
    fallback: object = None,
) -> object:
    if key in entry:
        return entry[key]
    if key in defaults:
        return defaults[key]
    return fallback


def batch_string(
    entry: Mapping[str, object],
    defaults: Mapping[str, object],
    key: str,
    context: str,
    fallback: str | None = None,
) -> str:
    value = batch_value(entry, defaults, key, fallback)
    if value is None:
        raise ConfigError(f"{context} must define string '{key}' or defaults.{key}.")
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{context} field '{key}' must be a non-empty string.")
    return value


def normalize_icon_override_mode(value: object, context: str) -> str:
    if value is None:
        return "byRole"
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{context} iconOverrideMode must be 'byRole' or 'group'.")
    normalized = value.strip().lower().replace("-", "").replace("_", "")
    if normalized in {"byrole", "role", "roles"}:
        return "byRole"
    if normalized in {"group", "shared", "sharedgroup"}:
        return "group"
    raise ConfigError(f"{context} iconOverrideMode must be 'byRole' or 'group'.")


def run_batch_manifest(args: argparse.Namespace, asset_root: Path) -> int:
    manifest_path = Path(args.batch_manifest).resolve()
    raw_manifest = load_json(manifest_path)
    if not isinstance(raw_manifest, dict):
        raise ConfigError(f"Batch manifest JSON must be an object: {manifest_path}")

    manifest_dir = manifest_path.parent
    defaults = raw_manifest.get("defaults")
    if defaults is None:
        defaults = {}
    if not isinstance(defaults, dict):
        raise ConfigError("Batch manifest defaults must be an object when provided.")

    sources = resolve_batch_sources(raw_manifest, manifest_dir)
    entries = read_batch_entries(raw_manifest)
    renderer_jobs_path = (
        Path(args.renderer_jobs_out).resolve()
        if args.renderer_jobs_out
        else (Path.cwd() / ".tmp" / "spawner_icon_render_jobs_batch.json").resolve()
    )

    renderer_name_raw = defaults.get("rendererName", args.renderer_name)
    if not isinstance(renderer_name_raw, str) or not renderer_name_raw.strip():
        raise ConfigError("defaults.rendererName must be a non-empty string.")
    renderer_name = renderer_name_raw

    icon_size = int(defaults.get("iconSize", args.icon_size))
    camera_scale = float(defaults.get("cameraScale", args.camera_scale))
    camera_rotation = parse_float_field(
        defaults.get("cameraRotation", args.camera_rotation), 3, "defaults.cameraRotation"
    )
    camera_translation = parse_float_field(
        defaults.get("cameraTranslation", args.camera_translation),
        2,
        "defaults.cameraTranslation",
    )
    camera_auto_frame = parse_bool_field(
        defaults.get("cameraAutoFrame", args.camera_auto_frame),
        "defaults.cameraAutoFrame",
    )
    camera_auto_frame_padding = max(
        0,
        int(defaults.get("cameraAutoFramePadding", args.camera_auto_frame_padding)),
    )
    camera_auto_frame_max_attempts = max(
        1,
        int(defaults.get("cameraAutoFrameMaxAttempts", args.camera_auto_frame_max_attempts)),
    )

    spawner_json = None
    spawner_path: Path | None = None
    if args.spawner_config:
        spawner_path = resolve_asset_path(asset_root, args.spawner_config).resolve()
        loaded_spawner = load_json(spawner_path)
        if not isinstance(loaded_spawner, dict):
            raise ConfigError(f"Spawner config JSON must be an object: {spawner_path}")
        spawner_json = loaded_spawner

    aggregate_role_overrides: Dict[str, List[dict]] = {}
    aggregate_icon_override_groups: List[dict] = []
    aggregate_combo_manifest: List[dict] = []
    renderer_payloads: List[Mapping[str, object]] = []
    report_entries: List[dict] = []
    total_skipped_empty: List[str] = []

    for index, entry in enumerate(entries, start=1):
        entry_id = get_string(entry, "id", f"Batch entry #{index}", default=f"entry_{index}")
        source_id = get_string(entry, "source", f"Batch entry '{entry_id}'")
        if source_id not in sources:
            raise ConfigError(
                f"Batch entry '{entry_id}' references unknown source '{source_id}'. "
                f"Available: {', '.join(sources.keys())}"
            )
        source = sources[source_id]
        model_path = get_string(entry, "model", f"Batch entry '{entry_id}'")
        model_source_input = join_manifest_model_path(source.models_root, model_path)
        model_json, model_source, model_stem = load_json_from_source(asset_root, model_source_input)
        if not isinstance(model_json, dict):
            raise ConfigError(f"Model JSON must be an object: {model_source}")

        roles = parse_string_list_field(entry.get("roles"), f"Batch entry '{entry_id}' roles")
        if not roles:
            roles = discover_roles([], spawner_json)

        include_empty_sets = parse_string_list_field(
            batch_value(entry, defaults, "includeEmptySets", []),
            f"Batch entry '{entry_id}' includeEmptySets",
        )
        keep_sets = parse_string_list_field(
            entry.get("keepAttachmentSets"),
            f"Batch entry '{entry_id}' keepAttachmentSets",
        )
        exclude_options = parse_string_list_mapping(
            entry.get("excludeAttachmentOptions"),
            f"Batch entry '{entry_id}' excludeAttachmentOptions",
        )
        icon_template = batch_string(entry, defaults, "iconTemplate", f"Batch entry '{entry_id}'")
        icon_override_mode = normalize_icon_override_mode(
            batch_value(entry, defaults, "iconOverrideMode", "byRole"),
            f"Batch entry '{entry_id}'",
        )
        icon_role_raw = batch_value(entry, defaults, "iconRole", None)
        if icon_role_raw is not None and (not isinstance(icon_role_raw, str) or not icon_role_raw.strip()):
            raise ConfigError(f"Batch entry '{entry_id}' field 'iconRole' must be a non-empty string.")
        icon_role = icon_role_raw.strip() if isinstance(icon_role_raw, str) else roles[0]
        empty_value_token = batch_string(
            entry,
            defaults,
            "emptyValueToken",
            f"Batch entry '{entry_id}'",
            fallback=args.empty_value_token,
        )
        max_combos = int(batch_value(entry, defaults, "maxCombos", args.max_combos))

        all_set_defs = extract_set_definitions(model_json, include_empty_sets)
        set_defs = filter_set_definitions(all_set_defs, keep_sets, f"Batch entry '{entry_id}'")
        set_defs = exclude_set_options(set_defs, exclude_options, f"Batch entry '{entry_id}'")
        generated_set_names = {set_def.name for set_def in set_defs}
        unused_empty_sets = sorted(set(include_empty_sets).difference(generated_set_names))
        if unused_empty_sets:
            raise ConfigError(
                f"Batch entry '{entry_id}' includeEmptySets selected set(s) not present "
                "in keepAttachmentSets: " + ", ".join(unused_empty_sets)
            )

        option_visuals = extract_option_visuals(model_json)
        role_overrides, combo_manifest, skipped_empty = build_combo_records(
            set_defs=set_defs,
            roles=roles,
            icon_template=icon_template,
            empty_value_token=empty_value_token,
            max_combos=max_combos,
            icon_role_for_paths=icon_role if icon_override_mode == "group" else None,
        )

        icon_override_group = None
        if icon_override_mode == "group":
            icon_override_group = build_icon_override_group(roles, combo_manifest)
            if icon_override_group["Overrides"] or icon_override_group.get("IconDefault"):
                append_or_merge_icon_override_group(aggregate_icon_override_groups, icon_override_group)
            role_overrides = {}
        else:
            for role, overrides in role_overrides.items():
                aggregate_role_overrides.setdefault(role, []).extend(overrides)

        entry_combo_manifest = []
        for combo in combo_manifest:
            combo_with_entry = dict(combo)
            combo_with_entry["entryId"] = entry_id
            combo_with_entry["source"] = source_id
            combo_with_entry["modelPath"] = model_source
            entry_combo_manifest.append(combo_with_entry)
        aggregate_combo_manifest.extend(entry_combo_manifest)
        total_skipped_empty.extend(f"{entry_id}:{combo}" for combo in skipped_empty)

        entry_camera_scale = float(batch_value(entry, defaults, "cameraScale", camera_scale))
        entry_camera_rotation = parse_float_field(
            batch_value(entry, defaults, "cameraRotation", list(camera_rotation)),
            3,
            f"Batch entry '{entry_id}' cameraRotation",
        )
        entry_camera_translation = parse_float_field(
            batch_value(entry, defaults, "cameraTranslation", list(camera_translation)),
            2,
            f"Batch entry '{entry_id}' cameraTranslation",
        )
        entry_camera_auto_frame = parse_bool_field(
            batch_value(entry, defaults, "cameraAutoFrame", camera_auto_frame),
            f"Batch entry '{entry_id}' cameraAutoFrame",
        )
        entry_camera_auto_frame_padding = max(
            0,
            int(batch_value(entry, defaults, "cameraAutoFramePadding", camera_auto_frame_padding)),
        )
        entry_camera_auto_frame_max_attempts = max(
            1,
            int(
                batch_value(
                    entry,
                    defaults,
                    "cameraAutoFrameMaxAttempts",
                    camera_auto_frame_max_attempts,
                )
            ),
        )
        entry_icon_size = int(batch_value(entry, defaults, "iconSize", icon_size))
        renderer_payload = build_renderer_jobs(
            asset_root=asset_root,
            common_asset_roots=source.common_roots,
            common_extract_root=renderer_jobs_path.parent / "spawner_icon_source_assets" / source_id,
            model_json=model_json,
            model_source=model_source,
            roles=roles,
            combo_manifest=combo_manifest,
            option_visuals=option_visuals,
            renderer_name=renderer_name,
            icon_size=entry_icon_size,
            camera_scale=entry_camera_scale,
            camera_rotation=entry_camera_rotation,
            camera_translation=entry_camera_translation,
            camera_auto_frame=entry_camera_auto_frame,
            camera_auto_frame_padding=entry_camera_auto_frame_padding,
            camera_auto_frame_max_attempts=entry_camera_auto_frame_max_attempts,
        )
        for job in renderer_payload.get("jobs", []):
            if isinstance(job, dict):
                job["entryId"] = entry_id
                job["source"] = source_id
                job["modelSource"] = model_source
                if entry_icon_size != icon_size:
                    job["iconSize"] = entry_icon_size
                if (
                    entry_camera_scale != camera_scale
                    or entry_camera_rotation != camera_rotation
                    or entry_camera_auto_frame != camera_auto_frame
                    or entry_camera_auto_frame_padding != camera_auto_frame_padding
                    or entry_camera_auto_frame_max_attempts != camera_auto_frame_max_attempts
                ):
                    job["camera"] = {
                        "scale": entry_camera_scale,
                        "rotation": list(entry_camera_rotation),
                        "autoFrame": entry_camera_auto_frame,
                        "autoFramePadding": entry_camera_auto_frame_padding,
                        "autoFrameMaxAttempts": entry_camera_auto_frame_max_attempts,
                    }
                if entry_camera_translation != camera_translation:
                    job["translation"] = list(entry_camera_translation)
        renderer_payloads.append(renderer_payload)

        report_entries.append(
            {
                "id": entry_id,
                "source": source_id,
                "modelPath": model_source,
                "roles": roles,
                "iconOverrideMode": icon_override_mode,
                "iconRole": icon_role if icon_override_mode == "group" else None,
                "keptAttachmentSets": [set_def.name for set_def in set_defs],
                "excludedAttachmentOptions": {
                    set_name: list(options) for set_name, options in exclude_options.items()
                },
                "comboCount": len(combo_manifest),
                "overridesGeneratedByRole": {
                    role: len(overrides) for role, overrides in role_overrides.items()
                },
                "iconOverrideGroupCount": 1 if icon_override_group is not None and icon_override_group["Overrides"] else 0,
                "overridesGeneratedByGroup": (
                    len(icon_override_group["Overrides"]) if icon_override_group is not None else 0
                ),
                "skippedEmptyAttachmentCombos": skipped_empty,
                "modelStem": model_stem,
            }
        )

    manifest_out_path = (
        Path(args.manifest_out).resolve()
        if args.manifest_out
        else (Path.cwd() / ".tmp" / "spawner_icon_manifest_batch.json").resolve()
    )
    manifest_payload = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "assetRoot": str(asset_root),
        "batchManifest": str(manifest_path),
        "sources": {source_id: source.as_report() for source_id, source in sources.items()},
        "entryCount": len(entries),
        "comboCount": len(aggregate_combo_manifest),
        "overridesGeneratedByRole": {
            role: len(overrides) for role, overrides in aggregate_role_overrides.items()
        },
        "iconOverrideGroupCount": len(aggregate_icon_override_groups),
        "overridesGeneratedByGroup": sum(
            len(group.get("Overrides", [])) for group in aggregate_icon_override_groups
        ),
        "skippedEmptyAttachmentCombos": total_skipped_empty,
        "entries": report_entries,
        "combos": aggregate_combo_manifest,
    }
    write_json(manifest_out_path, manifest_payload)

    output_lines = [
        f"Batch manifest report written: {manifest_out_path}",
        f"Entries: {len(entries)}",
        f"Attachment combos: {len(aggregate_combo_manifest)}",
    ]

    if spawner_json is not None:
        updated_spawner = apply_overrides_to_spawner(
            spawner_json=spawner_json,
            role_overrides=aggregate_role_overrides,
            icon_override_groups=aggregate_icon_override_groups,
            icon_default=args.icon_default,
            replace_icon_overrides=args.replace_icon_overrides,
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

    renderer_payload = combine_renderer_payloads(
        asset_root=asset_root,
        renderer_name=renderer_name,
        icon_size=icon_size,
        camera_scale=camera_scale,
        camera_rotation=camera_rotation,
        camera_translation=camera_translation,
        camera_auto_frame=camera_auto_frame,
        camera_auto_frame_padding=camera_auto_frame_padding,
        camera_auto_frame_max_attempts=camera_auto_frame_max_attempts,
        payloads=renderer_payloads,
    )
    write_json(renderer_jobs_path, renderer_payload)
    output_lines.append(f"Renderer jobs written: {renderer_jobs_path}")

    if total_skipped_empty:
        output_lines.append(
            "Note: Some combos had no attachments. Group mode writes the first one as "
            "IconOverrideGroups[].IconDefault; byRole mode still needs a separate default."
        )

    print("\n".join(output_lines))
    return 0


def main() -> int:
    args = parse_args()
    asset_root = Path(args.asset_root).resolve()
    if args.batch_manifest:
        return run_batch_manifest(args, asset_root)
    return run_single_model(args, asset_root)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ConfigError as exc:
        print(f"ERROR: {exc}")
        raise SystemExit(2)
