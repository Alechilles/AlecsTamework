# Rig tests

Live tests with robot players on a local test environment, run with the Rune_MCP harness
(`Modding/Runeteria/Rune_MCP`, `tools/rigtest.py`). They open game windows on this PC and never touch a real server.

| File | What it checks | How to run |
| --- | --- | --- |
| `shared_access.py` | Shared access, in named cases (below). Two bots (ShareOwner, ShareFriend), two Rune_Husbandry cows. Needs a Tamework build with `tw debug needs advance` (the 5.11 work), so `offline_owner` does not wait in real time. Not yet timed as a whole. | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/shared_access.py --env rigtests/shared_access.env.json --stall 400` |
| `shared_access.env.json` | The environment for `shared_access.py`: Tamework's HEAD, Rune_Husbandry's HEAD, RuneCore (Rune_Professions keeps the Husbandry levels Rune_Husbandry's feed and harvest need). The bots are started by the test, not the spec. | |
| `shared_access_runeteria.env.json` | The environment for the Runeteria checks (guild group, Husbandry XP, network store), done by hand. See its `_comment` for the build workaround. Guild founding also needs RuneClaims and RuneEconomy (add `RuneClaims@latest` and `RuneEconomy@latest`) and a founder 400 blocks from spawn with money (`/eco add`). | `py -3 -I <Rune_MCP>/tools/env.py up rigtests/shared_access_runeteria.env.json` |

## Cases in `shared_access.py`

`setup` always runs first: both bots join with the `tester` preset, get the Husbandry level, feed, buckets and a
sword, and the owner spawns two tamed cows 16 blocks apart (a rerun in the same environment reuses them). Each case
then sets the grant, private flag and settings it needs, so any case can run alone.

| Case | What it checks |
| --- | --- |
| `stranger_baseline` | With no grant the friend cannot feed or harvest cow A; the owner can, and the owner's sword hurts it (controls). |
| `grant_flow` | `/tw share add` without `confirm` warns and grants nothing; with it, both players are told and both lists show it. |
| `caretaker` | A Caretaker can feed; harvest and the F mode cycle are refused; one sword swing does no damage. |
| `handler` | A level change needs no confirm and tells the friend; a Handler can harvest and cycle; no damage. |
| `manager` | A Manager's swing does the same damage as the owner's swing just before it. |
| `private_flag` | The friend cannot feed a private cow but can feed the other; `private off` restores it. |
| `revoke_and_leave` | `/tw share remove` and `/tw share leave` stop access at once. |
| `server_settings` | Sharing off in `tamework-settings.json` stops access and keeps the grant; `highestLevel` CARETAKER caps a Handler. The file is restored at the end. |
| `restart_persistence` | A server restart keeps the grant and the private flag. |
| `offline_owner` | With the owner bot stopped and the friend online, `tw debug needs advance 600` counts for the shared cow, not for the private one, and not for cow A once the friend has left the share; one short real-time look that cow A's hunger still drops. The owner bot comes back at the end. |

Run one case (setup still runs first):

```
py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/shared_access.py --env rigtests/shared_access.env.json --stall 400 --case manager
```

`--case` takes a comma-separated list. When a run in a fresh `--env` environment does not pass, the environment stays
up (the reaper deletes it after 30 idle minutes) and the harness prints the command to rerun only the failed cases
there:

```
py -3 -I <Rune_MCP>/tools/rigtest.py run <abs path>/rigtests/shared_access.py --in <env id> --failed
```

Results land in `<Rune_MCP>/out/rigtest/<test>-<time>/results.json` with screenshots and logs.
