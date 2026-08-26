---
title: "Command HUD Renderer and Contributor API Reference"
order: 21
published: true
draft: false
---
# Command HUD Renderer and Contributor API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.12.0`)**
> This page describes the current `TameworkApi.commandHud()` contract. The
> contract can change while the API is experimental.

`CommandHudApi` exposes two independent presentation surfaces:

- `TARGET`: the HUD for the NPC under the player's crosshair; and
- `HOTSWAP`: the equipped command item's LMB, RMB, Q, E, and R strip.

Each surface has its own renderer selection, contributor list, session, and
standard Tamework fallback. A renderer selected for one surface does not change
the other surface.

## Capability and API checks

Check both capabilities before registering HUD integrations. Also check the
facade because older or degraded API adapters fail closed:

```java
Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin == null ? null : plugin.getApi();
if (api == null) return;

EnumSet<TameworkApiCapability> required = EnumSet.of(
        TameworkApiCapability.COMMAND_HUD_RENDERERS,
        TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS
);
if (!api.getCapabilities().containsAll(required)
        || !api.commandHud().available()) {
    return;
}

CommandHudApi hud = api.commandHud();
```

`COMMAND_HUD_RENDERERS` permits target and hotswap renderer registration.
`COMMAND_HUD_CONTRIBUTORS` permits target and hotswap presentation
contributors. The current Tamework implementation advertises the two
capabilities together. Do not infer support from `getApiVersion()` or from a
class being present.

## Command-item configuration

The effective `TwCommandItemConfig` selects each surface separately:

```json
{
  "TargetHudRendererId": "runeteria:husbandry_target",
  "TargetHudContributors": [
    { "Id": "runeteria:husbandry", "Required": true },
    { "Id": "runeteria:seasonal_badge", "Required": false }
  ],
  "HotswapHudRendererId": "runeteria:husbandry_hotswap",
  "HotswapHudContributors": [
    { "Id": "runeteria:husbandry", "Required": false }
  ]
}
```

The exact fields are:

| Config field | Meaning |
| --- | --- |
| `TargetHudRendererId` | Namespaced renderer for the target HUD. Blank or omitted selects the standard target HUD. |
| `TargetHudContributors` | Ordered target contributor requirements. |
| `HotswapHudRendererId` | Namespaced renderer for the equipped-tool hotswap HUD. Blank or omitted selects the standard hotswap HUD. |
| `HotswapHudContributors` | Ordered hotswap contributor requirements. |
| contributor `Id` | Namespaced contributor ID. |
| contributor `Required` | Whether an unavailable or incompatible contributor must cause standard fallback. |

Renderer and contributor IDs are normalized lowercase namespaced IDs. The
`tamework:` namespace is reserved. An explicit empty contributor list clears an
inherited list. The fields inherit independently from command config parents.

If a selected renderer is not registered, is invalid, or cannot be created,
that surface uses the standard Tamework HUD. If a required contributor is
missing, incompatible, or fails composition, that surface also uses its
standard HUD. An optional contributor is retained as an unavailable or
unsupported contribution and the custom HUD continues.

## Registration

Register renderers and contributors with independent exact-generation handles:

```java
CommandHudRegistration targetRenderer = null;
CommandHudRegistration hotswapRenderer = null;
CommandHudRegistration targetContributor = null;
CommandHudRegistration hotswapContributor = null;

void register(CommandHudApi hud) {
    CommandHudRegistrationResult target = hud.registerTargetRenderer(
            "runeteria:husbandry_target",
            new CommandHudRendererDescriptor(Set.of("runeteria:husbandry")),
            ignored -> new HusbandryTargetController());
    if (target.registered()) targetRenderer = target.registration();

    CommandHudRegistrationResult hotswap = hud.registerHotswapRenderer(
            "runeteria:husbandry_hotswap",
            new CommandHudRendererDescriptor(Set.of("runeteria:husbandry")),
            ignored -> new HusbandryHotswapController());
    if (hotswap.registered()) hotswapRenderer = hotswap.registration();

    CommandHudRegistrationResult targetData = hud.registerTargetContributor(
            "runeteria:husbandry",
            new CommandHudContributorDescriptor(Set.of("runeteria:husbandry")),
            HusbandryTargetContributor::new);
    if (targetData.registered()) targetContributor = targetData.registration();

    CommandHudRegistrationResult hotswapData = hud.registerHotswapContributor(
            "runeteria:husbandry",
            new CommandHudContributorDescriptor(Set.of("runeteria:husbandry")),
            HusbandryHotswapContributor::new);
    if (hotswapData.registered()) hotswapContributor = hotswapData.registration();
}

void unregister() {
    if (targetContributor != null) targetContributor.close();
    if (hotswapContributor != null) hotswapContributor.close();
    if (targetRenderer != null) targetRenderer.close();
    if (hotswapRenderer != null) hotswapRenderer.close();
    targetContributor = null;
    hotswapContributor = null;
    targetRenderer = null;
    hotswapRenderer = null;
}
```

`CommandHudRegistrationResult.Status` is `REGISTERED`, `CONFLICT`, `INVALID`,
or `UNAVAILABLE`. A conflict does not replace the live registration. A handle
closes only its own generation, and `close()` is idempotent. Use explicit
descriptors for new integrations. The no-descriptor overloads are unrestricted
compatibility overloads.

`CommandHudRendererDescriptor` accepts contributor namespaces or exact IDs.
`CommandHudContributorDescriptor` declares the data namespaces or exact IDs
that the contributor supplies. A renderer must declare support for the
contributor's ID and its declared data namespaces. Use an unrestricted
descriptor only when the renderer truly supports arbitrary contributor data.

## Renderer lifecycle

Implement one controller per custom HUD session:

```java
final class HusbandryTargetController implements CommandTargetHudController {
    @Override
    public void buildInitial(
            CommandHudOpenContext context,
            CommandTargetHudView view,
            UICommandBuilder commands
    ) {
        commands.append("Rune_UI/HusbandryTarget.ui");
        renderAll(view, commands);
    }

    @Override
    public void update(
            CommandTargetHudUpdate update,
            UICommandBuilder commands
    ) {
        if (update.fullRefresh()) {
            renderAll(update.view(), commands);
            return;
        }
        if (update.changeSet().changed(CommandTargetHudChangeSet.Section.VITALS)) {
            renderVitals(update.view().snapshot().vitals(), commands);
        }
        if (update.changeSet().changed(CommandTargetHudChangeSet.Section.CONTRIBUTIONS)) {
            renderContributions(update.view(), commands);
        }
    }

    @Override
    public void close() {
        // Release renderer-local state and listeners.
    }
}
```

The hotswap controller uses the same lifecycle with
`CommandHotswapHudController`, `CommandHotswapHudView`, and
`CommandHotswapHudUpdate`. `CommandHudOpenContext` is detached data. It can
contain `playerUuid`, `language`, `toolId`, `itemId`, `configId`, `surface`,
`rendererId`, `targetUuid`, `targetKey`, and `sessionGeneration`. It does not
provide a live player, ECS store, item stack, or gameplay authority.

UI files belong below `Common/UI/Custom`. Paths passed to
`UICommandBuilder.append(...)` are relative to that directory. For example,
`Common/UI/Custom/Rune_UI/HusbandryTarget.ui` is appended as
`Rune_UI/HusbandryTarget.ui`; do not include `Common/UI/Custom/` in the path.

The target HUD is active while the player holds a registered command item and
looks at a supported NPC. The hotswap HUD is active while the matching command
item is equipped. Tamework binds each session to the exact target or equipped
tool. A target change, tool change, unequip, world transfer, player unload,
store removal, config change, renderer failure, or unregister closes the old
session before a new session can render.

## Detached snapshots

The host sends a complete immutable view on every initial build and update.
The view contains the base snapshot and an immutable map of isolated
`CommandHudContribution` values keyed by `CommandHudContributorId`.

### Target snapshot

`CommandTargetHudSnapshot` exposes:

- `targetUuid` and `targetKey`;
- `displayName`, `speciesId`, `speciesLabel`, `gender`, and
  `lifecycleStatus`;
- `vitals`: current and maximum health, happiness, hunger, and thirst, plus
  `targetHappinessPercent`;
- `happinessModifierBreakdown`;
- `cooldowns.harvest` and `cooldowns.breeding`, each with `active`,
  `remainingMillis`, `ratio`, and `known`;
- `favoriteFood` and `compatibleFoods`, whose rows contain `itemId`,
  `displayName`, `iconPath`, and optional `happinessDelta`;
- `attachments`, whose rows contain `setLabel` and `valueLabel`;
- `tameRequirement` with `tranquilizerRequired`, `requiredStacks`, and
  `currentStacksText`;
- `progression` with `level`, `experience`, `experienceToNextLevel`,
  `availableTalentPoints`, `maxLevel`, `atMaxLevel`,
  `tooltipHeaderText`, and `tooltipText`;
- `traits`, whose rows contain `id`, `label`, `iconPath`, `iconText`,
  `tooltipText`, `fillRatio`, `counterClockwise`, and `belowDefault`; and
- `ownerDisplayName`.

Nullable values mean that the source does not have that value. Lists are
detached immutable lists. Ratios are finite and bounded to the supported
range by Tamework.

### Hotswap snapshot

`CommandHotswapHudSnapshot` exposes `primary`, `secondary`, `q`, `e`, `r`, and
`groupStatus`.

Each `Slot` contains `visible`, `bindingLabel`, `iconTexturePath`, and
`fallbackGlyph`. Each `GroupStatus` contains `visible`, `label`, and
`colorHex`. An unassigned slot is hidden. The standard snapshot still gives a
custom renderer every fixed slot, so a renderer can choose a different layout.

### Contributions

`CommandHudContribution.data()` is a contributor-local map of string paths to
`CommandUiValue`. Values can be `STRING`, `BOOLEAN`, `LONG`, `DOUBLE`, `LIST`,
or `OBJECT`. Read one value with `contribution.value(path)`. The contribution
status is one of `AVAILABLE`, `UNAVAILABLE`, `FAILED`, or
`UNSUPPORTED_BY_RENDERER`.

Contributors return `CommandHudContribution.available(id, data)` when data is
ready, or the `unavailable`, `failed`, and `unsupported` factories when it is
not. Diagnostic reasons are safe human-readable text. Do not put live Hytale
objects, action tokens, or private server state in a contribution.

## Contributors and focused invalidation

Register a target contributor with `CommandTargetHudContributorProvider` or a
hotswap contributor with `CommandHotswapHudContributorProvider`. Each provider
creates one session contributor from `CommandHudContributorCreateContext`:

```java
final class HusbandryTargetContributor
        implements CommandTargetHudSessionContributor {
    private final CommandHudContributorId id;
    private final CommandHudContributorDirtySink dirty;

    HusbandryTargetContributor(CommandHudContributorCreateContext context) {
        id = context.contributorId();
        dirty = context.dirtySink();
    }

    @Override
    public CommandHudContribution compose(
            CommandTargetHudSnapshot base,
            CommandHudContribution previous,
            CommandHudDirtyScope scope
    ) {
        return CommandHudContribution.available(id, Map.of(
                "ready", CommandUiValue.of(isReady(base)),
                "label", CommandUiValue.of("Husbandry")
        ));
    }

    @Override
    public void close() {
        // Release listeners and timers.
    }
}
```

The context supplies the detached open context, contributor ID, exact
`registrationGeneration`, and `dirtySink`. `compose` receives the complete
base snapshot, the previous contribution when one exists, and a bounded
`CommandHudDirtyScope`. Return the complete current contribution for the
affected namespace, even when the scope is focused.

Call the sink when contributor data changes:

- `markPathsDirty(Set<String>)` marks exact contributor-local paths;
- `markAllDirty()` marks the complete contributor namespace.

Paths are normalized by removing leading and trailing `/` characters. They do
not address the renderer's UI selectors. A path set is bounded to 256 paths;
overflow becomes a full contributor refresh. A contributor cannot mark another
contributor's namespace.

## Focused change hints and partial updates

`CommandTargetHudUpdate` and `CommandHotswapHudUpdate` always contain the
complete current view. `previousView()` can be used for diff-aware rendering.
The change set is a hint. A renderer can ignore it and rebuild everything, or
update only the changed selector. Tamework submits the update with
`clear=false`, so untouched UI elements remain in place.

Target change sets use these exact `Section` values:

`IDENTITY`, `VITALS`, `COOLDOWNS`, `FOOD`, `ATTACHMENTS`,
`TAME_REQUIREMENTS`, `PROGRESSION`, `TRAITS`, `OWNER`, and `CONTRIBUTIONS`.

Hotswap change sets use these exact `Slot` values:

`PRIMARY`, `SECONDARY`, `Q`, `E`, and `R`, plus the
`groupStatusChanged()` flag.

Use `CommandTargetHudChangeSet.of(Set<Section>)` or
`CommandHotswapHudChangeSet.of(Set<Slot>, boolean)` for base changes. Use
`contributorPaths(contributorId, paths)`, `fullContributor(contributorId)`, or
`contributorScopes(paths, fullRefreshContributors)` for contributor changes.
`full()` requests every base and contributor region. Each contributor can
inspect `changeSet().scopeFor(id)` or use its `CommandHudDirtyScope` argument.

## Fallback, timing, and cleanup

Fallback is scoped to the selected surface. A target renderer failure does not
replace a valid hotswap renderer. Required contributor failure falls back to
the standard HUD for that surface. Optional contributor failure leaves the
custom HUD open and reports its status. A later renderer or required
composition failure closes the custom session and prevents stale updates.

The session is closed when the exact target or equipped tool is no longer
valid, when the player or store unloads, when the world changes, when config
selection changes, or when a renderer or contributor registration generation
is closed. Controllers and contributors must release local listeners in
`close()`. Tamework closes the host, controller, contributors, and any pending
composition state in order. Updates that arrive after close or generation
change are ignored.

Contributor composition is measured. A callback taking more than 10 ms counts
as slow. Tamework emits at most one warning for a contributor in 60 seconds.
Keep composition detached, bounded, and fast. Do not perform blocking I/O or
world-thread work from `compose`.

## Diagnostics

`api.commandHud().diagnostics()` returns an immutable, value-only
`CommandHudDiagnostics` snapshot. It includes:

- live target and hotswap renderer IDs and exact generations;
- live target and hotswap contributor IDs and exact generations;
- active session IDs, surface, renderer, item/config identity, and safe failure
  state;
- contributor status, compose count, total/last/slow compose times, and safe
  failure reason; and
- process-local slow-composition and throttled-warning counts.

Diagnostics never expose action tokens, mutable runtime objects, raw item
stacks, private contribution values, or exception objects. Use the snapshot to
diagnose registration conflicts, fallback, unavailable contributors, and
slow composition without coupling to Tamework internals.

## Version 0.12.0 non-goals

This API is passive in v1. It does not add custom HUD event handlers, custom
server actions, confirmation flows, or multi-step UI flows. It only lets a Java
plugin render command target and equipped-tool HUD presentation data. Use the
separate `commandUi()` API for command-menu actions and flows. A future HUD
version may add interactive controls after a separate capability and authority
contract is defined.

## Related pages

- [Register Custom Command HUDs Recipe](/mod/alecs-tamework/register-custom-command-huds-recipe)
- [API Bootstrap and Capability Checks](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)
- [Command UI Renderer and Contributor API Reference](/mod/alecs-tamework/command-ui-provider-api-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [Command Items](https://github.com/AlecHilles/Tamework/blob/main/docs/Command-Items.md)
