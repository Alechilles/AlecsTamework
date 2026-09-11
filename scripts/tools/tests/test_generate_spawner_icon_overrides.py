import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "scripts" / "tools" / "generate_spawner_icon_overrides.py"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def goat_model() -> dict:
    return {
        "Model": "Models/Livestock/Goat_Base.json",
        "Texture": "Textures/Livestock/Goat_Base.png",
        "RandomAttachmentSets": {
            "BaseColor": {
                "Brown": {"Model": "Models/Livestock/Goat_Brown.json"},
                "White": {"Model": "Models/Livestock/Goat_White.json"},
            },
            "Horns": {
                "Short": {"Model": "Models/Livestock/Goat_Horns_Short.json"},
                "Long": {"Model": "Models/Livestock/Goat_Horns_Long.json"},
            },
        },
    }


def cow_model() -> dict:
    return {
        "Model": "Models/Livestock/Cow_Base.json",
        "Texture": "Textures/Livestock/Cow_Base.png",
    }


def base_only_model(model_path: str) -> dict:
    return {"Model": model_path, "Texture": "Textures/Livestock/Base.png"}


class DynamicIconGeneratorTests(unittest.TestCase):
    def run_generator(self, cwd: Path, *arguments: str, env=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--asset-root", str(cwd), *arguments],
            cwd=cwd,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_single_model_writes_dynamic_icon_asset_with_attachment_overrides(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model = root / "models" / "Goat.json"
            write_json(model, goat_model())
            jobs = root / "jobs.json"

            result = self.run_generator(
                root,
                "--model", str(model),
                "--roles", "Goat,Tamed_Goat",
                "--icon-template", "Icons/Generated/{role}_{set_basecolor}_{set_horns}.png",
                "--dynamic-icons-output-dir", "Server/Tamework/DynamicIcons/Generated",
                "--dynamic-icon-id", "Goat Icons",
                "--renderer-jobs-out", str(jobs),
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            config = json.loads(
                (root / "Server/Tamework/DynamicIcons/Generated/Goat_Icons.json").read_text(encoding="utf-8")
            )
            self.assertEqual(config["RoleIds"], ["Goat", "Tamed_Goat"])
            self.assertEqual(len(config["IconOverrides"]), 4)
            self.assertEqual(config["IconOverrides"][0], {
                "Icon": "Icons/Generated/Goat_Brown_Short.png",
                "Attachments": {"BaseColor": "Brown", "Horns": "Short"},
            })
            renderer_jobs = json.loads(jobs.read_text(encoding="utf-8"))
            self.assertEqual(renderer_jobs["schema"], "tamework.spawner-icon-render-jobs.v1")
            self.assertEqual(renderer_jobs["jobCount"], 4)

    def test_batch_merges_same_role_group_into_one_asset_in_manifest_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models/Aures/Goat.json", goat_model())
            write_json(root / "models/Base/Goat.json", goat_model())
            manifest = root / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {"iconTemplate": "Icons/Generated/{combo_slug}.png"},
                    "sources": {"models": {"modelsRoot": "models"}},
                    "entries": [
                        {
                            "id": "goat_aures",
                            "source": "models",
                            "model": "Aures/Goat.json",
                            "roles": ["Goat", "Tamed_Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                            "iconTemplate": "Icons/Aures/{combo_slug}.png",
                        },
                        {
                            "id": "goat_base",
                            "source": "models",
                            "model": "Base/Goat.json",
                            "roles": ["Goat", "Tamed_Goat"],
                            "keepAttachmentSets": ["Horns"],
                            "iconTemplate": "Icons/Base/{combo_slug}.png",
                        },
                    ],
                },
            )
            report = root / "report.json"

            result = self.run_generator(
                root,
                "--batch-manifest", str(manifest),
                "--dynamic-icons-output-dir", "generated/icons",
                "--dynamic-icon-id-prefix", "AH_DynamicIcon",
                "--manifest-out", str(report),
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            config = json.loads((root / "generated/icons/AH_DynamicIcon_Goat.json").read_text(encoding="utf-8"))
            self.assertEqual(config["RoleIds"], ["Goat", "Tamed_Goat"])
            self.assertEqual(
                [override["Icon"] for override in config["IconOverrides"]],
                [
                    "Icons/Aures/basecolor-brown.png",
                    "Icons/Aures/basecolor-white.png",
                    "Icons/Base/horns-short.png",
                    "Icons/Base/horns-long.png",
                ],
            )
            generated = json.loads(report.read_text(encoding="utf-8"))["dynamicIconAssets"]
            self.assertEqual(generated[0]["entryIds"], ["goat_aures", "goat_base"])

    def test_batch_writes_base_only_default_and_optional_config_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models/Cow.json", cow_model())
            manifest = root / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{combo_slug}.png",
                        "enabled": False,
                        "priority": 3,
                    },
                    "sources": {"models": {"modelsRoot": "models"}},
                    "entries": [{
                        "id": "cow base!",
                        "source": "models",
                        "model": "Cow.json",
                        "roles": ["Cow Base"],
                    }],
                },
            )

            result = self.run_generator(root, "--batch-manifest", str(manifest))

            self.assertEqual(result.returncode, 0, result.stdout)
            config = json.loads(
                (root / "Server/Tamework/DynamicIcons/DynamicIcon_Cow_Base.json").read_text(encoding="utf-8")
            )
            self.assertEqual(config, {
                "RoleIds": ["Cow Base"],
                "IconOverrides": [],
                "IconDefault": "Icons/Generated/base.png",
                "Enabled": False,
                "Priority": 3,
            })

    def test_batch_keeps_fixed_render_attachment_out_of_dynamic_icon_predicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "Animal Husbandry!"
            root.mkdir()
            model = goat_model()
            model["RandomAttachmentSets"]["Mane"] = {
                "Long": {"Model": "Models/Livestock/Goat_Mane_Long.json",
                         "Texture": "Textures/Livestock/Goat_Mane_Long.png"}
            }
            write_json(root / "models/Goat.json", model)
            manifest = root / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {"iconTemplate": "Icons/Generated/{combo_slug}.png"},
                    "sources": {"models": {"modelsRoot": "models"}},
                    "entries": [{
                        "id": "goat",
                        "source": "models",
                        "model": "Goat.json",
                        "roles": ["Goat"],
                        "keepAttachmentSets": ["BaseColor"],
                        "renderAttachmentDefaults": {"Mane": "Long"},
                    }],
                },
            )
            jobs = root / "jobs.json"

            result = self.run_generator(
                root,
                "--batch-manifest", str(manifest),
                "--renderer-jobs-out", str(jobs),
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            config = json.loads(
                (root / "Server/Tamework/DynamicIcons/DynamicIcon_Goat.json").read_text(encoding="utf-8")
            )
            self.assertEqual(len(config["IconOverrides"]), 2)
            self.assertTrue(all(
                override["Attachments"] in ({"BaseColor": "Brown"}, {"BaseColor": "White"})
                for override in config["IconOverrides"]
            ))
            renderer_jobs = json.loads(jobs.read_text(encoding="utf-8"))
            self.assertEqual(renderer_jobs["jobCount"], 2)
            for job in renderer_jobs["jobs"]:
                assets = {asset["set"]: asset for asset in job["selectedOptionAssets"]}
                self.assertEqual(assets["Mane"]["option"], "Long")
                self.assertEqual(
                    Path(assets["Mane"]["textureFile"]),
                    root / "Common/Textures/Livestock/Goat_Mane_Long.png",
                )
                self.assertIn(assets["BaseColor"]["option"], ("Brown", "White"))

    def test_batch_rejects_different_render_specs_that_share_an_output_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models/Aures/Goat.json", base_only_model("Models/Aures/Goat.json"))
            write_json(root / "models/Base/Goat.json", base_only_model("Models/Base/Goat.json"))
            manifest = root / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {"iconTemplate": "Icons/Generated/shared.png"},
                    "sources": {"models": {"modelsRoot": "models"}},
                    "entries": [
                        {
                            "id": "goat_aures",
                            "source": "models",
                            "model": "Aures/Goat.json",
                            "roles": ["Goat"],
                        },
                        {
                            "id": "goat_base",
                            "source": "models",
                            "model": "Base/Goat.json",
                            "roles": ["Goat"],
                        },
                    ],
                },
            )

            result = self.run_generator(root, "--batch-manifest", str(manifest))

            self.assertEqual(result.returncode, 2, result.stdout)
            self.assertIn("Renderer output collision", result.stdout)
            self.assertIn("goat_aures", result.stdout)
            self.assertIn("goat_base", result.stdout)

    def test_batch_preserves_zip_source_render_jobs_and_attachment_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "Animal Husbandry!"
            root.mkdir()
            source_zip = root / "Aures.zip"
            with zipfile.ZipFile(source_zip, "w") as archive:
                archive.writestr("Server/Models/Livestock/Goat.json", json.dumps(goat_model()))
                archive.writestr("Common/Models/Livestock/Goat_Base.json", b"asset")
                archive.writestr("Common/Textures/Livestock/Goat_Base.png", b"asset")
                archive.writestr("Common/Models/Livestock/Goat_Brown.json", b"asset")
                archive.writestr("Common/Models/Livestock/Goat_White.json", b"asset")
                archive.writestr("Common/Models/Livestock/Goat_Horns_Short.json", b"asset")
                archive.writestr("Common/Models/Livestock/Goat_Horns_Long.json", b"asset")
            manifest = root / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {"iconTemplate": "Icons/Generated/{combo_slug}.png"},
                    "sources": {"zip": {"modelsRoot": "${TEST_AURES_ZIP}!Server/Models"}},
                    "entries": [{
                        "id": "goat_zip",
                        "source": "zip",
                        "model": "Livestock/Goat.json",
                        "roles": ["Goat"],
                        "keepAttachmentSets": ["BaseColor"],
                    }],
                },
            )
            jobs = root / "jobs.json"
            env = os.environ.copy()
            env["TEST_AURES_ZIP"] = str(source_zip)

            result = self.run_generator(
                root,
                "--batch-manifest", str(manifest),
                "--renderer-jobs-out", str(jobs),
                env=env,
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            renderer_jobs = json.loads(jobs.read_text(encoding="utf-8"))
            self.assertEqual(renderer_jobs["jobCount"], 2)
            self.assertTrue(all(set(job["attachments"]) == {"BaseColor"} for job in renderer_jobs["jobs"]))
            self.assertTrue(Path(renderer_jobs["jobs"][0]["baseModelFile"]).is_file())


if __name__ == "__main__":
    unittest.main()
