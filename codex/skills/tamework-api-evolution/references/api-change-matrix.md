# API Change Matrix

Use each applicable row before editing a public contract.

| Layer | Questions |
| --- | --- |
| Existing surface | Can a current method already express the behavior? |
| Ownership | Which sub-API owns the domain? |
| Interface | Is the change source, binary, or behavioral compatible? |
| Reflection | Does the NPC Debug Inspector or Runeteria call it by name? Is the implementing class public, and is the name unchanged? |
| Implementations | Index-backed root, `unavailable()` fallbacks, mock, and external? |
| Capability | How does a client discover support at runtime? |
| Input | Nullability, units, ranges, identity, authority, and idempotency? |
| Result | Which stable status explains each denial or failure? |
| Threading | Is the call synchronous, async, or world-thread confined? Which thread completes a future (often the writer thread)? |
| Events | Does a semantic transition need publication? Events go out after the index lock is released, on the changing thread, with no order between threads. |
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
  API 3.0.0 was such a break: it removed six capabilities and their accessors.
- **Removed capability:** consumers that resolve capability names by text stop
  becoming ready. List the removal in the consumer checklists.

## Useful Starting Points

Verify all names in current source:

- `TameworkApi`, domain sub-interfaces, and `TameworkApiCapability`
- `IndexTameworkApi` (the API root, version 3.0.0) and `TameworkApiImpl` (the
  base it delegates to and the `API_VERSION` constant)
- the index-backed delegates in `api/internal`: `IndexNpcProfilesApi`,
  `IndexProfileDataApi`, `IndexDiagnosticsApi`, `IndexPopulationGroupApi`,
  `AdmissionProviderRegistry`, `RequiredContentProfileReadiness`
- `companion/bonded/IndexBondedCompanionApi` and `companion/admission`
- result/status records, `CompanionEventPublisher`, and the internal event bus
- `ApiSelfTestRunner`, `HyDragonApiSelfTestSuite`, and API contract tests
- `ApiSurfaceCompatibilityTest`: add each new call a reflective consumer makes
- `wiki/Modder-Documentation/Public-API`

`ReplacementTameworkApi` and `BondedOnlyTameworkApi` are dead code kept until
the old persistence package is deleted. Do not extend them.
