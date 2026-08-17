#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(git -C "${script_dir}/../.." rev-parse --show-toplevel)"
if [[ -n "${CODEX_HOME:-}" ]]; then
    codex_root="$(cygpath -u "${CODEX_HOME}")"
else
    codex_root="$(cygpath -u "${USERPROFILE}")/.codex"
fi
skills_source_root="${repo_root}/codex/skills"
skills_target_root="${codex_root}/skills"

if [[ ! -d "${skills_source_root}" ]]; then
    echo "Repository skill directory is missing: ${skills_source_root}" >&2
    exit 1
fi

mapfile -t skill_manifests < <(
    find "${skills_source_root}" -mindepth 2 -maxdepth 2 -type f -name SKILL.md -print | sort
)
if [[ ${#skill_manifests[@]} -eq 0 ]]; then
    echo "No repository skills were found under: ${skills_source_root}" >&2
    exit 1
fi

mkdir -p "${skills_target_root}"

for skill_manifest in "${skill_manifests[@]}"; do
    skill_source="$(dirname "${skill_manifest}")"
    skill_name="$(basename "${skill_source}")"
    skill_target="${skills_target_root}/${skill_name}"

    if [[ -L "${skill_target}" && ! -e "${skill_target}" ]]; then
        echo "Codex skill target is a dangling junction:" >&2
        echo "  ${skill_target}" >&2
        echo "Remove that junction explicitly, then run this script again." >&2
        exit 1
    fi

    if [[ -e "${skill_target}" || -L "${skill_target}" ]]; then
        source_real="$(cd "${skill_source}" && pwd -P)"
        target_real="$(cd "${skill_target}" && pwd -P)"
        if [[ "${source_real}" == "${target_real}" ]]; then
            echo "Codex skill link is already correct: ${skill_target}"
            continue
        fi
        echo "Codex skill target already exists and points elsewhere:" >&2
        echo "  ${skill_target}" >&2
        exit 1
    fi

    target_windows="$(cygpath -w "${skill_target}")"
    source_windows="$(cygpath -w "${skill_source}")"
    cmd.exe //d //c mklink //J "${target_windows}" "${source_windows}"
    echo "Linked ${skill_target} -> ${skill_source}"
done
