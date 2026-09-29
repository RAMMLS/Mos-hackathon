"""Generate sidecar event stream for the Fly Heatnet viewer.

The browser reads this small NDJSON file and only visualizes decisions. A real
connectome runner can later replace this script while keeping the same event
shape.
"""

from __future__ import annotations

import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPLAY = ROOT / "examples" / "ui_demo.json"
OUT_EXAMPLES = ROOT / "examples" / "ui_demo.events.ndjson"
OUT_WEB = ROOT / "web" / "demo" / "ui_demo.events.ndjson"

PHASES = [
    ("scan", "sensory scan of geometry and constraints"),
    ("decide", "connectome reservoir readout selects an operation"),
    ("draw", "motor-like output traces the candidate pipe"),
    ("commit", "checker feedback reinforces accepted route"),
]

REJECT_PHASES = [
    ("scan", "sensory scan of geometry and constraints"),
    ("decide", "connectome reservoir readout selects an operation"),
    ("draw", "motor-like output traces the candidate pipe"),
    ("reject", "checker feedback suppresses invalid route"),
]

NEURON_ROLES = [
    "visual_edge",
    "visual_motion",
    "constraint_avoidance",
    "cost_readout",
    "junction_memory",
    "route_motor",
    "checker_feedback",
]


def activation(step_index: int, phase_index: int, neuron_index: int) -> float:
    wave = math.sin(step_index * 1.7 + phase_index * 2.2 + neuron_index * 0.61)
    gate = 0.55 + 0.45 * math.cos((neuron_index % 7) - phase_index)
    value = 0.18 + 0.72 * max(0.0, wave * 0.5 + 0.5) * gate
    return round(max(0.04, min(1.0, value)), 3)


def active_neurons(step_index: int, phase_index: int, rejected: bool) -> list[dict]:
    ranked = []
    for neuron_index in range(28):
      value = activation(step_index, phase_index, neuron_index)
      if rejected and phase_index == 3 and neuron_index % 7 == 6:
          value = min(1.0, value + 0.42)
      ranked.append((value, neuron_index))
    ranked.sort(reverse=True)
    selected = ranked[:12]
    return [
        {
            "id": f"FLY-N{neuron_index:02d}",
            "role": NEURON_ROLES[neuron_index % len(NEURON_ROLES)],
            "activation": value,
        }
        for value, neuron_index in selected
    ]


def main() -> None:
    replay = json.loads(REPLAY.read_text(encoding="utf-8"))
    events = [
        {
            "type": "run_started",
            "mode": replay["mode"],
            "source": "sidecar.demo",
            "message": "Sidecar event stream loaded. Browser is visualization only.",
        }
    ]
    for step_index, step in enumerate(replay["steps"], start=1):
        phases = REJECT_PHASES if step["status"] == "rejected" else PHASES
        for phase_index, (phase, message) in enumerate(phases):
            events.append(
                {
                    "type": "brain_phase",
                    "step_id": step["id"],
                    "step_index": step_index,
                    "operation": step["operation"],
                    "phase": phase,
                    "message": message,
                    "candidate_status": step["status"],
                    "active_neurons": active_neurons(step_index, phase_index, step["status"] == "rejected"),
                }
            )
    text = "".join(json.dumps(event, ensure_ascii=False) + "\n" for event in events)
    OUT_EXAMPLES.write_text(text, encoding="utf-8")
    OUT_WEB.parent.mkdir(parents=True, exist_ok=True)
    OUT_WEB.write_text(text, encoding="utf-8")
    print(f"wrote {OUT_EXAMPLES}")
    print(f"wrote {OUT_WEB}")


if __name__ == "__main__":
    main()

