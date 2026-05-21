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
                "Brown": {
                    "Model": "Models/Livestock/Goat_Brown.json",
                    "Texture": "Textures/Livestock/Goat_Brown.png",
                    "Weight": 1,
                },
                "White": {
                    "Model": "Models/Livestock/Goat_White.json",
                    "Texture": "Textures/Livestock/Goat_White.png",
                    "Weight": 1,
                },
            },
            "Eyes": {
                "Blue": {"Texture": "Textures/Livestock/Goat_Eyes_Blue.png"},
                "Gold": {"Texture": "Textures/Livestock/Goat_Eyes_Gold.png"},
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


class BatchManifestTests(unittest.TestCase):
    def run_generator(
        self,
        cwd: Path,
        manifest: Path,
        jobs_out: Path,
        manifest_out: Path,
        env=None,
        extra_args=None,
    ):
        command = [
            sys.executable,
            str(SCRIPT),
            "--asset-root",
            str(cwd),
            "--batch-manifest",
            str(manifest),
            "--renderer-jobs-out",
            str(jobs_out),
            "--manifest-out",
            str(manifest_out),
        ]
        if extra_args:
            command.extend(extra_args)
        return subprocess.run(
            command,
            cwd=cwd,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_batch_manifest_resolves_source_alias_zip_and_filters_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source_zip = root / "Aures_Livestock.zip"
            with zipfile.ZipFile(source_zip, "w") as archive:
                archive.writestr(
                    "Server/Models/Livestock/Goat.json",
                    json.dumps(goat_model()),
                )
                for asset_path in [
                    "Models/Livestock/Goat_Base.json",
                    "Textures/Livestock/Goat_Base.png",
                    "Models/Livestock/Goat_Brown.json",
                    "Textures/Livestock/Goat_Brown.png",
                    "Models/Livestock/Goat_White.json",
                    "Textures/Livestock/Goat_White.png",
                    "Textures/Livestock/Goat_Eyes_Blue.png",
                    "Textures/Livestock/Goat_Eyes_Gold.png",
                    "Models/Livestock/Goat_Horns_Short.json",
                    "Models/Livestock/Goat_Horns_Long.json",
                ]:
                    archive.writestr(f"Common/{asset_path}", b"asset")

            manifest = root / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{role}_{set_basecolor}.png",
                        "rendererName": "Animal Husbandry curated icons",
                    },
                    "sources": {
                        "auresLivestock": {
                            "modelsRoot": "${TEST_AURES_ZIP}!Server/Models"
                        }
                    },
                    "entries": [
                        {
                            "id": "goat_aures",
                            "source": "auresLivestock",
                            "model": "Livestock/Goat.json",
                            "roles": ["Goat", "Goat_Tamed"],
                            "keepAttachmentSets": ["BaseColor"],
                        }
                    ],
                },
            )

            env = os.environ.copy()
            env["TEST_AURES_ZIP"] = str(source_zip)
            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(root, manifest, jobs_out, manifest_out, env=env)

            self.assertEqual(result.returncode, 0, result.stdout)
            jobs = json.loads(jobs_out.read_text(encoding="utf-8"))
            self.assertEqual(jobs["schema"], "tamework.spawner-icon-render-jobs.v1")
            self.assertEqual(jobs["renderer"], "Animal Husbandry curated icons")
            self.assertEqual(jobs["jobCount"], 4)
            self.assertEqual(len(jobs["jobs"]), 4)
            self.assertIn("Aures_Livestock.zip!Server/Models/Livestock/Goat.json", jobs["modelSource"])
            self.assertTrue(
                any("Aures_Livestock.zip!Common" in root for root in jobs["sourceCommonRoots"])
            )
            for job in jobs["jobs"]:
                self.assertEqual(set(job["attachments"].keys()), {"BaseColor"})
                self.assertNotIn("Eyes", job["outputIcon"])
                self.assertNotIn("Horns", job["outputIcon"])
                self.assertTrue(Path(job["baseModelFile"]).is_file())
                self.assertTrue(Path(job["baseTextureFile"]).is_file())
                for selected_asset in job["selectedOptionAssets"]:
                    if selected_asset["modelFile"]:
                        self.assertTrue(Path(selected_asset["modelFile"]).is_file())
                    if selected_asset["textureFile"]:
                        self.assertTrue(Path(selected_asset["textureFile"]).is_file())

    def test_batch_manifest_resolves_manifest_relative_root_and_leading_slash_model_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Goat.json", goat_model())
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{role}_{set_basecolor}_{set_horns}.png"
                    },
                    "sources": {
                        "localModels": {
                            "modelsRoot": "../models"
                        }
                    },
                    "entries": [
                        {
                            "id": "goat_local",
                            "source": "localModels",
                            "model": "/Livestock/Goat.json",
                            "roles": "Goat",
                            "keepAttachmentSets": ["BaseColor", "Horns"],
                        }
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(root, manifest, jobs_out, manifest_out)

            self.assertEqual(result.returncode, 0, result.stdout)
            jobs = json.loads(jobs_out.read_text(encoding="utf-8"))
            self.assertEqual(jobs["jobCount"], 4)
            self.assertTrue(jobs["modelSource"].endswith("models\\Livestock\\Goat.json") or jobs["modelSource"].endswith("models/Livestock/Goat.json"))
            for job in jobs["jobs"]:
                self.assertEqual(set(job["attachments"].keys()), {"BaseColor", "Horns"})

    def test_replace_icon_overrides_drops_stale_roles(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Goat.json", goat_model())
            spawner = root / "spawner.json"
            write_json(
                spawner,
                {
                    "IconOverridesByRole": {
                        "OldRole": [
                            {
                                "Icon": "Icons/ItemsGenerated/Generated/Old.png",
                                "Attachments": {"BaseColor": "Old"},
                            }
                        ]
                    },
                    "IconOverrideGroups": [
                        {
                            "Roles": ["OldRole", "OldTamedRole"],
                            "Overrides": [
                                {
                                    "Icon": "Icons/ItemsGenerated/Generated/OldGroup.png",
                                    "Attachments": {"BaseColor": "Old"},
                                }
                            ],
                        }
                    ],
                },
            )
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{role}_{set_basecolor}.png"
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "goat_local",
                            "source": "localModels",
                            "model": "Livestock/Goat.json",
                            "roles": ["Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                        }
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(
                root,
                manifest,
                jobs_out,
                manifest_out,
                extra_args=[
                    "--spawner-config",
                    str(spawner),
                    "--in-place",
                    "--replace-icon-overrides",
                ],
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            updated = json.loads(spawner.read_text(encoding="utf-8"))
            self.assertEqual(set(updated["IconOverridesByRole"].keys()), {"Goat"})
            self.assertEqual(updated["IconOverrideGroups"], [])

    def test_group_mode_writes_shared_group_and_one_render_job_per_combo(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Goat.json", goat_model())
            spawner = root / "spawner.json"
            write_json(spawner, {"IconOverridesByRole": {}})
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{combo_slug}.png",
                        "iconOverrideMode": "group",
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "goat_local",
                            "source": "localModels",
                            "model": "Livestock/Goat.json",
                            "roles": ["Goat", "Tamed_Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                        }
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(
                root,
                manifest,
                jobs_out,
                manifest_out,
                extra_args=[
                    "--spawner-config",
                    str(spawner),
                    "--in-place",
                    "--replace-icon-overrides",
                ],
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            updated = json.loads(spawner.read_text(encoding="utf-8"))
            self.assertEqual(updated["IconOverridesByRole"], {})
            self.assertEqual(len(updated["IconOverrideGroups"]), 1)
            group = updated["IconOverrideGroups"][0]
            self.assertEqual(group["Roles"], ["Goat", "Tamed_Goat"])
            self.assertEqual(len(group["Overrides"]), 2)
            self.assertEqual(
                sorted(override["Icon"] for override in group["Overrides"]),
                [
                    "Icons/Generated/basecolor-brown.png",
                    "Icons/Generated/basecolor-white.png",
                ],
            )

            jobs = json.loads(jobs_out.read_text(encoding="utf-8"))
            self.assertEqual(jobs["jobCount"], 2)
            self.assertEqual(len(jobs["jobs"]), 2)

    def test_group_mode_writes_icon_default_for_model_without_attachment_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Cow.json", cow_model())
            spawner = root / "spawner.json"
            write_json(spawner, {"IconOverridesByRole": {}})
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{combo_slug}.png",
                        "iconOverrideMode": "group",
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "cow_local",
                            "source": "localModels",
                            "model": "Livestock/Cow.json",
                            "roles": ["Cow", "Tamed_Cow"],
                        }
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(
                root,
                manifest,
                jobs_out,
                manifest_out,
                extra_args=[
                    "--spawner-config",
                    str(spawner),
                    "--in-place",
                    "--replace-icon-overrides",
                ],
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            updated = json.loads(spawner.read_text(encoding="utf-8"))
            self.assertEqual(updated["IconOverridesByRole"], {})
            self.assertEqual(len(updated["IconOverrideGroups"]), 1)
            group = updated["IconOverrideGroups"][0]
            self.assertEqual(group["Roles"], ["Cow", "Tamed_Cow"])
            self.assertEqual(group["IconDefault"], "Icons/Generated/base.png")
            self.assertEqual(group["Overrides"], [])

            jobs = json.loads(jobs_out.read_text(encoding="utf-8"))
            self.assertEqual(jobs["jobCount"], 1)
            self.assertEqual(jobs["jobs"][0]["comboSlug"], "base")
            self.assertEqual(jobs["jobs"][0]["attachments"], {})

    def test_batch_manifest_writes_auto_frame_camera_defaults_and_entry_overrides(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Goat.json", goat_model())
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{combo_slug}.png",
                        "cameraAutoFrame": True,
                        "cameraAutoFramePadding": 5,
                        "cameraAutoFrameMaxAttempts": 7,
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "goat_local",
                            "source": "localModels",
                            "model": "Livestock/Goat.json",
                            "roles": ["Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                        },
                        {
                            "id": "goat_tight",
                            "source": "localModels",
                            "model": "Livestock/Goat.json",
                            "roles": ["Tamed_Goat"],
                            "keepAttachmentSets": ["Horns"],
                            "cameraAutoFramePadding": 2,
                        },
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(root, manifest, jobs_out, manifest_out)

            self.assertEqual(result.returncode, 0, result.stdout)
            jobs = json.loads(jobs_out.read_text(encoding="utf-8"))
            camera_defaults = jobs["defaults"]["camera"]
            self.assertEqual(camera_defaults["autoFrame"], True)
            self.assertEqual(camera_defaults["autoFramePadding"], 5)
            self.assertEqual(camera_defaults["autoFrameMaxAttempts"], 7)
            entry_override_jobs = [
                job for job in jobs["jobs"] if job.get("entryId") == "goat_tight"
            ]
            self.assertTrue(entry_override_jobs)
            self.assertTrue(all(job["camera"]["autoFrame"] is True for job in entry_override_jobs))
            self.assertTrue(all(job["camera"]["autoFramePadding"] == 2 for job in entry_override_jobs))
            self.assertTrue(all(job["camera"]["autoFrameMaxAttempts"] == 7 for job in entry_override_jobs))

    def test_group_mode_merges_duplicate_role_groups_from_multiple_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Aures" / "Goat.json", goat_model())
            write_json(root / "models" / "Base" / "Goat.json", goat_model())
            spawner = root / "spawner.json"
            write_json(spawner, {"IconOverridesByRole": {}})
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{combo_slug}.png",
                        "iconOverrideMode": "group",
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "goat_aures",
                            "source": "localModels",
                            "model": "Aures/Goat.json",
                            "roles": ["Goat", "Tamed_Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                            "iconTemplate": "Icons/Aures/{combo_slug}.png",
                        },
                        {
                            "id": "goat_base",
                            "source": "localModels",
                            "model": "Base/Goat.json",
                            "roles": ["Goat", "Tamed_Goat"],
                            "keepAttachmentSets": ["Horns"],
                            "iconTemplate": "Icons/Base/{combo_slug}.png",
                        },
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(
                root,
                manifest,
                jobs_out,
                manifest_out,
                extra_args=[
                    "--spawner-config",
                    str(spawner),
                    "--in-place",
                    "--replace-icon-overrides",
                ],
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            updated = json.loads(spawner.read_text(encoding="utf-8"))
            self.assertEqual(len(updated["IconOverrideGroups"]), 1)
            group = updated["IconOverrideGroups"][0]
            self.assertEqual(group["Roles"], ["Goat", "Tamed_Goat"])
            self.assertEqual(len(group["Overrides"]), 4)
            self.assertIn("Icons/Aures/basecolor-brown.png", [entry["Icon"] for entry in group["Overrides"]])
            self.assertIn("Icons/Base/horns-short.png", [entry["Icon"] for entry in group["Overrides"]])

    def test_group_mode_replaces_existing_same_role_group_instead_of_appending_stale_winner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Goat.json", goat_model())
            spawner = root / "spawner.json"
            write_json(
                spawner,
                {
                    "IconOverridesByRole": {},
                    "IconOverrideGroups": [
                        {
                            "Roles": ["Goat", "Tamed_Goat"],
                            "Overrides": [
                                {
                                    "Icon": "Icons/Old/basecolor-brown.png",
                                    "Attachments": {"BaseColor": "Brown"},
                                }
                            ],
                            "IconDefault": "Icons/Old/base.png",
                        },
                        {
                            "Roles": ["Cow"],
                            "Overrides": [
                                {
                                    "Icon": "Icons/Cow/base.png",
                                    "Attachments": {"BaseColor": "Brown"},
                                }
                            ],
                        },
                    ],
                },
            )
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/New/{combo_slug}.png",
                        "iconOverrideMode": "group",
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "goat_local",
                            "source": "localModels",
                            "model": "Livestock/Goat.json",
                            "roles": ["Goat", "Tamed_Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                        }
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(
                root,
                manifest,
                jobs_out,
                manifest_out,
                extra_args=[
                    "--spawner-config",
                    str(spawner),
                    "--in-place",
                ],
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            updated = json.loads(spawner.read_text(encoding="utf-8"))
            self.assertEqual(len(updated["IconOverrideGroups"]), 2)
            group = updated["IconOverrideGroups"][0]
            self.assertEqual(group["Roles"], ["Goat", "Tamed_Goat"])
            self.assertNotIn("IconDefault", group)
            self.assertEqual(
                sorted(override["Icon"] for override in group["Overrides"]),
                [
                    "Icons/New/basecolor-brown.png",
                    "Icons/New/basecolor-white.png",
                ],
            )
            self.assertEqual(updated["IconOverrideGroups"][1]["Roles"], ["Cow"])

    def test_exclude_attachment_options_removes_default_or_empty_variants(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_json(root / "models" / "Livestock" / "Goat.json", goat_model())
            manifest = root / "batch" / "icons.batch.json"
            write_json(
                manifest,
                {
                    "defaults": {
                        "iconTemplate": "Icons/Generated/{role}_{set_basecolor}.png"
                    },
                    "sources": {"localModels": {"modelsRoot": "../models"}},
                    "entries": [
                        {
                            "id": "goat_local",
                            "source": "localModels",
                            "model": "Livestock/Goat.json",
                            "roles": ["Goat"],
                            "keepAttachmentSets": ["BaseColor"],
                            "excludeAttachmentOptions": {"BaseColor": ["Brown"]},
                        }
                    ],
                },
            )

            jobs_out = root / "jobs.json"
            manifest_out = root / "report.json"
            result = self.run_generator(root, manifest, jobs_out, manifest_out)

            self.assertEqual(result.returncode, 0, result.stdout)
            jobs = json.loads(jobs_out.read_text(encoding="utf-8"))
            self.assertEqual(jobs["jobCount"], 1)
            self.assertEqual(jobs["jobs"][0]["attachments"], {"BaseColor": "White"})


if __name__ == "__main__":
    unittest.main()
