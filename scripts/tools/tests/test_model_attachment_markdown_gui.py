import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
GUI_SCRIPT = REPO_ROOT / "scripts" / "tools" / "model_attachment_markdown_gui.py"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


class ModelAttachmentMarkdownGuiTests(unittest.TestCase):
    def load_gui_module(self):
        try:
            spec = importlib.util.spec_from_file_location(
                "model_attachment_markdown_gui", GUI_SCRIPT
            )
            module = importlib.util.module_from_spec(spec)
            assert spec.loader is not None
            spec.loader.exec_module(module)
            return module
        except FileNotFoundError:
            self.fail("The GUI report adapter is not implemented.")

    def test_form_values_generate_a_role_specific_report(self):
        """Catches GUI fields that are not passed to the report engine."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model = root / "ExampleMod" / "Server" / "Models" / "Cat.json"
            write_json(
                model,
                {"RandomAttachmentSets": {"Tail": {"Long": {"Weight": 1}}}},
            )
            labels_root = root / "LabelsMod"
            write_json(
                labels_root
                / "Server"
                / "Tamework"
                / "AttachmentDisplays"
                / "CatLabels.json",
                {
                    "Entries": [
                        {
                            "AppliesTo": {"RoleIds": ["TamedCat"]},
                            "Sets": {
                                "Tail": {
                                    "Label": "Tail Length",
                                    "Values": {"Long": "Long Tail"},
                                }
                            },
                        }
                    ]
                },
            )
            gui = self.load_gui_module()

            report = gui.generate_report(
                str(model),
                model_roots=[],
                display_roots=[str(labels_root)],
                model_id="",
                role_id="TamedCat",
            )

            self.assertIn(
                "| Tail | Tail Length | Long | Long Tail | 1 | 100% |",
                report,
            )


if __name__ == "__main__":
    unittest.main()
