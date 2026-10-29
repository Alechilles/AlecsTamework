---
title: "Mod Settings Guide"
order: 14
published: true
draft: false
---
# Mod Settings Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Alec's Tamework Wiki](/mod/alecs-tamework/alecs-tamework-wiki)

Mod settings let your mod add its own options to the **Mod Settings** tab of `/tw settings`. Server admins flip a checkbox or pick a value, and your Patchwork patches change your assets to match. You do not need any Java.

Settings apply to the whole server. They are saved in Tamework's data folder (`mods/<Tamework>/ModSettings/values.json`), not per world.

## Add a setting in two files

### 1. Declare a group of settings

Create one file per group under `Server/Tamework/ModSettings/`. The file name is the group's ID.

`Server/Tamework/ModSettings/AH_MobSpawning.json`

```json
{
  "Label": "server.animalhusbandry.settings.group.mobSpawning",
  "Order": 10,
  "Settings": [
    {
      "Type": "Boolean",
      "Id": "AH_WildDragons",
      "Label": "server.animalhusbandry.settings.wildDragons",
      "Description": "server.animalhusbandry.settings.wildDragons.description",
      "Default": true
    }
  ]
}
```

In the tab this shows as **Alec's Animal Husbandry > Mob Spawning > Wild dragons**. The mod name comes from your manifest, so you do not type it.

### 2. Write a patch that reads the setting

Put a normal Patchwork patch under `Server/Patchwork/Patches/` and name the setting in its `When` condition:

```json
{
  "Id": "AH_Settings_WildDragons",
  "Targets": ["Server/NPC/Spawn/Beacons/AnimalHusbandry/AH_Dragon_Frost_Zone3_Mountains.json"],
  "When": { "Setting": { "Id": "AH_WildDragons", "Is": false } },
  "Operations": [
    { "Op": "Replace", "Path": "/Environments", "Value": [] }
  ]
}
```

Write patches for the non-default choice. Your shipped asset already matches the default, so if Tamework is missing or the setting is not loaded yet, the asset simply stays as you shipped it.

To put a number or choice straight into an asset, use `$Setting` as the value:

```json
{ "Op": "Replace", "Path": "/NPCs/0/Weight", "Value": { "$Setting": "AH_DragonSpawnWeight" } }
```

See the Patchwork wiki for the `Setting` condition (`Is`, `AtLeast`, `AtMost`) and `$Setting` values.

## Group fields

| Field | Meaning |
| --- | --- |
| `Label` | Language key for the group heading. |
| `Order` | Sort order among your mod's groups. Lower comes first. Default `0`. |
| `Settings` | The settings in this group, in display order. |

## Setting fields

Every setting has:

| Field | Meaning |
| --- | --- |
| `Type` | `Boolean`, `Enum`, `Integer` or `Decimal`. |
| `Id` | Unique ID that patches and saved values use. Start it with a short mod prefix, such as `AH_`. Keep it stable: renaming it loses admins' saved choices. |
| `Label` | Language key for the name shown in the tab. If missing, the Id is shown. |
| `Description` | Optional language key for the tooltip. |
| `Default` | Value used until an admin changes it. |

Extra fields by type:

| Type | Control | Extra fields |
| --- | --- | --- |
| `Boolean` | Checkbox | none |
| `Enum` | Dropdown | `Options`: a list of `{ "Value": "...", "Label": "<lang key>" }`. `Default` is an option's `Value`; if it is missing, the first option is used. |
| `Integer` | Number field | Optional `Min` and `Max`. Values outside the range are clamped. |
| `Decimal` | Number field | Optional `Min` and `Max`. Values outside the range are clamped. |

You can move a setting to another group or rename a group at any time. Patches and saved values only use the setting's `Id`.

If two settings share an `Id`, Tamework uses the first one and logs a warning.

## What admins see

- `/tw settings` > **Mod Settings**: change values, then press **Apply**. The status line says whether the change is live or needs a restart.
- `/tw modsetting list`, `get <id>`, `set <id> <value>`, `reset <id>`: the same from chat or the server console.

After a change, Tamework asks Patchwork to rebuild its patches. Some assets update live; others, such as spawn rules, may need a restart. Patchwork decides and the status line reports it. Saved values for mods that are later removed are kept, so reinstalling the mod restores them.
