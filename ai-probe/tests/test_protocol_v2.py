import copy
import importlib.util
import json
from pathlib import Path
import random
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("adapter", Path(__file__).resolve().parents[1] / "player-client" / "player.py")
adapter = importlib.util.module_from_spec(spec); spec.loader.exec_module(adapter)
import protocol_v2 as p
from reliable_client import ReliableClient, ProtocolError


class CodecTests(unittest.TestCase):
    def test_map_items_survive_fov_exit_and_delete_after_pickup(self):
        visible = {"x": 3, "y": 4, "name": "Potion", "visible": True}
        old = adapter.snapshot({"ground": [visible], "map_items": [visible]})
        hidden = dict(visible, visible=False)
        new = adapter.snapshot({"ground": [], "map_items": [hidden]})
        self.assertEqual(p.apply(old, p.diff(old, new)), new)
        self.assertEqual(new["map_items"][0]["name"], "Potion")
        picked = adapter.snapshot({"ground": [], "map_items": []})
        self.assertEqual(p.apply(new, p.diff(new, picked)), picked)

    def test_absent_null_reserved_marker_roundtrip(self):
        original = {"inventory": [{"id": "a", "description": "a" * 500}, {"id": "b", "description": None, "foo": False}],
                    "special": {"_spd_codec": "ref", "key": "literal"}}
        codec = p.Codec({})
        self.assertEqual(original, codec.decode(codec.encode(original)))
        out = codec.decode(codec.encode(original))
        self.assertNotIn("foo", out["inventory"][0])
        self.assertIsNone(out["inventory"][1]["description"])

    def test_long_short_description_action_change_reorder_delete(self):
        old = {"inventory": [{"id": "a", "description": "x" * 500, "action_time_estimates": {"EAT": 3}}, {"id": "b", "status": 2}]}
        new = {"inventory": [{"id": "b"}, {"id": "a", "description": "short", "action_time_estimates": {"EAT": 1}}, {"id": "c", "quantity": 1}]}
        codec = p.Codec({}); ops = codec.decode(codec.encode(p.diff(old, new)))
        self.assertEqual(new, p.apply(old, ops))

    def test_randomized_inventory_state_transitions(self):
        random.seed(19); current = {"inventory": []}; codec = p.Codec({})
        for _ in range(150):
            rows = [{"id": str(i), "quantity": random.randint(0, 6), "description": "x" * random.choice([0, 10, 400]),
                     "action_time_estimates": {"EAT": random.choice([None, 1, 3])}} for i in random.sample(range(20), random.randint(0, 20))]
            if rows: rows[0].pop("description")
            target = {"inventory": rows, "hero": {"hp": random.randint(0, 20)}}
            current = p.apply(current, codec.decode(codec.encode(p.diff(current, target))))
            self.assertEqual(target, current)


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.api = SimpleNamespace(**vars(adapter))
        self.api.HANDOFF = Path(self.temp.name) / "handoff.json"
        self.api.PAYLOADS = Path(self.temp.name) / "payloads.jsonl"
        self.records = []; self.api.record = self.records.append
        self.obs = {"scene": "GameScene", "ready": True, "input_ready": True, "observation_id": 7,
                    "hero": {"x": 5, "y": 5, "hp": 20, "shield": 0, "level": 1, "gold": 0, "experience": 0},
                    "inventory": [{"id": "food", "quantity": 2}], "timing": {"clock_id": "clock", "spent_total": 0}}
        self.session = "s"; self.epoch = 1; self.events = []; self.receipts = {}; self.posts = 0; self.lose = False; self.unknown = False
        self.api.http = self.http
        self.engine = ReliableClient(self.api, Path(self.temp.name) / "journal.json")

    def http(self, path, body=None):
        if path.startswith("/frame"):
            after = int(path.split("after=")[1].split("&")[0]); events = [e for e in self.events if e["id"] > after]
            return {"decision_version": 1, "map": copy.deepcopy(getattr(self, "map_frame", None)), "knowledge": copy.deepcopy(getattr(self, "knowledge_frame", {})), "consistent": True, "observation": copy.deepcopy(self.obs), "events": {"session": self.session, "epoch": self.epoch, "last_id": len(self.events), "gap": False, "events": copy.deepcopy(events)}}
        if path.startswith("/request"):
            request = path.split("id=")[1]
            return {"found": request in self.receipts, "receipt": self.receipts.get(request)}
        if body is not None:
            self.posts += 1
            if self.unknown: raise adapter.ClientError("No reply, unknown server state")
            self.obs["hero"]["x"] += 1; self.obs["observation_id"] += 1; self.obs["timing"]["spent_total"] += 1
            receipt = {"request_id": body["request_id"], "status": "accepted", "session": self.session, "result": {"accepted": True}}
            self.receipts[body["request_id"]] = receipt
            if self.lose: raise adapter.ClientError("Reply lost after server accepted")
            return {"accepted": True, "request_id": body["request_id"], "request_status": "accepted"}
        raise ValueError(path)

    def init(self):
        first = self.engine.state(); self.engine.ack(first["batch_id"]); return first

    def test_visible_destination_walks_more_than_five_steps_in_one_call(self):
        self.obs.update(cells=[[x, 5, "地板"] for x in range(5, 13)], entities=[], action_time_estimates={"move": 1})
        self.init()
        result = self.engine.goto(7, 11, 5)
        self.assertTrue(result["travel"]["arrived"])
        self.assertEqual(6, result["batch"]["submitted_steps"])
        self.assertEqual("path_complete", result["batch"]["stop_reason"])
        self.assertEqual(result, self.engine.state())

    def test_destination_unseen_and_blocked_routes_submit_nothing(self):
        self.obs.update(cells=[[5, 5, "地板"], [6, 5, "墙壁"], [7, 5, "地板"]], action_time_estimates={"move": 1})
        self.init()
        with self.assertRaises(ProtocolError): self.engine.goto(7, 8, 5)
        with self.assertRaises(ProtocolError): self.engine.goto(7, 7, 5)
        self.assertEqual(0, self.posts)

    def test_destination_walk_stops_when_enemy_appears(self):
        self.obs.update(cells=[[x, 5, "地板"] for x in range(5, 13)], entities=[], action_time_estimates={"move": 1})
        self.init(); original = self.api.http
        def encounter(path, body=None):
            result = original(path, body)
            if body is not None: self.obs["entities"] = [{"name": "rat", "x": 8, "y": 5, "interactive": False}]
            return result
        self.api.http = encounter
        result = self.engine.goto(7, 11, 5)
        self.assertFalse(result["travel"]["arrived"])
        self.assertEqual(1, self.posts)
        self.assertEqual(1, result["batch"]["submitted_steps"])

    def test_remembered_destination_and_normal_healing_continue(self):
        self.obs.update(depth=1, branch=0, cells=[[5,5,"地板"],[6,5,"地板"]], entities=[], action_time_estimates={"move":1})
        self.map_frame = {"cursor":"m1","reset":True,"changes":{"floor":"0:1","size":[20,10],"legend":["地板"],"tiles":{str(x+5*20):0 for x in range(5,12)}}}
        self.init(); original = self.api.http
        def reveal(path, body=None):
            out = original(path, body)
            if body is not None:
                x = self.obs["hero"]["x"]; self.obs["cells"] = [[p,5,"地板"] for p in range(x-1,x+2)]
                self.obs["hero"]["hp"] += 1
            return out
        self.api.http = reveal
        result = self.engine.goto(7,11,5)
        self.assertTrue(result["travel"]["arrived"]); self.assertEqual(6,self.posts)
        self.assertTrue(any(change["field"] == "hp" for change in result["batch"]["reported_allowed_changes"]))

    def test_compound_pickup_one_batch_and_no_internal_ack(self):
        self.obs.update(depth=1,branch=0,cells=[[x,5,"地板"] for x in range(5,10)],entities=[],action_time_estimates={"move":1},
                        ground=[{"x":8,"y":5,"name":"food","pickup":True}], map_items=[{"x":8,"y":5,"name":"food","pickup":True}])
        self.init(); revision = self.engine.read()["revision"]; original = self.api.http
        def collect(path, body=None):
            if body is not None and body["type"] == "pickup":
                self.posts += 1; self.obs["inventory"][0]["quantity"] += 1; self.obs["ground"] = []; self.obs["map_items"] = []
                self.obs["observation_id"] += 1; self.obs["timing"]["spent_total"] += 1
                return {"accepted":True,"request_status":"accepted","request_id":body["request_id"]}
            return original(path,body)
        self.api.http = collect
        result = self.engine.interact_at(7,8,5,"pickup")
        self.assertEqual("completed",result["interaction"]["status"])
        self.assertEqual(4,self.posts); self.assertEqual(revision,self.engine.read()["revision"])
        self.assertEqual(result,self.engine.state())
        self.engine.ack(result["batch_id"])
        self.assertEqual(3,self.engine.read()["state"]["inventory"][0]["quantity"])

    def test_compound_pickup_does_not_execute_after_damage(self):
        self.obs.update(depth=1,cells=[[x,5,"地板"] for x in range(5,10)],entities=[],action_time_estimates={"move":1},map_items=[{"x":8,"y":5,"name":"food","pickup":True}])
        self.init(); original = self.api.http; actions = []
        def hurt(path,body=None):
            if body is not None: actions.append(body["type"])
            result = original(path,body)
            if body is not None: self.obs["hero"]["hp"] -= 1
            return result
        self.api.http = hurt
        result = self.engine.interact_at(7,8,5,"pickup")
        self.assertEqual("interrupted",result["interaction"]["status"]); self.assertEqual(["move"],actions)

    def test_damage_on_arrival_prevents_final_pickup(self):
        self.obs.update(depth=1,cells=[[x,5,"地板"] for x in range(5,10)],entities=[],action_time_estimates={"move":1},map_items=[{"x":8,"y":5,"name":"food","pickup":True}])
        self.init(); original = self.api.http; actions = []
        def hurt_at_goal(path,body=None):
            if body is not None: actions.append(body["type"])
            out = original(path,body)
            if body is not None and self.obs["hero"]["x"] == 8: self.obs["hero"]["hp"] -= 1
            return out
        self.api.http = hurt_at_goal
        result = self.engine.interact_at(7,8,5,"pickup")
        self.assertTrue(result["interaction"]["approach_arrived"])
        self.assertEqual("interrupted",result["interaction"]["status"])
        self.assertNotIn("pickup",actions)

    def test_buy_preserves_confirmation_and_lost_reply_never_retries(self):
        self.obs.update(depth=1,cells=[[5,5,"地板"]],entities=[],map_items=[{"x":5,"y":5,"name":"sale","for_sale":True}])
        self.init(); original = self.api.http
        def buy(path,body=None):
            if body is not None:
                self.posts += 1; self.obs["selection"] = {"kind":"window","text":"Confirm purchase"}; self.obs["ready"] = False
                self.obs["observation_id"] += 1
                return {"accepted":True,"request_status":"accepted","request_id":body["request_id"]}
            return original(path,body)
        self.api.http = buy
        result = self.engine.interact_at(7,5,5,"buy")
        self.assertEqual("awaiting_user_choice",result["interaction"]["status"]); self.assertEqual(1,self.posts)
        self.engine.ack(result["batch_id"])
        self.obs.pop("selection"); self.obs["ready"] = True; self.api.http = original; self.lose = True
        result = self.engine.interact_at(8,5,5,"buy")
        self.assertEqual("result_pending_or_unknown",result["interaction"]["status"])
        self.engine.ack(result["batch_id"])
        count = self.posts; self.engine.resume(); self.assertEqual(count,self.posts)

    def test_alchemy_scene_round_trip_keeps_map_delta_baseline(self):
        self.obs.update(depth=1, branch=0)
        self.map_frame = {"cursor":"m1","reset":True,"changes":{"floor":"0:1","size":[20,10],"legend":["地板"],"tiles":{"1":0}}}
        self.init(); game = copy.deepcopy(self.obs)
        self.obs = {"scene":"AlchemyScene","ready":False,"input_ready":True,"observation_id":8,"inventory":[],"timing":{"clock_id":"clock","spent_total":0}}
        self.map_frame = None
        result = self.engine.state(); self.engine.ack(result["batch_id"])
        self.assertEqual({"1":0},self.engine.read()["state"]["map_memory"]["tiles"])
        self.obs = game; self.map_frame = {"cursor":"m2","reset":False,"changes":{"tiles":{"2":0}}}
        result = self.engine.state(); self.engine.ack(result["batch_id"])
        self.assertEqual({"1":0,"2":0},self.engine.read()["state"]["map_memory"]["tiles"])

    def test_unified_map_and_knowledge_commit_only_on_ack(self):
        self.map_frame = {"cursor": "m1", "reset": True, "changes": {"floor": "0:1", "size": [8, 8], "legend": ["floor"], "tiles": {"1": 0}}}
        self.knowledge_frame = {"entity:snake": {"kind": "entity", "name": "snake", "text": "Avoid normal attacks; player-visible explanation."}}
        first = self.engine.state()
        self.assertNotIn("map_cursor", self.engine.read())
        self.assertNotIn("map_memory", self.engine.read()["state"])
        self.assertEqual(first, self.engine.state())
        self.engine.ack(first["batch_id"])
        self.assertEqual("m1", self.engine.read()["map_cursor"])
        self.assertEqual({"1": 0}, self.engine.read()["state"]["map_memory"]["tiles"])
        self.assertIn("snake", p.canonical(first["patch"]))
        self.map_frame = {"cursor": "m2", "reset": False, "changes": {"tiles": {"2": 0}}}
        next_packet = self.engine.act(7, {"type": "move", "dx": 1, "dy": 0})
        self.assertEqual("m1", self.engine.read()["map_cursor"])
        self.assertNotIn("Avoid normal", p.canonical(next_packet))
        self.engine.ack(next_packet["batch_id"])
        self.assertEqual("m2", self.engine.read()["map_cursor"])
        self.assertEqual({"1": 0, "2": 0}, self.engine.read()["state"]["map_memory"]["tiles"])

    def test_changed_knowledge_automatically_replaces_old_definition(self):
        self.knowledge_frame = {"item:food": {"name": "Unknown", "text": "Unknown description"}}
        self.init()
        self.knowledge_frame["item:food"] = {"name": "Known", "text": "Identified description"}
        packet = self.engine.state()
        self.assertTrue(any(row["kind"] == "knowledge_updated" for row in packet["attention"]))
        self.engine.ack(packet["batch_id"])
        self.assertEqual("Known", self.engine.read()["state"]["knowledge"]["item:food"]["name"])

    def test_map_reset_on_floor_and_scope_keeps_no_old_tiles(self):
        self.map_frame = {"cursor": "m1", "reset": True, "changes": {"floor": "0:1", "tiles": {"1": 0}}}
        self.knowledge_frame = {"old": {"text": "old run"}}
        self.init()
        self.epoch += 1; self.obs["depth"] = 2
        self.map_frame = {"cursor": "m2", "reset": True, "changes": {"floor": "0:2", "tiles": {"5": 0}}}
        self.knowledge_frame = {}
        packet = self.engine.state(); self.engine.ack(packet["batch_id"])
        state = self.engine.read()["state"]
        self.assertEqual({"5": 0}, state["map_memory"]["tiles"])
        self.assertNotIn("knowledge", state)

    def test_busy_cannot_commit_map_or_descriptions(self):
        self.init()
        self.map_frame = {"cursor": "busy", "reset": True, "changes": {"tiles": {"secret": 9}}}
        self.knowledge_frame = {"bad": {"text": "incomplete"}}
        self.obs = {"scene": "GameScene", "reason": "processing", "input_ready": False}
        with patch("reliable_client.time.sleep", lambda _: None): packet = self.engine.state()
        self.assertEqual([], packet["patch"])
        self.assertEqual([], packet["attention"])
        self.engine.ack(packet["batch_id"])
        self.assertNotIn("map_cursor", self.engine.read())

    def test_attention_health_hunger_talents_and_enemy_state(self):
        self.obs.update(hunger={"stage": "not_hungry"}, talents=[{"tier": 1, "available_points": 0}], entities=[])
        self.init()
        self.obs["hero"]["hp"] -= 3
        self.obs["hunger"]["stage"] = "starving"
        self.obs["talents"][0]["available_points"] = 1
        self.obs["entities"] = [{"name": "rat", "x": 4, "y": 5, "visible_state": "sleeping", "health_bar": {"hp_percent": 50}}]
        packet = self.engine.state()
        kinds = {row["kind"] for row in packet["attention"]}
        self.assertTrue({"hero_changed", "hunger_stage", "talent_points_available", "entity_observation_changed"}.issubset(kinds))
        self.engine.ack(packet["batch_id"])
        next_packet = self.engine.state()
        self.assertEqual([], next_packet["attention"])
        self.assertEqual([], next_packet["patch"])

    def test_lost_output_replays_and_does_not_advance_baseline(self):
        first = self.engine.state(); self.obs["inventory"][0]["quantity"] = 1
        self.assertEqual(first, self.engine.state())
        self.assertEqual(0, self.engine.read()["revision"])
        restarted = ReliableClient(self.api, self.engine.file)
        self.assertEqual(first, restarted.state())
        restarted.ack(first["batch_id"])
        second = restarted.state(); restarted.ack(second["batch_id"])
        self.assertEqual(1, restarted.read()["state"]["inventory"][0]["quantity"])

    def test_wrong_ack_and_digest_are_rejected(self):
        first = self.engine.state()
        with self.assertRaises(ProtocolError): self.engine.ack("wrong")
        cache = self.engine.read(); cache["delivery"]["packet"]["state_digest"] = "bad"; self.engine.write(cache)
        with self.assertRaises(ValueError): self.engine.ack(first["batch_id"])
        self.assertEqual(0, self.engine.read()["revision"])

    def test_full_resync_while_busy_preserves_acknowledged_business_state(self):
        self.init(); old = copy.deepcopy(self.engine.read()["state"])
        self.obs = {"scene": "GameScene", "input_ready": False, "ready": False, "reason": "processing"}
        with patch("reliable_client.time.sleep", lambda _: None): packet = self.engine.state(full=True, resync=True)
        self.assertFalse(packet["valid"])
        self.engine.ack(packet["batch_id"])
        self.assertEqual(old, self.engine.read()["state"])

    def test_busy_keeps_business_fields_and_has_no_delete_patch(self):
        self.init(); old = copy.deepcopy(self.engine.read()["state"])
        self.obs = {"scene": "GameScene", "input_ready": False, "ready": False, "reason": "processing"}
        with patch("reliable_client.time.sleep", lambda _: None): packet = self.engine.state()
        self.assertFalse(packet["valid"]); self.assertEqual([], packet["patch"])
        self.engine.ack(packet["batch_id"])
        self.assertEqual(old, self.engine.read()["state"])

    def test_unknown_blocks_even_after_restart_and_resumes_without_post(self):
        self.init(); self.lose = True
        packet = self.engine.act(7, {"type": "move", "dx": 1, "dy": 0}); self.engine.ack(packet["batch_id"])
        self.assertEqual("unknown", self.engine.read()["operation"]["status"])
        restarted = ReliableClient(self.api, self.engine.file)
        with self.assertRaises(ProtocolError): restarted.act(8, {"type": "wait"})
        recovered = restarted.resume(); restarted.ack(recovered["batch_id"])
        self.assertEqual(1, self.posts)
        self.assertEqual("completed", restarted.read()["operation"]["status"])

    def test_not_found_is_not_proof_of_non_execution(self):
        self.init(); self.unknown = True
        packet = self.engine.act(7, {"type": "wait"}); self.engine.ack(packet["batch_id"])
        resumed = self.engine.resume(); self.engine.ack(resumed["batch_id"])
        self.assertEqual("unknown", self.engine.read()["operation"]["status"])
        with self.assertRaises(ProtocolError): self.engine.guard_operation()
        self.assertEqual(1, self.posts)

    def test_restart_requires_review_not_fresh_state_as_proof(self):
        self.init(); self.lose = True
        packet = self.engine.act(7, {"type": "wait"}); self.engine.ack(packet["batch_id"])
        self.session = "new"
        resumed = self.engine.resume(); self.engine.ack(resumed["batch_id"])
        self.assertEqual("review_required_after_restart", self.engine.read()["operation"]["status"])

    def test_unacknowledged_batch_blocks_action(self):
        self.engine.state()
        with self.assertRaises(ProtocolError): self.engine.act(7, {"type": "wait"})
        self.assertEqual(0, self.posts)

    def test_new_unseen_event_returns_delivery_before_act(self):
        self.init(); self.events.append({"id": 1, "kind": "visual", "category": "miss"})
        result = self.engine.act(7, {"type": "wait"})
        self.assertEqual(0, self.posts); self.assertTrue(result["requires_new_decision"])
        self.engine.ack(result["batch_id"]); self.assertEqual(1, self.engine.read()["event_id"])

    def test_brief_does_not_reset_until_context_ack_and_preserves_memory(self):
        self.init(); self.engine.memory({"goal": "stairs", "plan": "retreat", "unresolved": [{"text": "unknown effect", "source": "visible", "observed_at": 7}]})
        cache = self.engine.read(); cache["outputs"] = 25; self.engine.write(cache)
        packet = self.engine.brief(); self.assertEqual("stairs", packet["memory"]["goal"])
        self.assertEqual(25, self.engine.read()["outputs"])
        with self.assertRaises(ProtocolError): self.engine.context_ack(packet["context_proposal"])
        self.engine.ack(packet["batch_id"]); self.engine.context_ack(packet["context_proposal"])
        self.assertEqual(0, self.engine.read()["outputs"])
        cache = self.engine.read(); cache["outputs"] = 5; self.engine.write(cache)
        self.engine.context_ack(packet["context_proposal"])
        self.assertEqual(5, self.engine.read()["outputs"])

    def test_pending_packet_description_can_be_read_before_ack(self):
        self.obs["effects"] = [{"name": "unknown", "description": "public description " * 30}]
        self.engine.state()
        refs = self.engine.read()["references"]
        key = next(key for key in refs if refs[key]["kind"] == "description")
        self.assertEqual(self.obs["effects"][0]["description"], self.engine.detail(key)["value"])

    def test_path_changes_are_selective(self):
        before = {"hero": {"hp": 20, "gold": 1, "experience": 1}, "ready": True, "cells": [[1, 1, "floor"], [2, 1, "floor"]]}
        after = copy.deepcopy(before); after["hero"].update(gold=3, experience=2); after["cells"].append([9, 9, "grass"])
        policy = {"allow_gold_gain": True, "allow_experience_gain": True}
        reason, report = self.engine.path_change(before, after, [[2, 1]], policy)
        self.assertIsNone(reason); self.assertEqual(3, len(report))
        after["keys"] = ["key"]; self.assertEqual("keys_changed", self.engine.path_change(before, after, [], policy)[0])
        after = copy.deepcopy(before); after["cells"][1][2] = "trap"
        self.assertEqual("remaining_route_changed", self.engine.path_change(before, after, [[2, 1]], policy)[0])


if __name__ == "__main__": unittest.main()
