# API Change Matrix

Use each applicable row before editing a public contract.

| Layer | Questions |
| --- | --- |
| Existing surface | Can a current method already express the behavior? |
| Ownership | Which sub-API owns the domain? |
| Interface | Is the change source, binary, or behavioral compatible? |
| Implementations | Full, replacement, bonded-only, degraded, mock, and external? |
| Capability | How does a client discover support at runtime? |
| Input | Nullability, units, ranges, identity, authority, and idempotency? |
| Result | Which stable status explains each denial or failure? |
| Threading | Is the call synchronous, async, or world-thread confined? |
| Events | Does a semantic transition need publication, and in what order? |
| Versioning | API constant, mod version, deprecation, and migration window? |
| Verification | Unit contract, live self-test, and downstream compilation? |
| Documentation | Reference, recipe, capability check, changelog, and examples? |

## Compatibility Choices

- **Existing method:** add no new surface; document the recipe.
- **Default convenience method:** delegate to the stable primitive without new
  semantics.
- **New capability:** use when support can vary by runtime composition.
- **New sub-API version:** use when semantics cannot be added compatibly.
- **Breaking method:** use only with explicit version and migration approval.

## Useful Starting Points

Verify all names in current source:

- `TameworkApi`, domain sub-interfaces, and `TameworkApiCapability`
- `TameworkApiImpl`, `ReplacementTameworkApi`, and `BondedOnlyTameworkApi`
- result/status records and the internal event bus
- `ApiSelfTestRunner` and API contract tests
- `wiki/Modder-Documentation/Public-API`
