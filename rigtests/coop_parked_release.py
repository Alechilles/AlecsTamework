"""Coop residents released while their coop ticks but the release spot does not: no duplicates.

The bug (5.15.0 and earlier): a coop's morning release spawns each resident 3 blocks in front of the coop. When the
coop's chunk section is inside a player's simulated range and the release spot's section is just outside it (loaded,
not ticking), the engine parks the new body with that section and Store#addEntity returns null. Tamework read that as
"no body was added", kept the resident in its slot and tried again every 30 s, so every attempt left one more parked
copy, and all of them appeared when a player came near. The fix refuses a spawn into a loaded but non-ticking section
before anything is added (HytaleChunkAccess.parksEntitiesAt), and the coop sweep leaves its residents inside until the
release spot ticks.

The coop (Rune_Husbandry's Coop_Chicken, ACCoopChicken.json: release offset Z+3, roam hours 06 to 18) is placed by the
bot at z 61, so its release spot (z 64.5) is in the next 32-block section. The bot then stands N sections away on the
far side, where the coop's section is still simulated and the release spot's is not (the engine's hot radius is at
most 8 sections, the bot's view 12). Two wild cows prove that state before anything is judged: one in the coop's
section must be listed by RuneTestKit /entities (ticking), one in the release spot's section must not be (parked),
while its column is loaded. Then the morning comes, the test waits through several 30 s retries, the bot walks back,
and the chickens in the world are counted.

One case, `parked_release`. Run it on the fix and on the release before it:
    py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/coop_parked_release.py --env rigtests/coop_parked_release.env.json --stall 300
    py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/coop_parked_release.py --env rigtests/coop_parked_release_5150.env.json --stall 300
The second is expected to FAIL (it reproduces the duplication).
"""
import time

ABOUT = "Coop residents released toward a loaded but non-ticking section are not duplicated"
OWNER = "CoopOwner"
HUB = "survival"
GROUND = 79                     # the flat world's top block; feet at y 80
FEET = GROUND + 1
SPOT = (16, 63)                 # coop in the last row of its section: every release spot is in the next one
BOT_AT_PLACE = (16.5, 66.5)     # the bot faces the coop from +z when it places it, so the coop faces +z
NEAREST_RELEASE = 1.1           # CoopResidentReleasePositionService: 1 to 3 blocks from the centre, in a 100° cone
COW_TICKING = (16, 44)          # same 32-block section as the coop
COW_PARKED = (16, 80)           # same section as the release spot
WILD, OWNED = "Chicken", "Tamed_Chicken"
NIGHT, MORNING = 20, 7
SECTION = 32
W = "--world default"


def tk(t, path):
    t.progress(f"testkit {path}")
    r = t.B.tk(path, side=HUB)
    t.progress()
    return r


def near_point(t, x, z, r, role):
    rows = tk(t, f"/entities?world=default&x={x}&y={FEET}&z={z}&r={r}&role={role}")
    return rows if isinstance(rows, list) else []


def chickens(t, r=48):
    c = t.ctx["coop"]
    rows = [e for e in near_point(t, c[0], c[2], r, "Chicken") if e.get("alive", True)]
    return {"total": len(rows), WILD: sum(1 for e in rows if e.get("role") == WILD),
            OWNED: sum(1 for e in rows if e.get("role") == OWNED)}


def advance(t, seconds, at=None, side=HUB):
    at = at or t.ctx["coop"]
    return t.hook(f"tw debug coop advance {seconds} --at {at[0]} {at[1]} {at[2]} {W}", side=side)


def set_hour(t, hour, side=HUB):
    t.note(f"time {hour} on {side}: {t.console(side, f'time {hour} {W}')[:1]}")


def sleep(t, seconds, what):
    end = time.time() + seconds
    t.wait(lambda: time.time() >= end, seconds + 5, None, every=2.0)
    t.note(f"waited {seconds} s: {what}")


def find_coop(t, side=HUB):
    rows = t.B.tk(f"/blocks?world=default&x={SPOT[0]}&y={FEET}&z={SPOT[1]}&r=3&filter=Coop_Chicken", side=side)
    cells = sorted({(r["x"], r["y"], r["z"]) for r in rows}) if isinstance(rows, list) else []
    for cell in cells:
        r = advance(t, 1, at=cell, side=side)
        if r.ok or (r.refused and r.reason == "roaming-now"):
            return cell
    return None


def place_coop(t, side=HUB):
    B = t.B
    t.console(side, f"give {OWNER} Coop_Chicken --quantity=1")
    B.tp(BOT_AT_PLACE[0], FEET, BOT_AT_PLACE[1])
    if not B.select("Coop_Chicken").get("ok"):
        return None
    if not B.aim_block(SPOT[0], GROUND, SPOT[1]):
        return None
    u = B.use_item(hold=0.25)
    t.note(f"place: landed={u.get('landed')} changes={u.get('changes')}")
    return t.wait(lambda: find_coop(t, side), 10)


def spawn_wild(t, role, x, z):
    t.B.tp(x + 0.5, FEET, z + 0.5)
    r = t.hook(f"npc spawn {role}", bot=OWNER)
    t.note(f"npc spawn {role} at {x},{z}: {r.lines[:1]}")


def spawn_owned(t, x, z, side=HUB):
    end = time.time() + 40
    while True:
        r = t.hook(f"tw npc spawn tamed {OWNED} --owner {OWNER} --at {x} {FEET} {z}", side=side)
        if not (r.refused and r.get("detail") == "provider-decision-pending") or time.time() > end:
            break
        time.sleep(1.5)
    return t.check(bool(r.ok), f"spawn an owned {OWNED} beside the coop", r.describe())


def edge_state(t):
    """Both control cows judged from the server: the coop's section ticks, the release spot's is loaded and parked."""
    ticking = near_point(t, COW_TICKING[0], COW_TICKING[1], 12, "Cow")
    parked = near_point(t, COW_PARKED[0], COW_PARKED[1], 12, "Cow")
    ground = tk(t, f"/ground?world=default&x={COW_PARKED[0]}&z={COW_PARKED[1]}") or {}
    return {"coopSectionTicks": bool(ticking), "spotSectionListed": bool(parked),
            "spotColumnLoaded": bool(ground.get("loaded"))}


def setup(t):
    t.require(bots=[OWNER], preset="tester")
    t.B.use(OWNER)
    set_hour(t, NIGHT)
    t.ctx["coop"] = place_coop(t)
    if not t.ctx["coop"]:
        raise SystemExit(f"no coop the hook knows near {SPOT} after the bot's placement (see the notes)")
    t.note(f"coop at {t.ctx['coop']}; release spot about z {t.ctx['coop'][2] + 3.5}")


def parked_release(t):
    c = t.ctx["coop"]
    coop_section = c[2] // SECTION
    if (c[2] + 0.5 + NEAREST_RELEASE) // SECTION == coop_section:
        t.unjudged("every release spot in the next section", f"coop at {c}: the nearest release spot stays in "
                   f"section {coop_section}")
        return

    # Night: two wild and two owned chickens go in.
    set_hour(t, NIGHT)
    before = advance(t, 1).int("residents", 0)
    spawn_wild(t, WILD, c[0], c[2] + 3)
    spawn_wild(t, WILD, c[0] + 1, c[2] + 3)
    if not (spawn_owned(t, c[0] + 2, c[2]) and spawn_owned(t, c[0] - 2, c[2])):
        return
    want = before + 4
    took = t.wait_hook(f"tw debug coop advance 1 --at {c[0]} {c[1]} {c[2]} {W}",
                       lambda r: r.int("residents", 0) >= want, timeout=40)
    if not t.check(took.int("residents", 0) == want, "the coop takes in two wild and two owned chickens",
                   took.lines[:1]):
        return
    t.ctx["residents"] = want
    t.check(chickens(t)["total"] == 0, "no chicken is left in the world", chickens(t))

    # Controls: one wild cow in the coop's section, one in the release spot's section.
    spawn_wild(t, "Cow", *COW_TICKING)
    spawn_wild(t, "Cow", *COW_PARKED)

    # The bot moves to the far side until the coop's section ticks and the release spot's section is parked.
    state = None
    for hot in (8, 7, 6, 9, 5):
        z = (coop_section - hot) * SECTION + 16
        t.B.tp(c[0] + 0.5, FEET, z + 0.5)
        sleep(t, 15, f"bot at z {z} ({hot} sections from the coop's section)")
        state = edge_state(t)
        t.note(f"bot at z {z}: {state}")
        if state["coopSectionTicks"] and not state["spotSectionListed"] and state["spotColumnLoaded"]:
            t.ctx["botZ"] = z
            break
    else:
        t.unjudged("coop ticking, release spot loaded but parked", f"no bot position gave that state: {state}")
        return
    t.check(True, "control: the coop's section ticks while the release spot's is loaded and parked",
            f"bot at z {t.ctx['botZ']}: {state}")

    # Morning, with the release spot parked: wait through several 30 s retries.
    mark = t.log.mark()
    set_hour(t, MORNING)
    samples = []
    for _ in range(10):
        sleep(t, 10, "morning sweep with the release spot parked")
        samples.append(chickens(t)["total"])
    t.note(f"chickens listed (ticking only) every 10 s while parked: {samples}")
    failed = t.log.since(mark, regex=r"could not release its|Companion respawn did not complete", noise=True) or []
    skipped = t.log.since(mark, regex=r"destination chunk is loaded but not ticking", noise=True) or []
    coop = t.log.since(mark, regex=r"(?i)coop|respawn", noise=True) or []
    t.note(f"log while parked: {len(failed)} failed-release lines, {len(skipped)} not-ticking lines; "
           f"coop/respawn lines: {[line for _, line in coop][:12]}")
    still = edge_state(t)
    t.check(still["coopSectionTicks"] and not still["spotSectionListed"],
            "the state held through the wait", still)

    # The bot walks back: the release spot ticks, parked copies (if any) load, the residents come out.
    t.B.tp(c[0] + 0.5, FEET, c[2] - 8.5)
    out = t.wait(lambda: chickens(t)["total"] >= t.ctx["residents"] and chickens(t), 60)
    sleep(t, 15, "settle after the residents came out")
    seen = chickens(t)
    t.note(f"chickens near the coop: {seen}")
    t.check(seen["total"] >= t.ctx["residents"], "every resident came out once the release spot ticks",
            f"{seen}, expected {t.ctx['residents']}")
    t.check(seen["total"] == t.ctx["residents"] and seen[WILD] == 2 and seen[OWNED] == 2,
            "no duplicate chickens: exactly the two wild and two owned residents",
            f"{seen}, expected 2 {WILD} + 2 {OWNED}")
    t.shot("chickens_after_release")


CASES = [parked_release]
