# Real GeoJSON places research

## Decision

The research dataset must contain real GeoJSON only. We do not generate
synthetic heat-network objects for this corpus. The previous synthetic approach
was removed.

## Method

`scripts/download_real_geojson_places.py` builds a grid of real map windows over
Russian cities and downloads OSM geometry via Overpass. For every window it
keeps real map features only:

- buildings;
- roads and major roads;
- railways;
- water and waterways;
- parks and green restrictions.

The script computes simple spatial metrics from the downloaded geometry and
assigns the window to one research bucket:

- `separate_pipes`;
- `shared_pipe`;
- `fifty_fifty`;
- `refusal`.

These buckets are heuristic research labels, not official heat-network ground
truth.

## Target

Collect 400 places:

- 100 `separate_pipes`;
- 100 `shared_pipe`;
- 100 `fifty_fifty`;
- 100 `refusal`.

The resulting GeoJSON files are stored under `data/real_geojson_places/`.

## Operational notes

Overpass may rate-limit VPN traffic with `429` or time out with `504`. The
script therefore writes raw OSM responses to `data/real_geojson_places/raw_osm/`
and can resume without re-downloading successful windows.

Run without VPN when possible:

```bash
python3 scripts/download_real_geojson_places.py --download --target-per-category 100 --max-candidates 1600
```
