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
- Tamework keeps a direct `alecstelemetry-runtime` dependency because its
  conventional project is used by `CrashTelemetryService`. Patchwork carries
  the same runtime transitively for its contributed project. The release line
  is Patchwork `1.3.0` with Alec's Telemetry `1.1.0`; Gradle dependency
  convergence must select `1.1.0` for both edges.
- The two telemetry projects are independent: Tamework uses its conventional
  project and the embedded Patchwork runtime contributes a hosted-only
  `patchwork` project. They share one host-local Telemetry provider and one
  writable token per project, while consent remains project-specific.
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

`packagingTest` loads the built shaded jar in an isolated classloader and
executes Patchwork's packaged version/optional-telemetry behavior. It is a
behavior smoke test, not a ZIP-entry inventory check. Dependency convergence
can be inspected with:

```bash
./gradlew dependencyInsight --dependency alecstelemetry-runtime --configuration runtimeClasspath
```

The expected selected version is `1.1.0`, whether Alec's Telemetry is reached
directly from Tamework or transitively through Patchwork.

Tamework and Patchwork expose separate telemetry consent entries. The
contributed Patchwork project is hosted-only in this release. Do not document
live same-ID replacement or failover: after replacing an elected embedded
runtime, restart the server before expecting the new candidate to write.

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
