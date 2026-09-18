import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VIS_DIR = Path(r"C:\Users\igorv\.codex\visualizations\2026\09\17\01a0b0d2-68de-7170-a7c7-4911d512d855")


def round_coords(value):
    if isinstance(value, list):
        if value and all(isinstance(item, (int, float)) for item in value):
            return [round(float(value[0]), 6), round(float(value[1]), 6)]
        return [round_coords(item) for item in value]
    return value


def slim_feature(feature, keep_props):
    props = feature.get("properties") or {}
    geometry = feature.get("geometry")
    return {
        "type": "Feature",
        "geometry": {
            "type": geometry["type"],
            "coordinates": round_coords(geometry["coordinates"]),
        } if geometry else None,
        "properties": {key: props.get(key) for key in keep_props if key in props},
    }


def load_data():
    source = json.loads((ROOT / "data" / "Датасет" / "!!!_Датасет.geojson").read_text(encoding="utf-8"))
    result = json.loads((ROOT / "result.geojson").read_text(encoding="utf-8"))
    source_features = []
    for feature in source["features"]:
        props = feature.get("properties") or {}
        object_type = props.get("object_type")
        restriction_type = props.get("restriction_type")
        if object_type in ("heat_network", "heat_chamber", "oks_connection_point") or restriction_type:
            source_features.append(slim_feature(
                feature,
                ["id", "object_type", "restriction_type", "diameter", "flow_tph"],
            ))
    result_features = [
        slim_feature(feature, [
            "id", "object_type", "variant_id", "start_node_id", "end_node_id",
            "flow_tph", "diameter", "length", "cost", "calculated_cost",
            "new_network_length", "score", "unconnected_oks_ids",
        ])
        for feature in result["features"]
        if feature.get("geometry")
    ]
    summary = next(
        (
            feature.get("properties", {})
            for feature in result["features"]
            if (feature.get("properties") or {}).get("object_type") == "variant_summary"
        ),
        {},
    )
    counts = Counter((feature.get("properties") or {}).get("object_type") for feature in result["features"])
    return {
        "source": {"type": "FeatureCollection", "features": source_features},
        "result": {"type": "FeatureCollection", "features": result_features},
        "summary": summary,
        "counts": dict(counts),
    }


def build_fragment(data):
    summary = data["summary"]
    counts = data["counts"]
    blob = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    length = summary.get("new_network_length", 0)
    cost = round(summary.get("calculated_cost", 0) / 1_000_000, 1)
    tie_count = counts.get("tie_in", 0)
    return f"""<div id="heat-route-map" class="heat-route-map">
  <style>
    #heat-route-map {{ color: var(--foreground); }}
    #heat-route-map .viz-controls {{ margin-bottom: 10px; }}
    #heat-route-map .metrics {{ display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 10px; }}
    #heat-route-map .metric {{ padding: 8px 10px; border: 1px solid var(--border); background: color-mix(in srgb, var(--card) 72%, transparent); }}
    #heat-route-map .label {{ color: var(--muted-foreground); }}
    #heat-route-map .value {{ font-weight: 500; }}
    #heat-route-map svg {{ display: block; width: 100%; height: auto; }}
    #heat-route-map .map-frame {{ fill: color-mix(in srgb, var(--muted) 18%, transparent); stroke: var(--border); }}
    #heat-route-map .existing-net {{ fill: none; stroke: var(--muted-foreground); stroke-width: 1.1; opacity: .55; }}
    #heat-route-map .new-net {{ fill: none; stroke: var(--viz-series-1); stroke-linecap: round; stroke-linejoin: round; opacity: .94; }}
    #heat-route-map .restriction {{ opacity: .12; stroke: none; }}
    #heat-route-map .restriction-line {{ opacity: .35; fill: none; stroke-width: 1.3; }}
    #heat-route-map .oks {{ fill: var(--orange); stroke: var(--background); stroke-width: 1.3; }}
    #heat-route-map .tie {{ fill: var(--green); stroke: var(--background); stroke-width: 1.3; }}
    #heat-route-map .chamber {{ fill: var(--blue); stroke: var(--background); stroke-width: 1.1; }}
    #heat-route-map .legend {{ display: flex; flex-wrap: wrap; gap: 10px 14px; margin-top: 8px; align-items: center; }}
    #heat-route-map .legend span {{ display: inline-flex; gap: 6px; align-items: center; }}
    #heat-route-map .swatch {{ width: 18px; height: 3px; background: var(--viz-series-1); display: inline-block; }}
    #heat-route-map .dot {{ width: 9px; height: 9px; border-radius: 50%; background: var(--orange); display: inline-block; }}
    #heat-route-map .detail {{ min-height: 24px; margin-top: 6px; color: var(--muted-foreground); }}
    @media (max-width: 640px) {{ #heat-route-map .metrics {{ grid-template-columns: repeat(2, minmax(0, 1fr)); }} }}
  </style>
  <div class="metrics" aria-label="Route metrics">
    <div class="metric"><div class="label">ОКС подключены</div><div class="value tabular-nums">17 / 17</div></div>
    <div class="metric"><div class="label">Новая сеть</div><div class="value tabular-nums">{length} м</div></div>
    <div class="metric"><div class="label">Стоимость</div><div class="value tabular-nums">{cost} млн ₽</div></div>
    <div class="metric"><div class="label">Врезки</div><div class="value tabular-nums">{tie_count}</div></div>
  </div>
  <div class="viz-controls" aria-label="Map layers">
    <label class="form-check"><input class="form-check-input" id="show-existing" type="checkbox" checked><span class="form-check-label">Существующая сеть</span></label>
    <label class="form-check"><input class="form-check-input" id="show-restrictions" type="checkbox" checked><span class="form-check-label">Ограничения</span></label>
    <label class="form-check"><input class="form-check-input" id="show-points" type="checkbox" checked><span class="form-check-label">Точки</span></label>
  </div>
  <svg role="img" aria-label="Карта построенной трассы теплосети" viewBox="0 0 920 620"></svg>
  <div class="legend text-small">
    <span><i class="swatch"></i> новая сеть</span>
    <span><i class="swatch" style="background:var(--muted-foreground); opacity:.55"></i> существующая сеть</span>
    <span><i class="dot"></i> ОКС</span>
    <span><i class="dot" style="background:var(--green)"></i> врезка</span>
    <span><i class="dot" style="background:var(--blue)"></i> камера</span>
  </div>
  <div class="detail text-small" aria-live="polite">Наведись на новый участок, чтобы увидеть расход, диаметр и длину.</div>
</div>
<script src="https://cdn.jsdelivr.net/npm/d3@7.9.0/dist/d3.min.js"></script>
<script>
(function() {{
  const root = document.getElementById('heat-route-map');
  const data = {blob};
  const svg = d3.select(root).select('svg');
  const detail = root.querySelector('.detail');
  const width = 920, height = 620;
  const allFeatures = data.source.features.concat(data.result.features).filter(f => f.geometry);
  const projection = d3.geoMercator().fitExtent([[18,18],[width-18,height-18]], {{type:'FeatureCollection', features: allFeatures}});
  const path = d3.geoPath(projection);
  svg.append('rect').attr('class','map-frame').attr('x',1).attr('y',1).attr('width',width-2).attr('height',height-2);
  const restrictions = data.source.features.filter(f => f.properties.restriction_type);
  const existingNet = data.source.features.filter(f => f.properties.object_type === 'heat_network');
  const oks = data.source.features.filter(f => f.properties.object_type === 'oks_connection_point');
  const existingChambers = data.source.features.filter(f => f.properties.object_type === 'heat_chamber');
  const newNet = data.result.features.filter(f => f.properties.object_type === 'heat_network');
  const ties = data.result.features.filter(f => f.properties.object_type === 'tie_in');
  const chambers = data.result.features.filter(f => f.properties.object_type === 'heat_chamber');
  const restrictionColor = t => t === 'water' ? 'var(--blue)' : t === 'park' ? 'var(--green)' : t === 'road' ? 'var(--yellow)' : t === 'gas_pipeline' ? 'var(--red)' : 'var(--purple)';
  const gRestrictions = svg.append('g').attr('data-layer','restrictions');
  gRestrictions.selectAll('path').data(restrictions).join('path')
    .attr('d', path)
    .attr('class', d => d.geometry.type.includes('Line') ? 'restriction-line' : 'restriction')
    .attr('fill', d => restrictionColor(d.properties.restriction_type))
    .attr('stroke', d => restrictionColor(d.properties.restriction_type));
  const gExisting = svg.append('g').attr('data-layer','existing');
  gExisting.selectAll('path').data(existingNet).join('path').attr('d', path).attr('class','existing-net');
  const maxDia = d3.max(newNet, d => +d.properties.diameter || 1) || 1;
  const widthScale = d3.scaleLinear().domain([0, maxDia]).range([2.4, 8]);
  svg.append('g').selectAll('path').data(newNet).join('path')
    .attr('d', path)
    .attr('class','new-net')
    .attr('stroke-width', d => widthScale(+d.properties.diameter || 1))
    .attr('data-tooltip', d => `${{d.properties.id}}: D${{d.properties.diameter}}, ${{d.properties.flow_tph}} т/ч, ${{d.properties.length}} м`)
    .on('mouseenter focus', function(event, d) {{ detail.textContent = `${{d.properties.id}}: расход ${{d.properties.flow_tph}} т/ч, диаметр ${{d.properties.diameter}} мм, длина ${{d.properties.length}} м, стоимость ${{Math.round(d.properties.cost/100000)/10}} млн ₽`; }})
    .on('mouseleave blur', function() {{ detail.textContent = 'Наведись на новый участок, чтобы увидеть расход, диаметр и длину.'; }});
  const gPoints = svg.append('g').attr('data-layer','points');
  function pointXY(d) {{ return projection(d.geometry.coordinates); }}
  gPoints.selectAll('circle.oks').data(oks).join('circle').attr('class','oks').attr('r',4).attr('cx',d=>pointXY(d)[0]).attr('cy',d=>pointXY(d)[1]).attr('data-tooltip', d => `ОКС ${{d.properties.id}}: ${{d.properties.flow_tph || ''}} т/ч`);
  gPoints.selectAll('circle.tie').data(ties).join('circle').attr('class','tie').attr('r',5).attr('cx',d=>pointXY(d)[0]).attr('cy',d=>pointXY(d)[1]).attr('data-tooltip', d => `Врезка ${{d.properties.id}}`);
  gPoints.selectAll('circle.chamber-new').data(chambers).join('circle').attr('class','chamber').attr('r',4.5).attr('cx',d=>pointXY(d)[0]).attr('cy',d=>pointXY(d)[1]).attr('data-tooltip', d => `Новая камера ${{d.properties.id}}`);
  gPoints.selectAll('circle.chamber-old').data(existingChambers).join('circle').attr('class','chamber').attr('r',2.2).attr('opacity',.42).attr('cx',d=>pointXY(d)[0]).attr('cy',d=>pointXY(d)[1]);
  root.querySelector('#show-existing').addEventListener('change', e => gExisting.attr('display', e.target.checked ? null : 'none'));
  root.querySelector('#show-restrictions').addEventListener('change', e => gRestrictions.attr('display', e.target.checked ? null : 'none'));
  root.querySelector('#show-points').addEventListener('change', e => gPoints.attr('display', e.target.checked ? null : 'none'));
}})();
</script>
"""


def main():
    VIS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = VIS_DIR / "heat-route-result.html"
    out_path.write_text(build_fragment(load_data()), encoding="utf-8")
    print(out_path)
    print(out_path.stat().st_size)


if __name__ == "__main__":
    main()
