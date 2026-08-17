# Tamework Type Discovery

Do not use a hand-maintained skill list as authority for Tamework IDs. IDs can
change with the selected source commit, plugin build, and Hytale profile.

## Resolve Current IDs

1. Record the editable Tamework repository commit and intended project profile.
2. Inspect NPC builder registration in
   `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`.
3. Inspect item interaction, ECS component, and asset-store registration in
   `src/main/java/com/alechilles/alecstamework/Tamework.java`.
4. Search the relevant builder/config/codec class for its declared ID, codec
   domain, required fields, and defaults.
5. Use exact-profile `author options` to confirm the ID is available to the
   target mod's locked plugin set.
6. Use `author inspect` on current repo consumers to understand usage, but do
   not treat one example as a complete contract.

## Record the Result

For every consumed or introduced ID, record:

- category: action, sensor, filter, interaction, component, config preset, or
  command step;
- exact ID and declaring source symbol;
- Tamework commit and project-profile/plugin-set identity;
- codec/schema field contract;
- existing consumers and affected-scope validation result;
- verification claim or explicit evidence gap.

If source registration and the project profile disagree, stop and refresh or
fix the profile/plugin evidence. Do not choose whichever ID appears in an old
skill, installed runtime copy, shipped example, or documentation page.
