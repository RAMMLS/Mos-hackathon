import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np


FEATURE_SCHEMA_VERSION = "r1_pilot_features_v2"
ACTION_SCHEMA_VERSION = "r1_pilot_actions_v2"
CANDIDATE_SPACE_VERSION = "r1_pilot_candidates_v2"
ACTION_NAMES = ("B2-U", "B2-Q", "B2-C", "B2-C-J", "B3", "STOP")
STATE_DIM = 6
ACTION_DIM = len(ACTION_NAMES) + 2


def canonical_hash(value):
    payload = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class MacroAction:
    action_type: str
    parameters: dict
    features: tuple
    legal: bool = True

    def stable_id(self, state_revision):
        return canonical_hash({
            "action_schema": ACTION_SCHEMA_VERSION,
            "candidate_space": CANDIDATE_SPACE_VERSION,
            "state_revision": state_revision,
            "type": self.action_type,
            "parameters": self.parameters,
        })[:24]


@dataclass(frozen=True)
class CandidateSnapshot:
    state_revision: int
    action_ids: tuple
    features: np.ndarray
    mask: np.ndarray
    candidate_hash: str


class R1PilotEnvironment:
    """Finite R1 pilot over concrete, independently evaluated design proposals."""

    def __init__(self, scene, horizon=len(ACTION_NAMES)):
        self.scene = scene
        self.horizon = horizon
        self.initial_score = float(scene["initial_score"])
        self.current_score = self.initial_score
        self.best_score = self.initial_score
        self.best_action = "INITIAL_B1"
        self.normalizer = max(1.0, abs(self.initial_score))
        self.step_index = 0
        self.state_revision = 0
        self.evaluated = set()
        self.done = False
        self.failed_actions = 0

    def state_vector(self):
        remaining = max(0, self.horizon - self.step_index)
        return np.asarray([
            min(float(self.scene["oks_count"]) / 24.0, 2.0),
            min(float(self.scene["total_flow_tph"]) / 1000.0, 2.0),
            self.current_score / 50.0,
            self.best_score / 50.0,
            self.step_index / max(1, self.horizon),
            remaining / max(1, self.horizon),
        ], dtype=np.float64)

    def _actions(self):
        actions = []
        for index, name in enumerate(ACTION_NAMES[:-1]):
            one_hot = [0.0] * len(ACTION_NAMES)
            one_hot[index] = 1.0
            proposal = self.scene["proposals"].get(name) or {}
            actions.append(MacroAction(
                "BUILD_BACKBONE" if name.startswith("B2-") else "DESTROY_REPAIR",
                {"proposal_id": name, "result_hash": proposal.get("result_hash")},
                tuple(one_hot + [
                    min(float(self.scene["oks_count"]) / 24.0, 2.0),
                    min(float(self.scene["total_flow_tph"]) / 1000.0, 2.0),
                ]),
                legal=proposal.get("status") == "VALID"
                and proposal.get("benchmark_eligible", True)
                and proposal.get("score") is not None,
            ))
        stop_hot = [0.0] * len(ACTION_NAMES)
        stop_hot[-1] = 1.0
        actions.append(MacroAction("STOP", {}, tuple(stop_hot + [0.0, 0.0]), legal=True))
        return actions

    def observe(self):
        actions = self._actions()
        action_ids = tuple(action.stable_id(self.state_revision) for action in actions)
        mask = []
        for name, action in zip(ACTION_NAMES, actions):
            mask.append(action.legal and (name == "STOP" or name not in self.evaluated))
        features = np.asarray([action.features for action in actions], dtype=np.float64)
        payload = {
            "version": CANDIDATE_SPACE_VERSION,
            "revision": self.state_revision,
            "action_ids": action_ids,
            "features": features.tolist(),
            "mask": mask,
        }
        return self.state_vector(), CandidateSnapshot(
            self.state_revision,
            action_ids,
            features,
            np.asarray(mask, dtype=bool),
            canonical_hash(payload),
        )

    def step(self, action_id, snapshot):
        if self.done:
            raise RuntimeError("EPISODE_DONE")
        if snapshot.state_revision != self.state_revision:
            raise ValueError("STALE_CANDIDATE_SNAPSHOT")
        current_state, current = self.observe()
        if snapshot.candidate_hash != current.candidate_hash:
            raise ValueError("CANDIDATE_SNAPSHOT_MISMATCH")
        try:
            index = snapshot.action_ids.index(action_id)
        except ValueError as exc:
            raise ValueError("UNKNOWN_ACTION_ID") from exc
        if not snapshot.mask[index]:
            raise ValueError("MASKED_ACTION")

        name = ACTION_NAMES[index]
        best_before = self.best_score
        failed = False
        if name == "STOP":
            self.done = True
        else:
            self.evaluated.add(name)
            proposal = self.scene["proposals"][name]
            if (proposal.get("apply_failed") or proposal.get("status") != "VALID"
                    or not proposal.get("benchmark_eligible", True) or proposal.get("score") is None):
                failed = True
                self.failed_actions += 1
            else:
                self.current_score = float(proposal["score"])
                if self.current_score < self.best_score:
                    self.best_score = self.current_score
                    self.best_action = name

        self.step_index += 1
        self.state_revision += 1
        if self.step_index >= self.horizon:
            self.done = True
        reward = (best_before - self.best_score) / self.normalizer
        return self.state_vector(), reward, self.done, {
            "failed": failed,
            "best_score": self.best_score,
            "best_action": self.best_action,
            "state_before": current_state.tolist(),
        }


class LinearMaskedActorCritic:
    def __init__(self, seed=101):
        rng = np.random.default_rng(seed)
        self.interaction = rng.normal(0.0, 0.02, size=(STATE_DIM, ACTION_DIM))
        self.action_bias = np.zeros(ACTION_DIM, dtype=np.float64)
        self.critic = rng.normal(0.0, 0.02, size=STATE_DIM)
        self.critic_bias = 0.0

    def forward(self, state, action_features, mask):
        logits = action_features @ self.action_bias + np.einsum(
            "s,sa,na->n", state, self.interaction, action_features
        )
        masked_logits = np.where(mask, logits, -1.0e30)
        legal_logits = masked_logits[mask]
        if legal_logits.size == 0:
            raise ValueError("NO_LEGAL_ACTIONS")
        maximum = np.max(legal_logits)
        exp_logits = np.where(mask, np.exp(masked_logits - maximum), 0.0)
        probabilities = exp_logits / np.sum(exp_logits)
        value = float(state @ self.critic + self.critic_bias)
        return logits, probabilities, value

    def choose(self, state, snapshot, rng, deterministic=False):
        _, probabilities, value = self.forward(state, snapshot.features, snapshot.mask)
        if deterministic:
            legal = np.flatnonzero(snapshot.mask)
            index = int(legal[np.argmax(probabilities[legal])])
        else:
            index = int(rng.choice(len(probabilities), p=probabilities))
        return index, float(np.log(max(probabilities[index], 1.0e-12))), value

    def save(self, path, training_metadata):
        payload = {
            "manifest": {
                "feature_schema_version": FEATURE_SCHEMA_VERSION,
                "action_schema_version": ACTION_SCHEMA_VERSION,
                "candidate_space_version": CANDIDATE_SPACE_VERSION,
                "state_dim": STATE_DIM,
                "action_dim": ACTION_DIM,
                **training_metadata,
            },
            "weights": {
                "interaction": self.interaction.tolist(),
                "action_bias": self.action_bias.tolist(),
                "critic": self.critic.tolist(),
                "critic_bias": self.critic_bias,
            },
        }
        payload["manifest"]["weights_hash"] = canonical_hash(payload["weights"])
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return payload

    @classmethod
    def load(cls, path):
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        manifest = payload["manifest"]
        expected = {
            "feature_schema_version": FEATURE_SCHEMA_VERSION,
            "action_schema_version": ACTION_SCHEMA_VERSION,
            "candidate_space_version": CANDIDATE_SPACE_VERSION,
            "state_dim": STATE_DIM,
            "action_dim": ACTION_DIM,
        }
        if any(manifest.get(key) != value for key, value in expected.items()):
            raise ValueError("MODEL_SCHEMA_MISMATCH")
        if manifest.get("weights_hash") != canonical_hash(payload["weights"]):
            raise ValueError("MODEL_HASH_MISMATCH")
        model = cls(seed=0)
        weights = payload["weights"]
        model.interaction = np.asarray(weights["interaction"], dtype=np.float64)
        model.action_bias = np.asarray(weights["action_bias"], dtype=np.float64)
        model.critic = np.asarray(weights["critic"], dtype=np.float64)
        model.critic_bias = float(weights["critic_bias"])
        return model, manifest


def assert_no_split_leakage(records):
    seen = {}
    for record in records:
        parent = record["parent_scene_id"]
        split = record["split"]
        if parent in seen and seen[parent] != split:
            raise ValueError("SPLIT_LEAKAGE_ERROR")
        seen[parent] = split
