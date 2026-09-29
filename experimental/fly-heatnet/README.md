# Fly Heatnet Lab

Isolated experimental prototype for the "fly-brain heat-network planner" idea.
It is deliberately kept outside the main Java solver so the production solution
does not depend on the connectome experiment.

## What Is Included

- `web/fly_replay.html` - 3D replay viewer: fly, synthetic brain activity,
  candidate drawing, checker accept/reject phases, and the engineering map
  overlay.
- `examples/ui_demo.json` - replay log in the simplified `fly-replay-v1` format.
- `examples/ui_demo.events.ndjson` and `web/demo/ui_demo.events.ndjson` -
  sidecar event stream with per-phase `active_neurons`.
- `schemas/replay.schema.json` - machine-readable contract for replay files.
- `scripts/generate_ui_demo.py` - deterministic demo replay generator.
- `sidecar/generate_demo_events.py` - deterministic external brain-event
  generator. This stands in for a future Python/CUDA connectome runner.

The bundled replay is marked as `UI_DEMO`. It does not claim to run a trained
connectome model and it does not replace the engineering checker.

## Run

Run a tiny static server from the viewer folder:

```text
cd experimental/fly-heatnet/web
python -m http.server 8123 --bind 127.0.0.1
```

Then open:

```text
http://127.0.0.1:8123/fly_replay.html
```

Use "Open replay JSON" in the app to load another replay.

To regenerate the example:

```text
python experimental/fly-heatnet/scripts/generate_ui_demo.py
python experimental/fly-heatnet/sidecar/generate_demo_events.py
```

## Integration Boundary

This module reads replay snapshots plus sidecar events only. A future bridge
from the Java solver and connectome runner should export the same formats:

```text
action -> checked candidate -> event -> full snapshot -> replay JSON
connectome state -> readout -> active_neurons -> NDJSON event
```

The app must stay honest about its mode:

- `UI_DEMO`: artificial sequence, interface demonstration only.
- `B2_REPLAY`: existing solver log replay.
- `FLY_HYBRID`: fixed connectome features plus trained head, checked by solver.
