# Tamework Asset Path Discovery

Do not treat this skill reference as a complete config-family or path
inventory. Tamework adds and changes asset families over time.

## Resolve the Current Contract

1. Record the editable Tamework source commit.
2. Inspect asset-store registration and loaded/removed hooks in
   `src/main/java/com/alechilles/alecstamework/Tamework.java`.
3. Find the relevant `Tw*Config` class under
   `src/main/java/com/alechilles/alecstamework/config/assets`.
4. Read that class's codec, asset store, resolver/cache, parent fallback, and
   validation methods.
5. Check current `docs/Config-Discovery.md` and the feature-specific document.
6. Confirm the target mod's exact project profile contains the same Tamework
   plugin set and validates the asset.

## Last-Reviewed Common Locators

These are search hints, not an exhaustive or release-authoritative list:

- role-scoped config families commonly live under `Server/Tamework/<Family>`;
- item-scoped families commonly live under `Server/Tamework/Items/<Family>`;
- farming and other subsystem families may use deeper subsystem directories;
- global configs commonly resolve by enabled priority;
- role-scoped configs commonly resolve by role and priority;
- item-scoped configs commonly resolve by one or more item IDs.

Use the current config class and registration call to determine the exact
directory and resolution key. Do not infer support from whether a family is
named in this reference.

## Inheritance and Reload

- Use `$hytale-asset-inheritance-contract` for parent fallback. Inspect
  declared/effective values; do not reduce nested, array, map, alias, or
  local-only behavior to "child wins."
- Inspect the current reload command and feature-registry reload services before
  recommending `/tw reloadconfig`. At the last review it covered item-feature
  registries only, but current source is authoritative.
- For event-driven families, verify loaded/removed hooks and cache/index
  replacement behavior in current source.

## Evidence to Report

- Config family and source class.
- Registered path/store and resolution key.
- Tamework commit plus project-profile/plugin-set identity.
- Inheritance and reload behavior with source symbols.
- Candidate validation outcome and affected consumers.

If source, documentation, runtime copy, and profile disagree, stop and reconcile
the identities. Do not choose the most convenient answer.
