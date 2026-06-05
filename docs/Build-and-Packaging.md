# Build and Packaging

How the project is packaged and where outputs go.

## Plugin jar
Built via Maven. The jar includes:
- Java code
- `src/main/resources` assets (`Common/`, `Server/`, and metadata)
- Filtered manifests (`manifest.json`, `manifest-assets.json`)

## Packaging model
- Tamework is shipped as jar-only.
- No standalone `(Assets)` zip is produced by current build profiles.
- The release jar embeds Creditor through Cursemaven and Maven Shade so `/credits` is available without a separate Creditor install.
- Creditor's dependency `manifest.json` is excluded during shading so the packaged jar keeps Tamework's root manifest.
- Runtime asset-pack ordering keeps Tamework directly after `Hytale:Hytale` before the main load pass.
- Legacy standalone `Alec's Tamework! (Assets)` packs/zips are removed/replaced when detected in the same mods directory.

## Maven profiles
- `install-plugin`: copies only the built jar to server mods and userdata mods paths.
- `run-server`: copies only the built jar, then starts the server.
- `prerelease` (`-Dprerelease=true`): switches install paths to prerelease.

## Dev hot reload
During dev runs, the server references `src/main/resources` directly for faster iteration.

## Output location
- `target/` for build outputs
- Server deploy path configured by the Maven run task

## Manifest versioning
Manifest resources are versioned from Maven:
- `src/main/resources/manifest.json` and `manifest-assets.json` use `${project.version}`.
- Maven resource filtering stamps the version during build.

If you see a mismatched manifest version, re run `clean package` to refresh filtered resources.
