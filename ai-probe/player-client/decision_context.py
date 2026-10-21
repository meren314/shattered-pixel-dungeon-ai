"""Pure public-state merge and factual attention. No strategy or hidden data."""
import copy


def merge_map(previous, envelope):
    if not envelope or not envelope.get("cursor"):
        return None
    changes = envelope.get("changes", {})
    if envelope.get("reset"):
        return copy.deepcopy(changes)
    if not previous or previous.get("floor") != envelope.get("floor", previous.get("floor")):
        raise ValueError("Map delta without a matching baseline; resync")
    result = copy.deepcopy(previous)
    for key, value in changes.items():
        if key == "tiles": result.setdefault("tiles", {}).update(value)
        else: result[key] = copy.deepcopy(value)
    return result


def enrich(target, previous, frame, same_scope):
    """Keep descriptions inline once, so decision-critical facts need no detail call."""
    prior = previous if same_scope else {}
    knowledge = copy.deepcopy(prior.get("knowledge", {}))
    knowledge.update(copy.deepcopy(frame.get("knowledge", {})))
    if knowledge: target["knowledge"] = knowledge
    terrain = merge_map(prior.get("map_memory"), frame.get("map"))
    if terrain is not None: target["map_memory"] = terrain
    elif frame["observation"].get("scene") in ("AlchemyScene", "InterlevelScene") and "map_memory" in prior:
        target["map_memory"] = copy.deepcopy(prior["map_memory"])
    return attention(prior, target, frame)


def attention(old, new, frame):
    notices = []
    def add(kind, **facts): notices.append({"kind": kind, **facts})
    a, b = old.get("hero", {}), new.get("hero", {})
    if a and b:
        for field in ("hp", "shield", "level", "strength"):
            if a.get(field) != b.get(field): add("hero_changed", field=field, before=a.get(field), after=b.get(field))
    if old.get("hunger", {}).get("stage") != new.get("hunger", {}).get("stage") and "hunger" in new:
        add("hunger_stage", stage=new["hunger"].get("stage"))
    def entity_key(row): return (row.get("name"), row.get("x"), row.get("y"))
    prior_entities = {entity_key(row): row for row in old.get("entities", [])}
    for row in new.get("entities", []):
        previous = prior_entities.get(entity_key(row))
        if previous != row:
            add("entity_observation_changed", name=row.get("name"), x=row.get("x"), y=row.get("y"), fields=[key for key in row if previous is None or previous.get(key) != row[key]])
    for tier in new.get("talents", []):
        if tier.get("available_points", 0) > 0 and not any(t.get("tier") == tier.get("tier") and t.get("available_points") == tier.get("available_points") for t in old.get("talents", [])):
            add("talent_points_available", tier=tier.get("tier"), points=tier["available_points"])
    previous_items = {row.get("id"): row for row in old.get("inventory", [])}
    for row in new.get("inventory", []):
        prior = previous_items.get(row.get("id"))
        if prior != row: add("inventory_changed", item_id=row.get("id"), name=row.get("name"), identified=row.get("identified"), status=row.get("status"))
    removed = sorted(str(key) for key in previous_items if key not in {row.get("id") for row in new.get("inventory", [])})
    if removed: add("inventory_removed", item_ids=removed)
    if new.get("selection") and old.get("selection") != new["selection"]: add("selection_required")
    if new.get("terminal"): add("terminal", state=new["terminal"])
    if new.get("control_paused"): add("manual_control")
    if (old.get("depth"), old.get("branch")) != (new.get("depth"), new.get("branch")):
        add("floor_changed", depth=new.get("depth"), branch=new.get("branch"))
    if old.get("map_items") != new.get("map_items") and "map_items" in new: add("map_items_changed")
    if old.get("environment") != new.get("environment") and "environment" in new: add("visible_environment_changed")
    if old.get("effects") != new.get("effects") and "effects" in new: add("hero_effects_changed")
    if old.get("player_messages") != new.get("player_messages") and new.get("player_messages"): add("player_messages_updated")
    om, nm = old.get("map_memory", {}), new.get("map_memory", {})
    ot, nt = om.get("tiles", {}), nm.get("tiles", {})
    changed = sum(1 for key, value in nt.items() if ot.get(key) != value)
    if changed: add("map_updated", changed_cells=changed, floor=nm.get("floor"))
    for key, value in frame.get("knowledge", {}).items():
        if old.get("knowledge", {}).get(key) != value: add("knowledge_updated", key=key)
    return notices
