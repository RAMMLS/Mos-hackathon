import argparse
import json
import sys
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.r1.core import LinearMaskedActorCritic, canonical_hash
from scripts.r1.ppo import ppo_update


ACTIONS = ("CONNECT", "ATTACH", "MERGE", "BUILD_BACKBONE", "REATTACH", "STOP")
PROPOSALS = {
    "MERGE": ("B2-C-J",),
    "BUILD_BACKBONE": ("B2-U", "B2-Q", "B2-C"),
    "REATTACH": ("B3",),
}


def action_scores(scene):
    scores = {}
    for action in ACTIONS:
        candidates = PROPOSALS.get(action, ())
        valid = [
            float(scene["proposals"][name]["score"])
            for name in candidates
            if scene["proposals"].get(name, {}).get("status") == "VALID"
            and scene["proposals"][name].get("score") is not None
        ]
        scores[action] = min(valid) if valid else float(scene["initial_score"])
    return scores


def features(scene):
    result = np.zeros((len(ACTIONS), 8), dtype=np.float64)
    for index in range(len(ACTIONS)):
        result[index, index] = 1.0
        result[index, 6] = min(float(scene["total_flow_tph"]) / 1000.0, 2.0)
    return result


def collect(model, scenes, rng, deterministic=False):
    transitions = []
    summaries = []
    for scene in scenes:
        scores = action_scores(scene)
        current = float(scene["initial_score"])
        best = current
        normalizer = max(1.0, abs(best))
        mask = np.ones(len(ACTIONS), dtype=bool)
        episode = []
        sequence = []
        for step in range(len(ACTIONS)):
            state = np.asarray([
                min(float(scene["oks_count"]) / 24.0, 2.0),
                min(float(scene["total_flow_tph"]) / 1000.0, 2.0),
                current / 50.0,
                best / 50.0,
                step / len(ACTIONS),
                (len(ACTIONS) - step) / len(ACTIONS),
            ], dtype=np.float64)
            action_features = features(scene)
            _, probabilities, value = model.forward(state, action_features, mask)
            legal = np.flatnonzero(mask)
            if deterministic:
                action = int(legal[np.argmax(probabilities[legal])])
            else:
                action = int(rng.choice(len(ACTIONS), p=probabilities))
            old_log_prob = float(np.log(max(probabilities[action], 1e-12)))
            name = ACTIONS[action]
            sequence.append(name)
            before = best
            if name == "STOP":
                done = True
            else:
                current = scores[name]
                best = min(best, current)
                mask[action] = False
                done = step == len(ACTIONS) - 1
            reward = (before - best) / normalizer
            episode.append({
                "state": state,
                "features": action_features,
                "mask": mask.copy() if name == "STOP" else np.logical_or(mask, np.arange(len(ACTIONS)) == action),
                "action": action,
                "old_log_prob": old_log_prob,
                "value": value,
                "reward": reward,
                "done": done,
            })
            if done:
                break
        running = 0.0
        for item in reversed(episode):
            running = item["reward"] + running
            item["return"] = running
            item["advantage"] = running - item["value"]
        transitions.extend(episode)
        summaries.append({"scene_id": scene["scene_id"], "best_score": best, "sequence": sequence})
    return transitions, summaries


def main():
    parser = argparse.ArgumentParser(description="Train PPO for concrete R1 action types.")
    parser.add_argument("--dataset", default="data/r1_pilot/pilot_dataset.json")
    parser.add_argument("--model", default="models/r1_v2/model.json")
    parser.add_argument("--report", default="results/r1-v2/training-report.json")
    parser.add_argument("--epochs", type=int, default=750)
    parser.add_argument("--seed", type=int, default=307)
    args = parser.parse_args()
    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    train = [item for item in dataset["records"] if item["split"] == "train"]
    validation = [item for item in dataset["records"] if item["split"] == "validation"]
    test = [item for item in dataset["records"] if item["split"] == "test"]
    model = LinearMaskedActorCritic(seed=args.seed)
    rng = np.random.default_rng(args.seed)
    for _ in range(args.epochs):
        transitions, _ = collect(model, train, rng)
        ppo_update(model, transitions)
    _, train_summary = collect(model, train, rng, deterministic=True)
    _, validation_summary = collect(model, validation, rng, deterministic=True)
    _, test_summary = collect(model, test, rng, deterministic=True) if test else ([], [])

    weights = {
        "interaction": model.interaction.tolist(),
        "action_bias": model.action_bias.tolist(),
        "critic": model.critic.tolist(),
        "critic_bias": model.critic_bias,
    }
    payload = {
        "manifest": {
            "feature_schema_version": "r1_concrete_features_v1",
            "action_schema_version": "r1_concrete_actions_v1",
            "candidate_space_version": "r1_concrete_candidates_v1",
            "state_dim": 6,
            "action_dim": 8,
            "training_method": "masked_clipped_ppo",
            "epochs": args.epochs,
            "seed": args.seed,
            "dataset_hash": canonical_hash(dataset),
            "train_parent_scene_ids": sorted({item["parent_scene_id"] for item in train}),
            "validation_parent_scene_ids": sorted({item["parent_scene_id"] for item in validation}),
            "test_parent_scene_ids": sorted({item["parent_scene_id"] for item in test}),
            "weights_hash": canonical_hash(weights),
        },
        "action_types": list(ACTIONS),
        "weights": weights,
    }
    model_path = Path(args.model)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    report = {
        "status": "TRAINED_EXPERIMENTAL",
        "train": train_summary,
        "validation": validation_summary,
        "test": test_summary,
        "weights_hash": payload["manifest"]["weights_hash"],
    }
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
