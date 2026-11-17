"""A ready pair waits while its owner visits another server and breeds when they return."""
import json
import math
import os
from pathlib import Path
import time

ABOUT = __doc__
OWNER, WITNESS = "HerdOwner", "HerdWitness"
HOME, VISIT = "survival", "dungeon"
ADULT, CALF = "Tamed_Cow", "Tamed_Cow_Calf"


def travel(t, dest, server_id, owner=OWNER):
    t.B.use(owner)
    t.say(f"/server {server_id}")

    def arrived():
        player = t.B.tk(f"/player?name={owner}", side=dest)
        return isinstance(player, dict) and player.get("world")

    return t.check(bool(t.wait(arrived, 120)), f"The owner arrived on {dest}")


def enable_parent(t, entity, x):
    npc = t.B.tk(f"/npc?uuid={entity}", side=HOME)

    def on_card():
        return str(t.B.me().get("openPage")).endswith("TameworkCompanionCardPage")

    for z in (20, 12):
        t.B.tp(x, 80, z)
        opened = t.B.interact(npc["networkId"])
        if t.wait(on_card, 3):
            break
    else:
        return t.check(False, "The owner opened this parent's companion card", opened)

    # The rig's 1280x720 companion card for a cow. Hold keeps the pair within range.
    t.B.click(901, 316)

    def enabled():
        state = t.hook(f"tw debug get companion --entity {entity}", side=HOME)
        if state.ok and state.get("breedingReady") is not None:
            return True
        if on_card():
            t.B.click(857, 409)
        return False

    result = t.wait(enabled, 10, every=1.5)
    t.B.ensure_ready()
    return t.check(bool(result), "The owner enabled passive breeding for this parent", entity)


def breeding(t):
    rig = Path(os.environ["RUNE_RIG"])
    sides = json.loads((rig / "rig.json").read_text(encoding="utf-8-sig"))["sides"]
    server_ids = {s["name"]: s["serverId"] for s in sides}
    for side in (HOME, VISIT):
        marker = "Tamework companions follow their owners through RuneCore's database as server " + server_ids[side]
        installed = any(marker in p.read_text(encoding="utf-8", errors="replace")
                        for p in (rig / side / "logs").glob("*_server.log"))
        if not t.check(installed, f"{side} registered its network companion store", server_ids[side]):
            return
    t.require(bots=[OWNER, WITNESS], preset="tester")
    t.B.use(WITNESS)
    t.B.tp(418, 80, 20)
    t.B.use(OWNER)
    path = rig / HOME / "universe/Tamework/Settings/tamework-settings.json"
    original = path.read_bytes()
    settings = json.loads(original)

    def passive(enabled):
        settings["breeding"].update(passiveBreedingEnabled=enabled, genderEnabled=False, requiresHappiness=False)
        path.write_text(json.dumps(settings, indent=2), encoding="utf-8")
        t.note(t.console(HOME, "tw config reload --world default"))

    def animals():
        rows = t.B.tk("/entities?world=default&x=418&y=80&z=20&r=48&role=Cow", side=HOME)
        if not isinstance(rows, list):
            raise ValueError(f"Missing animal census on {HOME}: {rows}")
        return [r for r in rows if r.get("alive", True)]

    def companion(entity):
        return t.hook(f"tw debug get companion --entity {entity}", side=HOME)

    try:
        passive(False)
        if t.B.side_of_bot() != HOME:
            t.console(VISIT, f"op add {OWNER}")
            if not travel(t, HOME, server_ids[HOME]):
                return
        t.B.tp(418, 80, 20)
        owner_id = t.B.me()["uuid"]
        t.note(t.say("/prof admin setlevel husbandry 99"))
        t.note(t.say("/tw card card"))
        t.console(HOME, "time 8 --world default")
        parents = [t.B.tk(f"/npc?networkId={r['networkId']}&world=default", side=HOME)["uuid"]
                   for r in animals() if r["role"] == ADULT]
        if len(parents) > 2 or any(companion(p).get("owner") != owner_id for p in parents):
            t.unjudged("The arena holds only the test owner's parents", parents)
            return
        for i, x in enumerate((414, 422)):
            if i == len(parents):
                reply = t.wait_hook(f"tw npc spawn tamed {ADULT} --owner {OWNER} --at {x} 80 16",
                                    lambda r: r.get("detail") != "provider-decision-pending", side=HOME, timeout=30)
                if not t.check(reply.ok and reply.get("entity"), "Spawn an owned parent", reply.describe()):
                    return
                parents.append(reply.get("entity"))
            entity = parents[i]
            t.hook(f"tw debug set happiness 100 --entity {entity}", side=HOME)
            fertility = t.hook(f"tw debug set traits Trait_Fertility 1 --entity {entity}", side=HOME)
            if not fertility.ok:
                t.unjudged("Set the pair's fertility for a deterministic litter", fertility.describe())
                return
            if companion(entity).get("breedingReady") is None and not enable_parent(t, entity, x):
                return
        if not travel(t, VISIT, server_ids[VISIT]):
            return
        passive(True)

        def ready():
            states = [companion(p) for p in parents]
            return all(s.ok and s.get("breedingReady") == "true" for s in states)

        if not t.check(bool(t.wait(ready, 65)), "Both parents are breeding-ready while the owner is away"):
            return
        # The shipped livestock pack sweeps every 30 seconds. Keep observing for two full sweeps.
        deadline = time.monotonic() + 65
        while time.monotonic() < deadline:
            rows = animals()
            positions = [(r["x"], r["y"], r["z"]) for r in rows if r["role"] == ADULT]
            if len(positions) != 2 or math.dist(*positions) > 15:
                t.unjudged("Both loaded parents remain within breeding range", positions)
                return
            states = [companion(p) for p in parents]
            if any(not s.ok or s.int("breedingCooldownRemainingMs") != 0 for s in states) or any(r["role"] == CALF for r in rows):
                t.check(False, "The pair starts no breeding cooldown and produces no calf while the owner is away",
                        [s.describe() for s in states] + rows)
                return
            time.sleep(2)
        t.check(True, "Both parents stayed in range with no breeding cooldown or calf across two sweeps")
        t.console(VISIT, f"op add {OWNER}")
        if not travel(t, HOME, server_ids[HOME]):
            return
        calf = t.wait(lambda: next((r for r in animals() if r["role"] == CALF), None), 100)
        if not t.check(bool(calf), "The same pair produced a calf after its owner returned", calf):
            return
        npc = t.B.tk(f"/npc?networkId={calf['networkId']}&world=default", side=HOME)
        child = companion(npc["uuid"])
        t.check(child.ok and child.get("owner") == owner_id, "The calf belongs to the returning owner",
                child.describe())
    finally:
        path.write_bytes(original)
        t.console(HOME, "tw config reload --world default")


CASES = [breeding]
