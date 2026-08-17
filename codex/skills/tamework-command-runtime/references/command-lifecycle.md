# Command Lifecycle

Trace each affected entry point through this table.

| Stage | Questions |
| --- | --- |
| Definition | Does the action already exist in config, an enum, localization, or assets? |
| Assignment | Who can assign it, and where is that assignment stored? |
| Presentation | Which prompt, radial, HUD, or panel advertises it? |
| Session | Which page, roster, player, and revision produced the event? |
| Target | Is authority generic live selection or a bonded durable profile? |
| Execution | Which focused service performs the effect on the world thread? |
| Feedback | Which message, HUD, or panel refresh confirms or rejects it? |
| Cleanup | What happens on target loss, unload, world change, cooldown, or closure? |

## Authority Rules

- A client event is a request, not proof of permission.
- A panel row is a presentation snapshot, not current target authority.
- A bonded roster must reject forged generic linked-record events.
- A generic linked target must not gain bonded lifecycle authority.
- The server must re-resolve the player, target, capability, and relevant
  revision before mutation.

## Useful Starting Points

Verify all names in current source:

- `CommandItemFeatureHandler` and `CommandItemUseOrchestrator`
- `CommandHotswapAction` and `CommandHotswapAssignmentStore`
- `CommandSelectionPageService` and `CommandSelectionRosterEventBoundary`
- `BondedCompanionPanelActionRouter` and
  `BondedCompanionPanelActionService`
- `CommandTargetHudService`, `CommandHotswapHudService`, and linked-panel
  refresh classes
