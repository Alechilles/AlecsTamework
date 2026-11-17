"""Both sleeping chickens count toward the deployed limit when either one wakes."""
import json
import os
from pathlib import Path
import re
import sys
from uuid import UUID

# rigtest loads individual test files by path under Python's isolated mode.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import coop_parked_release as coop
from owner_away_breeding import travel

ABOUT = __doc__
setup = coop.setup


def stock_coop(t, side, at, count):
    x, y, z = at
    for dx in (-2, 2)[:count]:
        if not coop.spawn_owned(t, x + dx, z, side):
            return False
    inside = t.wait_hook(f"tw debug coop advance 1 --at {x} {y} {z} {coop.W}",
                         lambda r: r.ok and r.int("residents") == count, timeout=45, side=side)
    return t.check(inside.ok and inside.int("residents") == count, f"{side} has {count} owned coop residents",
                   inside.lines)


def coop_deployed_limit(t):
    x, y, z = t.ctx["coop"]
    rig = Path(os.environ["RUNE_RIG"])
    sides = json.loads((rig / "rig.json").read_text(encoding="utf-8-sig"))["sides"]
    ids = {s["name"]: s["serverId"] for s in sides}
    network = "dungeon" in ids
    local_count = 1 if network else 2
    path = rig / coop.HUB / "universe/Tamework/Settings/tamework-settings.json"
    original = path.read_bytes()
    settings = json.loads(original)

    def set_limit(limit):
        settings["population"]["limitPerPlayerDeployedTotal"] = limit
        if network:
            settings["population"]["perPlayerLimitScope"] = "Global"
        path.write_text(json.dumps(settings, indent=2), encoding="utf-8")
        t.note(f"deployed limit {limit}: {t.console(coop.HUB, 'tw config reload --world default')}")

    try:
        set_limit(2)
        if not stock_coop(t, coop.HUB, (x, y, z), local_count):
            return
        if network:
            other = "dungeon"
            if not travel(t, other, ids[other], coop.OWNER):
                return
            t.console(other, f"op add {coop.OWNER}")
            preset = t.B.apply_preset("tester")
            if not t.check(preset.get("ok"), f"The owner is ready to place blocks on {other}", preset.get("error")):
                return
            coop.set_hour(t, coop.NIGHT, other)
            other_coop = coop.place_coop(t, other)
            if not t.check(bool(other_coop), f"The owner placed a coop on {other}", other_coop):
                return
            if not stock_coop(t, other, other_coop, 1):
                return
            owner = UUID(t.B.me()["uuid"])
            query = ("SELECT holder FROM tamework_portable_companions "
                     f"WHERE owner_uuid='{owner}' AND location_kind='COOP' AND role_id='Tamed_Chicken'")

            def both_saved():
                return sorted(row[0] for row in t.sql(query)) == sorted(ids.values())

            if not t.check(bool(t.wait(both_saved, 45)), "The shared store holds one coop resident from each server"):
                return
            if not travel(t, coop.HUB, ids[coop.HUB], coop.OWNER):
                return
            t.B.tp(x + 0.5, coop.FEET, z + 4.5)

        # Each release must see the other sleeping resident. Require the admission reason
        # for each slot so a missed morning sweep cannot look like a successful check.
        set_limit(1)
        mark = t.log.mark()
        coop.set_hour(t, coop.MORNING)
        pattern = re.compile(r"Coop slot (\d+) .*could not release its Tamed_Chicken resident \(DEPLOYED_LIMIT\)")

        def refused_slots():
            lines = t.log.since(mark, regex=pattern.pattern, noise=True) or []
            slots = {pattern.search(line).group(1) for side, line in lines if side == coop.HUB}
            return sorted(slots) if len(slots) == local_count else None

        slots = t.wait(refused_slots, 60)
        if not t.check(bool(slots), "Each release counts the other resident against the limit of one", slots):
            return
        set_limit(2)
        released = t.wait(lambda: coop.chickens(t)[coop.OWNED] == local_count, 75)
        t.check(bool(released), "The local residents come out at the exact limit of two", coop.chickens(t))
    finally:
        path.write_bytes(original)
        t.console(coop.HUB, "tw config reload --world default")


CASES = [coop_deployed_limit]
