"""Protocol v2. A single acknowledged consumer with durable action recovery."""
import contextlib
import copy
import json
import os
from pathlib import Path
import time
import uuid
from collections import deque
import protocol_v2 as p
import decision_context as dc
import navigation as nav
from urllib.parse import urlencode


class ProtocolError(ValueError): pass


class ReliableClient:
    def __init__(self, adapter, file=None):
        self.api = adapter
        self.file = Path(file) if file else adapter.HERE / "protocol-state.json"
        self.lockfile = self.file.with_suffix(".lock")

    def read(self):
        if not self.file.exists():
            return {"schema": 2, "channel": uuid.uuid4().hex, "revision": 0, "state": {}, "scope": None,
                    "event_id": 0, "references": {}, "memory": {"goal": "", "plan": "", "facts": [], "unresolved": []}}
        try:
            out = json.loads(self.file.read_text(encoding="utf-8"))
            if out.get("schema") != 2: raise ValueError("Wrong schema")
            return out
        except (ValueError, OSError) as exc: raise ProtocolError("Protocol journal damaged; do not delete it or submit actions") from exc

    def write(self, cache):
        self.file.parent.mkdir(parents=True, exist_ok=True)
        temp = self.file.with_suffix(".tmp")
        with temp.open("w", encoding="utf-8") as stream:
            stream.write(p.canonical(cache)); stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, self.file)

    @contextlib.contextmanager
    def lock(self):
        self.lockfile.parent.mkdir(parents=True, exist_ok=True)
        with self.lockfile.open("a+b") as stream:
            stream.seek(0, 2)
            if stream.tell() == 0: stream.write(b"0"); stream.flush()
            stream.seek(0)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc: raise ProtocolError("Another client command is running") from exc
            try: yield
            finally:
                stream.seek(0)
                if os.name == "nt": msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else: fcntl.flock(stream, fcntl.LOCK_UN)

    def get_frame(self, cache):
        for attempt in range(20):
            frame = self.api.http("/frame?" + urlencode({"after": cache.get("event_id", 0), "map_after": cache.get("map_cursor", "") if cache.get("state", {}).get("map_memory") else ""}))
            if "error" in frame: raise ProtocolError(str(frame["error"]))
            if "observation" not in frame or frame.get("decision_version") != 1: raise ProtocolError("Rebuild/restart game: unified /frame decision_version=1 is required")
            scope = self.scope(frame, cache)
            if cache.get("scope") and scope != cache["scope"]:
                frame = self.api.http("/frame?after=0&map_after=")
            if self.valid(frame): break
            if attempt < 19: time.sleep(0.05)
        return frame

    @staticmethod
    def scope(frame, cache):
        events, obs = frame["events"], frame["observation"]
        previous = cache.get("scope") or {}
        same = (events.get("session"), events.get("epoch")) == (previous.get("session"), previous.get("epoch"))
        return {"session": events.get("session"), "epoch": events.get("epoch"),
                "clock": obs.get("timing", {}).get("clock_id", previous.get("clock") if same else None)}

    @staticmethod
    def valid(frame):
        obs = frame["observation"]
        return bool(frame.get("consistent")) and not obs.get("reason", "").startswith("processing") and not obs.get("reason") in ("ui_pending", "scene_change") and (
            bool(obs.get("input_ready")) or bool(obs.get("terminal")) or obs.get("scene") == "TitleScene"
            or obs.get("scene") == "InterlevelScene" and bool(obs.get("story")))

    def stage(self, cache, frame, full=False, extras=None):
        scope = self.scope(frame, cache); reset = full or scope != cache.get("scope") or bool(frame["events"].get("gap"))
        good = self.valid(frame); obs = frame["observation"]
        target = self.api.snapshot(obs) if good else ({} if scope != cache.get("scope") else copy.deepcopy(cache["state"]))
        if good and scope == cache.get("scope") and "map_memory" in cache["state"] and (target.get("depth"), target.get("branch")) == (cache["state"].get("depth"), cache["state"].get("branch")):
            target["map_memory"] = cache["state"]["map_memory"]
        if scope != cache.get("scope"):
            cache["references"] = {}
            cache["memory"] = {"goal": "", "plan": "", "facts": [], "unresolved": []}
        extras = {} if extras is None else extras
        if "map_memory" in extras: target["map_memory"] = extras.pop("map_memory")
        notices = dc.enrich(target, cache["state"], frame, scope == cache.get("scope")) if good else []
        codec = p.Codec(cache.setdefault("references", {}))
        ops = [{"op": "set", "path": [], "value": target}] if reset else p.diff(cache["state"], target)
        encoded = p.encode_ops(codec, ops)
        packet = {"protocol": 2, "channel": cache["channel"], "batch_id": uuid.uuid4().hex,
                  "base_revision": cache["revision"], "revision": cache["revision"] + 1, "scope": scope,
                  "reset": reset, "valid": good, "observation_id": obs.get("observation_id"),
                  "status": {key: obs.get(key) for key in ("scene", "ready", "input_ready", "control_paused", "reason")},
                  "patch": encoded, "events": codec.encode(frame["events"].get("events", [])),
                  "event_cutoff": frame["events"].get("last_id", cache.get("event_id", 0)),
                  "event_gap": bool(frame["events"].get("gap")), "state_digest": p.digest(target),
                  "references": codec.definitions, "decision_version": 1, "attention": notices, **extras}
        packet["pending_operation"] = self.public_operation(cache.get("operation"))
        if cache.get("navigation") and ("travel" in extras or "interaction" in extras or cache["navigation"].get("status") in ("walking", "executing_interaction", "result_pending_or_unknown", "recovery_requires_new_decision", "interrupted_requires_new_decision")):
            packet["navigation"] = copy.deepcopy(cache["navigation"])
        if packet["event_gap"]:
            packet["requires_new_decision"] = True
            packet["recovered_messages"] = obs.get("messages", [])
        if cache.get("outputs", 0) >= 20 or cache.get("chars", 0) >= 24000:
            packet["context_rotation"] = {"recommended": True, "command": "brief; then ack-context after actual history replacement"}
        cache["delivery"] = {"packet": packet, "target": target}
        if good and isinstance(frame.get("map"), dict) and frame["map"].get("cursor"):
            cache["delivery"]["map_cursor"] = frame["map"]["cursor"]
        # Validate before publication, not after an AI has interpreted it.
        p.verify_packet(packet, cache["state"], cache["revision"], cache.get("scope"), cache["references"])
        self.write(cache)
        return packet

    @staticmethod
    def public_operation(op):
        if not op: return None
        if op.get("status") in ("completed", "rejected", "not_submitted", "manually_resolved"): return None
        return {key: op.get(key) for key in ("request_id", "endpoint", "body", "status", "before_version", "result")}

    def state(self, full=False, resync=False):
        cache = self.read()
        if cache.get("delivery") and not resync: return cache["delivery"]["packet"]
        return self.stage(cache, self.get_frame(cache), full=full or resync)

    def ack(self, batch):
        cache = self.read(); delivery = cache.get("delivery")
        if not delivery:
            if batch in cache.get("ack_history", []): return {"acknowledged": batch, "duplicate": True, "revision": cache["revision"]}
            raise ProtocolError("Unknown batch; state/resync first")
        packet = delivery["packet"]
        if packet["channel"] != cache["channel"]: raise ProtocolError("Consumer channel mismatch")
        if packet["batch_id"] != batch: raise ProtocolError("Batch mismatch; no acknowledgement applied")
        reconstructed = p.verify_packet(packet, cache["state"], cache["revision"], cache.get("scope"), cache["references"])
        if reconstructed != delivery["target"]: raise ProtocolError("Decoder mismatch")
        cache.update(state=reconstructed, revision=packet["revision"], scope=packet["scope"], event_id=packet["event_cutoff"])
        cache["recent_events"] = (cache.get("recent_events", []) if not packet["reset"] else []) + p.Codec(cache["references"]).decode(packet["events"])
        cache["recent_events"] = cache["recent_events"][-12:]
        cache["ack_history"] = (cache.get("ack_history", []) + [batch])[-16:]
        cache["outputs"] = cache.get("outputs", 0) + 1
        cache["chars"] = cache.get("chars", 0) + len(p.canonical(packet))
        if "context_proposal" in packet: cache["context_proposal"] = {"id": packet["context_proposal"], "batch": batch, "scope": packet["scope"]}
        if "map_cursor" in delivery: cache["map_cursor"] = delivery["map_cursor"]
        del cache["delivery"]; self.write(cache)
        # Keep actual consumed public state locally for future reviews, never print it again.
        audit_error = None
        try:
            with self.file.with_name("decision-history.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(p.canonical({"batch_id": batch, "revision": cache["revision"], "scope": cache["scope"],
                                          "state": reconstructed, "packet": packet, "events": p.Codec(cache["references"]).decode(packet["events"])}) + "\n")
        except OSError as exc: audit_error = str(exc)
        return {"audit_warning": audit_error, "acknowledged": batch, "revision": cache["revision"], "state_digest": p.digest(reconstructed)}

    def seen(self, batch=None):
        if batch: self.ack(batch)
        if self.read().get("delivery"): raise ProtocolError("Acknowledge the last batch with ack/--seen before submitting a command")

    def guard_operation(self):
        op = self.read().get("operation")
        if op and op["status"] not in ("completed", "rejected", "not_submitted", "manually_resolved"):
            raise ProtocolError("An unresolved request blocks new actions; use resume, never resend")

    def perform(self, endpoint, body, before):
        self.guard_operation(); cache = self.read()
        request_id = uuid.uuid4().hex
        op = {"request_id": request_id, "endpoint": endpoint, "body": dict(body, request_id=request_id),
              "before_version": before.get("observation_id"), "before": self.api.snapshot(before), "scope": cache.get("scope"), "status": "prepared"}
        cache["operation"] = op; self.write(cache)
        op["status"] = "submitted"; self.write(cache)
        try:
            reply = self.api.http(endpoint, op["body"])
        except Exception as exc:
            op.update(status="unknown", error=str(exc)); self.write(cache)
            self.api.record({"protocol": 2, "pending_operation": op})
            return {"request_id": request_id, "status": "unknown", "followup": "resume_only_do_not_resubmit"}
        if reply.get("duplicate"):
            receipt = reply.get("receipt", {}); reply = dict(receipt.get("result", {"error": "request_outcome_unknown"}), request_status=receipt.get("status", "unknown"))
        status = reply.get("request_status", "unknown")
        if status not in ("accepted", "rejected"): status = "unknown"
        op.update(result=reply, status=status); self.write(cache)
        return reply

    def finish(self):
        cache = self.read(); op = cache.get("operation")
        frame = self.get_frame(cache)
        if op and op["status"] == "accepted" and self.valid(frame):
            if op.get("scope", {}).get("session") != frame["events"].get("session"):
                op["status"] = "review_required_after_restart"; self.write(cache); return frame
            before_clock = op["before"].get("timing", {}).get("clock_id")
            after_clock = frame["observation"].get("timing", {}).get("clock_id")
            if op["endpoint"] == "/action" and before_clock and after_clock and before_clock != after_clock:
                op["status"] = "review_required_after_run_change"; self.write(cache); return frame
            op["status"] = "completed"; op["after"] = self.api.snapshot(frame["observation"])
            bt, at = op["before"].get("timing", {}), frame["observation"].get("timing", {})
            if bt.get("clock_id") is not None and bt.get("clock_id") == at.get("clock_id"):
                op["actual_time_spent"] = round(at["spent_total"] - bt["spent_total"], 3)
            self.write(cache)
            self.api.record({"protocol": 2, **op, "events": frame["events"]})
        return frame

    def wait(self):
        end = time.monotonic() + 10
        while True:
            frame = self.finish()
            if self.valid(frame) or time.monotonic() >= end: return frame
            time.sleep(0.1)

    def act(self, obs_id, body, seen=None, endpoint="/action"):
        self.seen(seen); self.guard_operation()
        if not isinstance(body, dict): raise ProtocolError("Body must be an object")
        cache = self.read(); frame = self.get_frame(cache); before = frame["observation"]
        if not cache["revision"]: return self.stage(cache, frame, extras={"error": "acknowledge_initial_state_before_action"})
        if not self.valid(frame): return self.stage(cache, frame)
        if before.get("control_paused"): raise ProtocolError("Manual control active")
        if frame["events"].get("events") or frame["events"].get("gap"):
            return self.stage(cache, frame, extras={"requires_new_decision": True, "reason": "unseen_events_before_action_no_submission"})
        if endpoint == "/action":
            story = body.get("type") == "continue" and before.get("scene") == "InterlevelScene" and before.get("story")
            if not story and (obs_id is None or before.get("observation_id") != obs_id or not before.get("input_ready")):
                return self.stage(cache, frame, extras={"error": "stale_or_not_ready_no_submission", "requires_new_decision": True})
            body = dict(body) if story else dict(body, observation_id=obs_id)
        elif before.get("scene") != "TitleScene": raise ProtocolError("Return to title before start/load")
        if cache.get("navigation") and cache["navigation"].get("status") in ("recovery_requires_new_decision", "interrupted_requires_new_decision"):
            cache["navigation"]["status"] = "superseded_by_explicit_action"; self.write(cache)
        reply = self.perform(endpoint, body, before)
        frame = self.wait() if self.read().get("operation", {}).get("status") == "accepted" else self.get_frame(self.read())
        cache = self.read(); op = cache.get("operation", {})
        return self.stage(cache, frame, extras={"action_result": reply, "actual_time_spent": op.get("actual_time_spent"), "requires_new_decision": True})

    def resume(self, seen=None):
        self.seen(seen); cache = self.read(); op = cache.get("operation")
        if cache.get("navigation") and cache["navigation"].get("status") in ("walking", "executing_interaction", "result_pending_or_unknown", "interrupted_requires_new_decision"):
            cache["navigation"]["status"] = "recovery_requires_new_decision"; self.write(cache)
        if op and op["status"] == "prepared": op["status"] = "not_submitted"; self.write(cache)
        elif op and op["status"] in ("submitted", "unknown"):
            receipt = self.api.http("/request?id=" + op["request_id"])
            data = receipt.get("receipt") or {}
            if data.get("status") in ("accepted", "rejected"):
                op.update(status=data["status"], result=data.get("result")); self.write(cache)
        frame = self.wait() if self.read().get("operation", {}).get("status") == "accepted" else self.get_frame(self.read())
        op = self.read().get("operation") or {}
        return self.stage(self.read(), frame, extras={"requires_new_decision": True, "operation_resolution": {key: op.get(key) for key in ("request_id", "status", "actual_time_spent")}})

    def brief(self, goal=None, plan=None, seen=None):
        if goal is not None and len(goal) > 200 or plan is not None and len(plan) > 400: raise ProtocolError("Memory text too long")
        self.seen(seen); cache = self.read(); frame = self.get_frame(cache)
        if not self.valid(frame): return self.stage(cache, frame, extras={"error": "wait_for_stable_frame_before_context_rotation"})
        packet = self.stage(cache, frame, full=True)
        cache = self.read()
        memory = cache["memory"]
        if goal is not None:
            if len(goal) > 200: raise ProtocolError("Goal too long")
            memory["goal"] = goal
        if plan is not None:
            if len(plan) > 400: raise ProtocolError("Plan too long")
            memory["plan"] = plan
        packet["memory"] = copy.deepcopy(memory)
        packet["recent_events"] = (cache.get("recent_events", []) if packet["scope"] == cache.get("scope") else [])[-8:]
        packet["context_proposal"] = uuid.uuid4().hex
        packet["context_instruction"] = "Acknowledge this batch; replace external history; then ack-context this proposal. Creating brief does not reset counters."
        cache["delivery"]["packet"] = packet; self.write(cache)
        self.api.HANDOFF.write_text(p.canonical(packet), encoding="utf-8")
        return packet

    def context_ack(self, proposal):
        cache = self.read()
        record = cache.get("context_proposal", {})
        if cache.get("delivery") or record.get("id") != proposal or record.get("scope") != cache.get("scope"): raise ProtocolError("Current context proposal must have been acknowledged first")
        if cache.get("context_confirmed") == proposal: return {"context_replacement_reported": proposal, "duplicate": True, "counters_not_reset_again": True}
        cache["outputs"] = 0; cache["chars"] = 0; cache["context_confirmed"] = proposal; self.write(cache)
        return {"context_replacement_reported": proposal, "external_agent_assertion_not_automatic_verification": True}

    def memory(self, data):
        cache = self.read()
        if not isinstance(data, dict): raise ProtocolError("Memory must be an object")
        for key in data:
            if key not in ("goal", "plan", "facts", "unresolved"): raise ProtocolError("Unknown memory category")
        for key in ("goal", "plan"):
            if key in data and (not isinstance(data[key], str) or len(data[key]) > (200 if key == "goal" else 400)): raise ProtocolError("Invalid memory text")
        for key in ("facts", "unresolved"):
            if key in data:
                if not isinstance(data[key], list) or len(data[key]) > 16: raise ProtocolError("Memory list too large; resolve/archive explicitly")
                for row in data[key]:
                    if not isinstance(row, dict) or not isinstance(row.get("text"), str) or len(row["text"]) > 400 or not row.get("source") or "observed_at" not in row:
                        raise ProtocolError("Facts/issues require text, source and observed_at")
        cache["memory"].update(copy.deepcopy(data)); self.write(cache)
        return {"memory": cache["memory"], "manual_claims_not_authoritative_game_state": True}

    def detail(self, ref):
        cache = self.read(); frame = self.get_frame(cache)
        reference_scope = cache.get("delivery", {}).get("packet", {}).get("scope", cache.get("scope"))
        if self.scope(frame, cache) != reference_scope: raise ProtocolError("Run changed; resync before querying old references")
        entry = cache["references"].get(ref)
        if not entry: raise ProtocolError("Unknown reference")
        return {"ref": ref, **entry}

    def map(self, seen=None):
        # Compatibility command: maps are now part of every normal decision packet.
        self.seen(seen); cache = self.read()
        return self.stage(cache, self.get_frame(cache))

    def telemetry(self, result, command):
        row = {"protocol": 2, "command": command, "time": time.time(), "output_chars": len(p.canonical(result)), "model_tokens": None}
        with self.api.PAYLOADS.open("a", encoding="utf-8") as stream: stream.write(p.canonical(row) + "\n")

    def resolve(self, request_id, reason, human_reviewed):
        if not human_reviewed or not reason or len(reason) > 400: raise ProtocolError("Manual resolution needs human review and an explicit reason")
        cache = self.read(); op = cache.get("operation")
        if not op or op["request_id"] != request_id: raise ProtocolError("Request mismatch")
        op.update(status="manually_resolved", resolution_reason=reason, human_review_reported=True)
        self.write(cache); self.api.record({"protocol": 2, "manual_resolution": op})
        return {"request_id": request_id, "status": "manually_resolved", "not_a_claim_that_the_action_completed": True}

    @staticmethod
    def path_change(before, after, remaining, policy):
        a, b = before.get("hero", {}), after.get("hero", {})
        if after.get("control_paused"): return "manual_control", []
        if after.get("selection") or not after.get("ready"): return "interface_changed", []
        if any(not row.get("interactive") for row in after.get("entities", [])): return "visible_object_requires_decision", []
        if before.get("timing", {}).get("clock_id") != after.get("timing", {}).get("clock_id"): return "clock_changed", []
        if (before.get("depth"), before.get("branch")) != (after.get("depth"), after.get("branch")): return "floor_changed", []
        gains = []
        for key in ("hp", "shield", "level", "strength", "max_hp"):
            if policy.get("target_travel") and key in ("hp", "shield") and b.get(key, 0) > a.get(key, 0):
                gains.append({"field": key, "before": a.get(key), "after": b.get(key)}); continue
            if a.get(key) != b.get(key): return "hero_" + key + "_changed", []
        for key in ("keys", "inventory", "effects", "talents", "run_info", "player_messages"):
            if before.get(key) != after.get(key): return key + "_changed", []
        if before.get("hunger", {}).get("stage") != after.get("hunger", {}).get("stage"): return "hunger_changed", []
        old = {(row[0], row[1]): row[2] for row in before.get("cells", [])}
        new = {(row[0], row[1]): row[2] for row in after.get("cells", [])}
        route_changed = any(tuple(cell) in old and tuple(cell) in new and old[tuple(cell)] != new[tuple(cell)] for cell in remaining) if policy.get("target_travel") else any(old.get(tuple(cell)) != new.get(tuple(cell)) for cell in remaining)
        if route_changed: return "remaining_route_changed", []
        if before.get("environment") != after.get("environment"): return "environment_changed", []
        if before.get("ground") != after.get("ground") and not policy.get("target_travel"): return "ground_items_changed", []
        allowed = gains
        if policy.get("target_travel") and before.get("ground") != after.get("ground"): allowed.append({"field": "visible_ground_changed"})
        for key, permission in (("gold", "allow_gold_gain"), ("experience", "allow_experience_gain")):
            av, bv = a.get(key, 0), b.get(key, 0)
            if av != bv:
                if bv < av or not policy[permission]: return key + "_changed", []
                allowed.append({"field": key, "before": av, "after": bv})
        changed_cells = sum(1 for cell in set(old) | set(new) if old.get(cell) != new.get(cell))
        if changed_cells: allowed.append({"field": "unrelated_visible_cells", "count": changed_cells})
        return None, allowed

    def goto(self, obs_id, x, y, seen=None):
        return self.navigate(obs_id, x, y, seen=seen)

    def interact_at(self, obs_id, x, y, intent, seen=None):
        if intent not in nav.INTENTS: raise ProtocolError("Unsupported interaction intent")
        return self.navigate(obs_id, x, y, intent, seen)

    def navigate(self, obs_id, x, y, intent=None, seen=None):
        self.seen(seen); self.guard_operation()
        if type(x) is not int or type(y) is not int: raise ProtocolError("Target coordinates must be integers")
        cache = self.read(); frame = self.get_frame(cache); obs = frame["observation"]
        if not self.valid(frame) or not obs.get("ready") or obs.get("observation_id") != obs_id:
            return self.stage(cache, frame, extras={"error": "stale_or_not_ready_no_submission", "requires_new_decision": True})
        if not cache["revision"]: return self.stage(cache, frame, extras={"error": "acknowledge_initial_state_before_action"})
        if frame["events"].get("events") or frame["events"].get("gap"):
            return self.stage(cache, frame, extras={"reason": "unseen_events_before_navigation_no_submission", "requires_new_decision": True})
        known = nav.known_cells(cache, frame); goal = (x, y)
        if goal not in known: raise ProtocolError("Target must be on player-known terrain")
        try:
            fact = nav.target_fact(obs, known, goal, intent) if intent else None
            route = nav.route(known, obs, goal, intent in nav.ADJACENT)
        except ValueError as exc: raise ProtocolError(str(exc)) from exc
        workflow = {"id": uuid.uuid4().hex, "target": [x, y], "intent": intent or "move", "status": "walking", "target_fact": fact,
                    "scope": self.scope(frame, cache), "automatic_resume": False}
        cache["navigation"] = workflow; self.write(cache)
        try:
            result = self.path(obs_id, route, len(route), 256, _travel=True, _publish=False) if route else {
                "frame": frame, "batch": {"submitted_steps": 0, "actual_time_spent": 0, "stop_reason": "path_complete"}}
            frame = result["frame"]; current = frame["observation"]; batch = result["batch"]
            pos = (current.get("hero", {}).get("x"), current.get("hero", {}).get("y"))
            arrived = (pos == goal) if intent not in nav.ADJACENT else (None not in pos and max(abs(pos[0]-x),abs(pos[1]-y)) <= 1)
            extras = {"batch": batch, "travel": {"target": [x,y], "arrived": arrived, "basis": "player-known terrain; each next step revalidated in current view"}, "requires_new_decision": True}
            if not intent: workflow["status"] = "arrived" if arrived and batch["stop_reason"] == "path_complete" else "interrupted"
            elif not arrived or batch["stop_reason"] != "path_complete": workflow["status"] = "interrupted"
            else:
                # No internal ACK: movement and interaction are a single delivered decision batch.
                fresh = self.get_frame(self.read())
                if (not self.valid(fresh) or fresh["observation"].get("observation_id") != current.get("observation_id")
                        or self.scope(fresh, cache) != workflow["scope"]
                        or fresh["events"].get("last_id") != frame["events"].get("last_id") or fresh["events"].get("gap")):
                    frame = fresh; workflow["status"] = "interrupted_before_interaction"
                elif route and any(not row.get("interactive") for row in fresh["observation"].get("entities", [])):
                    frame = fresh; workflow["status"] = "interrupted_by_visible_enemy"
                elif not any(row[:2] == [x,y] for row in fresh["observation"].get("cells", [])):
                    frame = fresh; workflow["status"] = "target_not_currently_visible"
                else:
                    now = fresh["observation"]
                    try: same = nav.target_fact(now, nav.known_cells(self.read(), fresh), goal, intent) == fact
                    except ValueError: same = False
                    if not same:
                        frame = fresh; workflow["status"] = "target_changed"
                    else:
                        workflow["status"] = "executing_interaction"; cache = self.read(); cache["navigation"] = workflow; self.write(cache)
                        body = {"type": nav.INTENTS[intent], "observation_id": now["observation_id"]}
                        if intent not in ("pickup", "descend", "ascend"): body.update(x=x, y=y)
                        reply = self.perform("/action", body, now)
                        workflow["request_id"] = self.read()["operation"]["request_id"]
                        frame = self.wait() if self.read()["operation"]["status"] == "accepted" else self.get_frame(self.read())
                        if self.scope(frame, cache) == workflow["scope"]:
                            knowledge = dict(result["frame"].get("knowledge", {})); knowledge.update(frame.get("knowledge", {})); frame = dict(frame, knowledge=knowledge)
                        op = self.read()["operation"]; after = frame["observation"]
                        extras.update(action_result=reply, interaction_time_spent=op.get("actual_time_spent"))
                        workflow["status"] = self.interaction_outcome(intent, now, after, op, goal)
            interaction_time = extras.get("interaction_time_spent", 0)
            extras["navigation_time_spent"] = None if interaction_time is None else round(batch.get("actual_time_spent", 0) + interaction_time, 3)
            if intent: extras["interaction"] = {"intent": intent, "target": [x,y], "status": workflow["status"], "approach_arrived": arrived,
                                                "request_id": workflow.get("request_id")}
            cache = self.read(); cache["navigation"] = workflow; self.write(cache)
            return self.stage(cache, frame, extras=extras)
        except Exception:
            cache = self.read(); workflow["status"] = "interrupted_requires_new_decision"; cache["navigation"] = workflow; self.write(cache)
            raise

    @staticmethod
    def interaction_outcome(intent, before, after, op, goal):
        if op.get("status") == "rejected": return "rejected"
        if op.get("status") != "completed": return "result_pending_or_unknown"
        if after.get("selection") or after.get("story") or after.get("scene") == "AlchemyScene": return "awaiting_user_choice"
        if intent in ("ascend", "descend"):
            return "completed" if (before.get("depth"),before.get("branch")) != (after.get("depth"),after.get("branch")) else "not_completed"
        if intent in ("pickup", "buy"):
            old = {row.get("id"): row.get("quantity", 0) for row in before.get("inventory", [])}
            gained = any(row.get("id") not in old or row.get("quantity", 0) > old[row.get("id")] for row in after.get("inventory", []))
            money = after.get("hero", {}).get("gold", 0) > before.get("hero", {}).get("gold", 0) if intent == "pickup" else after.get("hero", {}).get("gold", 0) < before.get("hero", {}).get("gold", 0)
            # Keys/dew/unique pickups may change public state outside inventory.
            keys = intent == "pickup" and before.get("keys") != after.get("keys")
            return "completed" if gained or money or keys else "not_completed"
        if intent == "open":
            heap = next((row for row in after.get("map_items", after.get("ground", [])) if (row.get("x"),row.get("y")) == goal), None)
            return "completed" if heap is None or not heap.get("container") else "not_completed"
        if intent == "unlock":
            tile = next((row[2] for row in after.get("cells", []) if (row[0],row[1]) == goal), None)
            return "completed" if tile is not None and tile not in ("锁着的门", "水晶之门", "锁着的楼层出口") else "not_completed"
        return "not_completed"

    def path(self, obs_id, cells, steps, budget, seen=None, strict_gold=False, strict_experience=False, _travel=False, _publish=True):
        self.seen(seen); self.guard_operation()
        if not isinstance(cells, list) or any(not isinstance(cell, list) or len(cell) != 2 or any(type(n) is not int or n < 0 for n in cell) for cell in cells): raise ProtocolError("Path must contain integer coordinate pairs")
        if not 1 <= steps <= (256 if _travel else 5) or not 0 < budget <= (256 if _travel else 5): raise ProtocolError("Invalid path limits")
        cache = self.read(); frame = self.get_frame(cache); obs = frame["observation"]
        path_knowledge = copy.deepcopy(frame.get("knowledge", {}))
        if obs.get("observation_id") != obs_id: raise ProtocolError("Stale path observation")
        if frame["events"].get("events") or frame["events"].get("gap"):
            if not _publish: return {"frame": frame, "batch": {"submitted_steps": 0, "actual_time_spent": 0, "stop_reason": "unseen_events_before_path_no_submission"}}
            return self.stage(cache, frame, extras={"requires_new_decision": True, "reason": "unseen_events_before_path_no_submission"})
        policy = {"allow_gold_gain": not strict_gold, "allow_experience_gain": not strict_experience}
        if _travel: policy["target_travel"] = True
        count = 0; spent = 0; reason = "path_complete"; allowed = []
        safe_tiles = nav.SAFE
        deadline = time.monotonic() + 45
        for index, cell in enumerate(cells):
            if time.monotonic() >= deadline: reason = "execution_time_limit"; break
            if count >= steps: reason = "step_limit"; break
            if not self.valid(frame) or not obs.get("ready") or obs.get("selection"): reason = "not_ready"; break
            if obs.get("control_paused"): reason = "manual_control"; break
            if any(not row.get("interactive") for row in obs.get("entities", [])): reason = "visible_object_requires_decision"; break
            x, y = cell; hero = obs["hero"]; dx, dy = x - hero["x"], y - hero["y"]
            if max(abs(dx), abs(dy)) != 1: reason = "non_adjacent"; break
            terrain = next((row[2] for row in obs.get("cells", []) if row[:2] == cell), None)
            if terrain not in safe_tiles: reason = "unknown_or_unsafe_terrain"; break
            if any(row.get("x") == x and row.get("y") == y for row in obs.get("entities", [])): reason = "occupied"; break
            if any(row.get("kind") == "field" or row.get("x") == x and row.get("y") == y for row in obs.get("environment", [])): reason = "environment"; break
            estimate = obs.get("action_time_estimates", {}).get("move")
            if estimate is None or spent + estimate > budget: reason = "time_budget"; break
            before = obs; cutoff = frame["events"]["last_id"]
            reply = self.perform("/action", {"type": "move", "dx": dx, "dy": dy, "observation_id": before["observation_id"]}, before)
            count += 1
            if not reply.get("accepted"): reason = "request_not_confirmed"; frame = self.get_frame(self.read()); break
            frame = self.wait(); obs = frame["observation"]
            if self.valid(frame): path_knowledge.update(frame.get("knowledge", {}))
            op = self.read()["operation"]; cost = op.get("actual_time_spent")
            if cost is None: reason = "time_unavailable"; break
            spent += cost
            if frame["events"].get("gap") or self.scope(frame, cache) != self.scope({"events": frame["events"], "observation": before}, cache): reason = "event_history_changed"; break
            if any(e.get("id", 0) > cutoff and e.get("kind") == "visual" and e.get("category") in ("damage", "damage_over_time", "miss") for e in frame["events"].get("events", [])): reason = "combat_feedback"; break
            if obs.get("hero", {}).get("x") != x or obs.get("hero", {}).get("y") != y: reason = "unexpected_position"; break
            if abs(cost - estimate) > 0.002: reason = "time_cost_changed"; break
            change, accepted = self.path_change(before, obs, cells[index + 1:], policy); allowed += accepted
            if change: reason = change; break
            if not _travel and index + 1 < len(cells) and any(row.get("x") == x and row.get("y") == y for row in obs.get("ground", [])): reason = "ground_item_requires_decision"; break
        batch = {"submitted_steps": count, "actual_time_spent": round(spent, 3), "stop_reason": reason,
                 "policy": policy, "reported_allowed_changes": allowed, "requires_new_decision": True}
        self.api.record({"protocol": 2, "path": cells, "batch": batch})
        if self.scope(frame, cache) == cache.get("scope"):
            frame = dict(frame, knowledge=path_knowledge)
        if not _publish: return {"frame": frame, "batch": batch}
        return self.stage(self.read(), frame, extras={"batch": batch, "requires_new_decision": True})

    def usage(self, data=None):
        file = self.file.with_name("protocol-model-usage.jsonl")
        rows = [json.loads(line) for line in file.read_text(encoding="utf-8").splitlines() if line] if file.exists() else []
        scope = self.read().get("scope"); duplicate = False
        if data is not None:
            entry = self.api.context_tools.usage_entry(data, scope)
            duplicate = any(row["request_id"] == entry["request_id"] for row in rows)
            if not duplicate:
                with file.open("a", encoding="utf-8") as stream: stream.write(p.canonical(entry) + "\n")
                rows.append(entry)
        return {"all_runs": self.api.context_tools.usage_totals(rows), "current_scope": self.api.context_tools.usage_totals([row for row in rows if row["scope"] == scope]), "duplicate_ignored": duplicate}
