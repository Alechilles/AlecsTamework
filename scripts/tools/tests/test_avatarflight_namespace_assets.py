import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "scripts" / "tools" / "avatarflight_namespace_assets.py"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


class AvatarFlightNamespaceAssetsTest(unittest.TestCase):
    def test_namespaces_model_animation_and_camera_while_preserving_origin(self):
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
                                        {"name": "Chest", "children": [{"name": "Head"}]}
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
            self.assertEqual(["AF_Head"], server_model["Camera"]["Pitch"]["TargetNodes"])
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
            self.assertEqual("Origin", origin["name"])
            self.assertEqual("AF_Pelvis", pelvis["name"])
            self.assertEqual("AF_Chest", chest["name"])
            self.assertEqual("AF_Head", chest["children"][0]["name"])

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
                {"Origin", "AF_Pelvis", "AF_Head"},
                set(animation["nodeAnimations"].keys()),
            )


if __name__ == "__main__":
    unittest.main()
