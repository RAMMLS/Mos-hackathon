import argparse
import hashlib
import json
from pathlib import Path


ACTIONS = (
    "RANDOM_20+GREEDY",
    "GEOGRAPHIC_25+GREEDY",
    "HIGH_FLOW_25+REGRET",
    "BACKBONE_40+JUNCTION",
    "SUBTREE_25+REGRET",
    "STOP",
)

PROPOSAL_BY_ACTION = {
    "RANDOM_20+GREEDY": "B2-U",
    "GEOGRAPHIC_25+GREEDY": "B2-Q",
    "HIGH_FLOW_25+REGRET": "B2-C",
    "BACKBONE_40+JUNCTION": "B2-C-J",
    "SUBTREE_25+REGRET": "B3",
}


def canonical_hash(value):
    payload = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def reward(record, action):
    if action == "STOP":
        return 0.0
    proposal = record["proposals"].get(PROPOSAL_BY_ACTION[action], {})
    if proposal.get("status") != "VALID" or proposal.get("score") is None:
        return -0.05
    before = max(1.0, abs(float(record["initial_score"])))
    return max(0.0, float(record["initial_score"]) - float(proposal["score"])) / before


def main():
    parser = argparse.ArgumentParser(description="Fit R2 operator priors from checked solver transitions.")
    parser.add_argument("--dataset", default="data/r1_pilot/pilot_dataset.json")
    parser.add_argument("--model", default="models/r2_v1/model.json")
    parser.add_argument("--report", default="results/r2/training-report.json")
    args = parser.parse_args()

    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    train = [record for record in dataset["records"] if record["split"] == "train"]
    validation = [record for record in dataset["records"] if record["split"] == "validation"]
    test = [record for record in dataset["records"] if record["split"] == "test"]
    if not train or not validation:
        raise ValueError("R2 requires non-empty train and validation splits")

    priors = []
    for action in ACTIONS:
        mean_reward = sum(reward(record, action) for record in train) / len(train)
        priors.append(0.0 if action == "STOP" else max(0.001, mean_reward))
    weights = {"operator_priors": priors}
    payload = {
        "manifest": {
            "schema_version": "r2_operator_policy_v1",
            "action_count": len(ACTIONS),
            "training_method": "checked_transition_mean_reward_plus_online_update",
            "dataset_hash": canonical_hash(dataset),
            "train_parent_scene_ids": sorted({record["parent_scene_id"] for record in train}),
            "validation_parent_scene_ids": sorted({record["parent_scene_id"] for record in validation}),
            "test_parent_scene_ids": sorted({record["parent_scene_id"] for record in test}),
            "weights_hash": canonical_hash(weights),
        },
        "actions": list(ACTIONS),
        "weights": weights,
    }
    model_path = Path(args.model)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    report = {
        "status": "TRAINED_EXPERIMENTAL",
        "train_scene_count": len(train),
        "validation_scene_count": len(validation),
        "test_scene_count": len(test),
        "operator_priors": dict(zip(ACTIONS, priors)),
        "validation_rewards": {
            record["scene_id"]: {action: reward(record, action) for action in ACTIONS}
            for record in validation
        },
        "test_rewards": {
            record["scene_id"]: {action: reward(record, action) for action in ACTIONS}
            for record in test
        },
        "weights_hash": payload["manifest"]["weights_hash"],
    }
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
