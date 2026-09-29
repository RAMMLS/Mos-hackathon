(() => {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";
  const MAP_WIDTH = 1200;
  const MAP_HEIGHT = 800;
  const MAP_PADDING = 54;
  const ALGORITHM_ORDER = ["B1", "B2-U", "B2-Q", "B2-C", "B2-C-J", "B3"];
  const BUCKET_NAMES = {
    separate_pipes: "Раздельные ветки",
    shared_pipe: "Общий ствол",
    fifty_fifty: "Пограничный случай",
    refusal: "Сильные барьеры",
  };
  const SPLIT_NAMES = { train: "Train", validation: "Validation", test: "Test" };

  const state = {
    catalog: null,
    records: [],
    filteredRecords: [],
    selectedScene: null,
    input: null,
    result: null,
    inputName: "",
    resultName: "",
    resultProfile: null,
    selectedElement: null,
    demandBuildings: new WeakSet(),
    projection: null,
    viewBox: { x: 0, y: 0, width: MAP_WIDTH, height: MAP_HEIGHT },
    drag: null,
    sceneLoadToken: 0,
    layerVisibility: {
      restrictions: true,
      "existing-network": true,
      "input-points": true,
      "result-network": true,
      chambers: true,
    },
  };

  const dom = {
    svg: document.getElementById("map"),
    content: document.getElementById("map-content"),
    emptyState: document.getElementById("empty-state"),
    dropTarget: document.getElementById("drop-target"),
    dropOverlay: document.getElementById("drop-overlay"),
    inputFile: document.getElementById("input-file"),
    resultFile: document.getElementById("result-file"),
    runButton: document.getElementById("run-algorithm"),
    downloadButton: document.getElementById("download-result"),
    algorithmSelect: document.getElementById("algorithm-select"),
    rulesetSelect: document.getElementById("ruleset-select"),
    runState: document.getElementById("run-state"),
    runStateText: document.getElementById("run-state-text"),
    tooltip: document.getElementById("map-tooltip"),
    inspector: document.getElementById("inspector"),
    inspectorKind: document.getElementById("inspector-kind"),
    inspectorTitle: document.getElementById("inspector-title"),
    inspectorBody: document.getElementById("inspector-body"),
    closeInspector: document.getElementById("close-inspector"),
    toggleInspector: document.getElementById("toggle-inspector"),
    inspector: document.getElementById("inspector"),
    metricOks: document.getElementById("metric-oks"),
    metricStatus: document.getElementById("metric-status"),
    metricLength: document.getElementById("metric-length"),
    metricCost: document.getElementById("metric-cost"),
    metricScore: document.getElementById("metric-score"),
    metricRuleset: document.getElementById("metric-ruleset"),
    sceneList: document.getElementById("scene-list"),
    sceneSearch: document.getElementById("scene-search"),
    splitFilter: document.getElementById("split-filter"),
    bucketFilter: document.getElementById("bucket-filter"),
    rlOnly: document.getElementById("rl-only"),
    catalogCount: document.getElementById("catalog-count"),
    sceneTitle: document.getElementById("scene-title"),
    sceneContext: document.getElementById("scene-context"),
  };

  function setStatus(text, tone = "neutral") {
    dom.runStateText.textContent = text;
    dom.runState.dataset.tone = tone;
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function formatNumber(value, digits = 2) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "—";
    return new Intl.NumberFormat("ru-RU", { maximumFractionDigits: digits }).format(number);
  }

  function validateGeoJson(value) {
    if (!value || value.type !== "FeatureCollection" || !Array.isArray(value.features)) {
      throw new Error("Ожидался GeoJSON FeatureCollection с массивом features");
    }
    return value;
  }

  async function readGeoJson(file) {
    try {
      return validateGeoJson(JSON.parse(await file.text()));
    } catch (error) {
      if (error instanceof SyntaxError) throw new Error(`Файл ${file.name} содержит некорректный JSON`);
      throw error;
    }
  }

  function looksLikeResult(data) {
    return data.features.some((feature) => feature.properties?.object_type === "variant_summary");
  }

  function objectType(feature) {
    return String(feature.properties?.object_type || "unknown");
  }

  function pointOnSegment(point, start, end) {
    const squaredLength = (end[0] - start[0]) ** 2 + (end[1] - start[1]) ** 2;
    if (squaredLength < 1e-20) {
      return Math.abs(point[0] - start[0]) < 1e-10 && Math.abs(point[1] - start[1]) < 1e-10;
    }
    const cross = (point[1] - start[1]) * (end[0] - start[0])
      - (point[0] - start[0]) * (end[1] - start[1]);
    if (Math.abs(cross) > 1e-10) return false;
    const dot = (point[0] - start[0]) * (end[0] - start[0])
      + (point[1] - start[1]) * (end[1] - start[1]);
    return dot >= 0 && dot <= squaredLength;
  }

  function pointInRing(point, ring) {
    let inside = false;
    for (let index = 0, previous = ring.length - 1; index < ring.length; previous = index++) {
      const a = ring[previous];
      const b = ring[index];
      if (pointOnSegment(point, a, b)) return true;
      const crosses = (a[1] > point[1]) !== (b[1] > point[1])
        && point[0] < ((b[0] - a[0]) * (point[1] - a[1])) / (b[1] - a[1]) + a[0];
      if (crosses) inside = !inside;
    }
    return inside;
  }

  function pointInPolygon(point, rings) {
    return Boolean(rings?.length) && pointInRing(point, rings[0])
      && !rings.slice(1).some((ring) => pointInRing(point, ring));
  }

  function geometryContainsPoint(geometry, point) {
    if (geometry?.type === "Polygon") return pointInPolygon(point, geometry.coordinates);
    if (geometry?.type === "MultiPolygon") {
      return geometry.coordinates.some((polygon) => pointInPolygon(point, polygon));
    }
    return false;
  }

  function detectDemandBuildings(data) {
    const result = new WeakSet();
    const connectionPoints = (data?.features || [])
      .filter((feature) => objectType(feature) === "oks_connection_point")
      .map((feature) => feature.geometry?.coordinates)
      .filter((coordinate) => Array.isArray(coordinate));
    for (const feature of data?.features || []) {
      const properties = feature.properties || {};
      if (objectType(feature) !== "restriction" || properties.restriction_type !== "oks") continue;
      if (properties.selected_as_heat_demand_proxy
          || connectionPoints.some((point) => geometryContainsPoint(feature.geometry, point))) {
        result.add(feature);
      }
    }
    return result;
  }

  function updateHeaderSummary(summary) {
    document.getElementById("header-scenes").textContent = summary.scene_count ?? "—";
    document.getElementById("header-eligible").textContent = summary.benchmark_eligible_count ?? "—";
    document.getElementById("header-rl").textContent = summary.rl_scene_count ?? "—";
  }

  async function loadCatalog() {
    try {
      const query = new URLSearchParams(window.location.search);
      const response = await fetch("/api/dataset/catalog", { cache: "no-store" });
      if (!response.ok) throw new Error(`Каталог недоступен: HTTP ${response.status}`);
      state.catalog = await response.json();
      state.records = state.catalog.records || [];
      updateHeaderSummary(state.catalog.summary || {});
      renderCatalog();
      const requested = query.get("scene");
      const first = state.records.find((item) => item.scene_id === requested)
        || state.filteredRecords.find((item) => item.split === "train")
        || state.filteredRecords[0];
      if (first) {
        await loadScene(first.scene_id);
        const requestedAlgorithm = query.get("algorithm");
        if (requestedAlgorithm
            && Array.from(dom.algorithmSelect.options)
              .some((option) => option.value === requestedAlgorithm)) {
          dom.algorithmSelect.value = requestedAlgorithm;
        }
        if (query.get("autorun") === "1") {
          await runAlgorithm();
        }
      }
      else setStatus("В каталоге нет сцен", "error");
    } catch (error) {
      dom.sceneList.innerHTML = `<p class="catalog-empty">${escapeHtml(error.message)}. Можно открыть локальный GeoJSON кнопкой ниже.</p>`;
      dom.sceneTitle.textContent = "Каталог недоступен";
      setStatus(error.message, "error");
      renderSceneInspector();
    }
  }

  function sceneMatches(record) {
    const query = dom.sceneSearch.value.trim().toLocaleLowerCase("ru");
    const split = dom.splitFilter.value;
    const bucket = dom.bucketFilter.value;
    if (dom.rlOnly.checked && !record.selected_for_rl) return false;
    if (split !== "all" && record.split !== split) return false;
    if (bucket !== "all" && record.research_bucket !== bucket) return false;
    if (!query) return true;
    return [record.scene_id, record.city, record.seed, BUCKET_NAMES[record.research_bucket]]
      .some((value) => String(value || "").toLocaleLowerCase("ru").includes(query));
  }

  function renderCatalog() {
    state.filteredRecords = state.records.filter(sceneMatches).sort((left, right) =>
      left.split.localeCompare(right.split)
      || left.city.localeCompare(right.city, "ru")
      || left.research_bucket.localeCompare(right.research_bucket)
      || left.scene_id.localeCompare(right.scene_id)
    );
    dom.catalogCount.textContent = state.filteredRecords.length;
    dom.sceneList.replaceChildren();
    if (!state.filteredRecords.length) {
      dom.sceneList.innerHTML = '<p class="catalog-empty">По этим фильтрам сцен нет.</p>';
      return;
    }
    const fragment = document.createDocumentFragment();
    state.filteredRecords.forEach((record) => {
      const button = document.createElement("button");
      button.className = "scene-row";
      button.type = "button";
      button.role = "option";
      button.dataset.sceneId = record.scene_id;
      button.setAttribute("aria-selected", String(state.selectedScene?.scene_id === record.scene_id));
      const connected = record.b1?.connected_oks;
      const total = record.b1?.total_oks ?? record.oks_count;
      const qualityClass = record.b1?.benchmark_eligible ? "good" : "warn";
      const qualityText = connected == null ? "—" : `${connected}/${total}`;
      button.innerHTML = `
        <span class="scene-copy">
          <strong>${escapeHtml(record.city)}</strong>
          <span>${escapeHtml(BUCKET_NAMES[record.research_bucket])} · ${escapeHtml(SPLIT_NAMES[record.split])}</span>
        </span>
        <span class="scene-state">
          <span>B1 ОКС</span>
          <strong class="${qualityClass}">${qualityText}</strong>
        </span>`;
      button.addEventListener("click", () => loadScene(record.scene_id));
      fragment.appendChild(button);
    });
    dom.sceneList.appendChild(fragment);
  }

  function updateSelectedCatalogRow() {
    dom.sceneList.querySelectorAll(".scene-row").forEach((row) => {
      row.setAttribute("aria-selected", String(row.dataset.sceneId === state.selectedScene?.scene_id));
    });
  }

  async function loadScene(sceneId) {
    const record = state.records.find((item) => item.scene_id === sceneId);
    if (!record) return;
    const token = ++state.sceneLoadToken;
    state.selectedScene = record;
    state.result = null;
    state.resultName = "";
    state.resultProfile = null;
    updateSelectedCatalogRow();
    updateSceneHeader();
    renderSceneInspector();
    dom.runButton.disabled = true;
    setStatus(`Загружаю ${record.city}`, "working");
    try {
      const response = await fetch(`/api/dataset/scenes/${encodeURIComponent(sceneId)}`, { cache: "no-store" });
      if (!response.ok) throw new Error(`Сцена недоступна: HTTP ${response.status}`);
      const data = validateGeoJson(await response.json());
      if (token !== state.sceneLoadToken) return;
      state.input = data;
      state.inputName = `${sceneId}.geojson`;
      dom.runButton.disabled = false;
      dom.downloadButton.disabled = true;
      renderMap();
      renderSceneInspector();
      history.replaceState(null, "", `?scene=${encodeURIComponent(sceneId)}`);
      setStatus(`${record.city}: ${record.feature_count} объектов`, "ready");
    } catch (error) {
      if (token !== state.sceneLoadToken) return;
      state.input = null;
      renderMap();
      setStatus(error.message, "error");
    }
  }

  function updateSceneHeader() {
    if (!state.selectedScene) {
      dom.sceneContext.textContent = state.inputName ? "Локальный файл" : "Сцена не выбрана";
      dom.sceneTitle.textContent = state.inputName || "Откройте GeoJSON";
      return;
    }
    const record = state.selectedScene;
    dom.sceneContext.textContent = `${SPLIT_NAMES[record.split]} · ${BUCKET_NAMES[record.research_bucket]} · ${record.scene_id}`;
    dom.sceneTitle.textContent = record.city;
  }

  function validationBadge(record) {
    if (record.selected_for_rl) return '<span class="scene-badge good">В RL-наборе</span>';
    if (record.b1?.benchmark_eligible) return '<span class="scene-badge">Полное подключение</span>';
    return '<span class="scene-badge warn">Robustness-корпус</span>';
  }

  function algorithmRows(record) {
    if (!record || !Object.keys(record.algorithms || {}).length) {
      return '<p class="quality-note">Для этой сцены нет полного набора эталонных прогонов. Её можно запустить вручную сверху.</p>';
    }
    const items = ALGORITHM_ORDER
      .map((name) => ({ name, ...(record.algorithms[name] || {}) }))
      .filter((item) => item.score != null || item.status);
    const validScores = items.filter((item) => item.benchmark_eligible !== false && item.score != null).map((item) => Number(item.score));
    const best = validScores.length ? Math.min(...validScores) : 0;
    const worst = validScores.length ? Math.max(...validScores) : 1;
    return `<div class="algorithm-list">${items.map((item) => {
      const score = Number(item.score);
      const valid = item.status === "VALID" && item.benchmark_eligible !== false && Number.isFinite(score);
      const width = valid ? (worst === best ? 100 : 24 + 76 * (worst - score) / (worst - best)) : 8;
      return `<div class="algorithm-row ${valid ? "" : "invalid"}">
        <strong>${escapeHtml(item.name)}</strong>
        <span class="algorithm-track"><i style="width:${width.toFixed(1)}%"></i></span>
        <span>${valid ? formatNumber(score) : "INVALID"}</span>
      </div>`;
    }).join("")}</div>`;
  }

  function currentRunBlock() {
    const summary = summaryProperties();
    if (!summary) return "";
    const missing = Array.isArray(summary.unconnected_oks_ids) ? summary.unconnected_oks_ids : [];
    return `<section class="inspector-section">
      <h3>Текущий запуск</h3>
      <div class="scene-stats">
        <div><span>Алгоритм</span><strong>${escapeHtml(dom.algorithmSelect.value)}</strong></div>
        <div><span>Статус</span><strong>${escapeHtml(summary.solution_status || "НЕИЗВЕСТНО")}</strong></div>
        <div><span>Score</span><strong>${formatNumber(summary.score)}</strong></div>
        <div><span>Длина</span><strong>${formatNumber(summary.new_network_length)} м</strong></div>
        <div><span>Стоимость</span><strong>${formatNumber(Number(summary.calculated_cost) / 1_000_000, 1)} млн ₽</strong></div>
      </div>
      ${missing.length ? `<p class="quality-note"><strong>Не подключены:</strong> ${missing.map(escapeHtml).join(", ")}</p>` : ""}
    </section>`;
  }

  function layerControls() {
    const layers = [
      ["restrictions", "restriction", "Ограничения"],
      ["existing-network", "existing", "Существующая сеть"],
      ["input-points", "oks", "ОКС и источники"],
      ["result-network", "result", "Новая трасса"],
      ["chambers", "chamber", "Камеры и узлы"],
    ];
    return `<div class="layer-list">${layers.map(([key, css, label]) =>
      `<label class="layer-toggle"><input type="checkbox" data-layer-toggle="${key}" ${state.layerVisibility[key] ? "checked" : ""}><i class="${css}"></i><span>${label}</span></label>`
    ).join("")}</div>`;
  }

  function bindLayerControls() {
    dom.inspectorBody.querySelectorAll("[data-layer-toggle]").forEach((checkbox) => {
      checkbox.addEventListener("change", () => {
        state.layerVisibility[checkbox.dataset.layerToggle] = checkbox.checked;
        applyLayerVisibility();
      });
    });
  }

  function renderSceneInspector() {
    state.selectedElement?.classList.remove("selected");
    state.selectedElement = null;
    dom.closeInspector.classList.toggle("hidden", !window.matchMedia("(max-width: 1220px)").matches);
    dom.inspectorKind.textContent = state.selectedScene ? "Паспорт сцены" : "Источник данных";
    dom.inspectorTitle.textContent = state.selectedScene?.city || state.inputName || "Данные не выбраны";
    if (!state.selectedScene) {
      dom.inspectorBody.innerHTML = `
        <p class="inspector-placeholder">Выберите сцену в каталоге. Здесь появятся качество входа, результаты алгоритмов и управление слоями.</p>
        <section class="inspector-section"><h3>Слои</h3>${layerControls()}</section>`;
      bindLayerControls();
      return;
    }
    const record = state.selectedScene;
    const connected = record.b1?.connected_oks;
    const total = record.b1?.total_oks ?? record.oks_count;
    dom.inspectorBody.innerHTML = `
      <div class="scene-badges">
        <span class="scene-badge">${escapeHtml(SPLIT_NAMES[record.split])}</span>
        <span class="scene-badge">${escapeHtml(BUCKET_NAMES[record.research_bucket])}</span>
        ${validationBadge(record)}
      </div>
      <section class="inspector-section">
        <h3>Состав сцены</h3>
        <div class="scene-stats">
          <div><span>Объектов карты</span><strong>${formatNumber(record.restriction_count, 0)}</strong></div>
          <div><span>Точек ОКС</span><strong>${formatNumber(record.oks_count, 0)}</strong></div>
          <div><span>Подключено B1</span><strong>${connected == null ? "—" : `${connected} / ${total}`}</strong></div>
          <div><span>Score B1</span><strong>${formatNumber(record.b1?.score)}</strong></div>
        </div>
      </section>
      <section class="inspector-section">
        <h3>Проверенные результаты</h3>
        ${algorithmRows(record)}
      </section>
      ${currentRunBlock()}
      <section class="inspector-section">
        <h3>Слои карты</h3>
        ${layerControls()}
      </section>
      <section class="inspector-section">
        <h3>Происхождение</h3>
        <p class="quality-note">Геометрия и выделенные здания взяты из OpenStreetMap. Голубая точка всегда находится внутри подсвеченного здания и хранит его OSM ID. Выбор здания как потребителя, его нагрузка и стартовая теплосеть являются синтетическими прокси, а не подтверждёнными эксплуатационными данными.</p>
      </section>`;
    bindLayerControls();
  }

  function renderFeatureInspector(feature, origin, element) {
    state.selectedElement?.classList.remove("selected");
    state.selectedElement = element;
    element.classList.add("selected");
    const properties = feature.properties || {};
    dom.closeInspector.classList.remove("hidden");
    dom.inspector.classList.add("is-open");
    dom.inspectorKind.textContent = origin === "result" ? "Результат алгоритма" : "Исходный объект";
    dom.inspectorTitle.textContent = featureTitle(feature);
    dom.inspectorBody.replaceChildren();
    const geometry = document.createElement("p");
    geometry.className = "geometry-meta";
    geometry.textContent = `Геометрия: ${feature.geometry?.type || "нет"} · Полей: ${Object.keys(properties).length}`;
    const table = document.createElement("table");
    table.className = "property-table";
    Object.entries(properties).forEach(([key, value]) => {
      const row = document.createElement("tr");
      const name = document.createElement("th");
      const content = document.createElement("td");
      name.textContent = key;
      content.textContent = typeof value === "object" ? JSON.stringify(value) : String(value);
      row.append(name, content);
      table.appendChild(row);
    });
    dom.inspectorBody.append(geometry, table);
  }

  function allRenderableFeatures() {
    const input = state.input?.features.filter((feature) => feature.geometry) || [];
    const result = state.result?.features.filter((feature) => feature.geometry) || [];
    return input.concat(result);
  }

  function visitCoordinates(geometry, callback) {
    if (!geometry) return;
    if (geometry.type === "GeometryCollection") {
      (geometry.geometries || []).forEach((item) => visitCoordinates(item, callback));
      return;
    }
    const walk = (value) => {
      if (!Array.isArray(value)) return;
      if (value.length >= 2 && typeof value[0] === "number" && typeof value[1] === "number") {
        callback(value);
        return;
      }
      value.forEach(walk);
    };
    walk(geometry.coordinates);
  }

  function rawMercator(coordinate) {
    const lon = Number(coordinate[0]);
    const lat = Math.max(-85, Math.min(85, Number(coordinate[1])));
    const radians = lat * Math.PI / 180;
    return [lon * Math.PI / 180, Math.log(Math.tan(Math.PI / 4 + radians / 2))];
  }

  function createProjection(features) {
    const points = [];
    const bbox = state.input?.metadata?.bbox_south_west_north_east;
    if (Array.isArray(bbox) && bbox.length === 4) {
      const [south, west, north, east] = bbox.map(Number);
      points.push(rawMercator([west, south]), rawMercator([east, north]));
    } else {
      features.forEach((feature) => visitCoordinates(feature.geometry, (coordinate) => points.push(rawMercator(coordinate))));
    }
    if (!points.length) return null;
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    points.forEach(([x, y]) => {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    });
    const spanX = Math.max(maxX - minX, 0.000001);
    const spanY = Math.max(maxY - minY, 0.000001);
    const scale = Math.min((MAP_WIDTH - MAP_PADDING * 2) / spanX, (MAP_HEIGHT - MAP_PADDING * 2) / spanY);
    const usedWidth = spanX * scale;
    const usedHeight = spanY * scale;
    const offsetX = (MAP_WIDTH - usedWidth) / 2;
    const offsetY = (MAP_HEIGHT - usedHeight) / 2;
    return (coordinate) => {
      const [x, y] = rawMercator(coordinate);
      return [offsetX + (x - minX) * scale, MAP_HEIGHT - offsetY - (y - minY) * scale];
    };
  }

  function linePath(coordinates, project, close = false) {
    if (!Array.isArray(coordinates) || !coordinates.length) return "";
    return coordinates.map((coordinate, index) => {
      const [x, y] = project(coordinate);
      return `${index ? "L" : "M"}${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(" ") + (close ? " Z" : "");
  }

  function geometryPath(geometry, project) {
    if (!geometry) return "";
    switch (geometry.type) {
      case "LineString": return linePath(geometry.coordinates, project);
      case "MultiLineString": return geometry.coordinates.map((line) => linePath(line, project)).join(" ");
      case "Polygon": return geometry.coordinates.map((ring) => linePath(ring, project, true)).join(" ");
      case "MultiPolygon": return geometry.coordinates.flatMap((polygon) => polygon.map((ring) => linePath(ring, project, true))).join(" ");
      case "GeometryCollection": return (geometry.geometries || []).map((item) => geometryPath(item, project)).join(" ");
      default: return "";
    }
  }

  function svgElement(name, attributes = {}) {
    const element = document.createElementNS(SVG_NS, name);
    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
    return element;
  }

  function layerFor(feature, origin) {
    const type = objectType(feature);
    if (type === "restriction") return "restrictions";
    if (origin === "result" && type === "heat_network") return "result-network";
    if (origin === "input" && type === "heat_network") return "existing-network";
    if (type === "heat_chamber" || type === "technical_node" || type === "tie_in") return "chambers";
    return "input-points";
  }

  function classFor(feature, origin) {
    const type = objectType(feature);
    if (type === "restriction") return "restriction-path";
    if (type === "heat_network") return origin === "result" ? "result-network-path" : "existing-network-path";
    if (type === "oks_connection_point") {
      const summary = summaryProperties();
      if (!summary) return "point-oks";
      const unconnected = new Set(summary.unconnected_oks_ids || []);
      return unconnected.has(String(feature.properties?.id))
        ? "point-oks point-oks-unconnected"
        : "point-oks point-oks-connected";
    }
    if (type === "heat_source" || type === "source") return "point-source";
    if (type === "technical_node") return "point-technical";
    if (type === "tie_in") return "point-tie";
    if (type === "heat_chamber") return origin === "result" ? "point-result-chamber" : "point-existing-chamber";
    return "point-existing-chamber";
  }

  function featureTitle(feature) {
    const properties = feature.properties || {};
    if (properties.selected_as_heat_demand_proxy || state.demandBuildings.has(feature)) {
      return `Здание-потребитель · ${properties.osm_id ?? properties.id ?? "без id"}`;
    }
    if (properties.consumer_role === "osm_building_heat_demand_proxy") {
      return `Точка здания OSM · ${properties.source_building_osm_id ?? "без id"}`;
    }
    const id = properties.id ?? "без id";
    const type = properties.object_type || feature.geometry?.type || "объект";
    return `${type} · ${id}`;
  }

  function attachInteraction(element, feature, origin) {
    element.classList.add("geo-feature");
    element.dataset.layer = layerFor(feature, origin);
    element.addEventListener("pointerenter", (event) => showTooltip(event, feature));
    element.addEventListener("pointermove", moveTooltip);
    element.addEventListener("pointerleave", hideTooltip);
    element.addEventListener("click", (event) => {
      event.stopPropagation();
      renderFeatureInspector(feature, origin, element);
    });
  }

  function renderPointFeature(group, feature, origin, project) {
    const coordinates = [];
    visitCoordinates(feature.geometry, (coordinate) => coordinates.push(coordinate));
    coordinates.forEach((coordinate) => {
      const [cx, cy] = project(coordinate);
      const type = objectType(feature);
      const radius = type === "heat_source" || type === "source" ? 7 : type === "oks_connection_point" ? 5.5 : 4.5;
      const point = svgElement(type === "heat_chamber" || type === "technical_node" ? "rect" : "circle");
      if (point.tagName === "rect") {
        point.setAttribute("x", (cx - radius).toFixed(2));
        point.setAttribute("y", (cy - radius).toFixed(2));
        point.setAttribute("width", (radius * 2).toFixed(2));
        point.setAttribute("height", (radius * 2).toFixed(2));
        point.setAttribute("transform", `rotate(45 ${cx.toFixed(2)} ${cy.toFixed(2)})`);
      } else {
        point.setAttribute("cx", cx.toFixed(2));
        point.setAttribute("cy", cy.toFixed(2));
        point.setAttribute("r", radius);
      }
      point.setAttribute("class", classFor(feature, origin));
      group.appendChild(point);
    });
    attachInteraction(group, feature, origin);
  }

  function renderFeature(group, feature, origin, project, underlayGroup = group) {
    if (!feature.geometry) return;
    if (feature.geometry.type === "Point" || feature.geometry.type === "MultiPoint") {
      renderPointFeature(group, feature, origin, project);
      return;
    }
    const pathData = geometryPath(feature.geometry, project);
    if (!pathData) return;
    const path = svgElement("path", { d: pathData, class: classFor(feature, origin), "fill-rule": "evenodd" });
    if (objectType(feature) === "restriction") {
      path.dataset.restriction = String(feature.properties?.restriction_type || "other");
      path.dataset.demandBuilding = String(state.demandBuildings.has(feature));
    }
    if (origin === "result" && objectType(feature) === "heat_network") {
      const diameter = Number(feature.properties?.diameter || 0);
      const routeWidth = Math.max(1.6, Math.min(3.2, 1.4 + diameter / 350));
      const halo = svgElement("path", { d: pathData, class: "result-network-halo", "aria-hidden": "true" });
      halo.dataset.layer = "result-network";
      halo.style.setProperty("--route-halo-width", `${routeWidth + 2.6}px`);
      underlayGroup.appendChild(halo);
      path.style.setProperty("--route-width", `${routeWidth}px`);
      path.setAttribute("pathLength", "1");
    }
    group.appendChild(path);
    attachInteraction(path, feature, origin);
  }

  function drawGrid() {
    const grid = svgElement("g", { "aria-hidden": "true" });
    for (let x = 0; x <= MAP_WIDTH; x += 80) {
      grid.appendChild(svgElement("line", { x1: x, y1: 0, x2: x, y2: MAP_HEIGHT, class: "map-grid" }));
    }
    for (let y = 0; y <= MAP_HEIGHT; y += 80) {
      grid.appendChild(svgElement("line", { x1: 0, y1: y, x2: MAP_WIDTH, y2: y, class: "map-grid" }));
    }
    dom.content.appendChild(grid);
  }

  function sortedFeatures(data, origin) {
    const priority = (feature) => {
      const type = objectType(feature);
      if (type === "restriction") return 0;
      if (type === "heat_network") return origin === "input" ? 1 : 2;
      return 3;
    };
    return (data?.features || []).filter((feature) => feature.geometry).slice().sort((a, b) => priority(a) - priority(b));
  }

  function renderMap() {
    dom.content.replaceChildren();
    state.selectedElement = null;
    const features = allRenderableFeatures();
    dom.emptyState.classList.toggle("hidden", features.length > 0);
    if (!features.length) {
      updateMetrics();
      return;
    }
    state.projection = createProjection(features);
    state.demandBuildings = detectDemandBuildings(state.input);
    drawGrid();
    const inputGroup = svgElement("g", { "data-origin": "input" });
    const resultUnderlayGroup = svgElement("g", { "data-origin": "result-underlay" });
    const resultGroup = svgElement("g", { "data-origin": "result" });
    sortedFeatures(state.input, "input").forEach((feature) => renderFeature(inputGroup, feature, "input", state.projection));
    sortedFeatures(state.result, "result").forEach((feature) =>
      renderFeature(resultGroup, feature, "result", state.projection, resultUnderlayGroup));
    dom.content.append(inputGroup, resultUnderlayGroup, resultGroup);
    applyLayerVisibility();
    fitMap();
    updateMetrics();
  }

  function summaryProperties() {
    return state.result?.features.find((feature) => feature.properties?.object_type === "variant_summary")?.properties || null;
  }

  function inferResultProfile(data) {
    const summary = data?.features?.find(
      (feature) => feature.properties?.object_type === "variant_summary"
    )?.properties;
    const diagnostics = Array.isArray(summary?.diagnostics) ? summary.diagnostics : [];
    const rulesetLine = diagnostics.find((line) => String(line).startsWith("ruleset="));
    const experimentalLine = diagnostics.find((line) =>
      String(line).startsWith("ruleset_experimental="));
    const id = summary?.ruleset_id || (rulesetLine ? String(rulesetLine).slice("ruleset=".length) : null);
    if (!id) return null;
    return {
      id,
      experimental: summary?.ruleset_experimental === true
        || String(experimentalLine || "").endsWith("=true"),
    };
  }

  function selectedRulesetProfile() {
    const id = dom.rulesetSelect.value;
    return { id, experimental: id === "EXPERIMENTAL_ANY_BOUNDARY_V1" };
  }

  function rulesetLabel(profile) {
    if (!profile) return "—";
    if (profile.id === "EXPERIMENTAL_ANY_BOUNDARY_V1") return "ЭКСПЕРИМЕНТ";
    if (profile.id === "DOCUMENT_NEAREST_V1") return "ТЗ · ближайшая грань";
    return profile.id;
  }

  function updateMetrics() {
    const inputOks = state.input?.features.filter((feature) => objectType(feature) === "oks_connection_point").length || 0;
    const summary = summaryProperties();
    const fallbackConnected = state.selectedScene?.b1?.connected_oks;
    const fallbackTotal = state.selectedScene?.b1?.total_oks ?? inputOks;
    const unconnected = Array.isArray(summary?.unconnected_oks_ids) ? summary.unconnected_oks_ids.length : 0;
    const solutionStatus = summary?.solution_status
      || (summary ? (unconnected ? "PARTIAL" : "FULL") : "NONE");
    const statusLabels = {
      FULL: "ПОЛНОЕ РЕШЕНИЕ",
      PARTIAL: "ЧАСТИЧНОЕ РЕШЕНИЕ",
      NO_CERTIFIED_SOLUTION: "НЕТ СЕРТИФИЦИРОВАННОГО РЕШЕНИЯ",
      NONE: "НЕТ РЕЗУЛЬТАТА",
    };
    dom.metricStatus.dataset.status = solutionStatus.toLowerCase();
    dom.metricStatus.querySelector("strong").textContent = statusLabels[solutionStatus] || solutionStatus;
    dom.metricOks.textContent = summary
      ? `${Math.max(0, inputOks - unconnected)} / ${inputOks}`
      : fallbackConnected == null ? (inputOks ? `— / ${inputOks}` : "—") : `${fallbackConnected} / ${fallbackTotal}`;
    dom.metricLength.textContent = summary ? `${formatNumber(summary.new_network_length)} м` : "—";
    dom.metricCost.textContent = summary ? `${formatNumber(Number(summary.calculated_cost) / 1_000_000, 1)} млн ₽` : "—";
    dom.metricScore.textContent = summary ? formatNumber(summary.score) : formatNumber(state.selectedScene?.b1?.score);
    const profile = state.resultProfile || selectedRulesetProfile();
    dom.metricRuleset.dataset.experimental = String(Boolean(profile?.experimental));
    dom.metricRuleset.querySelector("strong").textContent = rulesetLabel(profile);
  }

  function showTooltip(event, feature) {
    dom.tooltip.textContent = featureTitle(feature);
    dom.tooltip.classList.add("visible");
    moveTooltip(event);
  }

  function moveTooltip(event) {
    const bounds = dom.dropTarget.getBoundingClientRect();
    dom.tooltip.style.left = `${Math.min(bounds.width - 290, Math.max(8, event.clientX - bounds.left + 12))}px`;
    dom.tooltip.style.top = `${Math.min(bounds.height - 50, Math.max(74, event.clientY - bounds.top + 12))}px`;
  }

  function hideTooltip() {
    dom.tooltip.classList.remove("visible");
  }

  function applyLayerVisibility() {
    Object.entries(state.layerVisibility).forEach(([layer, visible]) => {
      dom.content.querySelectorAll(`[data-layer="${layer}"]`).forEach((element) => {
        element.classList.toggle("layer-hidden", !visible);
      });
    });
  }

  function setViewBox(next) {
    state.viewBox = next;
    dom.svg.setAttribute("viewBox", `${next.x} ${next.y} ${next.width} ${next.height}`);
  }

  function fitMap() {
    setViewBox({ x: 0, y: 0, width: MAP_WIDTH, height: MAP_HEIGHT });
  }

  function zoom(factor, centerX = .5, centerY = .5) {
    const current = state.viewBox;
    const width = Math.max(120, Math.min(MAP_WIDTH * 8, current.width * factor));
    const height = width * (current.height / current.width);
    const x = current.x + current.width * centerX - width * centerX;
    const y = current.y + current.height * centerY - height * centerY;
    setViewBox({ x, y, width, height });
  }

  async function runAlgorithm() {
    if (!state.input) return;
    dom.runButton.disabled = true;
    setStatus(`${dom.algorithmSelect.value}: строю трассу`, "working");
    try {
      const apiUrl = new URL("/api/trace", window.location.href);
      apiUrl.searchParams.set("algorithm", dom.algorithmSelect.value);
      apiUrl.searchParams.set("ruleset", dom.rulesetSelect.value);
      if (dom.rulesetSelect.value === "EXPERIMENTAL_ANY_BOUNDARY_V1") {
        apiUrl.searchParams.set("entryStrategy", "DIRECT_ALLOWED");
      }
      const payload = new Blob([JSON.stringify(state.input)], { type: "application/geo+json" });
      const form = new FormData();
      form.append("file", payload, state.inputName || "input.geojson");
      const response = await fetch(apiUrl.toString(), { method: "POST", body: form });
      if (!response.ok) {
        const details = await response.text();
        throw new Error(`API ${response.status}: ${details.slice(0, 180)}`);
      }
      state.result = validateGeoJson(await response.json());
      state.resultProfile = {
        id: response.headers.get("X-Ruleset-Id") || dom.rulesetSelect.value,
        experimental: response.headers.get("X-Ruleset-Experimental") === "true",
      };
      state.resultName = `${state.selectedScene?.scene_id || "scene"}-${dom.algorithmSelect.value}.geojson`;
      dom.downloadButton.disabled = false;
      renderMap();
      renderSceneInspector();
      const summary = summaryProperties();
      setStatus(summary ? `${dom.algorithmSelect.value}: score ${formatNumber(summary.score)}` : "Трасса рассчитана", "ready");
    } catch (error) {
      setStatus(error.message || "Не удалось запустить алгоритм", "error");
    } finally {
      dom.runButton.disabled = !state.input;
    }
  }

  function downloadResult() {
    if (!state.result) return;
    const blob = new Blob([JSON.stringify(state.result, null, 2)], { type: "application/geo+json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = state.resultName || "result.geojson";
    link.click();
    URL.revokeObjectURL(url);
  }

  async function loadLocalFile(file, target) {
    try {
      setStatus(`Читаю ${file.name}`, "working");
      const data = await readGeoJson(file);
      if (target === "result") {
        state.result = data;
        state.resultName = file.name;
        state.resultProfile = inferResultProfile(data);
        if (state.resultProfile
            && Array.from(dom.rulesetSelect.options).some(
              (option) => option.value === state.resultProfile.id)) {
          dom.rulesetSelect.value = state.resultProfile.id;
        }
        dom.downloadButton.disabled = false;
      } else {
        state.sceneLoadToken += 1;
        state.selectedScene = null;
        state.input = data;
        state.inputName = file.name;
        state.result = null;
        state.resultName = "";
        state.resultProfile = null;
        dom.runButton.disabled = false;
        dom.downloadButton.disabled = true;
        updateSelectedCatalogRow();
        updateSceneHeader();
      }
      renderMap();
      renderSceneInspector();
      setStatus(`${file.name}: ${data.features.length} объектов`, "ready");
    } catch (error) {
      setStatus(error.message, "error");
    }
  }

  async function handleDroppedFiles(files) {
    for (const file of files) {
      const data = await readGeoJson(file);
      const target = looksLikeResult(data) ? "result" : (!state.input ? "input" : "result");
      if (target === "result") {
        state.result = data;
        state.resultName = file.name;
        state.resultProfile = inferResultProfile(data);
        if (state.resultProfile
            && Array.from(dom.rulesetSelect.options).some(
              (option) => option.value === state.resultProfile.id)) {
          dom.rulesetSelect.value = state.resultProfile.id;
        }
      } else {
        state.sceneLoadToken += 1;
        state.selectedScene = null;
        state.input = data;
        state.inputName = file.name;
        state.result = null;
        state.resultName = "";
        state.resultProfile = null;
      }
    }
    dom.runButton.disabled = !state.input;
    dom.downloadButton.disabled = !state.result;
    updateSelectedCatalogRow();
    updateSceneHeader();
    renderMap();
    renderSceneInspector();
    setStatus(`Загружено файлов: ${files.length}`, "ready");
  }

  [dom.sceneSearch, dom.splitFilter, dom.bucketFilter, dom.rlOnly].forEach((control) => {
    control.addEventListener(control === dom.sceneSearch ? "input" : "change", renderCatalog);
  });
  dom.rulesetSelect.addEventListener("change", updateMetrics);
  document.getElementById("open-local").addEventListener("click", () => dom.inputFile.click());
  document.getElementById("choose-input").addEventListener("click", () => dom.inputFile.click());
  document.getElementById("choose-result").addEventListener("click", () => dom.resultFile.click());
  document.getElementById("empty-open").addEventListener("click", () => dom.inputFile.click());
  dom.inputFile.addEventListener("change", () => dom.inputFile.files[0] && loadLocalFile(dom.inputFile.files[0], "input"));
  dom.resultFile.addEventListener("change", () => dom.resultFile.files[0] && loadLocalFile(dom.resultFile.files[0], "result"));
  dom.runButton.addEventListener("click", runAlgorithm);
  dom.downloadButton.addEventListener("click", downloadResult);
  dom.toggleInspector.addEventListener("click", () => {
    dom.inspector.classList.add("is-open");
    renderSceneInspector();
  });
  dom.closeInspector.addEventListener("click", () => {
    dom.inspector.classList.remove("is-open");
    renderSceneInspector();
  });
  document.getElementById("fit-map").addEventListener("click", fitMap);
  document.getElementById("zoom-in").addEventListener("click", () => zoom(.8));
  document.getElementById("zoom-out").addEventListener("click", () => zoom(1.25));

  dom.svg.addEventListener("wheel", (event) => {
    event.preventDefault();
    const bounds = dom.svg.getBoundingClientRect();
    zoom(event.deltaY > 0 ? 1.13 : .885, (event.clientX - bounds.left) / bounds.width, (event.clientY - bounds.top) / bounds.height);
  }, { passive: false });

  dom.svg.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || event.target.closest?.(".geo-feature")) return;
    state.drag = { x: event.clientX, y: event.clientY, viewBox: { ...state.viewBox } };
    dom.svg.setPointerCapture(event.pointerId);
    dom.svg.classList.add("dragging");
  });
  dom.svg.addEventListener("pointermove", (event) => {
    if (!state.drag) return;
    const bounds = dom.svg.getBoundingClientRect();
    const dx = (event.clientX - state.drag.x) / bounds.width * state.drag.viewBox.width;
    const dy = (event.clientY - state.drag.y) / bounds.height * state.drag.viewBox.height;
    setViewBox({ ...state.drag.viewBox, x: state.drag.viewBox.x - dx, y: state.drag.viewBox.y - dy });
  });
  const stopDrag = () => {
    state.drag = null;
    dom.svg.classList.remove("dragging");
  };
  dom.svg.addEventListener("pointerup", stopDrag);
  dom.svg.addEventListener("pointercancel", stopDrag);
  dom.svg.addEventListener("click", (event) => {
    if (event.target === dom.svg || event.target.classList.contains("map-background")) renderSceneInspector();
  });

  let dragDepth = 0;
  dom.dropTarget.addEventListener("dragenter", (event) => {
    event.preventDefault();
    dragDepth += 1;
    dom.dropOverlay.classList.add("visible");
  });
  dom.dropTarget.addEventListener("dragover", (event) => event.preventDefault());
  dom.dropTarget.addEventListener("dragleave", () => {
    dragDepth -= 1;
    if (dragDepth <= 0) dom.dropOverlay.classList.remove("visible");
  });
  dom.dropTarget.addEventListener("drop", async (event) => {
    event.preventDefault();
    dragDepth = 0;
    dom.dropOverlay.classList.remove("visible");
    const files = Array.from(event.dataTransfer.files).filter((file) => /\.(geojson|json)$/i.test(file.name));
    if (!files.length) return;
    try {
      await handleDroppedFiles(files.slice(0, 2));
    } catch (error) {
      setStatus(error.message, "error");
    }
  });

  updateMetrics();
  renderSceneInspector();
  loadCatalog();
})();
