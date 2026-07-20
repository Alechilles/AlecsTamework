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

## Packaged claims runtime verification

Use `scripts/tools/verify-claims-runtime-startup.ps1` for the final packaged-runtime gate covering
claim-provider startup, claim settings, and population persistence. The command requires explicit,
read-only inputs and a brand-new evidence root:

```powershell
.\scripts\tools\verify-claims-runtime-startup.ps1 `
  -BuiltArtifact "C:\build\Alec's Tamework! v2.16.1.jar" `
  -HytaleServerJar "C:\hytale\Server\HytaleServer.jar" `
  -HytaleAssets "C:\hytale\Assets" `
  -JavaExecutable "C:\jdk-25\bin\java.exe" `
  -SimpleClaimsJar "C:\providers\SimpleClaims-1.0.38.jar" `
  -QuestLinesClaimsJar "C:\providers\questlines-claims-1.3.1.jar" `
  -UpgradeSaveSource "C:\stopped-copies\Demo Prefab World" `
  -OutputRoot "C:\claims-evidence\run-2026-07-11" `
  -DwellSeconds 15 `
  -StartupTimeoutSeconds 180 `
  -ShutdownTimeoutSeconds 60 `
  -UpgradeReadinessTimeoutSeconds 300
```

`OutputRoot` must not already exist. The harness refuses an output root under the live
`%APPDATA%\Hytale\UserData` tree. `UpgradeSaveSource` must also be outside live UserData and must be
a stopped server/save-root copy containing `universe\Tamework\Data\tamework.sqlite`. Never point it at
a running save. Reparse points/junctions in either path are refused so an external-looking path cannot
redirect the isolated run into live state.

The copied-upgrade fixture preserves active `universe`, root configuration/permissions, and mod data
directories. It filters inherited archives, unpacked plugin manifests/classes, and native executable
payloads from `mods`, then stages only the explicit Tamework/provider jars. It also omits inactive or
generated root trees such as `backup`, `assetEditor`, logs, caches, and temporary homes. Each scenario
runs with an isolated working directory, default `home\mods`, universe, environment home, app-data
directories, and loopback ephemeral bind. For the copied lane, its copied `config.json` is rewritten so
Tamework and exactly the staged scenario providers are enabled and scheduled server backups are disabled.
The supplied source config remains unchanged.

The five scenarios are:

1. Fresh universe with no provider.
2. Fresh universe with SimpleClaims 1.0.38.
3. Fresh universe with QuestLines Claims 1.3.1.
4. Fresh universe with both providers and `Auto` configuration.
5. A copied pre-v6 upgrade save with both providers.

All fixtures configure and read back a global per-owner tame limit of 3, claim limits of 2 per chunk and
6 total, claim-required breeding, and non-member damage protection. Startup evidence proves that each
expected plugin is enabled exactly once and that forbidden provider jars are absent. Provider artifact
identity and public binary contracts are verified before startup. Actual claim-provider selection occurs when a
claim operation is resolved; startup alone cannot prove that operation-scoped selection, so it remains
an interactive/operation test gate. The same limitation applies to proving the staged settings were
consumed by a gameplay operation: this harness proves their packaged configuration and readback.

For each scenario, the evidence root records the command line, PID/timestamps, artifact manifests and
SHA-256 hashes, stdout/stderr, combined server logs, graceful-stop/exit state, JSON results, and a
Markdown summary. Unexpected `SEVERE`, `ERROR`, exceptions, JVM linkage failures, plugin-load failures,
or provider-contract failures fail the run. Diagnostic classification uses one canonical server log so
console/file mirrors cannot inflate exact counts; raw process stderr is checked separately for pre-logger
JVM fatal/linkage/exception signatures. The exact numeric Hytale `[SERR] Reallocate: <n> to <n>` line is
recorded as an ignored base-engine baseline. Any changed Reallocate text remains fatal.

Two provider-origin SLF4J clusters are recorded and ignored only in their exact lanes with the audited
provider identities and SHA-256 values: the three-line SLF4J 2.x no-provider cluster for QuestLines-only,
and the five-line provider-instantiation/type-collision cluster when both providers are staged. The
audited hashes are `664C6F5681695238FD898E851B044A90812AA13282D2A97A0770802182B7683B` for
SimpleClaims 1.0.38 and `9AA23C0CCD0FD8BB70F305D952AA1B9A0BBF1AEC46D9D8D6DAD37E04B3F2F592`
for QuestLines Claims 1.3.1. Wrong lanes, hashes, counts, text, stack traces, legacy
`StaticLoggerBinder` warnings, and every
other severe diagnostic remain fatal.

SQLite is queried read-only for `integrity_check=ok`, WAL, FULL synchronous mode, schema v7, all seven
coverage dimensions, configured owner-scope readiness, zero nonterminal operations, breeding/total
`RETRYABLE` counts (which must match so no unsupported operation kind can escape readiness), and
canonical/profile row consistency. A fully authoritative save must report every row and the scan session
`READY` with zero coverage errors. Because these fixtures configure the owner cap as `GLOBAL`, the copied
upgrade may instead report exactly one non-ready row:
`PER_WORLD_OWNER:owner-population:per-world:RECONCILING`, an `ACTIVE` scan session, and the exact
`owned-profiles-have-unknown-world` reason. That sentinel proves global counts are authoritative while
per-world positive admissions remain fail-closed until legacy profiles acquire an authoritative world;
no other partial-readiness shape is accepted. The copied upgrade must also retain at least its pre-run
profile/canonical row floor and create a new, non-empty, `tamework_pre_v7_*.sqlite.bak` whose read-only
integrity check passes and whose pre-v6 migration set and profile count match the source baseline. A
copied/preexisting or unrelated valid SQLite file cannot satisfy that proof.

After the normal dwell, the copied-upgrade lane polls those SQLite invariants while the server remains
running. It stops only after terminal readiness or after `UpgradeReadinessTimeoutSeconds`; the default is
300 seconds. `ACTIVE`, partial coverage, nonterminal operations, or a timeout remain failures. Fresh lanes
use the fixed dwell because their empty databases already reached READY in the packaged startup runs.
The `Companion population ledger loaded: ... RECONCILING` log is an initial bootstrap observation, not a
terminal readiness anchor; the persisted scan session and coverage rows are authoritative here.

The harness snapshots SHA-256, length, and modification time before and after the run for the built
artifact, server jar, Java executable, both provider jars, the upgrade-source database, and file-form
assets. SQLite `-wal`/`-shm` sidecars are included when present, and the isolated copy records matching
hashes for the complete database snapshot before boot. The copied source `config.json` is tracked too.
Any input mutation fails the overall summary.

### Safe validation without a Hytale boot

Add `-ValidateOnly` with all parameters above to validate paths, manifests, exact provider dependency
ranges, both real provider binary contracts, and the upgrade database baseline. This mode can launch
short-lived `java`/`javap` processes for read-only contract and SQLite probes. Source SQLite files and
their WAL/SHM sidecars are first copied to a disposable probe snapshot, so JDBC never opens the supplied
save in place. This mode does not launch the Hytale server and does not create `OutputRoot`.

Run the focused PowerShell self-test with the same explicit artifacts:

```powershell
.\scripts\tools\tests\test-verify-claims-runtime-startup.ps1 `
  -BuiltArtifact "C:\build\Alec's Tamework! v2.16.1.jar" `
  -HytaleServerJar "C:\hytale\Server\HytaleServer.jar" `
  -HytaleAssets "C:\hytale\Assets" `
  -JavaExecutable "C:\jdk-25\bin\java.exe" `
  -SimpleClaimsJar "C:\providers\SimpleClaims-1.0.38.jar" `
  -QuestLinesClaimsJar "C:\providers\questlines-claims-1.3.1.jar"
```

The self-test builds only disposable SQLite and fake-process fixtures; it never starts Hytale. It covers
pre-v6 probing, copied-save layout, archive filtering, new-backup discrimination, immutability checks,
copied config overrides, exact diagnostic allow/fail matrices, readiness success/timeout polling,
graceful console stop, forced timeout cleanup, input refusal, and validate-only wiring.

The process contract is grounded in Hytale 0.5.6 server code: `Options` supplies the explicit assets,
universe, bind, offline-auth, Sentry, and file-watcher flags; the working directory supplies the default
`mods` directory; `HytaleServer.boot` emits `Hytale Server Booted!`; console `StopCommand` invokes the
graceful shutdown path; `HytaleServer.shutdown0` emits `Shutdown completed!`; and `PluginManager.start`
emits `Enabled plugin <id>`. Re-check these anchors when updating the Hytale server version.

### Persistence single-cutover candidate evidence

After all three persistence-resilience branches are clean, generate the exact candidate and evidence
record with:

```powershell
.\scripts\tools\verify-persistence-release-candidate.ps1 `
  -TelemetryRoot "C:\worktrees\AlecsTelemetry" `
  -PlatformRoot "C:\worktrees\AlecsTelemetryPlatform" `
  -HytaleVersion "0.5.6"
```

The verifier reruns the complete Tamework and Alec's Telemetry Maven suites, the telemetry platform
type, lint, bounded-worker Vitest, and production-build gates, then packages Tamework once. It refuses
dirty or changing worktrees, validates required nested classes/resources and embedded telemetry runtime
`1.0.4`, and writes `target/persistence-release-evidence/candidate.json` with all three commits, test
totals, source/descriptor hashes, and the final JAR hash.

The verifier never opens or copies a Hytale world. Its backup section always records that Tamework did
not create a whole-save backup. An operator may pass `-ExternalHytaleBackupReference` to record an
already-created Hytale-owned rehearsal backup reference; the verifier does not create or validate that
backup itself.

Numeric and live persistence budgets are defined in
[`Persistence-Performance-Budgets.md`](Persistence-Performance-Budgets.md). The automated indexed
admission/reload gates run inside the full Maven suite; copied-world startup and tick deltas are recorded
during the live rehearsal because a wall-clock unit test cannot represent Hytale world load behavior.
