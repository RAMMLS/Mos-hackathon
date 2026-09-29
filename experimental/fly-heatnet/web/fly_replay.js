import * as THREE from "./vendor/three.module.js";

(() => {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";
  const MAP = { width: 1000, height: 680, padding: 54 };

  const demoReplay = {
    schema_version: "fly-replay-v1",
    mode: "UI_DEMO",
    coordinate_system: "LOCAL_METERS",
    provenance: "Bundled synthetic demo. Generated from the isolated fly-heatnet prototype.",
    input: {
      type: "FeatureCollection",
      features: [
        feature("src-1", "heat_source", point(70, 370), { label: "Source" }),
        feature("cam-1", "heat_chamber", point(190, 370), { network_role: "existing" }),
        feature("old-1", "heat_network", line([70, 370], [190, 370], [330, 360]), { network_role: "existing", diameter: 500 }),
        feature("oks-1", "oks_connection_point", point(520, 210), { flow_tph: 9.1 }),
        feature("oks-2", "oks_connection_point", point(720, 255), { flow_tph: 8.4 }),
        feature("oks-3", "oks_connection_point", point(620, 470), { flow_tph: 12.5 }),
        feature("oks-4", "oks_connection_point", point(840, 505), { flow_tph: 7.4 }),
        feature("bld-1", "restriction", polygon([470, 165], [560, 165], [560, 245], [470, 245]), { restriction_type: "oks" }),
        feature("bld-2", "restriction", polygon([675, 210], [760, 210], [760, 295], [675, 295]), { restriction_type: "oks" }),
        feature("bld-3", "restriction", polygon([575, 425], [665, 425], [665, 515], [575, 515]), { restriction_type: "oks" }),
        feature("bld-4", "restriction", polygon([795, 462], [890, 462], [890, 548], [795, 548]), { restriction_type: "oks" }),
        feature("park-1", "restriction", polygon([405, 300], [555, 300], [555, 395], [405, 395]), { restriction_type: "park" }),
        feature("road-1", "restriction", polygon([320, 135], [365, 135], [365, 565], [320, 565]), { restriction_type: "road" }),
        feature("water-1", "restriction", polygon([695, 355], [930, 355], [930, 410], [695, 410]), { restriction_type: "water" })
      ]
    },
    initial: fc([
      segment("new-0-a", [[190, 370], [360, 370], [515, 260]], 125),
      segment("new-0-b", [[515, 260], [520, 210]], 80)
    ]),
    initial_metrics: metrics(1, 343.5, 49700000, 18.76, "VALID", 0),
    steps: [
      {
        id: "step-001",
        operation: "JUNCTION_TO_OKS",
        message: "Connect oks-2 through the existing shared branch.",
        status: "committed",
        candidate: fc([segment("new-1-a", [[515, 260], [700, 258], [720, 255]], 100)]),
        after: fc([
          segment("new-0-a", [[190, 370], [360, 370], [515, 260]], 125),
          segment("new-0-b", [[515, 260], [520, 210]], 80),
          segment("new-1-a", [[515, 260], [700, 258], [720, 255]], 100)
        ]),
        metrics_after: metrics(2, 530.8, 68200000, 15.21, "VALID", 21)
      },
      {
        id: "step-002",
        operation: "DIRECT_BRANCH",
        message: "Reject a visually short branch because checker flags the park buffer.",
        status: "rejected",
        candidate: fc([segment("cand-2-a", [[515, 260], [620, 340], [620, 470]], 125, "rejected")]),
        after: fc([
          segment("new-0-a", [[190, 370], [360, 370], [515, 260]], 125),
          segment("new-0-b", [[515, 260], [520, 210]], 80),
          segment("new-1-a", [[515, 260], [700, 258], [720, 255]], 100)
        ]),
        metrics_after: metrics(2, 530.8, 68200000, 15.21, "REJECTED", 14)
      },
      {
        id: "step-003",
        operation: "BYPASS_REPAIR",
        message: "Route oks-3 around the restricted park area.",
        status: "committed",
        candidate: fc([segment("new-3-a", [[360, 370], [505, 420], [620, 470]], 125)]),
        after: fc([
          segment("new-0-a", [[190, 370], [360, 370], [515, 260]], 125),
          segment("new-0-b", [[515, 260], [520, 210]], 80),
          segment("new-1-a", [[515, 260], [700, 258], [720, 255]], 100),
          segment("new-3-a", [[360, 370], [505, 420], [620, 470]], 125)
        ]),
        metrics_after: metrics(3, 802.4, 94800000, 12.64, "VALID", 29)
      },
      {
        id: "step-004",
        operation: "WATER_SAFE_EXTENSION",
        message: "Add a lower branch to oks-4 without crossing the water polygon.",
        status: "committed",
        candidate: fc([segment("new-4-a", [[620, 470], [730, 528], [840, 505]], 100)]),
        after: fc([
          segment("new-0-a", [[190, 370], [360, 370], [515, 260]], 125),
          segment("new-0-b", [[515, 260], [520, 210]], 80),
          segment("new-1-a", [[515, 260], [700, 258], [720, 255]], 100),
          segment("new-3-a", [[360, 370], [505, 420], [620, 470]], 125),
          segment("new-4-a", [[620, 470], [730, 528], [840, 505]], 100)
        ]),
        metrics_after: metrics(4, 1032.7, 124900000, 9.88, "VALID", 33)
      }
    ]
  };

  const state = {
    replay: demoReplay,
    stepIndex: 0,
    playing: false,
    timer: 0,
    phase: "idle",
    events: [],
    eventsByStepPhase: new Map(),
    project: (p) => p
  };

  const dom = {
    grid: document.getElementById("grid"),
    inputLayer: document.getElementById("input-layer"),
    networkLayer: document.getElementById("network-layer"),
    candidateLayer: document.getElementById("candidate-layer"),
    decisionLayer: document.getElementById("decision-layer"),
    flyLayer: document.getElementById("fly-layer"),
    canvas3d: document.getElementById("brain-scene"),
    play: document.getElementById("play"),
    prev: document.getElementById("prev"),
    next: document.getElementById("next"),
    range: document.getElementById("step-range"),
    label: document.getElementById("step-label"),
    mode: document.getElementById("mode-pill"),
    title: document.getElementById("operation-title"),
    note: document.getElementById("honesty-note"),
    phaseLabel: document.getElementById("phase-label"),
    phaseTitle: document.getElementById("phase-title"),
    brainBars: document.getElementById("brain-bars"),
    activeNeurons: document.getElementById("active-neurons"),
    message: document.getElementById("step-message"),
    provenance: document.getElementById("provenance"),
    connected: document.getElementById("m-connected"),
    length: document.getElementById("m-length"),
    cost: document.getElementById("m-cost"),
    score: document.getElementById("m-score"),
    checker: document.getElementById("m-checker"),
    action: document.getElementById("m-action"),
    file: document.getElementById("file-input")
  };

  class BrainRouteSimulation {
    constructor(canvas) {
      this.canvas = canvas;
      this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
      this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      this.scene = new THREE.Scene();
      this.scene.background = new THREE.Color(0x101417);
      this.camera = new THREE.PerspectiveCamera(42, 1, 0.1, 200);
      this.camera.position.set(0, 18, 34);
      this.camera.lookAt(0, 0, 0);
      this.clock = new THREE.Clock();
      this.elapsed = 0;
      this.currentPath = [];
      this.activeNeuronMap = new Map();
      this.flyStart = new THREE.Vector3(-12, 4, 0);
      this.flyEnd = new THREE.Vector3(0, 4, 0);
      this.phase = "idle";

      this.staticGroup = new THREE.Group();
      this.routeGroup = new THREE.Group();
      this.candidateGroup = new THREE.Group();
      this.brainGroup = new THREE.Group();
      this.flyGroup = new THREE.Group();
      this.scene.add(this.staticGroup, this.routeGroup, this.candidateGroup, this.brainGroup, this.flyGroup);

      this.scene.add(new THREE.HemisphereLight(0xdcecff, 0x15100c, 2.5));
      const key = new THREE.DirectionalLight(0xffffff, 2.4);
      key.position.set(10, 18, 9);
      this.scene.add(key);

      this.routeMaterial = new THREE.MeshStandardMaterial({
        color: 0x68d391,
        emissive: 0x1c7044,
        emissiveIntensity: 0.45,
        roughness: 0.42,
        metalness: 0.12
      });
      this.oldMaterial = new THREE.MeshStandardMaterial({
        color: 0x668ba5,
        emissive: 0x142536,
        emissiveIntensity: 0.25,
        roughness: 0.5
      });
      this.candidateMaterial = new THREE.LineBasicMaterial({ color: 0xf7c96b, transparent: true, opacity: 0.95 });
      this.rejectMaterial = new THREE.LineBasicMaterial({ color: 0xff746c, transparent: true, opacity: 0.95 });

      this.buildBrain();
      this.buildFly();
      window.addEventListener("resize", () => this.resize());
      this.resize();
      this.animate();
    }

    resize() {
      const rect = this.canvas.getBoundingClientRect();
      const width = Math.max(1, rect.width);
      const height = Math.max(1, rect.height);
      this.camera.aspect = width / height;
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(width, height, false);
    }

    coordToWorld(coord) {
      const [sx, sy] = state.project(coord);
      const x = (sx / MAP.width - 0.5) * 30;
      const z = (sy / MAP.height - 0.5) * 20;
      return new THREE.Vector3(x, 0.2, z);
    }

    clearGroup(group) {
      for (const child of group.children) {
        child.traverse?.((node) => {
          node.geometry?.dispose?.();
        });
      }
      group.clear();
    }

    makeTube(coords, material, radius = 0.11) {
      const points = coords.map((coord) => this.coordToWorld(coord));
      if (points.length < 2) return null;
      const curve = new THREE.CatmullRomCurve3(points, false, "catmullrom", 0.08);
      const geometry = new THREE.TubeGeometry(curve, Math.max(16, points.length * 18), radius, 10, false);
      return new THREE.Mesh(geometry, material);
    }

    makeLine(coords, material) {
      const points = coords.map((coord) => this.coordToWorld(coord).add(new THREE.Vector3(0, 0.18, 0)));
      const geometry = new THREE.BufferGeometry().setFromPoints(points);
      return new THREE.Line(geometry, material);
    }

    buildStatic(replay) {
      this.clearGroup(this.staticGroup);
      const plane = new THREE.Mesh(
        new THREE.PlaneGeometry(34, 23, 1, 1),
        new THREE.MeshStandardMaterial({ color: 0x171c20, roughness: 0.85, metalness: 0.08 })
      );
      plane.rotation.x = -Math.PI / 2;
      plane.position.y = -0.05;
      this.staticGroup.add(plane);

      replay.input.features.forEach((item) => {
        const type = objectType(item);
        if (type === "heat_network" && item.geometry?.type === "LineString") {
          const tube = this.makeTube(item.geometry.coordinates, this.oldMaterial, 0.085);
          if (tube) this.staticGroup.add(tube);
        }
        if (type === "restriction" && item.geometry?.type === "Polygon") {
          const ring = item.geometry.coordinates[0] || [];
          const shape = new THREE.Shape(ring.map((coord) => {
            const point = this.coordToWorld(coord);
            return new THREE.Vector2(point.x, point.z);
          }));
          const geometry = new THREE.ShapeGeometry(shape);
          const kind = item.properties?.restriction_type;
          const color = kind === "water" ? 0x2f718b : kind === "oks" ? 0x6d4054 : 0x5b513f;
          const mesh = new THREE.Mesh(geometry, new THREE.MeshStandardMaterial({
            color,
            transparent: true,
            opacity: kind === "oks" ? 0.34 : 0.48,
            roughness: 0.9,
            side: THREE.DoubleSide
          }));
          mesh.rotation.x = -Math.PI / 2;
          mesh.position.y = 0.015;
          this.staticGroup.add(mesh);
        }
        if (["oks_connection_point", "heat_source", "heat_chamber"].includes(type) && item.geometry?.type === "Point") {
          const point = this.coordToWorld(item.geometry.coordinates);
          const color = type === "oks_connection_point" ? 0xf46f9f : type === "heat_source" ? 0x72dfb1 : 0xd3e3ec;
          const marker = new THREE.Mesh(
            new THREE.SphereGeometry(type === "oks_connection_point" ? 0.2 : 0.26, 18, 12),
            new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.2 })
          );
          marker.position.copy(point).setY(type === "heat_source" ? 0.72 : 0.48);
          this.staticGroup.add(marker);
        }
      });
    }

    buildBrain() {
      this.brainGroup.position.set(-10.8, 5.6, -6.5);
      const core = new THREE.Mesh(
        new THREE.IcosahedronGeometry(1.05, 2),
        new THREE.MeshStandardMaterial({
          color: 0x72dfb1,
          emissive: 0x1f8f65,
          emissiveIntensity: 0.85,
          roughness: 0.35,
          transparent: true,
          opacity: 0.92
        })
      );
      this.brainGroup.add(core);
      this.neurons = [];
      for (let index = 0; index < 28; index += 1) {
        const theta = index * 2.399;
        const y = -0.8 + (index % 7) * 0.27;
        const r = 1.35 + (index % 5) * 0.09;
        const node = new THREE.Mesh(
          new THREE.SphereGeometry(0.085, 10, 8),
          new THREE.MeshStandardMaterial({
            color: 0x43505a,
            emissive: 0x12222b,
            emissiveIntensity: 0.18
          })
        );
        node.userData.neuronId = `FLY-N${String(index).padStart(2, "0")}`;
        node.position.set(Math.cos(theta) * r, y, Math.sin(theta) * r * 0.78);
        this.neurons.push(node);
        this.brainGroup.add(node);
      }
      const lineMaterial = new THREE.LineBasicMaterial({ color: 0x72dfb1, transparent: true, opacity: 0.28 });
      for (let index = 0; index < this.neurons.length - 2; index += 2) {
        const geometry = new THREE.BufferGeometry().setFromPoints([
          this.neurons[index].position,
          this.neurons[(index + 5) % this.neurons.length].position
        ]);
        this.brainGroup.add(new THREE.Line(geometry, lineMaterial));
      }
    }

    buildFly() {
      const bodyMaterial = new THREE.MeshStandardMaterial({ color: 0xf2f4ee, roughness: 0.38, metalness: 0.05 });
      const darkMaterial = new THREE.MeshStandardMaterial({ color: 0x111315, roughness: 0.5 });
      const wingMaterial = new THREE.MeshStandardMaterial({
        color: 0x99f0d0,
        emissive: 0x286e55,
        emissiveIntensity: 0.45,
        transparent: true,
        opacity: 0.42,
        side: THREE.DoubleSide
      });
      const body = new THREE.Mesh(new THREE.SphereGeometry(0.44, 22, 14), bodyMaterial);
      body.scale.set(1, 1.25, 1.55);
      this.flyGroup.add(body);
      const head = new THREE.Mesh(new THREE.SphereGeometry(0.3, 18, 12), bodyMaterial);
      head.position.set(0, 0.72, 0);
      this.flyGroup.add(head);
      [-0.17, 0.17].forEach((x) => {
        const eye = new THREE.Mesh(new THREE.SphereGeometry(0.075, 10, 8), darkMaterial);
        eye.position.set(x, 0.86, -0.23);
        this.flyGroup.add(eye);
      });
      [-1, 1].forEach((side) => {
        const wing = new THREE.Mesh(new THREE.CircleGeometry(0.48, 32), wingMaterial);
        wing.scale.set(1.55, 0.62, 1);
        wing.rotation.set(Math.PI / 2.2, 0.3 * side, 0.38 * side);
        wing.position.set(0.46 * side, 0.08, 0.1);
        this.flyGroup.add(wing);
      });
      this.flyGroup.scale.setScalar(1.15);
      this.flyGroup.position.copy(this.flyStart);
    }

    previousNetwork(stepIndex, replay) {
      if (stepIndex <= 0) return replay.initial;
      return replay.steps[stepIndex - 2]?.after || replay.initial;
    }

    setReplay(replay) {
      this.buildStatic(replay);
    }

    setActiveNeurons(neurons) {
      this.activeNeuronMap = new Map((neurons || []).map((item) => [item.id, Number(item.activation) || 0]));
    }

    showStep(replay, stepIndex) {
      this.clearGroup(this.routeGroup);
      this.clearGroup(this.candidateGroup);
      this.committedPreview = null;
      const prior = this.previousNetwork(stepIndex, replay);
      prior.features.forEach((item) => {
        if (item.geometry?.type === "LineString") {
          const tube = this.makeTube(item.geometry.coordinates, this.routeMaterial, 0.12);
          if (tube) this.routeGroup.add(tube);
        }
      });
      const step = stepIndex === 0 ? null : replay.steps[stepIndex - 1];
      const candidate = step?.candidate?.features?.find((item) => item.geometry?.type === "LineString");
      this.currentPath = candidate?.geometry?.coordinates || prior.features.at(-1)?.geometry?.coordinates || [[190, 370]];
      const worldPath = this.currentPath.map((coord) => this.coordToWorld(coord).add(new THREE.Vector3(0, 0.45, 0)));
      this.flyStart = worldPath[0] || this.flyGroup.position.clone();
      this.flyEnd = worldPath.at(-1) || this.flyStart;
      this.flyGroup.position.copy(this.flyStart);
      this.elapsed = 0;
      this.phase = step ? "scan" : "idle";
      this.stepStatus = step?.status || "committed";
      this.candidateLine = null;
      if (candidate) {
        this.candidateLine = this.makeLine(candidate.geometry.coordinates, step.status === "rejected" ? this.rejectMaterial : this.candidateMaterial);
        this.candidateGroup.add(this.candidateLine);
      }
    }

    phaseForTime(t) {
      if (!this.currentPath.length || state.stepIndex === 0) return "idle";
      if (t < 0.24) return "scan";
      if (t < 0.46) return "decide";
      if (t < 0.82) return "draw";
      return this.stepStatus === "rejected" ? "reject" : "commit";
    }

    pointAlongPath(progress) {
      const points = this.currentPath.map((coord) => this.coordToWorld(coord).add(new THREE.Vector3(0, 0.95, 0)));
      if (points.length < 2) return this.flyStart;
      const lengths = [];
      let total = 0;
      for (let index = 1; index < points.length; index += 1) {
        total += points[index - 1].distanceTo(points[index]);
        lengths.push(total);
      }
      const target = progress * total;
      for (let index = 0; index < lengths.length; index += 1) {
        const previous = index === 0 ? 0 : lengths[index - 1];
        if (target <= lengths[index]) {
          const local = (target - previous) / Math.max(0.0001, lengths[index] - previous);
          return points[index].clone().lerp(points[index + 1], local);
        }
      }
      return points.at(-1);
    }

    updateCandidateDraw(progress) {
      if (!this.candidateLine) return;
      const material = this.candidateLine.material;
      material.opacity = this.phase === "scan" ? 0.25 : this.phase === "decide" ? 0.65 : 1;
      this.candidateLine.scale.setScalar(this.phase === "reject" ? 1.015 : 1);
      if (this.phase === "commit" && this.stepStatus === "committed") {
        const tube = this.makeTube(this.currentPath, this.routeMaterial, 0.14);
        if (tube && !this.committedPreview) {
          this.committedPreview = tube;
          this.routeGroup.add(tube);
        }
      } else {
        this.committedPreview = null;
      }
    }

    animate() {
      requestAnimationFrame(() => this.animate());
      const delta = this.clock.getDelta();
      this.elapsed += delta;
      const normalized = Math.min(1, this.elapsed / 2.4);
      this.phase = this.phaseForTime(normalized);
      const drawProgress = Math.max(0, Math.min(1, (normalized - 0.42) / 0.42));
      const travelProgress = Math.max(0, Math.min(1, (normalized - 0.2) / 0.62));
      this.flyGroup.position.copy(this.pointAlongPath(travelProgress));
      this.flyGroup.rotation.y = Math.sin(this.elapsed * 4) * 0.12;
      this.flyGroup.rotation.z = Math.sin(this.elapsed * 10) * 0.05;
      this.brainGroup.rotation.y += delta * 0.42;
      this.brainGroup.rotation.x = Math.sin(this.elapsed * 0.9) * 0.12;
      this.neurons?.forEach((node, index) => {
        const activation = this.activeNeuronMap.get(node.userData.neuronId) || 0;
        const pulse = 1 + activation * 1.35 + Math.max(0, Math.sin(this.elapsed * 5 + index * 0.7)) * (this.phase === "decide" ? 0.35 : 0.12);
        node.scale.setScalar(pulse);
        const hot = activation > 0.62;
        node.material.color.setHex(hot ? 0xf7c96b : activation > 0.25 ? 0x72dfb1 : 0x43505a);
        node.material.emissive.setHex(hot ? 0xe68a24 : activation > 0.25 ? 0x1f8f65 : 0x12222b);
        node.material.emissiveIntensity = 0.15 + activation * 1.55;
      });
      this.updateCandidateDraw(drawProgress);
      syncPhasePanel();
      this.renderer.render(this.scene, this.camera);
    }
  }

  function feature(id, objectType, geometry, props = {}) {
    return { type: "Feature", properties: { id, object_type: objectType, ...props }, geometry };
  }

  function point(x, y) {
    return { type: "Point", coordinates: [x, y] };
  }

  function line(...points) {
    return { type: "LineString", coordinates: points };
  }

  function polygon(...points) {
    const ring = points.map((p) => [p[0], p[1]]);
    if (ring.length && (ring[0][0] !== ring.at(-1)[0] || ring[0][1] !== ring.at(-1)[1])) {
      ring.push([...ring[0]]);
    }
    return { type: "Polygon", coordinates: [ring] };
  }

  function fc(features) {
    return { type: "FeatureCollection", features };
  }

  function segment(id, coordinates, diameter, status = "accepted") {
    return feature(id, "heat_network", { type: "LineString", coordinates }, {
      network_role: "new",
      diameter,
      status
    });
  }

  function metrics(connected, length, cost, score, checkerStatus, actionMs) {
    return {
      connected_oks: connected,
      total_oks: 4,
      flow_tph: connected === 4 ? 37.4 : 28.2,
      new_network_length_m: length,
      cost_rub: cost,
      score,
      checker_status: checkerStatus,
      action_ms: actionMs,
      animation_ms: 1050
    };
  }

  const simulation = new BrainRouteSimulation(dom.canvas3d);

  function eventKey(stepIndex, phase) {
    return `${stepIndex}:${phase}`;
  }

  function indexEvents(events) {
    state.events = events;
    state.eventsByStepPhase = new Map();
    events
      .filter((event) => event.type === "brain_phase")
      .forEach((event) => {
        state.eventsByStepPhase.set(eventKey(event.step_index, event.phase), event);
      });
  }

  async function loadSidecarEvents() {
    try {
      const response = await fetch("demo/ui_demo.events.ndjson", { cache: "no-store" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const text = await response.text();
      const events = text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => JSON.parse(line));
      indexEvents(events);
      dom.activeNeurons.textContent = "Sidecar event stream loaded.";
      syncPhasePanel(true);
    } catch (error) {
      dom.activeNeurons.textContent = `No sidecar events loaded: ${error.message}`;
    }
  }

  function currentBrainEvent(step, phase) {
    if (!step) return null;
    return state.eventsByStepPhase.get(eventKey(state.stepIndex, phase)) || null;
  }

  function fallbackNeurons(step, phase) {
    if (!step) return [];
    const seed = state.stepIndex * 17 + phase.length * 11;
    return Array.from({ length: 12 }, (_, index) => {
      const neuronIndex = Math.abs(Math.round(Math.sin(seed + index * 1.73) * 1000)) % 28;
      const activation = Math.max(0.12, Math.min(1, Math.sin(seed + index * 0.91) * 0.5 + 0.5));
      return {
        id: `FLY-N${String(neuronIndex).padStart(2, "0")}`,
        role: "fallback_visualization",
        activation
      };
    });
  }

  function activeNeuronsFor(step, phase) {
    const event = currentBrainEvent(step, phase);
    return event?.active_neurons || fallbackNeurons(step, phase);
  }

  function renderBrainBars(step, phase) {
    dom.brainBars.replaceChildren();
    const neurons = activeNeuronsFor(step, phase);
    neurons.forEach((neuron, index) => {
      const value = Math.max(0.04, Math.min(1, Number(neuron.activation) || 0));
      const bar = document.createElement("i");
      bar.style.height = `${Math.round(18 + value * 58)}px`;
      bar.style.animationDelay = `${index * -38}ms`;
      if (step?.status === "rejected") bar.style.background = "linear-gradient(to top, var(--bad), #f7c96b)";
      dom.brainBars.appendChild(bar);
    });
    simulation.setActiveNeurons(neurons);
    dom.activeNeurons.textContent = neurons.length
      ? neurons.slice(0, 5).map((item) => `${item.id} ${Math.round((item.activation || 0) * 100)}%`).join(" · ")
      : "No active neurons for this phase.";
  }

  function phaseText(phase, step) {
    if (!step) return ["Idle", "Initial network loaded"];
    if (phase === "scan") return ["Scan", "The connectome reservoir samples candidate geometry"];
    if (phase === "decide") return ["Brain decision", "Readout selects the next heat-network operation"];
    if (phase === "draw") return ["Drawing", "Fly traces the candidate pipe through the map"];
    if (phase === "reject") return ["Checker rejected", "Candidate is shown but not committed"];
    return ["Checker accepted", "Candidate becomes part of the routed heat network"];
  }

  function svg(name, attrs = {}) {
    const el = document.createElementNS(SVG_NS, name);
    Object.entries(attrs).forEach(([key, value]) => el.setAttribute(key, String(value)));
    return el;
  }

  function objectType(featureItem) {
    return featureItem?.properties?.object_type || "unknown";
  }

  function visitCoords(geometry, callback) {
    if (!geometry) return;
    if (geometry.type === "Point") {
      callback(geometry.coordinates);
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

  function createProjection(replay) {
    const points = [];
    [...replay.input.features, ...replay.initial.features, ...replay.steps.flatMap((s) => s.after.features)]
      .forEach((item) => visitCoords(item.geometry, (pointItem) => points.push(pointItem)));
    const xs = points.map((p) => p[0]);
    const ys = points.map((p) => p[1]);
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const spanX = Math.max(1, maxX - minX);
    const spanY = Math.max(1, maxY - minY);
    const scale = Math.min((MAP.width - MAP.padding * 2) / spanX, (MAP.height - MAP.padding * 2) / spanY);
    const usedW = spanX * scale;
    const usedH = spanY * scale;
    const offsetX = (MAP.width - usedW) / 2;
    const offsetY = (MAP.height - usedH) / 2;
    return ([x, y]) => [offsetX + (x - minX) * scale, MAP.height - offsetY - (y - minY) * scale];
  }

  function pathData(geometry) {
    if (!geometry) return "";
    if (geometry.type === "LineString") {
      return geometry.coordinates.map((pointItem, index) => {
        const [x, y] = state.project(pointItem);
        return `${index ? "L" : "M"}${x.toFixed(2)},${y.toFixed(2)}`;
      }).join(" ");
    }
    if (geometry.type === "Polygon") {
      return geometry.coordinates.map((ring) => ring.map((pointItem, index) => {
        const [x, y] = state.project(pointItem);
        return `${index ? "L" : "M"}${x.toFixed(2)},${y.toFixed(2)}`;
      }).join(" ") + " Z").join(" ");
    }
    return "";
  }

  function drawGrid() {
    dom.grid.replaceChildren();
    for (let x = 0; x <= MAP.width; x += 80) {
      dom.grid.appendChild(svg("line", { x1: x, y1: 0, x2: x, y2: MAP.height, class: "grid-line" }));
    }
    for (let y = 0; y <= MAP.height; y += 80) {
      dom.grid.appendChild(svg("line", { x1: 0, y1: y, x2: MAP.width, y2: y, class: "grid-line" }));
    }
  }

  function drawFeature(parent, item, origin) {
    const type = objectType(item);
    if (item.geometry?.type === "Point") {
      const [cx, cy] = state.project(item.geometry.coordinates);
      const className = type === "oks_connection_point" ? "point oks"
        : type === "heat_source" ? "point source"
          : "point chamber";
      const radius = type === "heat_source" ? 8 : type === "oks_connection_point" ? 6 : 5;
      parent.appendChild(svg("circle", { cx, cy, r: radius, class: className }));
      return;
    }
    const d = pathData(item.geometry);
    if (!d) return;
    if (type === "restriction") {
      parent.appendChild(svg("path", {
        d,
        class: "restriction",
        "data-kind": item.properties?.restriction_type || "other"
      }));
      return;
    }
    if (type === "heat_network") {
      const status = item.properties?.status;
      const className = origin === "candidate" ? `candidate drawn ${status === "rejected" ? "rejected" : ""}`
        : item.properties?.network_role === "existing" ? "old-network" : "new-network";
      parent.appendChild(svg("path", { d, class: className, pathLength: 1 }));
    }
  }

  function drawDecisionOverlay(step) {
    if (!step) return;
    const candidate = step.candidate?.features?.find((item) => item.geometry?.type === "LineString");
    const coords = candidate?.geometry?.coordinates;
    if (!coords?.length) return;
    const [brainX, brainY] = [145, 150];
    const [targetX, targetY] = state.project(coords[coords.length - 1]);
    dom.decisionLayer.appendChild(svg("path", {
      d: `M${brainX},${brainY} L${targetX.toFixed(2)},${targetY.toFixed(2)}`,
      class: "decision-beam"
    }));
    dom.decisionLayer.appendChild(svg("circle", {
      cx: targetX.toFixed(2),
      cy: targetY.toFixed(2),
      r: 10,
      class: "target-ring"
    }));
  }

  function currentStep() {
    return state.stepIndex === 0 ? null : state.replay.steps[state.stepIndex - 1];
  }

  function currentNetwork() {
    const step = currentStep();
    return step ? step.after : state.replay.initial;
  }

  function currentMetrics() {
    const step = currentStep();
    return step ? step.metrics_after : state.replay.initial_metrics;
  }

  function candidateEndPoint() {
    const step = currentStep();
    const features = step?.candidate?.features || currentNetwork().features;
    const lastLine = [...features].reverse().find((item) => item.geometry?.type === "LineString");
    const coords = lastLine?.geometry?.coordinates || [[190, 370]];
    return coords[coords.length - 1];
  }

  function drawFly() {
    dom.flyLayer.replaceChildren();
    const [x, y] = state.project(candidateEndPoint());
    const group = svg("g", { class: "fly", transform: `translate(${x.toFixed(2)} ${y.toFixed(2)})` });
    group.appendChild(svg("circle", { class: "fly-pulse", cx: 0, cy: 0, r: 8 }));
    group.appendChild(svg("ellipse", { class: "fly-wing", cx: -8, cy: -5, rx: 9, ry: 5, transform: "rotate(-24 -8 -5)" }));
    group.appendChild(svg("ellipse", { class: "fly-wing", cx: 8, cy: -5, rx: 9, ry: 5, transform: "rotate(24 8 -5)" }));
    group.appendChild(svg("ellipse", { class: "fly-body", cx: 0, cy: 0, rx: 8, ry: 10 }));
    group.appendChild(svg("circle", { class: "fly-body", cx: 0, cy: -10, r: 5 }));
    dom.flyLayer.appendChild(group);
  }

  function formatNumber(value, digits = 2) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "-";
    return new Intl.NumberFormat("ru-RU", { maximumFractionDigits: digits }).format(number);
  }

  function updatePanel() {
    const step = currentStep();
    const m = currentMetrics() || {};
    const phase = simulation.phase || "idle";
    const [phaseLabel, phaseTitle] = phaseText(phase, step);
    dom.mode.textContent = state.replay.mode;
    dom.note.textContent = state.replay.mode === "FLY_HYBRID"
      ? "Hybrid replay. Trust only checked solver snapshots and manifest versions."
      : state.replay.mode === "B2_REPLAY"
        ? "Solver replay. The fly only visualizes logged operations."
        : "Demonstration interface. Not a trained model.";
    dom.title.textContent = step ? `${step.operation} · ${step.status}` : "Initial state";
    dom.phaseLabel.textContent = phaseLabel;
    dom.phaseTitle.textContent = phaseTitle;
    dom.message.textContent = step?.message || "Initial replay snapshot.";
    dom.provenance.textContent = state.replay.provenance || "-";
    dom.connected.textContent = `${m.connected_oks ?? "-"} / ${m.total_oks ?? "-"}`;
    dom.length.textContent = `${formatNumber(m.new_network_length_m, 1)} m`;
    dom.cost.textContent = `${formatNumber((m.cost_rub || 0) / 1_000_000, 1)}M RUB`;
    dom.score.textContent = formatNumber(m.score, 2);
    dom.checker.textContent = m.checker_status || "-";
    dom.action.textContent = `${formatNumber(m.action_ms, 0)} ms`;
    dom.label.textContent = state.stepIndex === 0 ? "Initial" : `${state.stepIndex}/${state.replay.steps.length}`;
    dom.range.value = String(state.stepIndex);
    renderBrainBars(step, phase);
  }

  function syncPhasePanel() {
    const step = currentStep();
    const phase = simulation.phase || "idle";
    const [phaseLabel, phaseTitle] = phaseText(phase, step);
    if (dom.phaseLabel.textContent !== phaseLabel) {
      dom.phaseLabel.textContent = phaseLabel;
      dom.phaseTitle.textContent = phaseTitle;
      renderBrainBars(step, phase);
    }
  }

  function render() {
    state.project = createProjection(state.replay);
    simulation.setReplay(state.replay);
    drawGrid();
    dom.inputLayer.replaceChildren();
    dom.networkLayer.replaceChildren();
    dom.candidateLayer.replaceChildren();
    dom.decisionLayer.replaceChildren();
    state.replay.input.features
      .slice()
      .sort((a, b) => (objectType(a) === "restriction" ? 0 : 1) - (objectType(b) === "restriction" ? 0 : 1))
      .forEach((item) => drawFeature(dom.inputLayer, item, "input"));
    currentNetwork().features.forEach((item) => drawFeature(dom.networkLayer, item, "network"));
    const step = currentStep();
    (step?.candidate?.features || []).forEach((item) => drawFeature(dom.candidateLayer, item, "candidate"));
    drawDecisionOverlay(step);
    drawFly();
    updatePanel();
    simulation.showStep(state.replay, state.stepIndex);
  }

  function validateReplay(replay) {
    if (!replay || replay.schema_version !== "fly-replay-v1") {
      throw new Error("Expected schema_version=fly-replay-v1");
    }
    if (!["UI_DEMO", "B2_REPLAY", "FLY_HYBRID"].includes(replay.mode)) {
      throw new Error("Unknown replay mode");
    }
    if (replay.input?.type !== "FeatureCollection" || replay.initial?.type !== "FeatureCollection") {
      throw new Error("Replay must contain input and initial FeatureCollections");
    }
    if (!Array.isArray(replay.steps)) {
      throw new Error("Replay steps must be an array");
    }
  }

  function setReplay(replay) {
    validateReplay(replay);
    stop();
    state.replay = replay;
    state.stepIndex = 0;
    dom.range.max = String(replay.steps.length);
    render();
  }

  function setStep(next) {
    state.stepIndex = Math.max(0, Math.min(state.replay.steps.length, next));
    render();
  }

  function stop() {
    state.playing = false;
    dom.play.textContent = "Play";
    window.clearInterval(state.timer);
  }

  function play() {
    if (state.playing) {
      stop();
      return;
    }
    state.playing = true;
    dom.play.textContent = "Pause";
    state.timer = window.setInterval(() => {
      if (state.stepIndex >= state.replay.steps.length) {
        stop();
        return;
      }
      setStep(state.stepIndex + 1);
    }, 2750);
  }

  dom.play.addEventListener("click", play);
  dom.prev.addEventListener("click", () => setStep(state.stepIndex - 1));
  dom.next.addEventListener("click", () => setStep(state.stepIndex + 1));
  dom.range.addEventListener("input", () => setStep(Number(dom.range.value)));
  dom.file.addEventListener("change", async () => {
    const file = dom.file.files?.[0];
    if (!file) return;
    const replay = JSON.parse(await file.text());
    setReplay(replay);
  });

  setReplay(demoReplay);
  loadSidecarEvents();
})();
