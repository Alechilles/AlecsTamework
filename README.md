<img width="400" height="400" alt="Alec&#39;sTamework400Transparent" src="https://github.com/user-attachments/assets/251cbac2-26ea-4daf-b552-30594e96f8da" />

A modular taming framework for Hytale. Add follow/hold, defend, and sleep behaviors to NPCs using reusable components and templates.

## Highlights
- Componentized tame behaviors (IdleFollow, Hold, Defend, Sleep)
- Example templates and NPC roles (full + minimal)
- Item-based pickup and feeding
- Designed to be reused across mods

## Quick Start
1. Add the dependency in your `manifest.json` (and set **Alec's Tamework!** as a required dependency when uploading your mod on CurseForge):

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "1.0.0"
},
"IncludesAssetPack": true
```

Note on asset pack load order: Hytale loads asset packs alphabetically by folder name, so your mod's folder must come after `.Alec's Tamework!`. The `manifest.json` name does not affect load order (only the folder name does), which is why the leading `.` exists on the Tamework folder.

2. Copy one of the example templates:
- `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json`
- `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json`

3. Copy the matching NPC role and tweak values:
- `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example.json`
- `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Simple.json`

4. Add translations in `Server/Languages/en-US/server.lang`.

5. If you want testers/players to spawn the NPCs easily, add spawner items (or reuse existing spawner items) that point at your NPC role.

## Documentation
- [Home](https://github.com/Alechilles/AlecsTamework/wiki)
- [Quick-Start](https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start)
- [Components](https://github.com/Alechilles/AlecsTamework/wiki/Components)
- [Templates](https://github.com/Alechilles/AlecsTamework/wiki/Templates)
- [Interactions-and-Items](https://github.com/Alechilles/AlecsTamework/wiki/Interactions-and-Items)
- [Troubleshooting](https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting)

## License
See `LICENSE.txt`.
