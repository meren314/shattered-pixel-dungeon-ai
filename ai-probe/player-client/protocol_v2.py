"""Deterministic public-state transport, including absent/null and inventory identity."""
import copy
import hashlib
import json


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def diff(old, new, path=None):
    path = [] if path is None else path
    if old == new:
        return []
    if isinstance(old, dict) and isinstance(new, dict):
        ops = [{"op": "delete", "path": path + [key]} for key in old if key not in new]
        for key in new:
            ops += diff(old[key], new[key], path + [key]) if key in old else [{"op": "set", "path": path + [key], "value": new[key]}]
        return ops
    if path == ["inventory"] and isinstance(old, list) and isinstance(new, list):
        if all(isinstance(row, dict) and isinstance(row.get("id"), str) for row in old + new):
            a, b = {row["id"]: row for row in old}, {row["id"]: row for row in new}
            if len(a) == len(old) and len(b) == len(new):
                return [{"op": "items", "path": path, "added": [row for row in new if row["id"] not in a],
                         "removed": [key for key in a if key not in b],
                         "updated": [{"id": key, "patch": diff(a[key], b[key])} for key in b if key in a and a[key] != b[key]],
                         "order": [row["id"] for row in new] if [row["id"] for row in old] != [row["id"] for row in new] else None}]
    return [{"op": "set", "path": path, "value": new}]


def apply(state, ops):
    result = copy.deepcopy(state)
    for op in ops:
        path = op["path"]
        if not path:
            if op["op"] != "set": raise ValueError("Invalid root operation")
            result = copy.deepcopy(op["value"]); continue
        parent = result
        for key in path[:-1]: parent = parent[key]
        key = path[-1]
        if op["op"] == "set": parent[key] = copy.deepcopy(op["value"])
        elif op["op"] == "delete": del parent[key]
        elif op["op"] == "items":
            rows = {row["id"]: copy.deepcopy(row) for row in parent[key]}
            for item_id in op["removed"]: del rows[item_id]
            for row in op["added"]:
                if row["id"] in rows: raise ValueError("Duplicate added item")
                rows[row["id"]] = copy.deepcopy(row)
            for update in op["updated"]:
                row = apply(rows[update["id"]], update["patch"])
                if row.get("id") != update["id"]: raise ValueError("Item identity changed")
                rows[update["id"]] = row
            order = op["order"] if op["order"] is not None else [row["id"] for row in parent[key]]
            if len(set(order)) != len(order) or set(order) != set(rows): raise ValueError("Invalid item order")
            parent[key] = [rows[item_id] for item_id in order]
        else: raise ValueError("Unknown patch operation")
    return result


class Codec:
    def __init__(self, store):
        self.store = store
        self.definitions = {}

    def ref(self, value, kind):
        key = kind[0] + ":" + digest(value)[:16]
        entry = {"kind": kind, "value": copy.deepcopy(value)}
        if key in self.store and self.store[key] != entry: raise ValueError("Reference collision")
        self.store[key] = entry
        self.definitions[key] = {"kind": kind, "value": value} if kind == "actions" else {"kind": kind, "characters": len(value)}
        return {"_spd_codec": "ref", "key": key}

    def encode(self, value, field=None):
        if field in ("description", "desc") and isinstance(value, str) and len(value) > 120:
            return self.ref(value, "description")
        if field in ("actions", "supported_actions", "action_time_estimates") and isinstance(value, (list, dict)):
            return self.ref(value, "actions")
        if field == "inventory" and isinstance(value, list) and all(isinstance(row, dict) for row in value):
            action_fields = ("actions", "supported_actions", "action_time_estimates")
            columns = list(dict.fromkeys(key for row in value for key in row if key not in action_fields))
            return {"_spd_codec": "table", "columns": columns,
                    "rows": [[self.encode(row.get(key), key) for key in columns] for row in value],
                    "present": [sum(1 << i for i, key in enumerate(columns) if key in row) for row in value],
                    "action_bundles": [self.ref({key: row[key] for key in action_fields if key in row}, "actions") if any(key in row for key in action_fields) else None for row in value]}
        if isinstance(value, dict):
            # Preserve literal objects containing the reserved discriminator.
            out = {key: self.encode(child, key) for key, child in value.items()}
            return {"_spd_codec": "object", "value": out} if "_spd_codec" in value else out
        if isinstance(value, list): return [self.encode(child) for child in value]
        return value

    def decode(self, value):
        if isinstance(value, list): return [self.decode(child) for child in value]
        if not isinstance(value, dict): return value
        kind = value.get("_spd_codec")
        if kind == "ref":
            if value["key"] not in self.store: raise ValueError("Unresolved reference")
            return copy.deepcopy(self.store[value["key"]]["value"])
        if kind == "object": return {key: self.decode(child) for key, child in value["value"].items()}
        if kind == "table":
            cols, rows, masks = value["columns"], value["rows"], value["present"]
            bundles = value["action_bundles"]
            if len(cols) != len(set(cols)) or len(rows) != len(masks) or len(bundles) != len(rows): raise ValueError("Invalid table")
            out = []
            for row, mask, bundle in zip(rows, masks, bundles):
                if len(row) != len(cols) or not isinstance(mask, int) or mask < 0 or mask >= 1 << len(cols): raise ValueError("Invalid presence mask")
                decoded = {key: self.decode(row[i]) for i, key in enumerate(cols) if mask & (1 << i)}
                if bundle is not None: decoded.update(self.decode(bundle))
                out.append(decoded)
            return out
        return {key: self.decode(child) for key, child in value.items()}


def verify_packet(packet, base, revision, scope, references):
    if packet.get("protocol") != 2 or packet["revision"] != packet["base_revision"] + 1: raise ValueError("Protocol/revision mismatch")
    if packet["base_revision"] != revision: raise ValueError("Baseline mismatch; resync required")
    if not packet["reset"] and packet["scope"] != scope: raise ValueError("Scope mismatch")
    codec = Codec(references)
    ops = codec.decode(packet["patch"])
    result = apply({} if packet["reset"] else base, ops)
    if digest(result) != packet["state_digest"]: raise ValueError("State digest mismatch")
    return result


def encode_ops(codec, ops):
    result = []
    for op in ops:
        encoded = copy.deepcopy(op)
        if op["op"] == "set": encoded["value"] = codec.encode(op["value"], op["path"][-1] if op["path"] else None)
        elif op["op"] == "items":
            encoded["added"] = [codec.encode(row) for row in op["added"]]
            encoded["updated"] = [{"id": row["id"], "patch": encode_ops(codec, row["patch"])} for row in op["updated"]]
        result.append(encoded)
    return result
