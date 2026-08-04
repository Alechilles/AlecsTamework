# Build and Packaging

How the project is packaged and where outputs go.

## Plugin jar

Gradle builds one shaded jar containing Java code and resources under
`src/main/resources`. The Hytale Gradle plugin writes the release version to
`manifest.json`.

## Packaging model

- Tamework ships as a jar.
- The release jar embeds Creditor so `/credits` does not require another mod.
- Tamework keeps its own root `manifest.json` and shades the Patchwork runtime
  into its release jar; it does not advertise Patchwork as a separate plugin.
- The shared workspace links both mods' asset files into its `run/mods` tree,
  so edits in Tamework and HyDragon can reload together.

## Development workspace

From the `Modding` directory, stage both projects and run one server:

```bash
./gradlew stageAllModAssets
./gradlew runAllMods
```

The default patchline is release. Use `-Phytale_patchline=pre-release` with a
matching `-Phytale_version` when testing a prerelease game build.

## Output and versioning

- Gradle output is written under `build/`.
- `gradle.properties` is the source of the mod and dependency versions.
- If a packaged manifest has the wrong version, run a clean build.

## Verification

Run the test suite before packaging:

```bash
./gradlew test packagingTest
```

Use the release-preparation workflow before publishing to validate versions,
release notes, assets, and the final artifact. Runtime verification should
cover the ordinary no-claims configuration and the direct SimpleClaims
integration used for breeding limits and tamed-companion damage.

For the first replacement-persistence release, also complete
[Persistence Replacement Release Checklist](Persistence-Replacement-Release-Checklist.md).
It uses the normal Gradle and release scripts plus two focused live-smoke lanes;
there is no separate persistence candidate builder or persistence rehearsal
runtime to package and maintain.

Tamework does not create or restore complete Hytale world backups. Operators
and hosting platforms remain responsible for consistent world backups.
Released schema v2-v4 sources are imported read-only into
`tamework-state.sqlite`; tester-only v5-v9 sources are refused unchanged.
