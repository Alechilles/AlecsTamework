# Command Item Hotswaps Design

## Goal

Let players assign three commands to the Q, E, and R ability keys on each
individual command flute. These hotswaps make common commands immediately
available and allow a flute to define commands that do not occupy one of the
eight radial-menu positions.

## Scope

- Preserve existing interactions: primary use continues to link/unlink and
  secondary use continues to open the command radial.
- Add three generic custom actions, one each for the Q, E, and R ability
  slots, following the Flightmaster's Talisman custom-action pattern.
- Store hotswap assignments in command-item metadata, scoped to that exact
  flute's existing stable tool identity.
- Add command visibility control so hotswap-only commands are available to
  hotswaps but absent from the radial.
- Add selectors to the radial menu and an equipped-flute HUD in the bottom
  right.

Out of scope for this iteration: remappable hotswap keys, gamepad bindings,
more than three hotswaps, radial layout expansion, and cross-flute assignment
sharing.

## Command Configuration

`TwCommandItemConfig.CommandEntry` gains an optional `ShowInRadial` boolean.

- It defaults to `true`, preserving all existing command configs.
- Commands with `ShowInRadial: true` are eligible for the existing eight-slot
  radial menu.
- Commands with `ShowInRadial: false` are hotswap-only. They do not consume a
  radial slot, but remain normal configured commands for validation, dropdown
  selection, feedback, targeting, recipient selection, cooldowns, and steps.
- The existing stable `Id` identifies a command everywhere. Display labels and
  icons are presentation only.

The radial continues to display at most eight visible commands. New flute
configs place any command beyond that practical limit behind
`ShowInRadial: false`; no second radial paging model is introduced.

## Per-Flute Assignment Storage

Each command flute stores three optional command IDs in its own metadata:

| Ability slot | Metadata assignment |
| --- | --- |
| Q | Q command ID |
| E | E command ID |
| R | R command ID |

Assignments are bound to the flute's stable tool ID, not a player profile,
command family, or another copy of the item. Copies therefore have independent
layouts. All assignments are initially unset.

Every configured command is selectable for every slot. The same command may be
assigned to one, two, or all three slots; the selector does not dynamically
remove choices.

If a later config reload removes an assigned command ID, the metadata is left
intact. The matching hotswap safely refuses to dispatch and reports that its
assignment is unavailable. Opening the selector lets the player replace it.

## Custom Action Contract

The feature adds three generic command-item custom actions, conceptually
`CommandHotswapQ`, `CommandHotswapE`, and `CommandHotswapR`. A command flute
binds these to its Q, E, and R ability entries the same way the Flightmaster's
Talisman binds its custom actions.

On activation, each action:

1. Resolves the active held stack and its command-item configuration.
2. Confirms the active held stack is the flute whose metadata it will read.
3. Looks up the assignment for its fixed slot.
4. Invokes the existing command-use orchestration with that stored command ID
   as the command override.

The client action identifies only the fixed Q, E, or R slot. It never sends an
arbitrary command ID for server execution. Existing command resolution remains
the authority for target acquisition, recipient eligibility, cooldowns, command
steps, state changes, relocation, and player feedback.

An unassigned slot reports the existing-style no-command feedback. If the
flute is no longer held, the assignment cannot resolve, or the configured
command was removed, the action performs no command dispatch. The custom
actions are available only through the equipped flute's ability entries, so
they do not replace normal key behavior while another item is held.

## UI

### Radial configuration column

`TameworkCommandRadialMenu.ui` gains a narrow Hotswaps column to the right of
the existing wheel. It has three rows, labeled Q, E, and R.

Each row contains a dropdown with:

- `Unassigned` as the empty value; and
- all commands in the active flute's `CommandList`, whether radial-visible or
  hotswap-only.

Changing a value persists the corresponding metadata assignment immediately
and refreshes the displayed hotswap state. Dropdown labels use the same
localized display-name resolution as the current command selection UI.

### Equipped-command HUD

While a command flute is actively held, a compact bottom-right HUD shows each
assigned ability as a Q, E, or R keycap followed by its command icon. Unset
slots have no indicator. Hover text includes the key and localized command
name.

The HUD uses the existing `CommandEntry.Icon` value. A neutral bundled fallback
icon is rendered when a configured command lacks an icon or its icon cannot be
resolved; cosmetic presentation must not prevent dispatch. The HUD hides as
soon as another item becomes active.

## Compatibility and Failure Behavior

- Existing command configs preserve their wheel behavior because
  `ShowInRadial` defaults to `true`.
- Existing item metadata remains valid; missing hotswap fields mean every slot
  is unassigned.
- Primary linking and secondary menu opening are unchanged.
- Hotswap-only commands receive no radial button or label, but can be assigned
  and executed normally.
- Missing assignment, stale command ID, inactive held flute, or invalid action
  context fails safely without mutating command state.
- Existing command cooldown and denial feedback remain the source of truth
  after a valid assignment is dispatched.

## Architecture Boundaries

- **Configuration:** `TwCommandItemConfig` owns command radial visibility;
  no duplicate hotswap command list is introduced.
- **Metadata:** a small dedicated assignment store reads and writes only the
  three per-tool command IDs. It does not own command execution.
- **Input:** the three custom-action handlers map a fixed ability slot to an
  assignment. They do not contain recipient or step logic.
- **Execution:** `CommandItemUseOrchestrator` remains the sole path for
  command resolution and dispatch.
- **Presentation:** a selector option source exposes all commands for the
  hotswap dropdowns, while the existing radial option source filters to
  `ShowInRadial` commands and retains its eight-button cap. The HUD is a small
  read-only projection of active-flute metadata plus command presentation.

## Validation and Verification

The implementation should add focused behavior tests only where they protect
these regressions:

1. A hotswap-only command is eligible for assignment and execution but is not
   supplied to the radial renderer.
2. Assignments persist independently for two flute tool identities and resolve
   only from the currently held flute.
3. Each custom action dispatches the command stored for its own fixed slot and
   refuses to dispatch when no valid active flute assignment exists.

Run the normal command-item test coverage plus `bash ../gradlew
:alecstamework:test`. Perform a live verification using a flute with at least
one radial command and one hotswap-only command:

1. Confirm primary and secondary use retain their current link/menu behavior.
2. Assign Q, E, and R, including a deliberate duplicate assignment.
3. Confirm the wheel excludes the hotswap-only command and the selectors
   include it.
4. Confirm the bottom-right HUD shows the assigned keycaps and icons only
   while that exact flute is held.
5. Trigger each ability against valid recipients, a denied state, and an
   unassigned slot; confirm existing command semantics and feedback apply.
6. Switch to another flute and verify its independent assignments and HUD are
   used.

## Documentation and Release Notes

Update `docs/Command-Items.md` with `ShowInRadial`, per-flute Q/E/R
assignments, and the custom-action item wiring. Add concise player-facing
release notes to `CHANGELOG.md` when the feature ships.
