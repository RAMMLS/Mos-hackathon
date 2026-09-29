"""Build the compact FlyWire v630 runtime asset used by the Unity simulation."""

from pathlib import Path
import struct

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "fly-connectome-source"
OUTPUT = Path(__file__).resolve().parents[1] / "Assets/FlyHeatNet/Resources/FlyWireV630.bytes"


def main() -> None:
    neurons = pd.read_csv(SOURCE / "2023_03_23_completeness_630_final.csv", index_col=0)
    connections = pd.read_parquet(
        SOURCE / "2023_03_23_connectivity_630_final.parquet",
        columns=["Presynaptic_Index", "Postsynaptic_Index", "Excitatory x Connectivity"],
    )

    pre = connections["Presynaptic_Index"].to_numpy(dtype=np.int32, copy=False)
    post = connections["Postsynaptic_Index"].to_numpy(dtype=np.int32, copy=False)
    weight = connections["Excitatory x Connectivity"].to_numpy(dtype=np.int16, copy=False)
    order = np.argsort(pre, kind="stable")
    pre, post, weight = pre[order], post[order], weight[order]

    node_count = len(neurons)
    counts = np.bincount(pre, minlength=node_count).astype("<i4")
    offsets = np.empty(node_count + 1, dtype="<i4")
    offsets[0] = 0
    np.cumsum(counts, out=offsets[1:])

    # Highly connected real neurons make propagation and the small on-screen witness graph legible.
    strength = np.bincount(pre, weights=np.abs(weight), minlength=node_count)
    input_neurons = np.argsort(strength)[-192:][::-1].astype("<i4")
    seed = int(input_neurons[0])
    local_start, local_end = offsets[seed], offsets[seed + 1]
    neighbors = post[local_start:local_end]
    neighbor_weights = np.abs(weight[local_start:local_end])
    display = [int(input_neurons[group * 32]) for group in range(6)]
    for index in neighbors[np.argsort(neighbor_weights)[::-1]]:
        value = int(index)
        if value not in display:
            display.append(value)
        if len(display) == 28:
            break
    for value in input_neurons:
        if len(display) == 28:
            break
        if int(value) not in display:
            display.append(int(value))

    ids = neurons.index.to_numpy(dtype=np.int64)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("wb") as stream:
        stream.write(b"FW63")
        stream.write(struct.pack("<5i", 1, node_count, len(post), len(display), 32))
        for index in display:
            stream.write(struct.pack("<iq", index, int(ids[index])))
        input_neurons.tofile(stream)
        offsets.tofile(stream)
        post.astype("<i4", copy=False).tofile(stream)
        weight.astype("<i2", copy=False).tofile(stream)

    print(
        f"FLYWIRE_ASSET_OK nodes={node_count} edges={len(post)} "
        f"display={len(display)} bytes={OUTPUT.stat().st_size} path={OUTPUT}"
    )


if __name__ == "__main__":
    main()
