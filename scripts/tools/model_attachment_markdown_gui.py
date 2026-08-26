#!/usr/bin/env python3
"""Desktop GUI for the ModelAsset attachment Markdown report."""

from __future__ import annotations

import sys
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from types import SimpleNamespace
from typing import Sequence


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))

import model_attachment_markdown as report_engine


def detect_base_game_models(input_path: str) -> str:
    source_path = Path(input_path) if input_path.strip() else None
    models = report_engine.find_base_game_models(source_path)
    return str(models) if models is not None else ""


def generate_report(
    input_path: str,
    model_roots: Sequence[str],
    display_roots: Sequence[str],
    base_game_models: str = "",
    batch: bool = False,
    columns: Sequence[str] = report_engine.DEFAULT_COLUMNS,
) -> str:
    """Convert GUI field values into a report-engine request."""
    if not input_path.strip():
        expected = "batch folder" if batch else "ModelAsset JSON file"
        raise report_engine.ReportError(f"Select a {expected}.")
    args = SimpleNamespace(
        model_asset=None if batch else Path(input_path),
        batch_root=Path(input_path) if batch else None,
        model_root=[Path(value) for value in model_roots],
        display_root=[Path(value) for value in display_roots],
        base_game_models=Path(base_game_models) if base_game_models.strip() else None,
        model_id=None,
        role_id=None,
        columns=columns,
        output=None,
    )
    return report_engine.run(args)


class DirectoryList(ttk.LabelFrame):
    """List editor for extra asset roots."""

    def __init__(self, master: tk.Misc, title: str, dialog_title: str) -> None:
        super().__init__(master, text=title, padding=8)
        self.dialog_title = dialog_title
        self.columnconfigure(0, weight=1)
        self.rowconfigure(0, weight=1)

        self.listbox = tk.Listbox(self, height=4, selectmode=tk.EXTENDED)
        self.listbox.grid(row=0, column=0, sticky="nsew")
        scrollbar = ttk.Scrollbar(self, orient=tk.VERTICAL, command=self.listbox.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.listbox.configure(yscrollcommand=scrollbar.set)

        buttons = ttk.Frame(self)
        buttons.grid(row=1, column=0, columnspan=2, sticky="w", pady=(8, 0))
        ttk.Button(buttons, text="Add Folder...", command=self.add_folder).grid(
            row=0, column=0
        )
        ttk.Button(buttons, text="Remove", command=self.remove_selected).grid(
            row=0, column=1, padx=(8, 0)
        )

    def add_folder(self) -> None:
        selected = filedialog.askdirectory(title=self.dialog_title)
        if selected and selected not in self.values():
            self.listbox.insert(tk.END, selected)

    def remove_selected(self) -> None:
        for index in reversed(self.listbox.curselection()):
            self.listbox.delete(index)

    def values(self) -> list[str]:
        return list(self.listbox.get(0, tk.END))


class ColumnSelector(ttk.LabelFrame):
    """Select and order the columns used in the Markdown tables."""

    def __init__(self, master: tk.Misc) -> None:
        super().__init__(master, text="Table Columns", padding=8)
        self.labels_by_key = {
            key: definition[0]
            for key, definition in report_engine.COLUMN_DEFINITIONS.items()
        }
        self.keys_by_label = {
            label: key for key, label in self.labels_by_key.items()
        }
        self.columnconfigure(0, weight=1)
        self.columnconfigure(2, weight=1)

        ttk.Label(self, text="Displayed Columns (in order)").grid(
            row=0, column=0, sticky="w"
        )
        ttk.Label(self, text="Hidden Columns").grid(
            row=0, column=2, sticky="w", padx=(8, 0)
        )
        self.displayed = tk.Listbox(self, height=6, exportselection=False)
        self.displayed.grid(row=1, column=0, sticky="nsew")
        for key in report_engine.DEFAULT_COLUMNS:
            self.displayed.insert(tk.END, self.labels_by_key[key])

        controls = ttk.Frame(self)
        controls.grid(row=1, column=1, padx=8)
        ttk.Button(controls, text="Move Up", command=lambda: self.move(-1)).grid(
            row=0, column=0, sticky="ew"
        )
        ttk.Button(controls, text="Move Down", command=lambda: self.move(1)).grid(
            row=1, column=0, sticky="ew", pady=(5, 0)
        )
        ttk.Button(controls, text="Hide >", command=self.hide).grid(
            row=2, column=0, sticky="ew", pady=(12, 0)
        )
        ttk.Button(controls, text="< Show", command=self.show).grid(
            row=3, column=0, sticky="ew", pady=(5, 0)
        )

        self.hidden = tk.Listbox(self, height=6, exportselection=False)
        self.hidden.grid(row=1, column=2, sticky="nsew")

    def move(self, direction: int) -> None:
        selection = self.displayed.curselection()
        if not selection:
            return
        index = selection[0]
        destination = index + direction
        if destination < 0 or destination >= self.displayed.size():
            return
        label = self.displayed.get(index)
        self.displayed.delete(index)
        self.displayed.insert(destination, label)
        self.displayed.selection_set(destination)

    def hide(self) -> None:
        selection = self.displayed.curselection()
        if not selection or self.displayed.size() == 1:
            return
        index = selection[0]
        label = self.displayed.get(index)
        self.displayed.delete(index)
        self.hidden.insert(tk.END, label)

    def show(self) -> None:
        selection = self.hidden.curselection()
        if not selection:
            return
        index = selection[0]
        label = self.hidden.get(index)
        self.hidden.delete(index)
        self.displayed.insert(tk.END, label)

    def values(self) -> list[str]:
        labels = self.displayed.get(0, tk.END)
        return [self.keys_by_label[label] for label in labels]


class AttachmentReportApplication:
    """Tkinter application for generating and saving attachment reports."""

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.report = ""
        self.input_mode = tk.StringVar(value="single")
        self.model_asset = tk.StringVar()
        self.input_label = tk.StringVar(value="ModelAsset JSON")
        self.base_game_models = tk.StringVar()
        self.status = tk.StringVar(value="Select a ModelAsset to begin.")

        root.title("Tamework Model Attachment Report")
        root.geometry("1000x850")
        root.minsize(780, 650)
        self.build_window()
        self.auto_detect_base_game_models()

    def build_window(self) -> None:
        content = ttk.Frame(self.root, padding=12)
        content.grid(row=0, column=0, sticky="nsew")
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)
        content.columnconfigure(0, weight=1)
        content.rowconfigure(4, weight=1)

        inputs = ttk.LabelFrame(content, text="Report Input", padding=10)
        inputs.grid(row=0, column=0, sticky="ew")
        inputs.columnconfigure(1, weight=1)

        modes = ttk.Frame(inputs)
        modes.grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 10))
        ttk.Radiobutton(
            modes,
            text="Single ModelAsset",
            variable=self.input_mode,
            value="single",
            command=self.change_input_mode,
        ).grid(row=0, column=0)
        ttk.Radiobutton(
            modes,
            text="Batch Folder",
            variable=self.input_mode,
            value="batch",
            command=self.change_input_mode,
        ).grid(row=0, column=1, padx=(12, 0))

        ttk.Label(inputs, textvariable=self.input_label).grid(
            row=1, column=0, sticky="w", padx=(0, 8)
        )
        ttk.Entry(inputs, textvariable=self.model_asset).grid(
            row=1, column=1, sticky="ew"
        )
        ttk.Button(inputs, text="Browse...", command=self.choose_input).grid(
            row=1, column=2, padx=(8, 0)
        )

        ttk.Label(inputs, text="Base Game Models").grid(
            row=2, column=0, sticky="w", padx=(0, 8), pady=(10, 0)
        )
        ttk.Entry(inputs, textvariable=self.base_game_models).grid(
            row=2, column=1, sticky="ew", pady=(10, 0)
        )
        base_game_buttons = ttk.Frame(inputs)
        base_game_buttons.grid(row=2, column=2, sticky="w", padx=(8, 0), pady=(10, 0))
        ttk.Button(
            base_game_buttons,
            text="Browse...",
            command=self.choose_base_game_models,
        ).grid(row=0, column=0)
        ttk.Button(
            base_game_buttons,
            text="Auto",
            command=self.auto_detect_base_game_models,
        ).grid(row=0, column=1, padx=(5, 0))

        roots = ttk.Frame(content)
        roots.grid(row=1, column=0, sticky="ew", pady=(10, 0))
        roots.columnconfigure(0, weight=1)
        roots.columnconfigure(1, weight=1)
        self.model_roots = DirectoryList(
            roots,
            "Extra Model Roots",
            "Select a mod root or Server/Models directory",
        )
        self.model_roots.grid(row=0, column=0, sticky="nsew", padx=(0, 5))
        self.display_roots = DirectoryList(
            roots,
            "Extra Display Roots",
            "Select a mod root or AttachmentDisplays directory",
        )
        self.display_roots.grid(row=0, column=1, sticky="nsew", padx=(5, 0))

        self.columns = ColumnSelector(content)
        self.columns.grid(row=2, column=0, sticky="ew", pady=(10, 0))

        actions = ttk.Frame(content)
        actions.grid(row=3, column=0, sticky="ew", pady=10)
        ttk.Button(actions, text="Generate Report", command=self.generate).grid(
            row=0, column=0
        )
        self.save_button = ttk.Button(
            actions,
            text="Save Markdown...",
            command=self.save,
            state=tk.DISABLED,
        )
        self.save_button.grid(row=0, column=1, padx=(8, 0))
        ttk.Label(actions, textvariable=self.status).grid(
            row=0, column=2, sticky="w", padx=(12, 0)
        )

        preview = ttk.LabelFrame(content, text="Markdown Preview", padding=8)
        preview.grid(row=4, column=0, sticky="nsew")
        preview.columnconfigure(0, weight=1)
        preview.rowconfigure(0, weight=1)
        self.preview = tk.Text(
            preview,
            wrap=tk.NONE,
            font="TkFixedFont",
            state=tk.DISABLED,
        )
        self.preview.grid(row=0, column=0, sticky="nsew")
        vertical = ttk.Scrollbar(preview, orient=tk.VERTICAL, command=self.preview.yview)
        vertical.grid(row=0, column=1, sticky="ns")
        horizontal = ttk.Scrollbar(
            preview, orient=tk.HORIZONTAL, command=self.preview.xview
        )
        horizontal.grid(row=1, column=0, sticky="ew")
        self.preview.configure(
            yscrollcommand=vertical.set,
            xscrollcommand=horizontal.set,
        )

    def change_input_mode(self) -> None:
        batch = self.input_mode.get() == "batch"
        self.input_label.set("Mod Root or Server/Models" if batch else "ModelAsset JSON")
        self.model_asset.set("")
        expected = "batch folder" if batch else "ModelAsset"
        self.status.set(f"Select a {expected} to begin.")

    def choose_input(self) -> None:
        if self.input_mode.get() == "batch":
            selected = filedialog.askdirectory(
                title="Select a mod root or Server/Models directory"
            )
        else:
            selected = filedialog.askopenfilename(
                title="Select a ModelAsset",
                filetypes=(("JSON files", "*.json"), ("All files", "*.*")),
            )
        if selected:
            self.model_asset.set(selected)
            self.auto_detect_base_game_models()

    def choose_base_game_models(self) -> None:
        selected = filedialog.askdirectory(
            title="Select a Hytale install root or base-game Server/Models directory"
        )
        if not selected:
            return
        try:
            models = report_engine.normalize_base_game_models(Path(selected))
        except (OSError, report_engine.ReportError) as exc:
            messagebox.showerror("Cannot Use Base Game Path", str(exc), parent=self.root)
            return
        self.base_game_models.set(str(models))
        self.status.set("Using the selected base-game Models folder.")

    def auto_detect_base_game_models(self) -> None:
        detected = detect_base_game_models(self.model_asset.get())
        self.base_game_models.set(detected)
        if not detected:
            self.status.set(
                "Base game was not detected. Select its Models folder if needed."
            )
        else:
            self.status.set("Base-game Models detected. Ready to generate.")

    def generate(self) -> None:
        try:
            report = generate_report(
                self.model_asset.get(),
                self.model_roots.values(),
                self.display_roots.values(),
                self.base_game_models.get(),
                batch=self.input_mode.get() == "batch",
                columns=self.columns.values(),
            )
        except (OSError, report_engine.ReportError) as exc:
            self.status.set("Report generation failed.")
            messagebox.showerror("Cannot Generate Report", str(exc), parent=self.root)
            return

        self.report = report
        self.preview.configure(state=tk.NORMAL)
        self.preview.delete("1.0", tk.END)
        self.preview.insert("1.0", report)
        self.preview.configure(state=tk.DISABLED)
        self.save_button.configure(state=tk.NORMAL)
        self.status.set("Report generated.")

    def save(self) -> None:
        if not self.report:
            return
        input_name = Path(self.model_asset.get()).stem or "model"
        if self.input_mode.get() == "batch":
            default_name = f"{input_name}-model-attachments.md"
        else:
            default_name = f"{input_name}-attachments.md"
        selected = filedialog.asksaveasfilename(
            title="Save Markdown Report",
            defaultextension=".md",
            initialfile=default_name,
            filetypes=(("Markdown files", "*.md"), ("All files", "*.*")),
        )
        if not selected:
            return
        try:
            Path(selected).write_text(self.report, encoding="utf-8")
        except OSError as exc:
            messagebox.showerror("Cannot Save Report", str(exc), parent=self.root)
            return
        self.status.set(f"Saved {selected}")


def main() -> int:
    try:
        root = tk.Tk()
    except tk.TclError as exc:
        print(f"error: cannot start the GUI: {exc}", file=sys.stderr)
        return 2
    AttachmentReportApplication(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
