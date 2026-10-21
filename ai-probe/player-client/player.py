"""Player interface: state, map, act --obs ID --action JSON, path --obs ID --cells JSON.

No automatic combat, retreat, healing or item choice. Python 3, standard library.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error
sys.path.insert(0, str(Path(__file__).resolve().parent))
import context_tools

BASE = "http://127.0.0.1:18765"
HERE = Path(__file__).resolve().parent
CACHE = HERE / "client-state.json"
TRACE = HERE / "trace.jsonl"
HANDOFF = HERE / "handoff.json"
USAGE = HERE / "model-usage.jsonl"
PAYLOADS = HERE / "payload-usage.jsonl"
FIELDS = ("observation_id", "scene", "ready", "input_ready", "control_paused", "reason",
          "hero", "depth", "run_info", "selected_challenges", "hunger", "talents", "timing",
          "action_time_estimates", "entities", "inventory", "effects", "environment", "ground", "map_items",
          "selection", "terminal", "allowed_actions", "keys", "player_messages", "story", "branch", "alchemy")


class ClientError(Exception):
    pass


def http(path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        try:
            return json.loads(exc.read())
        except (ValueError, UnicodeError):
            return {"error": "http_" + str(exc.code)}
    except (OSError, ValueError) as exc:
        raise ClientError(str(exc)) from exc


def read_cache():
    try:
        return json.loads(CACHE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def write_cache(cache):
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    temp = CACHE.with_suffix(".tmp")
    temp.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    os.replace(temp, CACHE)


def snapshot(obs):
    out = {key: obs[key] for key in FIELDS if key in obs}
    if "talents" in obs:
        out["active_talents"] = [{"id": talent.get("id"), "name": talent.get("name"), "points": talent["points"]}
                                 for tier in obs["talents"] for talent in tier.get("entries", [])
                                 if talent.get("points", 0) > 0]
        out["talent_rule"] = "Only active_talents are learned; a listed talent with points=0 has no effect."
    return out


def map_state():
    # Shared run reset by state clears this cursor. The service also resets on floor/session changes.
    cache = read_cache()
    result = http("/map?after=" + cache.get("map_cursor", ""))
    if "error" in result:
        raise ClientError(str(result["error"]))
    if result.get("cursor"):
        cache["map_cursor"] = result["cursor"]
        write_cache(cache)
    return result


def event_delta(cache):
    events = http("/events?after=" + str(cache.get("last_id", 0)))
    if "error" in events:
        raise ClientError("Event query failed: " + str(events["error"]))
    reset = (cache.get("session"), cache.get("epoch")) != (events.get("session"), events.get("epoch"))
    if reset:
        events = http("/events?after=0")
        if "error" in events:
            raise ClientError("Event reset failed")
    return events, reset


def inventory_delta(previous, current):
    """Stable item IDs permit patches without re-sending every item description."""
    old = {row["id"]: row for row in previous}
    new = {row["id"]: row for row in current}
    updates = []
    for item_id, row in new.items():
        if item_id not in old:
            continue
        before = old[item_id]
        changed = {key: value for key, value in row.items() if key not in before or before[key] != value}
        removed = [key for key in before if key not in row]
        if changed or removed:
            updates.append({"id": item_id, "changes": changed, "removed_fields": removed})
    return {"added": [row for item_id, row in new.items() if item_id not in old],
            "updated": updates, "removed": [item_id for item_id in old if item_id not in new],
            "order": [row["id"] for row in current] if list(old) != list(new) else None}


def state(full=False, reset=False):
    cache = {} if reset else read_cache()
    obs = http("/observe")
    if "error" in obs:
        raise ClientError(str(obs["error"]))
    events, changed_run = event_delta(cache)
    clock = (obs.get("timing") or {}).get("clock_id")
    changed_run |= bool(clock is not None and cache.get("clock") is not None and clock != cache["clock"])
    if changed_run:
        cache = {}
    current = snapshot(obs)
    # Busy responses are incomplete. Do not replace the last complete state with them.
    complete = bool(obs.get("input_ready")) or bool(obs.get("terminal")) or obs.get("scene") != "GameScene"
    previous = cache.get("state", {})
    output = {"reset": changed_run or not previous, "changes": current if full or changed_run or not previous
              else {key: current.get(key) for key in set(current) | set(previous)
                    if current.get(key) != previous.get(key)}}
    if not (full or changed_run or not previous) and "inventory" in output["changes"] and "inventory" in current and "inventory" in previous:
        output["inventory_delta"] = inventory_delta(previous["inventory"], current["inventory"])
        del output["changes"]["inventory"]
    output["observation_id"] = obs.get("observation_id")
    output["new_events"] = events.get("events", [])
    output["event_gap"] = bool(events.get("gap"))
    output["event_scope"] = {key: events.get(key) for key in ("session", "epoch", "last_id")}
    if output["event_gap"]:
        output["recovered_messages"] = obs.get("messages", [])
        output["requires_new_decision"] = True
    if complete:
        cache["state"] = current
    cache["recent_events"] = (cache.get("recent_events", []) + events.get("events", []))[-12:]
    cache.update({key: events.get(key) for key in ("session", "epoch", "last_id")})
    if clock is not None:
        cache["clock"] = clock
    write_cache(cache)
    return output


def settle(timeout=10):
    deadline = time.monotonic() + timeout
    while True:
        obs = http("/observe")
        if "error" in obs:
            raise ClientError(str(obs["error"]))
        if (obs.get("input_ready") or obs.get("terminal") or obs.get("scene") not in ("GameScene", "InterlevelScene")
                or obs.get("scene") == "InterlevelScene" and obs.get("story")):
            return obs
        if time.monotonic() >= deadline:
            return obs
        time.sleep(0.1)


def record(entry):
    TRACE.parent.mkdir(parents=True, exist_ok=True)
    with TRACE.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(entry, ensure_ascii=False) + "\n")


def action(obs_id, requested):
    if not isinstance(requested, dict) or not isinstance(requested.get("type"), str):
        raise ClientError("Action must be one object with type")
    before = http("/observe")
    story_continue = requested["type"] == "continue" and before.get("scene") == "InterlevelScene" and bool(before.get("story"))
    if not story_continue and (not before.get("input_ready") or before.get("observation_id") != obs_id):
        raise ClientError("Read current state and decide again; no action submitted")
    if before.get("control_paused"):
        raise ClientError("Manual control active; no action submitted")
    baseline = http("/events?after=0")
    if "error" in baseline:
        raise ClientError("Event baseline unavailable; no action submitted")
    payload = dict(requested) if story_continue else dict(requested, observation_id=obs_id)
    entry = {"time": time.time(), "before": snapshot(before), "action": payload}
    try:
        reply = http("/action", payload)
    except ClientError as exc:
        entry.update(submission_outcome="unknown", error=str(exc), followup="observe_only_do_not_resubmit")
        record(entry)
        raise ClientError("Submission outcome unknown; observe only, do not resubmit") from exc
    entry["action_result"] = reply
    try:
        after = settle()
        events = http("/events?after=" + str(baseline["last_id"]))
        entry.update(after=snapshot(after), events=events)
        bt, at = before.get("timing", {}), after.get("timing", {})
        if bt.get("clock_id") is not None and bt.get("clock_id") == at.get("clock_id"):
            entry["actual_time_spent"] = round(at["spent_total"] - bt["spent_total"], 3)
        reasons = []
        if after.get("terminal"):
            reasons.append("terminal")
        if any(e.get("kind") == "visual" and e.get("category") in ("damage", "damage_over_time", "miss")
               for e in events.get("events", [])):
            reasons.append("combat_feedback")
        if events.get("gap") or events.get("epoch") != baseline.get("epoch") or events.get("session") != baseline.get("session"):
            reasons.append("event_history_changed")
        entry.update(requires_new_decision=True, decision_triggers=reasons)
        result = state()
        record(entry)
        result.update(action_result=reply, actual_time_spent=entry.get("actual_time_spent"),
                      requires_new_decision=True, decision_triggers=reasons)
        return result
    except (ClientError, KeyError) as exc:
        entry.update(error=str(exc), followup="observe_only_do_not_resubmit")
        record(entry)
        return {"action_result": reply, "actual_time_spent": entry.get("actual_time_spent"), "error": str(exc), "requires_new_decision": True,
                "followup": "observe_only_do_not_resubmit"}


def path(obs_id, cells, steps, budget):
    # Delegate to the independently tested path executor; no automatic retreat algorithm.
    runtime = shutil.which("pwsh")
    if not runtime:
        raise ClientError("PowerShell 7 is required for bounded paths")
    before = snapshot(http("/observe"))
    proc = subprocess.run([runtime, "-NoProfile", "-File", str(HERE.parent / "Invoke-GuardedPath.ps1"),
                           "-ObservationId", str(obs_id), "-PathJson", json.dumps(cells),
                           "-MaxSteps", str(steps), "-TimeBudget", str(budget)],
                          capture_output=True, text=True, encoding="utf-8")
    if proc.returncode:
        record({"time": time.time(), "before": before, "path": cells, "error": proc.stderr,
                "followup": "observe_only_do_not_resubmit"})
        raise ClientError("Path executor stopped; observe only, do not resubmit: " + proc.stderr)
    result = json.loads(proc.stdout)
    after = state()
    after["batch"] = result.get("batch")
    after["requires_new_decision"] = True
    record({"time": time.time(), "before": before, "path": cells, "result": result, "after": read_cache().get("state")})
    return after


def checkpoint(goal=None, note=None):
    # Validation before observing avoids consuming events on invalid notes.
    if (goal is not None and len(goal) > 200) or (note is not None and len(note) > 400):
        raise ClientError("Goal <=200 chars; decision note <=400 chars")
    current = state(full=True)
    cache = read_cache()
    # Never export a stale cached hero as a current fact during processing.
    result = context_tools.brief(current, cache, goal, note)
    cache["outputs_since_checkpoint"] = 0
    cache["chars_since_checkpoint"] = 0
    write_cache(cache)
    HANDOFF.write_text(context_tools.compact(result), encoding="utf-8")
    return result


def switch_run(endpoint, body):
    before = http("/observe")
    if before.get("scene") != "TitleScene" or before.get("control_paused"):
        raise ClientError("Return to title and release manual control before starting/loading; no request submitted")
    entry = {"time": time.time(), "before": snapshot(before), "endpoint": endpoint, "request": body}
    try:
        reply = http(endpoint, body)
    except ClientError as exc:
        entry.update(error=str(exc), submission_outcome="unknown", followup="observe_only_do_not_resubmit")
        record(entry)
        raise ClientError("Start/load outcome unknown; observe only, do not resubmit") from exc
    entry["action_result"] = reply
    try:
        after = settle()
        entry["after"] = snapshot(after)
        result = state()
        result.update(action_result=reply, requires_new_decision=True)
    except ClientError as exc:
        entry["error"] = str(exc)
        result = {"action_result": reply, "followup": "observe_only_do_not_resubmit", "error": str(exc)}
    record(entry)
    return result


def detail(ref):
    cache = read_cache()
    scope = http("/events?after=" + str(cache.get("last_id", 0)))
    if (scope.get("session"), scope.get("epoch")) != (cache.get("session"), cache.get("epoch")):
        raise ClientError("Run changed; refresh state before using description references")
    entry = cache.get("references", {}).get(ref)
    if entry is None:
        raise ClientError("Unknown or previous-run reference; refresh state first")
    return {"ref": ref, **entry}


def usage(data=None):
    rows = []
    if USAGE.exists():
        rows = [json.loads(line) for line in USAGE.read_text(encoding="utf-8").splitlines() if line.strip()]
    cache = read_cache()
    scope = {key: cache.get(key) for key in ("session", "epoch", "clock")}
    duplicate = False
    if data is not None:
        entry = context_tools.usage_entry(data, scope)
        duplicate = any(row["request_id"] == entry["request_id"] for row in rows)
        if not duplicate:
            with USAGE.open("a", encoding="utf-8") as stream:
                stream.write(context_tools.compact(entry) + "\n")
            rows.append(entry)
    current = [row for row in rows if row.get("scope") == scope]
    return {"all_runs": context_tools.usage_totals(rows), "current_run": context_tools.usage_totals(current),
            "duplicate_ignored": duplicate, "source": "external model per-request usage; not inferred from game actions"}


def emit(result, command, raw=False):
    original = context_tools.compact(result)
    if not raw and command in ("state", "act", "path", "config", "start", "load"):
        cache = read_cache()
        result = context_tools.slim(result, cache)
        write_cache(cache)
    cache = read_cache()
    if command in ("state", "act", "path", "start", "load") and (result.get("changes") or result.get("new_events") or result.get("action_result") or result.get("batch")):
        cache["outputs_since_checkpoint"] = cache.get("outputs_since_checkpoint", 0) + 1
        cache["chars_since_checkpoint"] = cache.get("chars_since_checkpoint", 0) + len(context_tools.compact(result))
        if cache["outputs_since_checkpoint"] >= 20 or cache["chars_since_checkpoint"] >= 24000:
            result["context_rotation"] = {"recommended": True, "command": "brief", "requires_external_history_replacement": True}
        write_cache(cache)
    encoded = context_tools.compact(result)
    row = {"time": time.time(), "command": command, "raw_chars": len(original), "output_chars": len(encoded),
           "output_utf8_bytes": len(encoded.encode("utf-8")), "model_tokens": None,
           "session": cache.get("session"), "epoch": cache.get("epoch")}
    with PAYLOADS.open("a", encoding="utf-8") as stream:
        stream.write(context_tools.compact(row) + "\n")
    print(encoded)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", action="store_true", help="Decode patch values for debugging; retains protocol-v2 batch/ack semantics")
    parser.add_argument("--seen", help="Acknowledge the previously received batch before this command")
    sub = parser.add_subparsers(dest="command", required=True)
    look = sub.add_parser("state")
    look.add_argument("--full", action="store_true")
    look.add_argument("--reset", action="store_true")
    travel = sub.add_parser("goto", help="Click a player-known destination; automatically walk until arrival or a decision boundary")
    travel.add_argument("--obs", type=int, required=True)
    travel.add_argument("--x", type=int, required=True)
    travel.add_argument("--y", type=int, required=True)
    travel.add_argument("--seen", default=argparse.SUPPRESS)
    interaction = sub.add_parser("interact-at", help="Approach a known target and execute the explicit interaction intent")
    interaction.add_argument("--obs", type=int, required=True)
    interaction.add_argument("--x", type=int, required=True)
    interaction.add_argument("--y", type=int, required=True)
    interaction.add_argument("--intent", choices=("pickup", "open", "unlock", "talk", "descend", "ascend", "buy", "alchemy"), required=True)
    interaction.add_argument("--seen", default=argparse.SUPPRESS)
    sub.add_parser("map")
    acknowledgements = sub.add_parser("ack")
    acknowledgements.add_argument("--batch", required=True)
    sub.add_parser("resync")
    sub.add_parser("resume")
    sub.add_parser("view")
    context_ack = sub.add_parser("ack-context")
    context_ack.add_argument("--proposal", required=True)
    memories = sub.add_parser("memory")
    memories.add_argument("--json", type=json.loads, required=True)
    resolve = sub.add_parser("resolve")
    resolve.add_argument("--request", required=True)
    resolve.add_argument("--reason", required=True)
    resolve.add_argument("--human-reviewed", action="store_true")
    settings = sub.add_parser("config")
    settings.add_argument("--type", choices=("classes", "saves", "capabilities"), required=True)
    start = sub.add_parser("start")
    start.add_argument("--class", dest="hero_class", required=True)
    start.add_argument("--challenges", type=int, choices=range(512), required=True)
    load = sub.add_parser("load")
    load.add_argument("--slot", type=int, choices=range(1, 7), required=True)
    check = sub.add_parser("brief")
    check.add_argument("--goal")
    check.add_argument("--note")
    details = sub.add_parser("detail")
    details.add_argument("--ref", required=True)
    query = sub.add_parser("query")
    query.add_argument("--json", type=json.loads, required=True)
    count = sub.add_parser("usage")
    count.add_argument("--json", type=json.loads)
    act = sub.add_parser("act")
    act.add_argument("--obs", type=int, help="Required except visible floor story continue")
    act.add_argument("--action", type=json.loads, required=True)
    walk = sub.add_parser("path")
    walk.add_argument("--obs", type=int, required=True)
    walk.add_argument("--cells", type=json.loads, required=True)
    walk.add_argument("--max", type=int, choices=range(1, 6), default=3)
    walk.add_argument("--budget", type=float, default=3)
    walk.add_argument("--strict-gold", action="store_true")
    walk.add_argument("--strict-experience", action="store_true")
    for command in (look, act, walk, start, load, check):
        command.add_argument("--seen", default=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        from reliable_client import ReliableClient
        engine = ReliableClient(sys.modules[__name__])
        with engine.lock():
            if args.command in ("state", "map", "brief", "resume", "resync", "query", "config", "view", "memory", "detail", "ack-context") and args.seen: engine.ack(args.seen)
            if args.command == "state": result = engine.state(args.full, resync=args.reset)
            elif args.command == "map": result = engine.map()
            elif args.command == "brief": result = engine.brief(args.goal, args.note)
            elif args.command == "ack": result = engine.ack(args.batch)
            elif args.command == "ack-context": result = engine.context_ack(args.proposal)
            elif args.command == "resync": result = engine.state(full=True, resync=True)
            elif args.command == "resume": result = engine.resume()
            elif args.command == "memory": result = engine.memory(args.json)
            elif args.command == "view": result = {"protocol": 2, "revision": engine.read()["revision"], "scope": engine.read()["scope"], "acknowledged_public_state": engine.read()["state"], "fresh_observation_required_before_action": True}
            elif args.command == "detail": result = engine.detail(args.ref)
            elif args.command == "usage": result = engine.usage(args.json)
            elif args.command == "query": result = {"read_only": True, "result": http("/query", args.json)}
            elif args.command == "config":
                result = {"read_only": True, "result": http("/" + args.type)}
                if args.type == "capabilities":
                    result["client_commands"] = {"goto": {"parameters": ["obs", "x", "y", "seen"], "target": "player-known map", "interrupts_on_new_danger": True},
                                                 "interact-at": {"parameters": ["obs", "x", "y", "intent", "seen"], "intents": list(__import__("navigation").INTENTS),
                                                                 "preserves_confirmation": True, "automatic_resume": False}}

            elif args.command == "resolve": result = engine.resolve(args.request, args.reason, args.human_reviewed)
            elif args.command == "start": result = engine.act(None, {"hero_class": args.hero_class, "challenges": args.challenges}, args.seen, "/start")
            elif args.command == "load": result = engine.act(None, {"slot": args.slot}, args.seen, "/load")
            elif args.command == "interact-at": result = engine.interact_at(args.obs, args.x, args.y, args.intent, args.seen)
            elif args.command == "goto": result = engine.goto(args.obs, args.x, args.y, args.seen)
            elif args.command == "act": result = engine.act(args.obs, args.action, args.seen)
            else: result = engine.path(args.obs, args.cells, args.max, args.budget, args.seen, args.strict_gold, args.strict_experience)
            if args.raw and result.get("protocol") == 2 and "patch" in result:
                import protocol_v2
                result["patch"] = protocol_v2.Codec(engine.read()["references"]).decode(result["patch"])
                result["raw_patch"] = True
            engine.telemetry(result, args.command)
    except (ClientError, ValueError) as exc:
        failure = {"error": str(exc), "requires_new_decision": True}
        if "engine" in locals():
            try:
                journal = engine.read()
                failure["pending_operation"] = engine.public_operation(journal.get("operation"))
                if failure["pending_operation"]: failure["followup"] = "resume_only_do_not_resubmit"
                if journal.get("delivery"): failure["pending_batch_id"] = journal["delivery"]["packet"]["batch_id"]
            except ValueError: pass
        print(json.dumps(failure, ensure_ascii=False))
        return 1
    print(context_tools.compact(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
