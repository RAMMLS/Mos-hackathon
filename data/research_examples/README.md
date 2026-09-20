# Research GeoJSON examples

This folder contains small research fixtures for four routing decisions:

1. `01_separate_pipes_moscow_zil` - separate branches are expected to be better.
2. `02_shared_pipe_moscow_kommunarka` - a shared trunk is expected to be better.
3. `03_fifty_fifty_innopolis` - mixed / borderline topology.
4. `04_refusal_nizhny_novgorod_strelka` - refusal can be cheaper than connection.

Each fixture combines real surrounding geometry from OpenStreetMap with synthetic
heat-network task entities. The real layer is used as restrictions and visual
context. The synthetic layer contains `source`, `heat_network`, `heat_chamber`,
and `oks_connection_point` objects shaped to stress the expected decision.

## Generate

Run all cases:

```bash
python3 scripts/build_geojson_case_examples.py
```

List cases:

```bash
python3 scripts/build_geojson_case_examples.py --list-cases
```

Run one case:

```bash
python3 scripts/build_geojson_case_examples.py --case 02_shared_pipe_moscow_kommunarka
```

Refresh raw OSM cache for selected cases:

```bash
python3 scripts/build_geojson_case_examples.py --refresh --case 02_shared_pipe_moscow_kommunarka
```

Use a specific Overpass endpoint:

```bash
python3 scripts/build_geojson_case_examples.py \
  --endpoint https://overpass-api.de/api/interpreter
```

Include large OSM relation multipolygons, for example rivers and parks that are
not returned as simple ways:

```bash
python3 scripts/build_geojson_case_examples.py \
  --include-relations \
  --case 04_refusal_nizhny_novgorod_strelka
```

If Overpass returns `429` or `504`, disable VPN or try another endpoint. The
script keeps raw responses in `raw_osm/`, so successful cases do not need to be
downloaded again.

## Outputs

- `*.geojson` - FeatureCollection in EPSG:4326.
- `*.svg` - lightweight preview image for research and slides.
- `index.json` - compact list of generated cases and feature counts.
- `raw_osm/*.json` - cached raw Overpass responses.

## Important caveat

These examples are not official city heat-network datasets. Use them as
algorithmic fixtures and presentation illustrations, not as evidence of actual
planned heat-network routes.
