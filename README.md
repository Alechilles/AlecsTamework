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
Wiki pages are in `docs/wiki/`:
- Home
- Quick-Start
- Components
- Templates
- Interactions-and-Items
- Troubleshooting

## License
See `LICENSE.txt`.
