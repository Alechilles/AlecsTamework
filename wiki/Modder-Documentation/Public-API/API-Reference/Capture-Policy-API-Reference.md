---
title: "Capture Policy API Reference"
order: 14
published: true
draft: false
---
# Capture Policy API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> Use this surface only when `CAPTURE_POLICY` is advertised.

Capability: `CAPTURE_POLICY`

## Config reads

`TameworkApi.configs()` exposes immutable, revisioned capture views separately
from the base `SpawnerConfigView`:

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

## Integration rules

- `ChanceMode: Guaranteed` preserves deterministic capture and bypasses role
  capture policy, including custom requirements.
- `ChanceMode: Probability` is the explicit opt-in.
- Never roll chance in a downstream plugin and then call deterministic capture.
- Treat a missing capability, missing handler, or invalid live evidence as
  denial.
- `SuccessDisposition: CapturedItem` preserves the ordinary filled-item result.
- `SuccessDisposition: TameAndCommandLink` commits taming and command-family
  roster membership through one canonical operation and requires
  `CAPTURE_TAME_AND_LINK`.
- `SourceConsumption: ResolvedAttempt` consumes the configured source exactly
  once after either terminal success or terminal denial and requires
  `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`. Retryable or unresolved attempts do
  not consume the source.

## Related pages

- [TwCapturePolicyConfig Reference](/mod/alecs-tamework/twcapturepolicyconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [Interaction Extensions API Reference](/mod/alecs-tamework/interaction-extensions-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)
