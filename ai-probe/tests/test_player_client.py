import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("player_client", Path(__file__).resolve().parents[1] / "player-client" / "player.py")
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = patch.object(client, "CACHE", Path(self.temp.name) / "state.json")
        self.trace = patch.object(client, "TRACE", Path(self.temp.name) / "trace.jsonl")
        self.cache.start(); self.trace.start()
        self.addCleanup(self.cache.stop); self.addCleanup(self.trace.stop)
        self.obs = {"scene": "GameScene", "input_ready": True, "ready": True, "observation_id": 7,
                    "hero": {"hp": 6, "max_hp": 20, "level": 1}, "timing": {"clock_id": 1, "spent_total": 4},
                    "talents": [{"entries": [{"id": "HEARTY_MEAL", "points": 0}]}],
                    "hunger": {"stage": "hungry"}, "action_time_estimates": {"move": 1},
                    "entities": [{"name": "rat", "visible_state": "sleeping", "health_bar": {"hp_percent": 50}}]}
        self.events = {"session": "test", "epoch": 1, "last_id": 1, "gap": False, "events": []}

    def fake(self, path, body=None):
        return copy.deepcopy(self.obs if path == "/observe" else self.events)

    def test_zero_point_talent_and_critical_fields(self):
        with patch.object(client, "http", self.fake):
            out = client.state()
        self.assertEqual([], out["changes"]["active_talents"])
        for key in ("talents", "hunger", "action_time_estimates", "entities", "timing"):
            self.assertIn(key, out["changes"])

    def test_run_reset_and_event_dedup(self):
        self.obs["talents"][0]["entries"][0]["points"] = 1
        with patch.object(client, "http", self.fake):
            learned = client.state()
            self.assertEqual(1, learned["changes"]["active_talents"][0]["points"])
            second = client.state()
            self.assertFalse(second["reset"])
            self.assertEqual({}, second["changes"])
            self.events["epoch"] = 2
            self.obs["timing"]["clock_id"] = 2
            self.obs["talents"][0]["entries"][0]["points"] = 0
            fresh = client.state()
        self.assertTrue(fresh["reset"])
        self.assertEqual([], fresh["changes"]["active_talents"])

    def test_unknown_submission_no_retry(self):
        posts = []
        def fake(path, body=None):
            if body is not None:
                posts.append(body)
                raise client.ClientError("connection lost")
            return self.fake(path)
        with patch.object(client, "http", fake), self.assertRaises(client.ClientError):
            client.action(7, {"type": "wait"})
        self.assertEqual(1, len(posts))
        row = json.loads(client.TRACE.read_text())
        self.assertEqual("unknown", row["submission_outcome"])
        self.assertIn("entities", row["before"])

    def test_stale_or_paused_no_submission(self):
        with patch.object(client, "http", self.fake), self.assertRaises(client.ClientError):
            client.action(6, {"type": "wait"})
        self.obs["control_paused"] = True
        with patch.object(client, "http", self.fake), self.assertRaises(client.ClientError):
            client.action(7, {"type": "wait"})
        self.assertFalse(client.TRACE.exists())

    def test_observation_failure_keeps_reply_without_retry(self):
        posts = []
        def fake(path, body=None):
            if body is not None:
                posts.append(body)
                return {"accepted": True}
            if posts and path == "/observe":
                raise client.ClientError("observation unavailable")
            return self.fake(path)
        with patch.object(client, "http", fake):
            result = client.action(7, {"type": "wait"})
        self.assertEqual(1, len(posts))
        self.assertTrue(result["action_result"]["accepted"])
        self.assertEqual("observe_only_do_not_resubmit", result["followup"])
        self.assertEqual(1, len(client.TRACE.read_text().splitlines()))

    def test_gap_requires_decision_without_fabricated_events(self):
        self.events["gap"] = True
        self.obs["messages"] = ["visible message"]
        with patch.object(client, "http", self.fake):
            result = client.state()
        self.assertTrue(result["requires_new_decision"])
        self.assertEqual(["visible message"], result["recovered_messages"])
        self.assertEqual([], result["new_events"])

    def test_inventory_patch_does_not_resend_unchanged_descriptions(self):
        self.obs["inventory"] = [{"id": "food", "name": "ration", "quantity": 2, "description": "x" * 2000},
                                 {"id": "wand", "name": "wand", "quantity": 1, "description": "y" * 2000}]
        with patch.object(client, "http", self.fake):
            client.state()
            self.obs["inventory"][0]["quantity"] = 1
            result = client.state()
        self.assertNotIn("inventory", result["changes"])
        self.assertEqual({"quantity": 1}, result["inventory_delta"]["updated"][0]["changes"])
        self.assertLess(len(json.dumps(result)), 1000)

    def test_inventory_patch_preserves_new_items_removed_items_and_order(self):
        old = [{"id": "a", "quantity": 1}, {"id": "b", "status": "old", "quantity": 1}]
        new = [{"id": "b", "quantity": 2}, {"id": "c", "description": "new public details"}]
        delta = client.inventory_delta(old, new)
        self.assertEqual(["a"], delta["removed"])
        self.assertEqual(["status"], delta["updated"][0]["removed_fields"])
        self.assertEqual(new[1], delta["added"][0])
        self.assertEqual(["b", "c"], delta["order"])

    def test_death_is_final_and_trace_retains_combat(self):
        def fake(path, body=None):
            if body is not None:
                self.obs.update(ready=False, terminal={"outcome": "dead"}, hero={"hp": 0})
                self.obs["timing"]["spent_total"] = 7
                self.events.update(last_id=2, events=[{"kind": "visual", "category": "damage", "displayed_damage": 6}])
                return {"accepted": True}
            return self.fake(path)
        with patch.object(client, "http", fake):
            result = client.action(7, {"type": "wait"})
        self.assertEqual(3, result["actual_time_spent"])
        self.assertIn("terminal", result["decision_triggers"])
        row = json.loads(client.TRACE.read_text())
        self.assertIn("talents", row["before"])
        self.assertEqual("dead", row["after"]["terminal"]["outcome"])
        self.assertEqual(6, row["events"]["events"][0]["displayed_damage"])


if __name__ == "__main__":
    unittest.main()
