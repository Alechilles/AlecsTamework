# Runtime vs Source Checklist

Use this checklist when an in-game report, log, screenshot, packaged jar, save override, or runtime copy may disagree with source.

## Identify the Loaded Artifact

- Source repo: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework`
- Runtime copy: `C:\Users\22ale\AppData\Roaming\Hytale\UserData\Mods\alecstamework`
- Packaged artifacts: `artifacts`, `target`, GitHub release assets, or downloaded zips
- Save overrides: active world/save override roots under the Hytale user data tree

## Compare in This Order

1. Read the exact log/error/screenshot path or asset ID from the report.
2. Locate the source file and the runtime copy.
3. If a jar/zip is involved, inspect its contents directly.
4. If a generated Patchwork pack is involved, run `/patchwork status` to verify the elected runtime, contributions, definition roots, and last generation result. Then inspect `Alechilles:Patchwork_GeneratedPatches` under the configured generated root. A standalone runtime wins only an equal-version tie; otherwise the highest compatible version wins regardless of whether it is standalone or embedded.
5. If a save override exists, compare it against current source. Remember that Tamework JSON merge is object-deep but arrays are replacing.
6. Only then edit the source repo or runtime copy, depending on what the user actually asked for.

## Useful Commands

```powershell
rg -F "<asset-or-type-id>" src/main/resources src/main/java docs wiki
rg -F "<asset-or-type-id>" "C:\Users\22ale\AppData\Roaming\Hytale\UserData\Mods\alecstamework"
jar tf target\*.jar | Select-String "<asset-or-type-id>"
```

For patches, also compare the neutral `Server/Patchwork/Patches` definition with
any legacy `Server/Tamework/Patches` definition. Patchwork reads the legacy root
only while Tamework is installed, and the neutral definition wins a matching
legacy-definition conflict.

Use PowerShell fixed-string searches where possible. Broad regexes are easy to get wrong against Hytale asset paths and JSON fragments.

