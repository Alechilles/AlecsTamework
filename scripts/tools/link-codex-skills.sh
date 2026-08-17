#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(git -C "${script_dir}/../.." rev-parse --show-toplevel)"
if [[ -n "${CODEX_HOME:-}" ]]; then
    codex_root="$(cygpath -u "${CODEX_HOME}")"
else
    codex_root="$(cygpath -u "${USERPROFILE}")/.codex"
fi
skill_name="tamework-persistence"
skill_source="${repo_root}/codex/skills/${skill_name}"
skill_target="${codex_root}/skills/${skill_name}"

if [[ ! -f "${skill_source}/SKILL.md" ]]; then
    echo "Skill source is missing: ${skill_source}" >&2
    exit 1
fi

mkdir -p "$(dirname "${skill_target}")"

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
        exit 0
    fi
    echo "Codex skill target already exists and points elsewhere:" >&2
    echo "  ${skill_target}" >&2
    exit 1
fi

target_windows="$(cygpath -w "${skill_target}")"
source_windows="$(cygpath -w "${skill_source}")"
cmd.exe //d //c mklink //J "${target_windows}" "${source_windows}"
echo "Linked ${skill_target} -> ${skill_source}"
