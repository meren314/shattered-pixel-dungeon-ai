"""Local presentation and usage accounting. No LLM calls or hidden game reads."""
import hashlib
import json
import time


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def reference(cache, kind, value):
    key = kind[0] + ":" + hashlib.sha256(compact(value).encode("utf-8")).hexdigest()[:16]
    cache.setdefault("references", {})[key] = {"kind": kind, "value": value}
    return key


def slim(payload, cache):
    """Preserve critical data; move explanatory prose and repeated action tables to refs."""
    action_sets = {}

    def visit(value):
        if isinstance(value, list):
            return [visit(row) for row in value]
        if not isinstance(value, dict):
            return value
        result = {}
        for key, field in value.items():
            if key in ("description", "desc") and isinstance(field, str) and len(field) > 120:
                result[key + "_ref"] = reference(cache, "description", field)
            else:
                result[key] = visit(field)
        return result

    def item(row):
        tables = {key: row[key] for key in ("actions", "supported_actions", "action_time_estimates") if key in row}
        rest = visit({key: value for key, value in row.items() if key not in tables})
        if tables:
            ref = reference(cache, "action_set", tables)
            rest["action_set"] = ref
            action_sets[ref] = tables
        return rest

    out = visit(payload)
    changes = out.get("changes", {})
    if isinstance(changes.get("inventory"), list):
        rows = [item(row) for row in payload["changes"]["inventory"]]
        columns = list(dict.fromkeys(key for row in rows for key in row))
        changes["inventory"] = {"columns": columns, "rows": [[row.get(key) for key in columns] for row in rows]}
    delta = out.get("inventory_delta")
    if delta:
        delta["added"] = [item(row) for row in payload["inventory_delta"]["added"]]
    if action_sets:
        out["action_sets"] = action_sets
    return out


def brief(state_output, cache, goal=None, note=None):
    """A fresh public snapshot, not a summary of development transcripts."""
    if goal is not None:
        if len(goal) > 200:
            raise ValueError("Goal is limited to 200 characters")
        cache["play_goal"] = goal
    if note is not None:
        if len(note) > 400:
            raise ValueError("Decision note is limited to 400 characters")
        cache["decision_note"] = note
    out = slim(state_output, cache)
    out["checkpoint"] = {"schema": 1, "created_at": time.time(),
                         "goal": cache.get("play_goal", ""), "decision_note": cache.get("decision_note", ""),
                         "note_is_intention_not_game_fact": True,
                         "recent_events": cache.get("recent_events", [])[-8:],
                         "map": "Stored by game service; use map or query route/region on demand.",
                         "fresh_observation_required_before_action": True}
    return out


def usage_entry(data, scope):
    if not isinstance(data, dict) or not isinstance(data.get("request_id"), str) or not data["request_id"]:
        raise ValueError("request_id is required for deduplication")
    if len(data["request_id"]) > 200:
        raise ValueError("request_id too long")
    counts = {}
    for key in ("input_tokens", "cached_input_tokens", "output_tokens"):
        value = data.get(key)
        if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value < 0):
            raise ValueError(key + " must be a nonnegative integer or null")
        counts[key] = value
    if counts["input_tokens"] is None or counts["output_tokens"] is None:
        raise ValueError("Record per-request input/output usage; cumulative totals are not accepted")
    if counts["cached_input_tokens"] is not None and counts["cached_input_tokens"] > counts["input_tokens"]:
        raise ValueError("cached input cannot exceed input")
    return {"request_id": data["request_id"], "time": time.time(), "scope": scope, **counts}


def usage_totals(rows):
    result = {"requests": len(rows), "input_tokens": sum(row["input_tokens"] for row in rows),
              "output_tokens": sum(row["output_tokens"] for row in rows)}
    known = [row for row in rows if row.get("cached_input_tokens") is not None]
    result["cached_reporting_requests"] = len(known)
    result["cached_input_tokens_known"] = sum(row["cached_input_tokens"] for row in known)
    result["uncached_input_tokens_known"] = sum(row["input_tokens"] - row["cached_input_tokens"] for row in known)
    result["total_tokens"] = result["input_tokens"] + result["output_tokens"]
    result["billing_cost"] = "unknown"
    return result
