#!/usr/bin/env python3
"""Generate AvatarFlight-safe model and animation variants with namespaced rig nodes."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_PREFIX = "AF_"
DEFAULT_PRESERVED_NODES = ("Origin", "MountAnchor")
DEFAULT_COLLISION_MODEL = (
    Path(__file__).resolve().parents[2]
    / "src"
    / "main"
    / "resources"
    / "Common"
    / "Tamework"
    / "AvatarFlight"
    / "Rider"
    / "Player_MountAnchor.blockymodel"
)


@dataclass(frozen=True)
class GeneratedPath:
    source: Path
    target: Path
    asset_path: str


@dataclass
class GenerationResult:
    model_id: str
    output_model_id: str
    node_mapping: dict[str, str]
    server_model: GeneratedPath
    common_model: GeneratedPath
    animations: list[GeneratedPath]
    warnings: list[str]


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = generate(args)
    except UserError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    print_summary(result)
    return 0


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Create an AvatarFlight model variant by prefixing model node names and "
            "rewriting matching .blockyanim nodeAnimations keys. Origin is preserved by default."
        )
    )
    parser.add_argument(
        "--mod-root",
        required=True,
        type=Path,
        help="Source mod root containing Server/ and Common/.",
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument(
        "--model-id",
        help="Server/Models asset id to read, usually the JSON filename without .json.",
    )
    source.add_argument(
        "--server-model",
        type=Path,
        help="Path to a Server/Models JSON file.",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        help="Root where generated files are written. Defaults to --mod-root.",
    )
    parser.add_argument(
        "--out-model-id",
        help="Generated Server/Models id. Defaults to <source>_AvatarFlight.",
    )
    parser.add_argument(
        "--out-common-model",
        help=(
            "Generated Common model asset path without Common/ prefix. Defaults to the "
            "source model filename with _AvatarFlight before the extension."
        ),
    )
    parser.add_argument(
        "--animation-dir-name",
        default="AvatarFlight",
        help="Directory inserted under each source animation directory. Default: AvatarFlight.",
    )
    parser.add_argument(
        "--prefix",
        default=DEFAULT_PREFIX,
        help=f"Prefix applied to namespaced model nodes. Default: {DEFAULT_PREFIX}.",
    )
    parser.add_argument(
        "--rename-mode",
        choices=("collisions", "all"),
        default="collisions",
        help=(
            "Which nodes to namespace. 'collisions' only renames nodes that also exist in the "
            "rider/player rig; 'all' renames every non-preserved node. Default: collisions."
        ),
    )
    parser.add_argument(
        "--collision-model",
        type=Path,
        default=DEFAULT_COLLISION_MODEL,
        help=(
            "Blockymodel whose node names define collision-mode rename targets. Defaults to "
            "Tamework's fake-rider anchor model."
        ),
    )
    parser.add_argument(
        "--preserve-node",
        action="append",
        default=[],
        help="Node name to leave unchanged. May be repeated. Origin is always preserved unless --no-default-preserve is set.",
    )
    parser.add_argument(
        "--no-default-preserve",
        action="store_true",
        help="Do not automatically preserve Origin.",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace generated files if they already exist.",
    )
    return parser.parse_args(argv)


def generate(args: argparse.Namespace) -> GenerationResult:
    source_root = args.mod_root.resolve()
    output_root = (args.output_root or args.mod_root).resolve()
    if not source_root.exists():
        raise UserError(f"mod root does not exist: {source_root}")
    if not (source_root / "Server").exists() or not (source_root / "Common").exists():
        raise UserError(f"mod root must contain Server/ and Common/: {source_root}")

    server_model_path = resolve_server_model_path(source_root, args)
    source_model_id = server_model_path.stem
    output_model_id = args.out_model_id or f"{source_model_id}_AvatarFlight"
    if output_model_id == source_model_id:
        raise UserError("--out-model-id must differ from the source model id")

    server_model = read_json(server_model_path)
    source_common_model_asset = common_asset_path(require_string(server_model, "Model", server_model_path))
    source_common_model_path = source_root / "Common" / path_from_asset(source_common_model_asset)
    if not source_common_model_path.exists():
        raise UserError(f"referenced Common model file does not exist: {source_common_model_path}")

    output_common_model_asset = common_asset_path(
        args.out_common_model or default_output_common_model(source_common_model_asset)
    )
    output_common_model_path = output_root / "Common" / path_from_asset(output_common_model_asset)

    preserved_nodes = set(args.preserve_node)
    if not args.no_default_preserve:
        preserved_nodes.update(DEFAULT_PRESERVED_NODES)

    animation_paths = collect_animation_asset_paths(server_model)
    animation_outputs: dict[str, str] = {}
    generated_animations: list[GeneratedPath] = []
    warnings: list[str] = []
    animation_jsons: dict[str, Any] = {}
    for animation_asset in animation_paths:
        output_asset = default_output_animation(animation_asset, args.animation_dir_name)
        animation_outputs[animation_asset] = output_asset
        source_animation_path = source_root / "Common" / path_from_asset(animation_asset)
        if not source_animation_path.exists():
            warnings.append(f"missing animation file referenced by server model: {animation_asset}")
            continue
        animation_jsons[animation_asset] = read_json(source_animation_path)

    model_json = read_json(source_common_model_path)
    rename_candidates = resolve_rename_candidates(args, model_json, animation_jsons.values())
    node_mapping = build_node_mapping(model_json, args.prefix, preserved_nodes, rename_candidates)
    add_animation_only_mappings(node_mapping, animation_jsons.values(), args.prefix, preserved_nodes, rename_candidates)
    renamed_model = copy.deepcopy(model_json)
    rename_model_nodes(renamed_model, node_mapping)

    for animation_asset in animation_paths:
        output_asset = animation_outputs[animation_asset]
        source_animation_path = source_root / "Common" / path_from_asset(animation_asset)
        output_animation_path = output_root / "Common" / path_from_asset(output_asset)
        animation_json = animation_jsons.get(animation_asset)
        if animation_json is None:
            continue
        renamed_animation = rename_animation_nodes(animation_json, node_mapping, warnings, animation_asset)
        write_json(output_animation_path, renamed_animation, args.overwrite)
        generated_animations.append(GeneratedPath(source_animation_path, output_animation_path, output_asset))

    generated_server_model = rewrite_server_model(
        server_model,
        output_common_model_asset,
        animation_outputs
    )
    output_server_model_path = (
        output_root
        / server_model_path.relative_to(source_root).parent
        / f"{output_model_id}.json"
    )

    write_json(output_common_model_path, renamed_model, args.overwrite)
    write_json(output_server_model_path, generated_server_model, args.overwrite)

    return GenerationResult(
        model_id=source_model_id,
        output_model_id=output_model_id,
        node_mapping=node_mapping,
        server_model=GeneratedPath(server_model_path, output_server_model_path, output_model_id),
        common_model=GeneratedPath(source_common_model_path, output_common_model_path, output_common_model_asset),
        animations=generated_animations,
        warnings=warnings,
    )


def resolve_server_model_path(source_root: Path, args: argparse.Namespace) -> Path:
    if args.server_model is not None:
        path = args.server_model
        if not path.is_absolute():
            path = source_root / path
        path = path.resolve()
        if not path.exists():
            raise UserError(f"server model file does not exist: {path}")
        return path

    matches = sorted((source_root / "Server" / "Models").rglob(f"{args.model_id}.json"))
    if not matches:
        raise UserError(f"could not find Server/Models/**/{args.model_id}.json")
    if len(matches) > 1:
        rendered = "\n  ".join(str(path) for path in matches)
        raise UserError(f"model id is ambiguous; use --server-model:\n  {rendered}")
    return matches[0].resolve()


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise UserError(f"invalid JSON in {path}: {exc}") from exc


def write_json(path: Path, payload: Any, overwrite: bool) -> None:
    if path.exists() and not overwrite:
        raise UserError(f"target exists; pass --overwrite to replace it: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def require_string(payload: dict[str, Any], key: str, path: Path) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise UserError(f"{path} must contain a non-empty string field: {key}")
    return value


def common_asset_path(value: str) -> str:
    normalized = value.replace("\\", "/").strip()
    while normalized.startswith("/"):
        normalized = normalized[1:]
    if normalized.startswith("Common/"):
        normalized = normalized[len("Common/") :]
    return normalized


def path_from_asset(asset_path: str) -> Path:
    return Path(*asset_path.split("/"))


def default_output_common_model(source_asset: str) -> str:
    path = Path(source_asset.replace("\\", "/"))
    suffix = "".join(path.suffixes) or ".blockymodel"
    stem = path.name[: -len(suffix)] if path.name.endswith(suffix) else path.stem
    return normalize_asset_path(path.with_name(f"{stem}_AvatarFlight{suffix}"))


def default_output_animation(source_asset: str, directory_name: str) -> str:
    path = Path(source_asset.replace("\\", "/"))
    return normalize_asset_path(path.parent / directory_name / path.name)


def normalize_asset_path(path: Path) -> str:
    return path.as_posix().lstrip("/")


def resolve_rename_candidates(
    args: argparse.Namespace,
    model_json: Any,
    animation_jsons: Any,
) -> set[str] | None:
    if args.rename_mode == "all":
        return None

    collision_model_path = args.collision_model
    if not collision_model_path.is_absolute():
        collision_model_path = Path.cwd() / collision_model_path
    collision_model_path = collision_model_path.resolve()
    if not collision_model_path.exists():
        raise UserError(
            "collision-mode rename requires a readable --collision-model; "
            f"not found: {collision_model_path}"
        )
    collision_model = read_json(collision_model_path)
    collision_nodes = collect_model_node_names(collision_model)
    source_nodes = collect_model_node_names(model_json)
    animation_nodes: set[str] = set()
    for animation_json in animation_jsons:
        animation_nodes.update(collect_animation_node_names(animation_json))
    return collision_nodes.intersection(source_nodes.union(animation_nodes))


def collect_model_node_names(model_json: Any) -> set[str]:
    if not isinstance(model_json, dict) or not isinstance(model_json.get("nodes"), list):
        raise UserError("blockymodel JSON must contain a nodes array")
    names: set[str] = set()

    def visit(nodes: list[Any]) -> None:
        for node in nodes:
            if not isinstance(node, dict):
                continue
            name = node.get("name")
            if isinstance(name, str) and name:
                names.add(name)
            children = node.get("children")
            if isinstance(children, list):
                visit(children)

    visit(model_json["nodes"])
    return names


def collect_animation_node_names(animation_json: Any) -> set[str]:
    node_animations = animation_json.get("nodeAnimations") if isinstance(animation_json, dict) else None
    if not isinstance(node_animations, dict):
        return set()
    return {name for name in node_animations.keys() if isinstance(name, str) and name}


def build_node_mapping(
    model_json: Any,
    prefix: str,
    preserved_nodes: set[str],
    rename_candidates: set[str] | None,
) -> dict[str, str]:
    if not isinstance(model_json, dict) or not isinstance(model_json.get("nodes"), list):
        raise UserError("blockymodel JSON must contain a nodes array")
    mapping: dict[str, str] = {}
    reverse: dict[str, str] = {}

    def visit(nodes: list[Any]) -> None:
        for node in nodes:
            if not isinstance(node, dict):
                continue
            name = node.get("name")
            if isinstance(name, str) and name:
                mapped = namespaced_node_name(name, prefix, preserved_nodes, rename_candidates)
                existing = reverse.get(mapped)
                if existing is not None and existing != name:
                    raise UserError(
                        f"node namespace collision: both {existing!r} and {name!r} map to {mapped!r}"
                    )
                mapping[name] = mapped
                reverse[mapped] = name
            children = node.get("children")
            if isinstance(children, list):
                visit(children)

    visit(model_json["nodes"])
    return mapping


def add_animation_only_mappings(
    node_mapping: dict[str, str],
    animation_jsons: Any,
    prefix: str,
    preserved_nodes: set[str],
    rename_candidates: set[str] | None,
) -> None:
    for animation_json in animation_jsons:
        for name in collect_animation_node_names(animation_json):
            node_mapping.setdefault(
                name,
                namespaced_node_name(name, prefix, preserved_nodes, rename_candidates),
            )


def namespaced_node_name(name: str,
                         prefix: str,
                         preserved_nodes: set[str],
                         rename_candidates: set[str] | None) -> str:
    if name in preserved_nodes or name.startswith(prefix):
        return name
    if rename_candidates is not None and name not in rename_candidates:
        return name
    return f"{prefix}{name}"


def rename_model_nodes(model_json: Any, node_mapping: dict[str, str]) -> None:
    def visit(nodes: list[Any]) -> None:
        for node in nodes:
            if not isinstance(node, dict):
                continue
            name = node.get("name")
            if isinstance(name, str) and name in node_mapping:
                node["name"] = node_mapping[name]
            children = node.get("children")
            if isinstance(children, list):
                visit(children)

    visit(model_json.get("nodes", []))


def collect_animation_asset_paths(server_model: Any) -> list[str]:
    paths: list[str] = []
    seen: set[str] = set()
    animation_sets = server_model.get("AnimationSets")
    if not isinstance(animation_sets, dict):
        return paths
    for animation_set in animation_sets.values():
        if not isinstance(animation_set, dict):
            continue
        animations = animation_set.get("Animations")
        if not isinstance(animations, list):
            continue
        for animation in animations:
            if not isinstance(animation, dict):
                continue
            value = animation.get("Animation")
            if not isinstance(value, str) or not value.strip():
                continue
            asset_path = common_asset_path(value)
            if asset_path not in seen:
                seen.add(asset_path)
                paths.append(asset_path)
    return paths


def rename_animation_nodes(
    animation_json: Any,
    node_mapping: dict[str, str],
    warnings: list[str],
    asset_path: str,
) -> Any:
    renamed = copy.deepcopy(animation_json)
    node_animations = renamed.get("nodeAnimations") if isinstance(renamed, dict) else None
    if not isinstance(node_animations, dict):
        warnings.append(f"animation has no nodeAnimations object: {asset_path}")
        return renamed

    rewritten: dict[str, Any] = {}
    for node_name, animation_data in node_animations.items():
        mapped = node_mapping.get(node_name)
        if mapped is None:
            warnings.append(f"animation node {node_name!r} was not found in model and was left unchanged: {asset_path}")
            mapped = node_name
        if mapped in rewritten:
            raise UserError(f"animation node collision in {asset_path}: {node_name!r} maps to existing {mapped!r}")
        rewritten[mapped] = animation_data
    renamed["nodeAnimations"] = rewritten
    return renamed


def rewrite_server_model(
    server_model: Any,
    output_common_model_asset: str,
    animation_outputs: dict[str, str],
) -> Any:
    rewritten = copy.deepcopy(server_model)
    rewritten["Model"] = output_common_model_asset
    animation_sets = rewritten.get("AnimationSets")
    if not isinstance(animation_sets, dict):
        return rewritten
    for animation_set in animation_sets.values():
        if not isinstance(animation_set, dict):
            continue
        animations = animation_set.get("Animations")
        if not isinstance(animations, list):
            continue
        for animation in animations:
            if not isinstance(animation, dict):
                continue
            value = animation.get("Animation")
            if not isinstance(value, str):
                continue
            source_asset = common_asset_path(value)
            output_asset = animation_outputs.get(source_asset)
            if output_asset is not None:
                animation["Animation"] = output_asset
    normalize_avatar_locomotion_animation_sets(rewritten)
    return rewritten


def normalize_avatar_locomotion_animation_sets(server_model: Any) -> None:
    animation_sets = server_model.get("AnimationSets")
    if not isinstance(animation_sets, dict):
        return
    copy_animation_set(animation_sets, "Run", "Sprint")
    copy_animation_set(animation_sets, "JumpRun", "JumpSprint")
    copy_animation_set(animation_sets, "StepRun", "StepSprint")


def copy_animation_set(animation_sets: dict[str, Any], source_id: str, target_id: str) -> None:
    if target_id in animation_sets:
        return
    source = animation_sets.get(source_id)
    if source is None:
        return
    animation_sets[target_id] = copy.deepcopy(source)


def print_summary(result: GenerationResult) -> None:
    print(f"AvatarFlight namespace variant generated: {result.model_id} -> {result.output_model_id}")
    print(f"Nodes renamed: {sum(1 for old, new in result.node_mapping.items() if old != new)}")
    print(f"Server model: {result.server_model.target}")
    print(f"Common model: {result.common_model.target}")
    print(f"Animations: {len(result.animations)}")
    if result.warnings:
        print("Warnings:")
        for warning in result.warnings:
            print(f"- {warning}")


class UserError(Exception):
    pass


if __name__ == "__main__":
    raise SystemExit(main())
