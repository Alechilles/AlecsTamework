# Roster Capture Feedback Design

## Goal

Make roster-based companion capture feel conclusive on success and make every
capture denial tell the player the exact reason it was denied.

## Scope

- A successful `TAME_AND_COMMAND_LINK` capture shows a prominent two-line
  notification after the roster mutation is confirmed:
  - primary: `<NPC name> captured`
  - secondary: `<NPC name> has been added to your <command item name>`
- The command item name comes from the resolved command-family configuration,
  not an internal roster or family identifier. A stable fallback is used if no
  player-facing item name is available.
- Every capture denial, including early interaction checks and asynchronous
  roster-admission failures, maps to one precise player-safe message. The
  message identifies the failed prerequisite where meaningful, such as the
  capture item being below the target's required power.
- Existing successful captured-item behavior is unchanged.

## Design

### Denial facts and presentation

Capture evaluation already has the facts needed to decide whether an attempt
may proceed. The implementation will keep those facts structured until the
presentation boundary instead of reducing them to a generic denial early.
A small mapper will convert each recognized reason code and its available
context into a localized player message. The same mapper will be used by each
capture entry point so channel, immediate, and delayed outcomes agree.

Unknown or infrastructure failures will retain a safe generic message; they
will not claim a specific gameplay cause. Logs and telemetry retain the exact
diagnostic code for support.

### Successful roster capture

Once the durable tame-and-link operation and roster membership have succeeded,
the capture presentation service resolves the NPC display name and the
command-item display name. It sends the built-in notification with primary and
secondary messages, using the prominent success style already supported by the
client notification channel. The notification is emitted once per committed
operation, so retries and recovery do not duplicate it.

### Localisation

New messages live in the existing server language files. Parameterized text
keeps entity, item, and command-item names separate from the sentence, so all
supported locales can translate the surrounding grammar. Existing exact
messages for requirements such as tranquillization and required effects are
reused where they already fully explain the denial.

## Error Handling

- Missing NPC or command-item display data falls back to a safe localized
  generic label rather than an identifier or null text.
- A notification transport failure never changes the committed capture result.
- A message-mapping miss uses the existing generic denial wording and preserves
  the original diagnostic reason in logs.

## Testing

- Unit tests cover success text composition, including NPC and command-item
  display names and fallbacks.
- Unit tests cover representative denial mappings, especially insufficient
  capture power, required effects, ownership, and roster capacity.
- Capture-route tests prove the shared presentation receives both immediate and
  asynchronous denials without changing capture state.
- The full Maven test suite and the project's player-access safety grep run
  before completion.

## Non-goals

- Redesigning the base-game notification UI or its artwork.
- Changing capture chance, power formulas, ownership rules, or roster capacity
  behavior.
- Altering captured-item success notifications.
