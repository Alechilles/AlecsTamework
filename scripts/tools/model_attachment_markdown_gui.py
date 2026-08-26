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


def generate_report(
    model_asset: str,
    model_roots: Sequence[str],
    display_roots: Sequence[str],
    model_id: str = "",
    role_id: str = "",
) -> str:
    """Convert GUI field values into a report-engine request."""
    if not model_asset.strip():
        raise report_engine.ReportError("Select a ModelAsset JSON file.")
    args = SimpleNamespace(
        model_asset=Path(model_asset),
        model_root=[Path(value) for value in model_roots],
        display_root=[Path(value) for value in display_roots],
        model_id=model_id.strip() or None,
        role_id=role_id.strip() or None,
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


class AttachmentReportApplication:
    """Tkinter application for generating and saving attachment reports."""

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.report = ""
        self.model_asset = tk.StringVar()
        self.model_id = tk.StringVar()
        self.role_id = tk.StringVar()
        self.status = tk.StringVar(value="Select a ModelAsset to begin.")

        root.title("Tamework Model Attachment Report")
        root.geometry("960x720")
        root.minsize(720, 560)
        self.build_window()

    def build_window(self) -> None:
        content = ttk.Frame(self.root, padding=12)
        content.grid(row=0, column=0, sticky="nsew")
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)
        content.columnconfigure(0, weight=1)
        content.rowconfigure(3, weight=1)

        inputs = ttk.LabelFrame(content, text="Report Input", padding=10)
        inputs.grid(row=0, column=0, sticky="ew")
        inputs.columnconfigure(1, weight=1)

        ttk.Label(inputs, text="ModelAsset JSON").grid(
            row=0, column=0, sticky="w", padx=(0, 8)
        )
        ttk.Entry(inputs, textvariable=self.model_asset).grid(
            row=0, column=1, sticky="ew"
        )
        ttk.Button(inputs, text="Browse...", command=self.choose_model).grid(
            row=0, column=2, padx=(8, 0)
        )

        ttk.Label(inputs, text="Model ID (optional)").grid(
            row=1, column=0, sticky="w", padx=(0, 8), pady=(10, 0)
        )
        ttk.Entry(inputs, textvariable=self.model_id).grid(
            row=1, column=1, sticky="ew", pady=(10, 0)
        )
        ttk.Label(inputs, text="Role ID (optional)").grid(
            row=2, column=0, sticky="w", padx=(0, 8), pady=(8, 0)
        )
        ttk.Entry(inputs, textvariable=self.role_id).grid(
            row=2, column=1, sticky="ew", pady=(8, 0)
        )

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

        actions = ttk.Frame(content)
        actions.grid(row=2, column=0, sticky="ew", pady=10)
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
        preview.grid(row=3, column=0, sticky="nsew")
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

    def choose_model(self) -> None:
        selected = filedialog.askopenfilename(
            title="Select a ModelAsset",
            filetypes=(("JSON files", "*.json"), ("All files", "*.*")),
        )
        if selected:
            self.model_asset.set(selected)
            self.status.set("Ready to generate.")

    def generate(self) -> None:
        try:
            report = generate_report(
                self.model_asset.get(),
                self.model_roots.values(),
                self.display_roots.values(),
                self.model_id.get(),
                self.role_id.get(),
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
        row_count = max(0, report.count("\n") - 2)
        self.status.set(f"Generated {row_count} attachment rows.")

    def save(self) -> None:
        if not self.report:
            return
        model_name = Path(self.model_asset.get()).stem or "model"
        selected = filedialog.asksaveasfilename(
            title="Save Markdown Report",
            defaultextension=".md",
            initialfile=f"{model_name}-attachments.md",
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
