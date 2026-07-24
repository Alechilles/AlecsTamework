# Build and Packaging

How the project is packaged and where outputs go.

## Plugin jar

Maven builds one jar containing Java code, resources under
`src/main/resources`, and the filtered plugin manifests.

## Packaging model

- Tamework ships as a jar.
- The release jar embeds Creditor so `/credits` does not require another mod.
- Shading excludes dependency manifests so Tamework keeps its own root
  `manifest.json`.
- Development hot reload may reference `src/main/resources` directly.

## Maven profiles

- `install-plugin` copies the built jar to the configured development mod
  locations.
- `run-server` copies the jar and starts the development server.
- `prerelease` (`-Dprerelease=true`) selects prerelease install paths.

## Output and versioning

- Maven output is written under `target/`.
- `manifest.json` and `manifest-assets.json` receive `${project.version}`
  through resource filtering.
- If a packaged manifest has the wrong version, run a clean build.

## Verification

Run the test suite before packaging:

```bash
./mvnw test
```

Use the release-preparation workflow before publishing to validate versions,
release notes, assets, and the final artifact. Runtime verification should
cover the ordinary no-claims configuration and the direct SimpleClaims
integration used for breeding limits and tamed-companion damage.

For the first replacement-persistence release, also complete
[Persistence Replacement Release Checklist](Persistence-Replacement-Release-Checklist.md).
It uses the normal Maven and release scripts plus two focused live-smoke lanes;
there is no separate persistence candidate builder or persistence rehearsal
runtime to package and maintain.

Tamework does not create or restore complete Hytale world backups. Operators
and hosting platforms remain responsible for consistent world backups.
Released schema v2-v4 sources are imported read-only into
`tamework-state.sqlite`; tester-only v5-v9 sources are refused unchanged.
