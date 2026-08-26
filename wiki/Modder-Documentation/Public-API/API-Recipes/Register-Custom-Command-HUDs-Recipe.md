---
title: "Register Custom Command HUDs Recipe"
order: 25
published: true
draft: false
---
# Register Custom Command HUDs Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Public API](/mod/alecs-tamework/public-api)

Goal: let a Java plugin replace the target HUD, the equipped command-item
hotswap HUD, or both with a bespoke layout. Tamework continues to own the
snapshot, lifecycle, fallback, and server state.

This is the experimental `0.12.0` API. It is passive: it does not add custom
HUD actions, event handlers, or flows.

## 1. Check capabilities

Resolve the API from Tamework and fail closed when the HUD surface is absent:

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

Require only the capabilities used by your plugin. A renderer-only integration
can require `COMMAND_HUD_RENDERERS`; a contributor integration also requires
`COMMAND_HUD_CONTRIBUTORS`. Check `available()` at registration time. Do not
use the API version as an authorization check.

## 2. Register a renderer and contributor

Use a separate registration call for each surface. Keep every successful exact
generation handle as plugin state:

```java
private CommandHudRegistration targetRenderer;
private CommandHudRegistration hotswapRenderer;
private CommandHudRegistration targetContributor;

void registerHud(CommandHudApi hud) {
    CommandHudRegistrationResult renderer = hud.registerTargetRenderer(
            "runeteria:husbandry_target",
            new CommandHudRendererDescriptor(Set.of("runeteria:husbandry")),
            HusbandryTargetController::new);
    if (renderer.registered()) {
        targetRenderer = renderer.registration();
    }

    CommandHudRegistrationResult contributor = hud.registerTargetContributor(
            "runeteria:husbandry",
            new CommandHudContributorDescriptor(Set.of("runeteria:husbandry")),
            HusbandryTargetContributor::new);
    if (contributor.registered()) {
        targetContributor = contributor.registration();
    }
}

void unregisterHud() {
    if (targetContributor != null) targetContributor.close();
    if (hotswapRenderer != null) hotswapRenderer.close();
    if (targetRenderer != null) targetRenderer.close();
    targetContributor = null;
    hotswapRenderer = null;
    targetRenderer = null;
}
```

The hotswap calls use the matching types:

```java
CommandHudRegistrationResult hotswapResult = hud.registerHotswapRenderer(
        "runeteria:husbandry_hotswap",
        new CommandHudRendererDescriptor(Set.of("runeteria:husbandry")),
        HusbandryHotswapController::new);
if (hotswapResult.registered()) {
    hotswapRenderer = hotswapResult.registration();
}

CommandHudRegistrationResult hotswapData = hud.registerHotswapContributor(
        "runeteria:husbandry",
        new CommandHudContributorDescriptor(Set.of("runeteria:husbandry")),
        HusbandryHotswapContributor::new);
```

Use the `registered()` check before storing a result's `registration()`. The
result status is `REGISTERED`, `CONFLICT`, `INVALID`, or `UNAVAILABLE`. A live
ID cannot be replaced by a later registration. `CommandHudRendererDescriptor`
and `CommandHudContributorDescriptor` accept exact IDs or namespaces. The
`tamework:` namespace is reserved.

Register the contributor before the renderer is used by a command config. On
shutdown, close contributors before their renderer. A registration handle is
idempotent and closes only its own generation.

## 3. Select each surface in command config

Add the exact fields to the effective `TwCommandItemConfig`:

```json
{
  "TargetHudRendererId": "runeteria:husbandry_target",
  "TargetHudContributors": [
    { "Id": "runeteria:husbandry", "Required": true }
  ],
  "HotswapHudRendererId": "runeteria:husbandry_hotswap",
  "HotswapHudContributors": [
    { "Id": "runeteria:husbandry", "Required": false }
  ]
}
```

The target and hotswap selections inherit and replace independently. An
explicit empty list clears inherited contributors. A config that omits a
renderer ID uses the standard HUD for that surface.

`Required: true` means that a missing, incompatible, or failed contributor
causes standard fallback for that surface. `Required: false` lets the custom
HUD continue with an `UNAVAILABLE`, `UNSUPPORTED_BY_RENDERER`, or `FAILED`
contribution. A missing or invalid renderer also falls back only on its own
surface. It cannot replace a valid custom renderer on the other surface.

## 4. Implement the renderer

The target renderer creates one `CommandTargetHudController` per session:

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
            renderContributors(update.view(), commands);
        }
    }

    @Override
    public void close() {
        // Stop local listeners and release renderer state.
    }
}
```

The hotswap renderer implements `CommandHotswapHudController` and receives
`CommandHotswapHudView` and `CommandHotswapHudUpdate` instead. Tamework sends
the complete current view on every update. The focused change set is a hint,
so a renderer can update only one card indicator or slot.

Put the UI asset at `Common/UI/Custom/Rune_UI/HusbandryTarget.ui`. The runtime
path is `Rune_UI/HusbandryTarget.ui`; do not repeat the
`Common/UI/Custom/` prefix in `UICommandBuilder.append(...)`.

## 5. Read the target snapshot

`CommandTargetHudSnapshot` is detached and immutable. It includes:

- target identity: `targetUuid`, `targetKey`, `displayName`, `speciesId`,
  `speciesLabel`, `gender`, and `lifecycleStatus`;
- `vitals`: current/max health, happiness, hunger, thirst, and
  `targetHappinessPercent`;
- `happinessModifierBreakdown`;
- `cooldowns.harvest` and `cooldowns.breeding`, each with `active`,
  `remainingMillis`, `ratio`, and `known`;
- `favoriteFood` and `compatibleFoods`, with `itemId`, `displayName`,
  `iconPath`, and optional `happinessDelta`;
- `attachments`, with `setLabel` and `valueLabel`;
- `tameRequirement`, with `tranquilizerRequired`, `requiredStacks`, and
  `currentStacksText`;
- `progression`, with `level`, `experience`, `experienceToNextLevel`,
  `availableTalentPoints`, `maxLevel`, `atMaxLevel`, `tooltipHeaderText`, and
  `tooltipText`;
- `traits`, with `id`, `label`, `iconPath`, `iconText`, `tooltipText`,
  `fillRatio`, `counterClockwise`, and `belowDefault`; and
- `ownerDisplayName`.

Nullable fields mean that the source has no value. Do not retain a live NPC,
player, item, or ECS object from a callback.

`CommandHotswapHudSnapshot` contains the five fixed slots and group status:
`primary`, `secondary`, `q`, `e`, `r`, and `groupStatus`. A slot has
`visible`, `bindingLabel`, `iconTexturePath`, and `fallbackGlyph`. Group status
has `visible`, `label`, and `colorHex`.

## 6. Add a namespaced contributor

Create a session contributor for the surface where it is registered:

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
        // Stop local listeners and release contributor state.
    }
}
```

Use `CommandHotswapHudSessionContributor` and
`CommandHotswapHudContributorProvider` for the hotswap surface. The contributor
context contains the detached open context, contributor ID, exact registration
generation, and a guarded dirty sink. The open context exposes `playerUuid`,
`language`, `toolId`, `itemId`, `configId`, `surface`, `rendererId`, target
identity when applicable, and `sessionGeneration`.

Return the complete current namespace from `compose`, even when the scope is
focused. `CommandHudContribution.data()` is a map of local string paths to
`CommandUiValue` values. Values support `STRING`, `BOOLEAN`, `LONG`, `DOUBLE`,
`LIST`, and `OBJECT`. The view isolates each contributor by its
`CommandHudContributorId`.

## 7. Use focused invalidation

Mark only the contributor paths that changed:

```java
dirty.markPathsDirty(Set.of("ready"));
```

Use `dirty.markAllDirty()` when the whole contributor namespace changed. Paths
are local data paths, not Hytale UI selectors. Tamework removes leading and
trailing `/` characters and retains at most 256 paths. Overflow becomes a full
contributor refresh.

Target renderers can check these exact sections:

`IDENTITY`, `VITALS`, `COOLDOWNS`, `FOOD`, `ATTACHMENTS`, `TAME_REQUIREMENTS`,
`PROGRESSION`, `TRAITS`, `OWNER`, and `CONTRIBUTIONS`.

Hotswap renderers can check these exact slots:

`PRIMARY`, `SECONDARY`, `Q`, `E`, and `R`, plus
`changeSet().groupStatusChanged()`.

`CommandTargetHudUpdate` and `CommandHotswapHudUpdate` also provide
`previousView()`, `view()`, `snapshot()`, `changeSet()`, and `fullRefresh()`.
The host submits focused updates with `clear=false`, so an update to one
indicator does not clear the rest of the page.

## 8. Respect lifecycle and diagnostics

Target sessions are tied to the exact aimed target. Hotswap sessions are tied
to the exact equipped command item. Tamework closes a session on target/tool
change, unequip, world transfer, player unload, store removal, config change,
renderer failure, or registration removal. Controllers and contributors must
release listeners in `close()`. Updates after close or generation change are
ignored.

Composition callbacks taking more than 10 ms count as slow. Tamework throttles
warnings to one per contributor per 60 seconds. Keep callbacks detached,
bounded, and fast. Do not block on I/O or access world state from `compose`.

Use `api.commandHud().diagnostics()` for redacted registration and session
state. The snapshot reports renderer/contributor IDs and generations, active
surfaces, contributor statuses, composition counts and timing, safe failure
reasons, and slow-warning counts. It does not expose action tokens, private
contribution values, mutable Hytale objects, or exception objects.

## Related pages

- [Command HUD Renderer and Contributor API Reference](/mod/alecs-tamework/command-hud-renderer-and-contributor-api-reference)
- [API Bootstrap and Capability Checks](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)
- [Command Items](https://github.com/AlecHilles/Tamework/blob/main/docs/Command-Items.md)
