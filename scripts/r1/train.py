import argparse
import json
from pathlib import Path

import numpy as np

from scripts.r1.core import LinearMaskedActorCritic, assert_no_split_leakage, canonical_hash
from scripts.r1.ppo import collect_rollouts, ppo_update


def evaluate(model, scenes, seed):
    _, summaries = collect_rollouts(model, scenes, np.random.default_rng(seed), deterministic=True)
    return summaries


def oracle_score(scene):
    scores = [float(scene["initial_score"])]
    scores.extend(
        float(proposal["score"])
        for proposal in scene["proposals"].values()
        if proposal.get("status") == "VALID"
        and proposal.get("benchmark_eligible", True)
        and proposal.get("score") is not None
    )
    return min(scores)


def main():
    parser = argparse.ArgumentParser(description="Train the first R1 masked-action PPO pilot.")
    parser.add_argument("--dataset", default="data/r1_pilot/pilot_dataset.json")
    parser.add_argument("--model", default="models/r1_pilot_v1/model.json")
    parser.add_argument("--report", default="results/r1-pilot/training-report.json")
    parser.add_argument("--epochs", type=int, default=250)
    parser.add_argument("--seed", type=int, default=101)
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    records = dataset["records"]
    assert_no_split_leakage(records)
    train_scenes = [item for item in records if item["split"] == "train"]
    validation_scenes = [item for item in records if item["split"] == "validation"]
    if not train_scenes or not validation_scenes:
        raise ValueError("R1 pilot requires non-empty train and validation splits")

    rng = np.random.default_rng(args.seed)
    model = LinearMaskedActorCritic(seed=args.seed)
    history = []
    for epoch in range(args.epochs):
        transitions, train_summary = collect_rollouts(model, train_scenes, rng)
        losses = ppo_update(model, transitions)
        if epoch % 25 == 0 or epoch == args.epochs - 1:
            validation = evaluate(model, validation_scenes, args.seed)
            history.append({
                "epoch": epoch,
                "losses": losses,
                "train_mean_best_score": float(np.mean([item["best_score"] for item in train_summary])),
                "validation": validation,
            })

    train_evaluation = evaluate(model, train_scenes, args.seed)
    validation = evaluate(model, validation_scenes, args.seed)
    manifest_payload = model.save(Path(args.model), {
        "status": "PILOT_NOT_PRODUCTION",
        "training_seed": args.seed,
        "epochs": args.epochs,
        "dataset_hash": canonical_hash(dataset),
        "train_parent_scene_ids": sorted({item["parent_scene_id"] for item in train_scenes}),
        "validation_parent_scene_ids": sorted({item["parent_scene_id"] for item in validation_scenes}),
    })
    report = {
        "status": "PILOT_TRAINED",
        "dataset": str(dataset_path),
        "dataset_hash": canonical_hash(dataset),
        "model": args.model,
        "model_weights_hash": manifest_payload["manifest"]["weights_hash"],
        "train_scene_count": len(train_scenes),
        "validation_scene_count": len(validation_scenes),
        "epochs": args.epochs,
        "seed": args.seed,
        "validation": validation,
        "train_evaluation": train_evaluation,
        "train_oracle_match_count": sum(
            abs(item["best_score"] - oracle_score(scene)) < 1.0e-9
            for item, scene in zip(train_evaluation, train_scenes)
        ),
        "validation_oracle_match_count": sum(
            abs(item["best_score"] - oracle_score(scene)) < 1.0e-9
            for item, scene in zip(validation, validation_scenes)
        ),
        "history": history,
        "production_ready": False,
        "blocking_gaps": [
            "only BUILD_BACKBONE, DESTROY_REPAIR and STOP are represented",
            "training still has one official parent scene",
            "the policy selects complete deterministic proposals instead of editing individual graph objects",
        ],
    }
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
