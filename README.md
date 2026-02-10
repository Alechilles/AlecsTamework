Note: Major overhaul coming very soon. If you're about to start implementing the Tamework, maybe give it a day or two and check back here. Lots of *very* powerful features coming and massively simplified implementation!

<img width="400" height="400" alt="Alec&#39;sTamework400Transparent" src="https://github.com/user-attachments/assets/251cbac2-26ea-4daf-b552-30594e96f8da" />

A modular taming framework for Hytale. Add follow/hold/defend/sleep behaviors, capture + spawn, ownership, and tamed state to NPCs using reusable components and templates.

## Highlights
- Componentized pet behaviors (IdleFollow, Hold, Defend, Sleep)
- Owner + tamed state stored on NPCs (persisted through reloads)
- Capture + spawn items that preserve attachments/variants
- Template examples (full + minimal) and spawner examples
- Designed to be reused across mods

## Quick Start
1. Add the dependency in your `manifest.json` (and set **Alec's Tamework!** as a required dependency when uploading your mod on CurseForge):

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "1.1.0"
},
"IncludesAssetPack": true
```

Note on asset pack load order: Hytale loads asset packs alphabetically by folder name, so your mod's folder must come after `.Alec's Tamework!`. The `manifest.json` name does not affect load order (only the folder name does), which is why the leading `.` exists on the Tamework folder.

2. Copy a template:
- `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json`
- `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json`

3. Copy a matching NPC role and tweak values:
- `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example.json`
- `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Simple.json`

4. Add translations in `Server/Languages/en-US/server.lang`.

5. Add a spawner item (or reuse your own) and wire **TameworkSpawn** for the filled state.

6. Add your item config:
```
<ModRoot>/Server/Tamework/Tamework_Items_Config.json
```

Optional server override (global):
```
<ServerRoot>/Tamework/Tamework_Items_Config_Override.json
```

## Server Settings
Tamework writes a server settings file on first run:
```
<ServerRoot>/mods/Alec's Tamework!/tamework-settings.json
```

Optional server override (takes precedence if present):
```
<ServerRoot>/Tamework/tamework-settings.json
```

Current options:
- `BlockOwnerDamage` (default **true**): blocks pet damage from its owner.
- `BlockAllPlayerDamageIfOwned` (default **false**): blocks any player damage if the pet has an owner.
- `InvulnerableIfOwned` (default **false**): blocks all damage sources if the pet has an owner.

## Documentation
- [Home](https://github.com/Alechilles/AlecsTamework/wiki)
- [Quick-Start](https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start)
- [Components](https://github.com/Alechilles/AlecsTamework/wiki/Components)
- [Templates](https://github.com/Alechilles/AlecsTamework/wiki/Templates)
- [Interactions-and-Items](https://github.com/Alechilles/AlecsTamework/wiki/Interactions-and-Items)
- [Actions-and-Sensors](https://github.com/Alechilles/AlecsTamework/wiki/Actions-and-Sensors)
- [Item-Config](https://github.com/Alechilles/AlecsTamework/wiki/Item-Config)
- [Troubleshooting](https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting)

## <a id="issue-reporting">Issue Reporting</a>
If you've run into a bug or any kind of issue, please [submit a new issue](https://github.com/Alechilles/AlecsTamework/issues) on my GitHub repo. As much information as you can possibly provide is highly appreciated!
