import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("client", Path(__file__).resolve().parents[1] / "player-client" / "player.py")
client = importlib.util.module_from_spec(spec); spec.loader.exec_module(client)
tools = client.context_tools


class ContextTests(unittest.TestCase):
    def test_slim_inventory_can_reconstruct_actions_and_preserves_unknown_cost(self):
        cache = {}
        item = {"id": "food", "quantity": 1, "name": "food", "status": {"charges": 2},
                "supported_actions": ["EAT", "THROW"], "actions": ["EAT", "THROW"],
                "action_time_estimates": {"EAT": 3, "THROW": None}}
        payload = {"changes": {"inventory": [item, dict(item, id="food2")]},
                   "new_events": [{"category": "damage", "displayed_damage": 0, "surprise": True}]}
        out = tools.slim(payload, cache)
        table = out["changes"]["inventory"]
        row = dict(zip(table["columns"], table["rows"][0]))
        row.update(out["action_sets"][row.pop("action_set")])
        self.assertEqual(item, row)
        self.assertEqual(1, len(out["action_sets"]))
        self.assertEqual(payload["new_events"], out["new_events"])

    def test_description_ref_is_lossless_and_numeric_state_is_unchanged(self):
        original = {"changes": {"effects": [{"name": "unknown", "description": "rule" * 200}],
                                "hunger": {"stage": "starving"}}, "event_gap": True}
        cache = {}
        out = tools.slim(original, cache)
        ref = out["changes"]["effects"][0]["description_ref"]
        self.assertEqual("rule" * 200, cache["references"][ref]["value"])
        self.assertEqual("starving", out["changes"]["hunger"]["stage"])
        self.assertTrue(out["event_gap"])
        self.assertIn("description", original["changes"]["effects"][0])

    def test_brief_bounded_intention_recent_events_and_no_transcript(self):
        cache = {"recent_events": [{"id": i} for i in range(20)]}
        out = tools.brief({"changes": {"hero": {"hp": 3}, "active_talents": []}}, cache, "reach stairs", "retreat")
        self.assertEqual(8, len(out["checkpoint"]["recent_events"]))
        self.assertTrue(out["checkpoint"]["note_is_intention_not_game_fact"])
        self.assertEqual([], out["changes"]["active_talents"])
        with self.assertRaises(ValueError): tools.brief({}, cache, "a" * 201)

    def test_usage_dedup_and_unknown_cache_not_zero(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(client, "CACHE", Path(folder) / "cache.json"), patch.object(client, "USAGE", Path(folder) / "usage.jsonl"):
                one = {"request_id": "a", "input_tokens": 100, "cached_input_tokens": 80, "output_tokens": 10}
                client.usage(one)
                self.assertTrue(client.usage(one)["duplicate_ignored"])
                out = client.usage({"request_id": "b", "input_tokens": 50, "output_tokens": 5})["all_runs"]
                self.assertEqual(2, out["requests"])
                self.assertEqual(165, out["total_tokens"])
                self.assertEqual(1, out["cached_reporting_requests"])
                self.assertEqual(20, out["uncached_input_tokens_known"])
                self.assertEqual("unknown", out["billing_cost"])
        for data in ({"request_id": "a", "input_tokens": -1, "output_tokens": 1},
                     {"request_id": "a", "input_tokens": 1, "cached_input_tokens": 2, "output_tokens": 1}):
            with self.assertRaises(ValueError): tools.usage_entry(data, {})

    def test_emit_does_not_print_full_local_trace(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(client, "CACHE", Path(folder) / "cache.json"), patch.object(client, "PAYLOADS", Path(folder) / "payloads.jsonl"):
                stream = io.StringIO()
                with contextlib.redirect_stdout(stream): client.emit({"changes": {"description": "x" * 2000}}, "state")
                output = json.loads(stream.getvalue())
                self.assertNotIn("description", output["changes"])
                row = json.loads(client.PAYLOADS.read_text())
                self.assertIsNone(row["model_tokens"])
                self.assertLess(row["output_chars"], row["raw_chars"])

    def test_rotation_is_explicit_and_does_not_claim_to_delete_history(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(client, "CACHE", Path(folder) / "cache.json"), patch.object(client, "PAYLOADS", Path(folder) / "payloads.jsonl"):
                client.write_cache({"outputs_since_checkpoint": 19})
                stream = io.StringIO()
                with contextlib.redirect_stdout(stream): client.emit({"changes": {"hero": {"hp": 2}}}, "state")
                rotation = json.loads(stream.getvalue())["context_rotation"]
                self.assertTrue(rotation["requires_external_history_replacement"])
                before = client.read_cache()["outputs_since_checkpoint"]
                with contextlib.redirect_stdout(io.StringIO()): client.emit({"changes": {}, "new_events": []}, "state")
                self.assertEqual(before, client.read_cache()["outputs_since_checkpoint"])


if __name__ == "__main__": unittest.main()
