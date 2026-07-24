# Configurable Capture Policy

Status: supported optional API/config surface

## Goal

Allow an item to opt into generic probabilistic capture while keeping the
ordinary deterministic filled-spawner behavior as the default.

## Contract

- `ChanceMode: Guaranteed` bypasses role capture policy.
- `ChanceMode: Probability` uses item power/chance settings plus the resolved
  `TwCapturePolicyConfig` for the target role.
- Role policy may supply minimum power, resistance, chance multiplier,
  missing-health bonus, guaranteed power, and side-effect-free registered
  requirements.
- Invalid probability input or a missing required handler denies capture.
- Successful capture creates the configured filled item.
- A failed probability roll leaves the target unchanged and does not invoke a
  separate source-consumption transaction.

The API exposes immutable spawner-mechanics and role-policy views plus
namespaced capture-requirement registration. Integrations should not pre-roll
chance or write capture state directly.

## Boundary

Capture policy decides whether the ordinary filled-spawner capture may
continue. It does not introduce a second capture lifecycle, source-consumption
protocol, command-roster link, or coop intake path. Once capture is eligible,
Tamework's canonical capture operation owns the durable lifecycle transition;
integrations do not submit or persist a parallel capture operation.
