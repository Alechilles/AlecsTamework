#!/usr/bin/env python3
"""Verify Tamework's public autonomous-aerial component contracts."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
COMPONENT_ROOT = ROOT / "src/main/resources/Server/NPC/Roles/_Core/Components"
EXPECTED = {
    "Component_Tamework_Instruction_Hold_Flying.json": "Tamework.Instruction.Hold",
    "Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying.json":
        "Tamework.Instruction.SeekFood.PlayerFollow.Flying",
    "Component_Tamework_Instruction_Airborne_Mode_Transition.json":
        "Tamework.Instruction.AirborneModeTransition",
}


def dictionaries(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from dictionaries(child)
    elif isinstance(value, list):
        for child in value:
            yield from dictionaries(child)


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def load_components(errors: list[str]) -> dict[str, dict[str, Any]]:
    components: dict[str, dict[str, Any]] = {}
    for filename, expected_interface in EXPECTED.items():
        path = COMPONENT_ROOT / filename
        if not path.is_file():
            errors.append(f"missing shared component: {filename}")
            continue
        value = json.loads(path.read_text(encoding="utf-8"))
        require(value.get("Type") == "Component", f"{filename}: Type must be Component", errors)
        require(value.get("Class") == "Instruction", f"{filename}: Class must be Instruction", errors)
        require(value.get("Interface") == expected_interface,
                f"{filename}: unexpected Interface", errors)
        serialized = json.dumps(value, sort_keys=True)
        for forbidden in ("HyDragon", "AnimalHusbandry", "AH_"):
            require(forbidden not in serialized,
                    f"{filename}: contains downstream token {forbidden}", errors)
        components[filename] = value
    return components


def validate_hold(component: dict[str, Any], errors: list[str]) -> None:
    required = {
        "HoldGroundAnimation", "HoldLandingSearchRange", "HoldLandingSearchAngle",
        "HoldLandingSlowDownDistance", "HoldLandingStopDistance", "HoldLandingGoalLenience",
    }
    require(required <= set(component.get("Parameters", {})),
            "flying Hold is missing compatibility parameters", errors)
    motions = [node for node in dictionaries(component) if node.get("Type") == "Land"]
    require(not motions, "flying Hold must not compete with Tamework's landing controller", errors)


def validate_favorite_item(component: dict[str, Any], errors: list[str]) -> None:
    required = {
        "_ImportStates", "AttractiveItemSet", "FollowTargetSlot", "LandingPositionSlot",
        "FlightSeekStopDistance", "GroundApproachDistanceRange", "ReturnParentState",
    }
    require(required <= set(component.get("Parameters", {})),
            "flying favorite-item follow is missing public parameters", errors)
    exits = [node for node in dictionaries(component) if node.get("Type") == "ParentState"]
    require(bool(exits), "flying favorite-item follow must return to its parent state", errors)
    require(all(node.get("State") == {"Compute": "ReturnParentState"} for node in exits),
            "favorite-item parent exits must compute ReturnParentState", errors)


def validate_transition(component: dict[str, Any], errors: list[str]) -> None:
    required = {
        "ToggleAirborneModeHookId", "AirborneModeFlagName", "GroundedActivityFlagName",
        "LandingRayName", "LandingBlocks", "TakeOffJumpSpeed", "LandingSearchRange",
        "LandingSearchAngle", "LandingSlowDownDistance", "LandingStopDistance",
        "LandingHeightDifference", "LandingGoalLenience", "LandingDesiredAltitudeWeight",
    }
    require(required <= set(component.get("Parameters", {})),
            "airborne transition is missing public parameters", errors)
    nodes = list(dictionaries(component))
    hooks = [node for node in nodes if node.get("Type") == "TameworkHook"]
    require(len(hooks) == 1 and hooks[0].get("HookId") == {"Compute": "ToggleAirborneModeHookId"},
            "airborne transition hook must compute ToggleAirborneModeHookId", errors)
    require(any(node.get("Type") == "SearchRay"
                and node.get("Name") == {"Compute": "LandingRayName"}
                and node.get("Blocks") == {"Compute": "LandingBlocks"} for node in nodes),
            "airborne transition landing ray must use computed name and block set", errors)
    require(any(node.get("Type") == "TakeOff"
                and node.get("JumpSpeed") == {"Compute": "TakeOffJumpSpeed"} for node in nodes),
            "airborne transition takeoff speed must be configurable", errors)
    require(any(node.get("Type") == "Flag"
                and node.get("Name") == {"Compute": "GroundedActivityFlagName"}
                and node.get("Set") is False for node in nodes),
            "airborne transition must honor the grounded-activity gate", errors)


def main() -> int:
    errors: list[str] = []
    manifest = json.loads((ROOT / "src/main/resources/manifest.json").read_text(encoding="utf-8"))
    require(manifest.get("Version") == "3.0.0", "Tamework must remain version 3.0.0", errors)
    components = load_components(errors)
    hold = components.get("Component_Tamework_Instruction_Hold_Flying.json")
    favorite = components.get("Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying.json")
    transition = components.get("Component_Tamework_Instruction_Airborne_Mode_Transition.json")
    if hold is not None:
        validate_hold(hold, errors)
    if favorite is not None:
        validate_favorite_item(favorite, errors)
    if transition is not None:
        validate_transition(transition, errors)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Shared aerial component contracts verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
