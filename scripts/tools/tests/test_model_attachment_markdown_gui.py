import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


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

    def test_form_values_use_a_manual_base_game_models_path(self):
        """Catches a manual base-game Models selection that does not reach the engine."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model = root / "ExampleMod" / "Server" / "Models" / "Cat.json"
            write_json(model, {"Parent": "BaseCat"})
            base_models = root / "CustomGame" / "Assets" / "Server" / "Models"
            write_json(
                base_models / "BaseCat.json",
                {"RandomAttachmentSets": {"Tail": {"Long": {"Weight": 1}}}},
            )
            gui = self.load_gui_module()

            report = gui.generate_report(
                str(model),
                model_roots=[],
                display_roots=[],
                base_game_models=str(base_models),
            )

            self.assertIn("| Tail | Tail | Long | Long | 1 | 100.0% |", report)

    def test_auto_detects_the_default_install_before_an_input_is_selected(self):
        """Catches the Auto button skipping detection when the input field is empty."""
        with tempfile.TemporaryDirectory() as tmp:
            appdata = Path(tmp) / "AppData" / "Roaming"
            models = (
                appdata
                / "Hytale"
                / "install"
                / "release"
                / "package"
                / "game"
                / "latest"
                / "Assets"
                / "Server"
                / "Models"
            )
            models.mkdir(parents=True)
            gui = self.load_gui_module()

            with patch.dict(os.environ, {"APPDATA": str(appdata)}):
                detect = getattr(gui, "detect_base_game_models", lambda _: "")
                detected = detect("")

            self.assertEqual(detected, str(models.resolve()))

    def test_batch_form_values_apply_selected_columns(self):
        """Catches GUI batch mode or column choices that do not reach the engine."""
        with tempfile.TemporaryDirectory() as tmp:
            mod_root = Path(tmp) / "ExampleMod"
            write_json(
                mod_root / "Server" / "Models" / "Cat.json",
                {"RandomAttachmentSets": {"Tail": {"Long": {"Weight": 1}}}},
            )
            gui = self.load_gui_module()

            try:
                report = gui.generate_report(
                    str(mod_root),
                    model_roots=[],
                    display_roots=[],
                    batch=True,
                    columns=["attachment", "chance"],
                )
            except TypeError:
                self.fail("The GUI batch and column adapter is not implemented.")

            self.assertIn("# Model Attachment Report", report)
            self.assertIn("| Attachment | Chance |", report)
            self.assertNotIn("Attachment Set", report)


if __name__ == "__main__":
    unittest.main()
