# FlyBody model attribution

The detailed Drosophila body meshes and `fruitfly.xml` are sourced from
[`TuragaLab/flybody`](https://github.com/TuragaLab/flybody), developed by
Google DeepMind and HHMI Janelia Research Campus.

Source license: Apache License 2.0. A copy is included as `LICENSE.txt`.

Modifications in this project:

- MuJoCo XML hierarchy is converted into a Unity prefab.
- Source materials are recreated with the Fly HeatNet shaders.
- The front leg is procedurally posed to hold a pencil during the route drawing simulation.
