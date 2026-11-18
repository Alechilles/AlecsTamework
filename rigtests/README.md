# Rig tests

Live tests with robot players on a local test environment, run with the Rune_MCP harness
(`Modding/Runeteria/Rune_MCP`, `tools/rigtest.py`). They open game windows on this PC and never touch a real server.

| File | What it checks | How to run |
| --- | --- | --- |
| `coop_deployed_limit.py` | Two owned chickens spend the night in a coop. With the deployed limit lowered to one, each morning release reports `DEPLOYED_LIMIT`. Returning the limit to two lets both residents out. | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/coop_deployed_limit.py --env rigtests/coop_deployed_limit.env.json --stall 300` |
| `coop_deployed_limit.py` with `coop_network.env.json` | One owned chicken sleeps on each server. After the owner returns home, the global deployed limit must include the other server's sleeping chicken when the local coop releases. | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/coop_deployed_limit.py --env rigtests/coop_network.env.json --stall 300` |
| `owner_away_breeding.py` | A witness keeps two ready cows loaded and in range while their owner visits the other server. The test watches for a calf or a breeding cooldown over two sweep intervals, then verifies the calf and its owner after the player returns. | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/owner_away_breeding.py --env rigtests/coop_network.env.json --stall 400` |
| `shared_access.py` | Shared access, in named cases (below). Two bots (ShareOwner, ShareFriend), two Rune_Husbandry cows. Needs a Tamework build with `tw debug needs advance` (the 5.11 work), so `offline_owner` does not wait in real time. Not yet timed as a whole. | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/shared_access.py --env rigtests/shared_access.env.json --stall 400` |
| `shared_access.env.json` | The environment for `shared_access.py`: Tamework's HEAD, Rune_Husbandry's HEAD, RuneCore (Rune_Professions keeps the Husbandry levels Rune_Husbandry's feed and harvest need). The bots are started by the test, not the spec. | |
| `coop_parked_release.py` | Coop residents released toward a loaded but non-ticking section are not duplicated (the 5.15.1 fix). One bot places a Rune_Husbandry chicken coop on a section edge, two wild and two owned chickens go in at night, the bot stands where the coop's section ticks and the release spot's is parked (two control cows prove it), the morning runs 100 s, the bot returns and the chickens are counted. About 4 min with the environment's start. On 2026-11-15: 5.15.0 FAILED as expected (4 in, 10 out: each wild resident came back as 4), 5.15.1 passed (8 PASS). | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/coop_parked_release.py --env rigtests/coop_parked_release.env.json --stall 300` (5.15.0: `coop_parked_release_5150.env.json`) |
| `player_preferences.py` | A player's own preference (F on a companion, `/tw card`) follows them between two servers that share Rune_Professions' settings store: chosen on survival it shows on dungeon, which never saw the player, and a change made on dungeon wins over survival's own copy on the way back. The environment adds Rune_Husbandry only so Tamework's modules activate, and ops the bot on each server it reaches. On 2026-11-18: 6 PASS in 116 s (fresh environment). | `py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/player_preferences.py --env rigtests/player_preferences.env.json --stall 300` |
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
