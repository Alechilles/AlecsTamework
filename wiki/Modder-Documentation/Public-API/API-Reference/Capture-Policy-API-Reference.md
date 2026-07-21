---
title: "Capture Policy API Reference"
order: 14
published: true
draft: false
---
# Capture Policy API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> This surface is usable only when `CAPTURE_POLICY` is advertised. The 0.9
> types and default methods may exist while the packaged runtime still denies
> capture-policy work.

Tamework 3.0.0 advertises this capability only after bounded capture-attempt
journal recovery succeeds. Before that point, or when recovery fails, the
surface stays unavailable and gameplay capture fails closed before entropy or
mutation.

Capability: `CAPTURE_POLICY`

## Config reads

`TameworkApi.configs()` adds immutable, versioned reads without changing the
constructor of the API 0.8 `SpawnerConfigView`:

- `getSpawnerCaptureMechanicsById(String id)`
- `resolveSpawnerCaptureMechanicsForItemId(String itemId)`
- `getCapturePolicyById(String id)`
- `resolveCapturePolicyForRole(String roleId)`

`SpawnerCaptureMechanicsView` exposes the item-side mode, power, base chance,
chance-per-power, clamps, failure cooldown, and optional failure feedback.
`CapturePolicyConfigView` exposes the resolved role-side minimum power,
resistance, multiplier, missing-health bonus, guaranteed power, and immutable
custom requirements. Every view includes its config revision.

When the capability is absent, these default reads return `Optional.empty()`.

## Capture requirement extensions

`InteractionExtensionApi` adds:

- `registerCaptureRequirement(String id, CaptureRequirementHandler handler)`
- `listCaptureRequirementIds()`

Handlers are side-effect-free eligibility checks. Tamework may invoke a handler
during initial eligibility and final revalidation, so it must produce the same
decision from the supplied immutable context. Missing handlers, exceptions, or
an extension-generation change deny capture.

Close every registration handle during plugin shutdown. Use a namespaced ID;
the `tamework:` namespace is reserved.

## Outcome event

`CaptureAttemptResolvedEvent` is a post-durability notification for one
resolved roll. `FAILED_ROLL` means the NPC, owner, role, and source item remain
unchanged; it does not authorize a second entropy sample. Precondition and
capability denials occur before a capture attempt resolves and do not masquerade
as a failed roll.

## Integration rules

- `ChanceMode: Guaranteed` preserves deterministic capture and bypasses role
  capture policy, including custom requirements.
- `ChanceMode: Probability` is the explicit opt-in.
- Reuse the same attempt/idempotency identity across retries.
- Never roll chance in a downstream plugin and then call deterministic capture.
- Treat a missing capability, missing handler, unavailable persistence, or
  invalid live evidence as denial.

## Operator diagnostics

`/tw diagnose capture-attempt <id>` performs one exact, read-only journal
lookup. It emits at most five bounded lines: state/outcome/recovery, pinned
config revisions, formula inputs, operation/population correlation, and
cooldown/quarantine/incident evidence. Entropy and actor/owner UUIDs are never
printed. The base `/tw diagnose` overview is capped at eight lines and includes
capture-attempt state counters plus the duplicate callbacks observed since the
current server boot. Neither command retries, repairs, or otherwise mutates an
attempt.

## Related pages

- [TwCapturePolicyConfig Reference](/mod/alecs-tamework/twcapturepolicyconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [Interaction Extensions API Reference](/mod/alecs-tamework/interaction-extensions-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)
