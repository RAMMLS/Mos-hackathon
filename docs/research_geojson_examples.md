# GeoJSON examples research

## Purpose

We need a small bank of reproducible examples for four routing decisions:
separate pipes, shared pipe, borderline 50/50, and refusal. The official case
dataset gives one area only, so the examples use real Russian urban geometry
from OpenStreetMap and synthetic heat-network task entities shaped to exercise
each decision.

## Selected areas

| Case | Area | Expected decision | Why this area is useful |
| --- | --- | --- | --- |
| `01_separate_pipes_moscow_zil` | Moscow, ZIL | separate branches | Dense redevelopment territory with many barriers and spread connection points. |
| `02_shared_pipe_moscow_kommunarka` | Moscow, Kommunarka | shared trunk | New Moscow development area where compact demand behind one corridor is plausible. |
| `03_fifty_fifty_innopolis` | Innopolis | mixed / borderline | Compact city geometry with two small demand groups, good for checking tie-breaks. |
| `04_refusal_nizhny_novgorod_strelka` | Nizhny Novgorod, Strelka | refusal candidate | A visually understandable river/transport barrier scenario for testing penalties. |

## Source model

The generator at `scripts/build_geojson_case_examples.py` downloads OSM geometry
through Overpass API and converts selected ways/relations into GeoJSON
restrictions. It then adds synthetic competition-like features:

- `source`;
- `heat_network`;
- `heat_chamber`;
- `oks_connection_point`.

The generated files live in `data/research_examples/`.

## How to present this honestly

Use the wording: real surrounding geometry from OSM, synthetic heat-network
scenario on top of it. Do not claim these are official heat-network expansion
projects or confirmed city routes.

For Moscow expansion and redevelopment context, refer to official public
materials about New Moscow, renovation, and комплексное развитие территорий.
Those materials justify why the areas are realistic urban-planning scenarios,
while the exact fixtures remain algorithmic examples.

## Scaling target

The immediate target is four verified examples. The same script structure can
scale to 400-4000 pictures by adding case definitions, but that should be done
after:

- the service can score a generated fixture automatically;
- the generator has stable Overpass rate limiting;
- the team agrees which labels are produced by rules and which are hand-labeled.

For a large corpus, use smaller bbox windows, cache raw OSM responses, and avoid
requesting large multipolygon relations unless the case specifically needs
water or park barriers.
