<p>
    <a href="https://www.curseforge.com/hytale/mods/alecs-tamework" target="_blank" rel="noopener noreferrer"><img alt="Tamework downloads" src="https://img.shields.io/curseforge/dt/1447962?label=Tamework&amp;style=for-the-badge&amp;logo=curseforge&amp;color=rgb(241%2C100%2C54)" /></a>
    <a href="https://www.curseforge.com/hytale/mods/alecs-cats" target="_blank" rel="noopener noreferrer"><img alt="Cats downloads" src="https://img.shields.io/curseforge/dt/1432112?label=Cats&amp;style=for-the-badge&amp;logo=curseforge&amp;color=rgb(241%2C100%2C54)" /></a>
    <a href="https://www.curseforge.com/hytale/mods/alecs-nametags" target="_blank" rel="noopener noreferrer"><img alt="Nametags downloads" src="https://img.shields.io/curseforge/dt/1464844?label=Nametags&amp;style=for-the-badge&amp;logo=curseforge&amp;color=rgb(241%2C100%2C54)" /></a>
    <a href="https://www.curseforge.com/hytale/mods/alecs-animal-husbandry" target="_blank" rel="noopener noreferrer"><img alt="Animal Husbandry downloads" src="https://img.shields.io/curseforge/dt/1480275?label=Animal%20Husbandry&amp;style=for-the-badge&amp;logo=curseforge&amp;color=rgb(241%2C100%2C54)" /></a>
  </p>
  <p>
    <a href="https://discord.gg/E8n8RgTTdq" target="_blank" rel="noopener noreferrer"><img alt="Join Discord" src="https://img.shields.io/discord/1468261809739005996?style=for-the-badge&amp;logo=discord&amp;logoColor=white&amp;label=Join%20Discord&amp;color=rgb(88,101,242)" /></a>
    <a href="https://ko-fi.com/alechilles" target="_blank" rel="noopener noreferrer"><img alt="Support me on Ko-fi" src="https://img.shields.io/badge/ko--fi-Support%20Me-ff5f5f?logo=ko-fi&amp;style=for-the-badge" /></a>
    <a href="https://hytale.com/" target="_blank" rel="noopener noreferrer"><img alt="Creator Code Alec" src="https://img.shields.io/badge/Creator%20Code-Alec-00AEEF?style=for-the-badge" /></a>
    <a href="https://twitter.com/intent/user?screen_name=Alechilles" target="_blank" rel="noopener noreferrer"><img alt="Follow Alec on X" src="https://img.shields.io/badge/Follow-%40Alec-White?style=for-the-badge&amp;logo=x&amp;logoColor=rgb(255%2C255%2C255)&amp;logoSize=auto&amp;label=Follow&amp;labelColor=rgb(85%2C85%2C85)&amp;color=rgb(147%2C147%2C147)" /></a>
  </p>
<p>
    <a href="https://bisecthosting.com/Alec" target="_blank"><img alt="25% Off BisectHosting Servers With Code: Alec" src="https://www.bisecthosting.com/partners/custom-banners/249bd432-0996-4ccb-8184-65cd3791a3d2.webp" /></a>
</p>
<p>
    <a href="https://www.modstats.io/stats/alecs-tamework" target="_blank" rel="nofollow"><img src="https://www.modstats.io/api/v1/stats/projects/alecs-tamework/embed/card.svg?layout=live&amp;theme=curseforge" alt="Alec's Tamework! ModStats"></a>
</p>

# Alec's Tamework!
Alec's Tamework is a modular NPC framework for Hytale built to let modders add rich NPC features through assets, templates, and config-driven systems instead of writing custom Java code. It embeds Patchwork for non-destructive asset patching, so artists, designers, and less technical modders can build advanced optional integrations without first creating their own plugin.

Tamework also aims to establish a shared standard for tameable NPCs in Hytale so different mods can work on top of similar ownership, naming, command, progression, and companion-management systems instead of forcing players to learn a different workflow for every mod.

## This Is a Library
Tamework is a framework dependency for other mods. It does not add a standalone gameplay expansion by itself.

If you are a player looking for gameplay built on Tamework, start with [Alec's Animal Husbandry](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry) or [Alec's Cats](https://www.curseforge.com/hytale/mods/alecs-cats), or see the wiki for the specific mod you are using.

## Why Tamework
- **No Java required for most integrations**: the main integration path is built around JSON assets, templates, role wiring, and `Tw*Config` files rather than custom Java systems.
- **Non-destructive asset patching**: Embedded Patchwork can add, merge, and insert JSON into one or many Hytale assets with composable conditions. It regenerates at startup, on an administrator reload, and after relevant directory-pack edits, and it reports which changes require a restart.
- **A shared standard for tameable NPCs**: mods built on Tamework can present familiar ownership, naming, command, linked-panel, breeding, and progression behavior instead of inventing incompatible one-off systems.
- **Optimized interactions**: build taming, feeding, mounting, harvesting, breeding, and custom interactions with `TwInteractionConfig` and `TameworkInteract`.
- **Ownership and tame-state systems**: use reusable builders and role-scoped policy for owner checks, protection rules, and companion behavior.
- **Spawner, naming, and command items**: capture and respawn NPCs with metadata, name companions with custom items, and build command tools with radial and linked-panel support.
- **Linked companion runtime**: manage loaded, unloaded, dead, and lost companions through a linked panel with recall, home, revive, and related flows.
- **Progression systems**: add happiness, needs, breeding, life stage, and trait-driven variation through config-driven systems.
- **Coop integration**: configured coops can accept eligible live companions or
  canonical filled capture items and release the same saved companion again;
  other coops retain their normal behavior.
- **Durable population controls**: owner and role-defined group limits account
  for canonical companions across unloads, storage, capture, death, travel, and
  restarts, while direct SimpleClaims checks can still limit breeding by claim
  and apply its native tamed-companion damage rules.
- **Advanced extension points when needed**: bridge into custom logic through hooks and optional integrations without giving up the higher-level framework.
- **Stable integration data**: the Public API exposes canonical companion
  profiles and namespaced profile extension data, including revision-fenced
  compare-and-set operations for integrations that need restart-safe custom
  state.
- **Durable integration workflows**: capability-gated APIs expose population
  groups, command-family rosters, timed summon/storage, idempotent companion
  provisioning, and exact paid revival without exposing SQLite internals.
- **A clean persistence upgrade boundary**: released schema v2-v4 saves and
  released DAT companion records import into the replacement database without
  modifying the source files. Tester-only v5-v9 databases are refused; testers
  must restore a public backup or create a new world instead of carrying the
  unreleased persistence lineage forward.

## What Integration Looks Like
Integrating Tamework is usually a content-authoring workflow, not a programming workflow. Mods can use it in two ways:

### Required Dependency
- Wire your desired Tamework behavior directly into your NPC, item, config, and other server-side assets.
- Add configs for the Tamework systems you want to support.
- Add Alec's Tamework as a required dependency when deploying to CurseForge.

### Optional Dependency
- Keep your base assets clean of any references to Tamework functionality.
- Create asset patches under `Server/Patchwork/Patches` that add Tamework actions, interactions, configs, and other JSON-based behavior only when Tamework is installed. Use `Targets` for shared operations across several assets and `When.ModInstalled` for optional cross-mod gates. The legacy `Server/Tamework/Patches` root remains readable while Tamework is installed, but new integrations should use the neutral Patchwork root.
- Add configs for the Tamework systems you want to support.
- Declare Patchwork as a dependency for asset-only patch packs. Declare Alec's Tamework too when the patch uses Tamework macros or behavior.

### Optional example asset pack

The Tamework plugin jar does not include sample NPCs, sample items, or sample
progression configs. This keeps a library-only installation dormant until a
downstream mod supplies matching assets.
Reusable framework media, including the Nametag and Soul Lantern asset
closures, remain in the main jar so dependent mods can reference them without
installing the examples pack.

Build the optional examples with `./gradlew exampleAssetPack`. Install the
generated `Alec's Tamework! Examples v<version>.zip` beside the Tamework jar
and explicitly enable it only when you need the sample graph. The pack is
disabled by default, declares Tamework as a dependency, and can be omitted
from production servers.

In both cases, no Java is required: install the optional example pack when you
need a starting point, then copy and adapt its assets. Enable the systems you
want through comprehensive configs, and polish. The full setup and
implementation details can be found in the wiki.

Advanced integrations should use the Public API for canonical profiles and
namespaced profile extension data instead of writing Tamework metadata or
SQLite rows directly. Always check the relevant capability before using an
optional API surface.

Tamework also builds one immutable runtime activation plan at startup. It
installs no systems, feature listeners, workers, or database runtime for an
unused module. See [Runtime Activation](docs/Runtime-Activation.md) for
automatic evidence, restart-bound reloads, `/tw activation`, and the
Runeteria/RuneProfessions activation contract.

Player-facing Tamework config strings support `server.lang` keys. Built-in talents, traits, command labels, interaction messages, happiness labels, and major UI labels use language keys so translation packs can override copy without changing behavior assets.

## Documentation
- [Wiki Home](https://wiki.hytalemodding.dev/mod/alecs-tamework)
- [Player Guides](https://wiki.hytalemodding.dev/mod/alecs-tamework/player-guides)
- [Modder Documentation](https://wiki.hytalemodding.dev/mod/alecs-tamework/modder-documentation)
- [Developer Documentation](https://wiki.hytalemodding.dev/mod/alecs-tamework/developer-documentation)
- [HyDragon / API 0.9 Integration Guide](https://wiki.hytalemodding.dev/mod/alecs-tamework/hydragon-integration-guide)

## Licensing
Tamework is source-available under [Alec's Tamework Source Available License 1.0](LICENSE.txt). Unmodified dependency use and example/template reuse are allowed under the public license.

Custom private plugins, private forks, or server-specific adaptations that copy or derive from Tamework systems require separate written permission. See [Private Server Licensing Template](PRIVATE-SERVER-LICENSE-TEMPLATE.md) for the starting deal structure.

## Issue Reporting
If you run into a bug, integration issue, or behavior problem, report it in the Discord server:

https://discord.gg/E8n8RgTTdq

<H2>Telemetry</H2>
<p><a href="https://www.curseforge.com/hytale/mods/alecs-tamework">Alec's Tamework</a> uses <a href="https://wiki.hytalemodding.dev/mod/beacon/migrate-to-beacon-2-0">Beacon</a> for its own crash, error, performance, usage, and automatic persistence diagnostics. The Patchwork runtime embedded in Tamework reports Patchwork operations through a separate hosted-only project.</p>
<p>Automatic Tamework persistence diagnostics contain a bounded, redacted debug database ZIP and a safe error classification. They exclude the SQLite database, save data, player identity, coordinates, inventory payloads, secrets, exception messages, and unrestricted logs. Set <code>telemetry.enabled</code> to <code>false</code> in Tamework's global settings, or disable <code>Diag</code> in <code>/beacon consent</code>, to opt out. <code>Diag</code> is separate from Error consent. Existing projects that already reviewed consent must select Save and Close in <code>/beacon consent</code> before Diagnostics can run.</p>
<p>Automatic aggregate Tamework and Patchwork telemetry does not include personally identifiable information and is used to diagnose issues and improve the mods. Reports you submit manually can include the contact text and server-log attachments you choose to send; review those fields and files before submitting.</p>
<p>Tamework and Patchwork have independent consent entries, so disabling Tamework telemetry does not implicitly disable Patchwork telemetry. You may change either entry at any time in the `/beacon consent` menu.</p>
<p>Alec's Tamework also reports anonymized active user numbers to <a href="https://hstats.dev/">HStats</a> to track active user count summaries.</p>
<br />
