import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from scripts.r1.core import (
    ACTION_NAMES,
    LinearMaskedActorCritic,
    R1PilotEnvironment,
    assert_no_split_leakage,
    canonical_hash,
)
from scripts.r1.ppo import collect_rollouts, ppo_update


def scene(scene_id="scene", proposals=None):
    values = proposals or {
        "B2-U": {"status": "VALID", "benchmark_eligible": True, "score": 9.0, "result_hash": "u"},
        "B2-Q": {"status": "VALID", "benchmark_eligible": True, "score": 9.6, "result_hash": "q"},
        "B2-C": {"status": "VALID", "benchmark_eligible": True, "score": 8.0, "result_hash": "c"},
        "B2-C-J": {"status": "VALID", "benchmark_eligible": True, "score": 7.8, "result_hash": "cj"},
        "B3": {"status": "VALID", "benchmark_eligible": True, "score": 8.5, "result_hash": "b3"},
    }
    return {
        "scene_id": scene_id,
        "parent_scene_id": "parent-1",
        "split": "train",
        "oks_count": 4,
        "total_flow_tph": 100.0,
        "initial_score": 10.0,
        "proposals": values,
    }


class R1CoreTests(unittest.TestCase):
    def test_t049_snapshot_is_stable(self):
        env = R1PilotEnvironment(scene())
        state_a, snapshot_a = env.observe()
        state_b, snapshot_b = env.observe()
        np.testing.assert_array_equal(state_a, state_b)
        self.assertEqual(snapshot_a.action_ids, snapshot_b.action_ids)
        self.assertEqual(snapshot_a.candidate_hash, snapshot_b.candidate_hash)
        np.testing.assert_array_equal(snapshot_a.mask, snapshot_b.mask)

    def test_masked_action_cannot_execute(self):
        env = R1PilotEnvironment(scene())
        _, snapshot = env.observe()
        env.step(snapshot.action_ids[0], snapshot)
        _, next_snapshot = env.observe()
        self.assertFalse(next_snapshot.mask[0])
        with self.assertRaisesRegex(ValueError, "MASKED_ACTION"):
            env.step(next_snapshot.action_ids[0], next_snapshot)

    def test_t050_failed_action_rolls_back_design(self):
        values = scene()["proposals"]
        values["B2-U"] = {
            "status": "VALID", "benchmark_eligible": True, "score": 7.0,
            "result_hash": "u", "apply_failed": True,
        }
        env = R1PilotEnvironment(scene(proposals=values))
        _, snapshot = env.observe()
        before = (env.current_score, env.best_score, env.best_action)
        _, reward, _, info = env.step(snapshot.action_ids[0], snapshot)
        self.assertEqual(before, (env.current_score, env.best_score, env.best_action))
        self.assertEqual(0.0, reward)
        self.assertTrue(info["failed"])

    def test_t051_best_archive_reward_telescopes(self):
        env = R1PilotEnvironment(scene())
        rewards = []
        for name in ("B2-U", "B2-Q", "B2-C"):
            _, snapshot = env.observe()
            index = ACTION_NAMES.index(name)
            _, reward, _, _ = env.step(snapshot.action_ids[index], snapshot)
            rewards.append(reward)
        np.testing.assert_allclose(rewards, [0.1, 0.0, 0.1])
        self.assertAlmostEqual(sum(rewards), 0.2)
        self.assertEqual(8.0, env.best_score)

    def test_t052_expensive_legal_action_is_not_masked(self):
        values = scene()["proposals"]
        values["B2-U"]["calculated_cost"] = 10**12
        env = R1PilotEnvironment(scene(proposals=values))
        _, snapshot = env.observe()
        self.assertTrue(snapshot.mask[ACTION_NAMES.index("B2-U")])

    def test_t053_illegal_action_probability_is_zero(self):
        values = scene()["proposals"]
        values["B2-Q"] = {"status": "INVALID", "benchmark_eligible": False,
                           "score": 1.0, "result_hash": "bad"}
        env = R1PilotEnvironment(scene(proposals=values))
        state, snapshot = env.observe()
        model = LinearMaskedActorCritic(seed=1)
        _, probabilities, _ = model.forward(state, snapshot.features, snapshot.mask)
        self.assertEqual(0.0, probabilities[ACTION_NAMES.index("B2-Q")])

    def test_stale_snapshot_is_rejected(self):
        env = R1PilotEnvironment(scene())
        _, snapshot = env.observe()
        env.step(snapshot.action_ids[0], snapshot)
        with self.assertRaisesRegex(ValueError, "STALE_CANDIDATE_SNAPSHOT"):
            env.step(snapshot.action_ids[1], snapshot)

    def test_incomplete_design_is_masked(self):
        values = scene()["proposals"]
        values["B2-U"]["benchmark_eligible"] = False
        env = R1PilotEnvironment(scene(proposals=values))
        _, snapshot = env.observe()
        self.assertFalse(snapshot.mask[ACTION_NAMES.index("B2-U")])

    def test_horizon_is_finite_and_best_archive_never_worsens(self):
        env = R1PilotEnvironment(scene(), horizon=2)
        previous_best = env.best_score
        for name in ("B2-U", "B2-Q"):
            _, snapshot = env.observe()
            _, _, _, _ = env.step(snapshot.action_ids[ACTION_NAMES.index(name)], snapshot)
            self.assertLessEqual(env.best_score, previous_best)
            previous_best = env.best_score
        self.assertTrue(env.done)

    def test_t054_manifest_and_hash_are_checked(self):
        model = LinearMaskedActorCritic(seed=2)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.json"
            model.save(path, {"training_seed": 2})
            loaded, _ = LinearMaskedActorCritic.load(path)
            np.testing.assert_allclose(model.interaction, loaded.interaction)
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["manifest"]["feature_schema_version"] = "foreign"
            path.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "MODEL_SCHEMA_MISMATCH"):
                LinearMaskedActorCritic.load(path)

    def test_t055_proposal_dict_order_does_not_change_policy_order(self):
        first = scene()
        second = scene(proposals=dict(reversed(list(first["proposals"].items()))))
        env_a = R1PilotEnvironment(first)
        env_b = R1PilotEnvironment(second)
        _, snapshot_a = env_a.observe()
        _, snapshot_b = env_b.observe()
        np.testing.assert_array_equal(snapshot_a.features, snapshot_b.features)
        self.assertEqual(snapshot_a.action_ids, snapshot_b.action_ids)

    def test_t057_split_leakage_is_rejected(self):
        records = [
            {"parent_scene_id": "p", "split": "train"},
            {"parent_scene_id": "p", "split": "test"},
        ]
        with self.assertRaisesRegex(ValueError, "SPLIT_LEAKAGE_ERROR"):
            assert_no_split_leakage(records)

    def test_ppo_update_uses_saved_candidate_snapshot(self):
        model = LinearMaskedActorCritic(seed=4)
        transitions, _ = collect_rollouts(model, [scene()], np.random.default_rng(4))
        saved = [(item["candidate_hash"], item["action_ids"]) for item in transitions]
        ppo_update(model, transitions)
        self.assertEqual(saved, [(item["candidate_hash"], item["action_ids"]) for item in transitions])

    def test_concrete_r1_model_manifest_and_hash(self):
        payload = json.loads(Path("models/r1_v2/model.json").read_text(encoding="utf-8"))
        self.assertEqual("r1_concrete_features_v1", payload["manifest"]["feature_schema_version"])
        self.assertEqual("r1_concrete_actions_v1", payload["manifest"]["action_schema_version"])
        self.assertEqual(payload["manifest"]["weights_hash"], canonical_hash(payload["weights"]))
        self.assertEqual(
            ["CONNECT", "ATTACH", "MERGE", "BUILD_BACKBONE", "REATTACH", "STOP"],
            payload["action_types"],
        )

    def test_r2_model_manifest_and_hash(self):
        payload = json.loads(Path("models/r2_v1/model.json").read_text(encoding="utf-8"))
        self.assertEqual("r2_operator_policy_v1", payload["manifest"]["schema_version"])
        self.assertEqual(6, payload["manifest"]["action_count"])
        self.assertEqual(payload["manifest"]["weights_hash"], canonical_hash(payload["weights"]))


if __name__ == "__main__":
    unittest.main()
