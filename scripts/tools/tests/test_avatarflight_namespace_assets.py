import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.tools import avatarflight_namespace_assets as namespace_assets


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "scripts" / "tools" / "avatarflight_namespace_assets.py"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


class AvatarFlightNamespaceAssetsTest(unittest.TestCase):
    def test_keeps_the_standard_pose_root_stable_when_other_nodes_use_a_custom_prefix(self):
        collisions = {"Origin", "Pelvis"}

        self.assertEqual(
            "AF_Origin",
            namespace_assets.namespaced_node_name("Origin", "Dragon_", set(), collisions),
        )
        self.assertEqual(
            "AF_Origin",
            namespace_assets.namespaced_node_name("Origin", "Dragon_", {"Origin"}, set()),
        )
        self.assertEqual(
            "Dragon_Pelvis",
            namespace_assets.namespaced_node_name("Pelvis", "Dragon_", set(), collisions),
        )

    def test_namespaces_only_rider_collisions_and_preserves_other_nodes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "generated"
            write_json(
                root / "Server" / "Models" / "Creature" / "Dragon.json",
                {
                    "Model": "NPC/Test/Dragon/Models/Model.blockymodel",
                    "Texture": "NPC/Test/Dragon/Models/Texture.png",
                    "Camera": {
                        "Pitch": {"TargetNodes": ["Head"]},
                        "Yaw": {"TargetNodes": ["Head"]},
                    },
                    "AnimationSets": {
                        "Fly": {
                            "Animations": [
                                {"Animation": "NPC/Test/Dragon/Animations/Fly.blockyanim"}
                            ]
                        }
                    },
                },
            )
            write_json(
                root / "Common" / "NPC" / "Test" / "Dragon" / "Models" / "Model.blockymodel",
                {
                    "nodes": [
                        {
                            "name": "Origin",
                            "children": [
                                {
                                    "name": "Pelvis",
                                    "children": [
                                        {
                                            "name": "Chest",
                                            "children": [
                                                {"name": "Head"},
                                                {"name": "MountAnchor"},
                                                {"name": "Wing"},
                                            ],
                                        }
                                    ],
                                }
                            ],
                        }
                    ]
                },
            )
            write_json(
                root / "Common" / "NPC" / "Test" / "Dragon" / "Animations" / "Fly.blockyanim",
                {
                    "duration": 10,
                    "nodeAnimations": {
                        "Origin": {"position": []},
                        "Pelvis": {"orientation": []},
                        "Head": {"orientation": []},
                        "MountAnchor": {"orientation": []},
                        "Wing": {"orientation": []},
                        "R-Hand": {"orientation": []},
                    },
                },
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--mod-root",
                    str(root),
                    "--model-id",
                    "Dragon",
                    "--output-root",
                    str(output),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stdout)
            server_model = json.loads(
                (output / "Server" / "Models" / "Creature" / "Dragon_AvatarFlight.json").read_text()
            )
            self.assertEqual("NPC/Test/Dragon/Models/Model_AvatarFlight.blockymodel", server_model["Model"])
            self.assertEqual(["Head"], server_model["Camera"]["Pitch"]["TargetNodes"])
            self.assertEqual(
                "NPC/Test/Dragon/Animations/AvatarFlight/Fly.blockyanim",
                server_model["AnimationSets"]["Fly"]["Animations"][0]["Animation"],
            )

            model = json.loads(
                (
                    output
                    / "Common"
                    / "NPC"
                    / "Test"
                    / "Dragon"
                    / "Models"
                    / "Model_AvatarFlight.blockymodel"
                ).read_text()
            )
            origin = model["nodes"][0]
            pelvis = origin["children"][0]
            chest = pelvis["children"][0]
            self.assertEqual("AF_Origin", origin["name"])
            self.assertEqual("AF_Pelvis", pelvis["name"])
            self.assertEqual("AF_Chest", chest["name"])
            self.assertEqual("AF_Head", chest["children"][0]["name"])
            self.assertEqual("MountAnchor", chest["children"][1]["name"])
            self.assertEqual("Wing", chest["children"][2]["name"])

            animation = json.loads(
                (
                    output
                    / "Common"
                    / "NPC"
                    / "Test"
                    / "Dragon"
                    / "Animations"
                    / "AvatarFlight"
                    / "Fly.blockyanim"
                ).read_text()
            )
            self.assertEqual(
                {"AF_Origin", "AF_Pelvis", "AF_Head", "MountAnchor", "Wing", "AF_R-Hand"},
                set(animation["nodeAnimations"].keys()),
            )

    def test_warns_about_missing_player_locomotion_aliases_without_mutating_generated_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "generated"
            write_json(
                root / "Server" / "Models" / "Creature" / "Dragon.json",
                {
                    "Model": "NPC/Test/Dragon/Models/Model.blockymodel",
                    "AnimationSets": {
                        "Run": {
                            "Animations": [
                                {
                                    "Animation": "NPC/Test/Dragon/Animations/Run.blockyanim",
                                    "FootstepIntervals": [4],
                                }
                            ]
                        },
                        "JumpRun": {
                            "Animations": [
                                {"Animation": "NPC/Test/Dragon/Animations/Jump.blockyanim"}
                            ]
                        },
                        "StepRun": {
                            "Animations": [
                                {"Animation": "NPC/Test/Dragon/Animations/Step_Run.blockyanim"}
                            ]
                        },
                    },
                },
            )
            write_json(
                root / "Common" / "NPC" / "Test" / "Dragon" / "Models" / "Model.blockymodel",
                {"nodes": [{"name": "Origin"}]},
            )
            for animation in ("Run", "Jump", "Step_Run"):
                write_json(
                    root / "Common" / "NPC" / "Test" / "Dragon" / "Animations" / f"{animation}.blockyanim",
                    {"duration": 10, "nodeAnimations": {"Origin": {"position": []}}},
                )

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--mod-root",
                    str(root),
                    "--model-id",
                    "Dragon",
                    "--output-root",
                    str(output),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("missing native transformed-player animation set 'Sprint'", result.stdout)
            self.assertIn("missing native transformed-player animation set 'JumpSprint'", result.stdout)
            self.assertIn("missing native transformed-player animation set 'StepSprint'", result.stdout)
            self.assertIn("removed unsafe FootstepIntervals from animation set 'Run' entry 0", result.stdout)
            server_model = json.loads(
                (output / "Server" / "Models" / "Creature" / "Dragon_AvatarFlight.json").read_text()
            )
            animation_sets = server_model["AnimationSets"]

            self.assertNotIn("Sprint", animation_sets)
            self.assertNotIn("JumpSprint", animation_sets)
            self.assertNotIn("StepSprint", animation_sets)
            self.assertNotIn("FootstepIntervals", animation_sets["Run"]["Animations"][0])


if __name__ == "__main__":
    unittest.main()
