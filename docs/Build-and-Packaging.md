# Build and Packaging

How the project is packaged and where outputs go.

## Plugin jar
Built via Maven. The jar includes:
- Java code
- `src/main/resources` assets (`Common/`, `Server/`, and metadata)
- Manifest and metadata

## Assets zip
No standalone assets zip is produced by default.

## Why One
Builds now ship jar-only. Asset resources are embedded in the plugin jar, and install/run profiles copy only the jar to mods directories.

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
