# Fly HeatNet 3D

Standalone Unity simulation of a fly brain drafting a heat-network plan on the floor.

- A detailed articulated Drosophila stands and walks on the drafting surface.
- The fly holds a physical pencil in its front claw and draws a continuous trajectory on the floor.
- There are no preset left/middle/right route candidates: the connectome emits successive steering commands from target bearing and obstacle-clearance sensors.
- Control points are converted to a smooth Catmull-Rom trajectory before the pencil follows it.
- Whole trajectories are evaluated with the hackathon score `S = 0.7 * cost/25M + 0.3 * length/100`.
- Positive best-so-far improvement becomes the dopamine feedback used by the next connectome rollout.
- Four source-to-consumer examples rotate automatically after each completed drawing; `R` repeats the current example.
- Route features stimulate a FlyWire v630 graph with 127,400 real neuron IDs and 14,687,178 measured directed connections.
- Neuron dynamics use the Shiu et al. leaky integrate-and-fire constants; a project-specific readout converts connectome activity directly into steering.

The detailed fly mesh is derived from [TuragaLab/flybody](https://github.com/TuragaLab/flybody), licensed under Apache 2.0. Attribution files are included in `Assets/FlyHeatNet/ThirdParty/FlyBody` and copied beside the Windows build.

The connectome asset is derived from [philshiu/Drosophila_brain_model](https://github.com/philshiu/Drosophila_brain_model), FlyWire v630, commit `91bdd1e7dcf193f3e7ca5a8933497fcef63b7960`. The original model code is MIT licensed. This is a connectome-constrained computational model, not a claim that the biological animal understands district heating.

Build output: `Build/FlyHeatNet3D.exe`.

Controls: `Space` pauses, `R` restarts the simulation.
