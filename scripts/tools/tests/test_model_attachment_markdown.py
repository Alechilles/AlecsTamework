import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "scripts" / "tools" / "model_attachment_markdown.py"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


class ModelAttachmentMarkdownTests(unittest.TestCase):
    def run_report(self, model_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(model_path), *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_reports_inherited_attachment_weights_as_set_percentages(self):
        """Catches lost ModelAsset inheritance, default weights, or a global denominator."""
        with tempfile.TemporaryDirectory() as tmp:
            mod_root = Path(tmp) / "ExampleMod"
            models = mod_root / "Server" / "Models" / "Creatures"
            write_json(
                models / "Parent.json",
                {
                    "RandomAttachmentSets": {
                        "Coat": {
                            "Black": {"Weight": 1},
                            "White": {"Weight": 3},
                        },
                        "Horns": {
                            "Short": {},
                            "Long": {},
                        },
                    }
                },
            )
            child = models / "Child.json"
            write_json(child, {"Parent": "Parent"})

            result = self.run_report(child)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                result.stdout,
                "| Attachment Set | Set Display | Attachment | Attachment Display | Weight | Chance |\n"
                "| --- | --- | --- | --- | ---: | ---: |\n"
                "| Coat | Coat | Black | Black | 1 | 25% |\n"
                "| Coat | Coat | White | White | 3 | 75% |\n"
                "| Horns | Horns | Long | Long | 1 | 50% |\n"
                "| Horns | Horns | Short | Short | 1 | 50% |\n",
            )

    def test_prefers_exact_model_displays_over_higher_priority_global_displays(self):
        """Catches display selection by priority before Tamework match specificity."""
        with tempfile.TemporaryDirectory() as tmp:
            mod_root = Path(tmp) / "ExampleMod"
            model = mod_root / "Server" / "Models" / "Creatures" / "Child.json"
            write_json(
                model,
                {
                    "RandomAttachmentSets": {
                        "Coat": {"Black": {"Weight": 1}}
                    }
                },
            )
            write_json(
                mod_root / "Server" / "Tamework" / "AttachmentDisplays" / "Global.json",
                {
                    "Priority": 100,
                    "Entries": [
                        {
                            "Id": "global",
                            "Sets": {
                                "Coat": {
                                    "Label": "Global Coat",
                                    "Values": {"Black": "Global Black"},
                                }
                            },
                        }
                    ],
                },
            )
            extra_root = Path(tmp) / "LabelsMod"
            write_json(
                extra_root / "Server" / "Tamework" / "AttachmentDisplays" / "Child.json",
                {
                    "Priority": 0,
                    "Entries": [
                        {
                            "Id": "child",
                            "AppliesTo": {"ModelIds": ["Child"]},
                            "Sets": {
                                "Coat": {
                                    "Label": "Coat Color",
                                    "Values": {"Black": "Midnight"},
                                }
                            },
                        }
                    ],
                },
            )

            result = self.run_report(model, "--display-root", str(extra_root))

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("| Coat | Coat Color | Black | Midnight | 1 | 100% |", result.stdout)
            self.assertNotIn("Global Coat", result.stdout)

    def test_inherits_omitted_attachment_display_entries(self):
        """Catches raw labels when a display config inherits its Entries array."""
        with tempfile.TemporaryDirectory() as tmp:
            mod_root = Path(tmp) / "ExampleMod"
            model = mod_root / "Server" / "Models" / "Child.json"
            write_json(
                model,
                {"RandomAttachmentSets": {"Eyes": {"Blue": {"Weight": 1}}}},
            )
            display_dir = mod_root / "Server" / "Tamework" / "AttachmentDisplays"
            write_json(
                display_dir / "BaseLabels.json",
                {
                    "Enabled": False,
                    "Entries": [
                        {
                            "Id": "eyes",
                            "AppliesTo": {"ModelIds": ["Child"]},
                            "Sets": {
                                "Eyes": {
                                    "Label": "Eye Color",
                                    "Values": {"Blue": "Ocean Blue"},
                                }
                            },
                        }
                    ],
                },
            )
            write_json(
                display_dir / "EnabledLabels.json",
                {"Parent": "BaseLabels", "Enabled": True},
            )

            result = self.run_report(model)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("| Eyes | Eye Color | Blue | Ocean Blue | 1 | 100% |", result.stdout)

    def test_resolves_parent_from_an_extra_model_root(self):
        """Catches failure to report mod models that inherit from another asset pack."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model = root / "ExampleMod" / "Server" / "Models" / "Child.json"
            write_json(model, {"Parent": "BaseCreature"})
            base_models = root / "GameAssets" / "Server" / "Models"
            write_json(
                base_models / "BaseCreature.json",
                {"RandomAttachmentSets": {"Tail": {"Long": {"Weight": 2}}}},
            )

            result = self.run_report(model, "--model-root", str(base_models))

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("| Tail | Tail | Long | Long | 2 | 100% |", result.stdout)


if __name__ == "__main__":
    unittest.main()
