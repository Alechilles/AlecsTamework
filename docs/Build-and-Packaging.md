# Build and Packaging

How the project is packaged and where outputs go.

## Plugin jar
Built via Maven. The jar includes:
- Java code
- `src/main/resources` assets
- Manifest and metadata

## Assets zip
A separate assets zip is produced on build (same output directory as the jar). It contains:
- `Common/`
- `Server/`
- `manifest.json`
- `LICENSE.txt`

The zip does **not** include `config/` unless explicitly chosen.

## Why Two?
This is, hopefully, a temporary solution while we wait for Hypixel to release methods for more robust load order control.

We ship a separate assets pack because assets inside a plugin jar aren’t reliably available to the client asset pipeline or to other mods early enough. When assets load before the plugin registers custom builders/components, you’ll see “builder not found” errors.

The standalone assets pack ensures these resources are discoverable by the client and other mods at load time.
(Naming the pack with a leading . can help push it earlier, but it’s not a guaranteed ordering mechanism.)

## Dev hot‑reload
During dev runs, the server references `src/main/resources` directly for faster iteration.

## Output location
- `target/` for build outputs
- Server deploy path configured by the Maven run task

## Manifest versioning
Both the plugin manifest and the assets pack manifest are versioned from Maven:
- `src/main/resources/manifest.json` and `manifest-assets.json` use `${project.version}`.
- Maven resource filtering stamps the version during build.
- The assets zip pulls the filtered manifest from `target/classes`.

If you see a mismatched manifest version, re-run `clean package` to refresh the filtered resources and the assets zip.

