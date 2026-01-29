# Alec's Tamework!

A modular taming framework for Hytale. Add follow/hold, defend, and sleep behaviors to NPCs using reusable components and templates.

## Highlights
- Componentized tame behaviors (IdleFollow, Hold, Defend, Sleep)
- Example templates and NPC roles (full + minimal)
- Item-based pickup and feeding
- Designed to be reused across mods

## Quick Start
1. Add the dependency in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "1.0.0"
},
"IncludesAssetPack": true
```

2. Copy one of the example templates:
- `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json`
- `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json`

3. Copy the matching NPC role and tweak values:
- `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example.json`
- `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Simple.json`

4. Add translations in `Server/Languages/en-US/server.lang`.

## Documentation
- [Home](https://github.com/Alechilles/AlecsTamework/wiki)
- [Quick-Start](https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start)
- [Components](https://github.com/Alechilles/AlecsTamework/wiki/Components)
- [Templates](https://github.com/Alechilles/AlecsTamework/wiki/Templates)
- [Interactions-and-Items](https://github.com/Alechilles/AlecsTamework/wiki/Interactions-and-Items)
- [Troubleshooting](https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting)

## License
See `LICENSE.txt`.
