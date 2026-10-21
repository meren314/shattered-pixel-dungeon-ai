"""Planning from public remembered terrain, never from the live hidden level."""
from collections import deque
import decision_context as dc

SAFE = {"地板", "草", "高草", "枯萎的植被", "余烬", "开着的门", "关着的门", "浑浊水潭", "楼层入口", "楼层出口", "基座"}
INTENTS = {"pickup": "pickup", "open": "open_container", "unlock": "unlock", "talk": "interact",
           "descend": "descend", "ascend": "ascend", "buy": "buy", "alchemy": "alchemy"}
ADJACENT = {"open", "unlock", "talk", "alchemy"}


def known_cells(cache, frame):
    obs = frame["observation"]
    memory = dc.merge_map(cache.get("state", {}).get("map_memory"), frame.get("map"))
    result = {}
    if memory and memory.get("floor") == "%s:%s" % (obs.get("branch", 0), obs.get("depth")):
        width = memory.get("size", [0])[0]; legend = memory.get("legend", [])
        if width:
            for cell, index in memory.get("tiles", {}).items():
                if type(index) is int and 0 <= index < len(legend): result[(int(cell) % width, int(cell) // width)] = legend[index]
    result.update({(row[0], row[1]): row[2] for row in obs.get("cells", [])})
    return result


def route(known, obs, goal, adjacent=False):
    start = (obs["hero"]["x"], obs["hero"]["y"])
    blocked = {(row["x"], row["y"]) for row in obs.get("entities", [])}
    blocked.update((row["x"], row["y"]) for row in obs.get("environment", []) if "x" in row and "y" in row)
    if adjacent: blocked.add(goal)
    def arrived(cell): return max(abs(cell[0]-goal[0]), abs(cell[1]-goal[1])) <= 1 if adjacent else cell == goal
    queue = deque([start]); parents = {start: None}; dest = None
    while queue:
        cell = queue.popleft()
        if arrived(cell): dest = cell; break
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if dx == dy == 0: continue
                nxt = (cell[0]+dx, cell[1]+dy)
                if nxt in parents or nxt in blocked or known.get(nxt) not in SAFE: continue
                if dx and dy and (known.get((cell[0]+dx, cell[1])) not in SAFE or known.get((cell[0], cell[1]+dy)) not in SAFE): continue
                parents[nxt] = cell; queue.append(nxt)
    if dest is None: raise ValueError("No safe route over player-known terrain")
    cells = []; cursor = dest
    while cursor != start:
        cells.append(list(cursor)); cursor = parents[cursor]
    cells.reverse()
    if len(cells) > 256: raise ValueError("Route exceeds execution limit")
    return cells


def target_fact(obs, known, goal, intent):
    x, y = goal
    if intent not in INTENTS: raise ValueError("Unsupported interaction intent")
    piles = obs.get("map_items", obs.get("ground", []))
    heap = next((row for row in piles if (row.get("x"), row.get("y")) == goal), None)
    if intent in ("pickup", "open", "buy"):
        field = {"pickup": "pickup", "open": "container", "buy": "for_sale"}[intent]
        if not heap or not heap.get(field): raise ValueError("Requested heap interaction is not known here")
        return {"kind": field, "name": heap.get("name")}
    if intent == "talk":
        entity = next((row for row in obs.get("entities", []) if (row.get("x"), row.get("y")) == goal and row.get("interactive")), None)
        if not entity: raise ValueError("No observed interactive character at target")
        return {"kind": "character", "name": entity.get("name")}
    allowed = {"unlock": {"锁着的门", "水晶之门", "锁着的楼层出口"}, "descend": {"楼层出口"},
               "ascend": {"楼层入口"}, "alchemy": {"炼金釜"}}
    if known.get(goal) not in allowed[intent]: raise ValueError("Requested terrain interaction is not known here")
    return {"kind": "terrain", "name": known[goal]}
