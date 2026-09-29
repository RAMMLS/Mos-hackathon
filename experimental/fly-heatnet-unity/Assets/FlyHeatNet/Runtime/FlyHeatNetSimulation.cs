using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.UI;

namespace FlyHeatNet
{
    public sealed class FlyHeatNetSimulation : MonoBehaviour
    {
        private enum SimulationPhase { Thinking, Drawing, Complete }

        private static readonly Color Background = new Color(0.018f, 0.025f, 0.03f);
        private static readonly Color Cyan = new Color(0.04f, 0.92f, 0.85f);
        private static readonly Color Red = new Color(0.95f, 0.08f, 0.18f);
        private static readonly Color PencilYellow = new Color(1f, 0.82f, 0.03f);
        private static readonly Color Graphite = new Color(0.035f, 0.045f, 0.05f);

        private readonly FlyWireConnectomeBrain brain = new FlyWireConnectomeBrain();
        private readonly List<Vector3> drawnPoints = new List<Vector3>(512);
        private readonly List<Vector3> generatedRoute = new List<Vector3>(32);
        private readonly List<float[]> routeActivations = new List<float[]>(32);
        private readonly List<float> routeTurns = new List<float>(32);
        private readonly List<JointPose> legJoints = new List<JointPose>(24);
        private readonly List<Renderer> neuronRenderers = new List<Renderer>(FlyWireConnectomeBrain.NeuronCount);
        private readonly List<Material> neuronMaterials = new List<Material>(FlyWireConnectomeBrain.NeuronCount);
        private readonly float[] activations = new float[FlyWireConnectomeBrain.NeuronCount];

        private readonly Vector3[] scenarioSources =
        {
            new Vector3(-7.5f, 0.035f, -5.5f),
            new Vector3(-8.4f, 0.035f, 6.2f),
            new Vector3(4f, 0.035f, -8.1f),
            new Vector3(-9.3f, 0.035f, 0.4f)
        };
        private readonly Vector3[] scenarioConsumers =
        {
            new Vector3(7.6f, 0.035f, 3.2f),
            new Vector3(8.5f, 0.035f, 0.8f),
            new Vector3(4f, 0.035f, 7.8f),
            new Vector3(9.1f, 0.035f, 1.2f)
        };
        private readonly string[] scenarioNames = { "ЮГО-ЗАПАД → СЕВЕРО-ВОСТОК", "СЕВЕРО-ЗАПАД → ЮГО-ВОСТОК", "ЮГ → СЕВЕР", "ЗАПАД → ВОСТОК" };
        private Vector3 heatSource = new Vector3(-7.5f, 0.035f, -5.5f);
        private Vector3 consumer = new Vector3(7.6f, 0.035f, 3.2f);
        private readonly Rect[] blockedAreas =
        {
            new Rect(-9.1f, 3.05f, 3.2f, 2.5f),
            new Rect(-1.7f, -5.15f, 4.4f, 2.7f),
            new Rect(5.65f, -3.9f, 3.1f, 3.4f),
            new Rect(6.4f, 4.65f, 2.8f, 2.3f)
        };

        private Transform fly;
        private Transform detailedModel;
        private Transform drawingClaw;
        private Transform leftWing;
        private Transform rightWing;
        private Transform pencil;
        private Transform brainRig;
        private Transform heatSourceMarker;
        private Transform consumerMarker;
        private Camera followCamera;
        private LineRenderer planOuter;
        private LineRenderer planInner;
        private Text statusText;
        private Text metricsText;
        private Text neuronText;
        private SimulationPhase phase;
        private Vector3 segmentStart;
        private Vector3 segmentEnd;
        private Vector3 pencilTip;
        private float phaseClock;
        private float simulationClock;
        private int segmentIndex;
        private int scenarioIndex = -1;
        private float currentTurn;
        private float dopamineSignal;
        private float routeLengthMeters;
        private float estimatedCostRub;
        private float routeScore;
        private float initialRouteScore;
        private bool paused;
        private bool autoTest;
        private string capturePath;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        private static void Bootstrap()
        {
            if (FindObjectOfType<FlyHeatNetSimulation>() == null)
            {
                new GameObject("Fly HeatNet Floor Simulation").AddComponent<FlyHeatNetSimulation>();
            }
        }

        private void Awake()
        {
            QualitySettings.vSyncCount = 1;
            Application.targetFrameRate = 60;
            ParseCommandLine();
            ConfigureScene();
            CreateDraftingFloor();
            CreateDetailedFly();
            CreatePencil();
            CreatePlanRenderer();
            CreateBrainRig();
            CreateHud();
            ResetSimulation(true);

            if (autoTest)
            {
                StartCoroutine(CaptureProof());
            }
        }

        private void Update()
        {
            if (Input.GetKeyDown(KeyCode.Space)) paused = !paused;
            if (Input.GetKeyDown(KeyCode.R)) ResetSimulation(false);
            if (paused)
            {
                statusText.text = "ПАУЗА / SPACE - ПРОДОЛЖИТЬ";
                return;
            }

            var delta = Time.deltaTime;
            simulationClock += delta;
            phaseClock += delta;
            switch (phase)
            {
                case SimulationPhase.Thinking: UpdateThinking(); break;
                case SimulationPhase.Drawing: UpdateDrawing(); break;
                case SimulationPhase.Complete: UpdateComplete(); break;
            }

            AnimateFly();
            AnimateBrain();
            UpdatePencil();
            UpdateCamera(delta);
            UpdateHud();
        }

        private void ConfigureScene()
        {
            RenderSettings.ambientMode = AmbientMode.Flat;
            RenderSettings.ambientLight = new Color(0.22f, 0.24f, 0.25f);
            RenderSettings.fog = true;
            RenderSettings.fogMode = FogMode.ExponentialSquared;
            RenderSettings.fogColor = Background;
            RenderSettings.fogDensity = 0.0075f;

            followCamera = Camera.main;
            if (followCamera == null)
            {
                followCamera = new GameObject("Main Camera").AddComponent<Camera>();
                followCamera.tag = "MainCamera";
            }
            followCamera.clearFlags = CameraClearFlags.SolidColor;
            followCamera.backgroundColor = Background;
            followCamera.fieldOfView = 47f;
            followCamera.nearClipPlane = 0.05f;
            followCamera.farClipPlane = 160f;
            followCamera.transform.position = new Vector3(3f, 8f, -13f);
            followCamera.transform.LookAt(Vector3.zero);

            foreach (var light in FindObjectsOfType<Light>()) Destroy(light.gameObject);
            var key = new GameObject("Softbox key").AddComponent<Light>();
            key.type = LightType.Directional;
            key.color = new Color(0.82f, 0.93f, 1f);
            key.intensity = 1.18f;
            key.shadows = LightShadows.Soft;
            key.shadowStrength = 0.65f;
            key.transform.rotation = Quaternion.Euler(52f, -38f, 0f);
            var warm = new GameObject("Warm floor rim").AddComponent<Light>();
            warm.type = LightType.Directional;
            warm.color = new Color(1f, 0.38f, 0.2f);
            warm.intensity = 0.38f;
            warm.transform.rotation = Quaternion.Euler(32f, 142f, 0f);
        }

        private void CreateDraftingFloor()
        {
            CreatePrimitive("Drafting floor", PrimitiveType.Cube, new Vector3(0f, -0.18f, 0f), new Vector3(26f, 0.34f, 21f), MakeMaterial("Graphite floor", new Color(0.09f, 0.105f, 0.11f), 0.12f));
            var grid = MakeMaterial("Draft grid", new Color(0.12f, 0.22f, 0.23f), 0f, true, new Color(0.025f, 0.14f, 0.14f));
            for (var x = -12; x <= 12; x++) CreateLine("Grid vertical", new[] { new Vector3(x, 0.006f, -10f), new Vector3(x, 0.006f, 10f) }, 0.012f, grid);
            for (var z = -10; z <= 10; z++) CreateLine("Grid horizontal", new[] { new Vector3(-12f, 0.006f, z), new Vector3(12f, 0.006f, z) }, 0.012f, grid);

            var boundary = MakeMaterial("District outlines", new Color(0.32f, 0.37f, 0.38f), 0f, true, new Color(0.08f, 0.09f, 0.09f));
            CreateFloorRectangle(new Vector3(-7.5f, 0f, 4.3f), new Vector2(3.2f, 2.5f), boundary);
            CreateFloorRectangle(new Vector3(0.5f, 0f, -3.8f), new Vector2(4.4f, 2.7f), boundary);
            CreateFloorRectangle(new Vector3(7.2f, 0f, -2.2f), new Vector2(3.1f, 3.4f), boundary);
            CreateFloorRectangle(new Vector3(7.8f, 0f, 5.8f), new Vector2(2.8f, 2.3f), boundary);
            heatSourceMarker = CreatePlanNode("Heat source", heatSource, Red, 0.46f);
            consumerMarker = CreatePlanNode("Consumer", consumer, Cyan, 0.42f);
        }

        private void CreateDetailedFly()
        {
            var prefab = Resources.Load<GameObject>("DetailedDrosophila");
            if (prefab == null) throw new InvalidOperationException("DetailedDrosophila.prefab is missing. Run Fly HeatNet/Rebuild Detailed Fly Prefab.");
            fly = new GameObject("Drosophila drawing rig").transform;
            detailedModel = Instantiate(prefab, fly).transform;
            detailedModel.name = "Scientific Drosophila model";
            detailedModel.localPosition = Vector3.zero;
            detailedModel.localRotation = Quaternion.Euler(0f, 90f, 0f);
            detailedModel.localScale = Vector3.one * 8.4f;
            var bounds = CalculateBounds(detailedModel);
            detailedModel.position += Vector3.up * (0.12f - bounds.min.y);

            drawingClaw = FindDescendant(detailedModel, "claw_T1_left");
            if (drawingClaw == null) throw new InvalidOperationException("The detailed fly model has no claw_T1_left joint.");
            leftWing = FindDescendant(detailedModel, "wing_left");
            rightWing = FindDescendant(detailedModel, "wing_right");
            CaptureLegJoints();
        }

        private void CreatePencil()
        {
            pencil = new GameObject("Pencil held by front claw").transform;
            var lacquer = MakeMaterial("Pencil lacquer", PencilYellow, 0.18f, true, PencilYellow * 0.42f);
            var wood = MakeMaterial("Sharpened wood", new Color(0.78f, 0.56f, 0.31f), 0f);
            var lead = MakeMaterial("Pencil graphite", Graphite, 0.1f);
            var ferrule = MakeMaterial("Pencil ferrule", new Color(0.55f, 0.6f, 0.62f), 0.75f);
            var eraser = MakeMaterial("Pencil eraser", new Color(0.82f, 0.21f, 0.3f), 0.05f);
            CreateChildPrimitive("Yellow pencil body", PrimitiveType.Cylinder, pencil, new Vector3(0f, -0.47f, 0f), new Vector3(0.145f, 0.47f, 0.145f), lacquer);
            CreateCone("Sharpened wood", pencil, new Vector3(0f, -1.01f, 0f), 0.19f, 0.2f, wood);
            CreateCone("Graphite tip", pencil, new Vector3(0f, -1.15f, 0f), 0.075f, 0.12f, lead);
            CreateChildPrimitive("Metal ferrule", PrimitiveType.Cylinder, pencil, new Vector3(0f, 0.035f, 0f), new Vector3(0.154f, 0.08f, 0.154f), ferrule);
            CreateChildPrimitive("Eraser", PrimitiveType.Cylinder, pencil, new Vector3(0f, 0.15f, 0f), new Vector3(0.148f, 0.055f, 0.148f), eraser);
            CreateChildPrimitive("Claw grip", PrimitiveType.Cylinder, pencil, new Vector3(0f, -0.10f, 0f), new Vector3(0.158f, 0.035f, 0.158f), lead);
        }

        private void CreatePlanRenderer()
        {
            var root = new GameObject("Plan drawn by pencil");
            planOuter = root.AddComponent<LineRenderer>();
            ConfigureFloorLine(planOuter, 0.24f, MakeMaterial("Plan graphite edge", new Color(0.14f, 0.17f, 0.18f), 0.2f));
            planInner = new GameObject("Plan cyan core").AddComponent<LineRenderer>();
            planInner.transform.SetParent(root.transform, false);
            ConfigureFloorLine(planInner, 0.105f, MakeMaterial("Plan pencil stroke", Cyan * 0.74f, 0.05f, true, Cyan * 0.65f));
        }

        private void CreateBrainRig()
        {
            brainRig = new GameObject("Connectome activity").transform;
            brainRig.SetParent(followCamera.transform, false);
            brainRig.localPosition = new Vector3(-5.25f, 2.45f, 10.5f);
            brainRig.localRotation = Quaternion.Euler(5f, -8f, 0f);
            brainRig.localScale = Vector3.one * 0.48f;
            var shell = CreateChildPrimitive("Brain shell", PrimitiveType.Sphere, brainRig, Vector3.zero, new Vector3(4.8f, 3.1f, 1.2f), MakeTransparentMaterial("Brain glass", new Color(0.05f, 0.34f, 0.34f, 0.12f)));
            shell.GetComponent<Renderer>().shadowCastingMode = ShadowCastingMode.Off;

            var random = new System.Random(71);
            var positions = new Vector3[FlyWireConnectomeBrain.NeuronCount];
            for (var i = 0; i < positions.Length; i++)
            {
                var angle = (float)(i * Math.PI * 2.0 / positions.Length + (random.NextDouble() - 0.5) * 0.5);
                var radius = 1.15f + (float)random.NextDouble() * 0.9f;
                positions[i] = new Vector3(Mathf.Cos(angle) * radius, Mathf.Sin(angle * 1.7f) * 0.75f + ((i % 5) - 2) * 0.12f, ((i % 4) - 1.5f) * 0.2f);
                var material = MakeMaterial($"Neuron {i:00}", new Color(0.03f, 0.28f, 0.28f), 0.05f, true, new Color(0.02f, 0.18f, 0.18f));
                var neuron = CreateChildPrimitive($"FlyWire-{brain.GetDisplayNeuronId(i)}", PrimitiveType.Sphere, brainRig, positions[i], Vector3.one * 0.16f, material);
                neuron.GetComponent<Renderer>().shadowCastingMode = ShadowCastingMode.Off;
                neuronRenderers.Add(neuron.GetComponent<Renderer>());
                neuronMaterials.Add(material);
            }
            var synapse = MakeMaterial("Synapses", new Color(0.02f, 0.25f, 0.26f), 0f, true, new Color(0.015f, 0.13f, 0.14f));
            foreach (var edge in brain.DisplayConnections)
            {
                var width = Mathf.Clamp(0.012f + Mathf.Log10(Mathf.Abs(edge.Synapses) + 1f) * 0.008f, 0.012f, 0.035f);
                CreateLocalLine(brainRig, $"Real synapse {edge.Source}-{edge.Target}", new[] { positions[edge.Source], positions[edge.Target] }, width, synapse);
            }
        }

        private void CreateHud()
        {
            var canvasObject = new GameObject("Simulation HUD");
            var canvas = canvasObject.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            var scaler = canvasObject.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1280f, 720f);
            scaler.matchWidthOrHeight = 0.5f;
            canvasObject.AddComponent<GraphicRaycaster>();
            var font = Resources.GetBuiltinResource<Font>("Arial.ttf");
            CreateText(canvas.transform, "Brand", "FLY HEATNET / МОЗГ ПРОЕКТИРУЕТ", font, 18, TextAnchor.UpperLeft, new Vector2(24f, -22f), new Vector2(470f, 44f), Color.white);
            CreateText(canvas.transform, "Hint", "SPACE  пауза     R  текущий пример", font, 13, TextAnchor.UpperLeft, new Vector2(24f, -674f), new Vector2(360f, 30f), new Color(0.62f, 0.78f, 0.8f));
            statusText = CreateText(canvas.transform, "Decision status", "", font, 21, TextAnchor.UpperCenter, new Vector2(0f, -22f), new Vector2(640f, 52f), Color.white);
            statusText.rectTransform.anchorMin = statusText.rectTransform.anchorMax = new Vector2(0.5f, 1f);
            statusText.rectTransform.pivot = new Vector2(0.5f, 1f);
            metricsText = CreateText(canvas.transform, "Route metrics", "", font, 14, TextAnchor.UpperRight, new Vector2(-24f, -24f), new Vector2(430f, 190f), new Color(0.82f, 0.94f, 0.95f));
            metricsText.rectTransform.anchorMin = metricsText.rectTransform.anchorMax = Vector2.one;
            metricsText.rectTransform.pivot = Vector2.one;
            neuronText = CreateText(canvas.transform, "Neuron status", "", font, 13, TextAnchor.UpperLeft, new Vector2(24f, -88f), new Vector2(390f, 92f), new Color(0.45f, 1f, 0.92f));
        }

        private void ResetSimulation(bool advanceExample)
        {
            if (advanceExample)
            {
                scenarioIndex = (scenarioIndex + 1) % scenarioSources.Length;
                heatSource = scenarioSources[scenarioIndex];
                consumer = scenarioConsumers[scenarioIndex];
            }
            heatSourceMarker.position = new Vector3(heatSource.x, 0.035f, heatSource.z);
            consumerMarker.position = new Vector3(consumer.x, 0.035f, consumer.z);
            segmentIndex = 0;
            phaseClock = 0f;
            drawnPoints.Clear();
            planOuter.positionCount = planInner.positionCount = 0;
            BuildAutonomousRoute();
            AddDrawnPoint(generatedRoute[0]);
            BeginThinking();
            Debug.Log($"FLY_HEATNET_EXAMPLE index={scenarioIndex + 1} name={scenarioNames[scenarioIndex]} samples={generatedRoute.Count} length_m={routeLengthMeters:0.0} score={routeScore:0.000000}");
        }

        private void BuildAutonomousRoute()
        {
            generatedRoute.Clear();
            routeActivations.Clear();
            routeTurns.Clear();
            dopamineSignal = 0f;
            var bestScore = float.PositiveInfinity;
            var feedback = 0f;
            var bestPoints = new List<Vector3>();
            var bestActivity = new List<float[]>();
            var bestTurns = new List<float>();

            for (var episode = 0; episode < 8; episode++)
            {
                GenerateControlTrajectory(episode, feedback, out var controls, out var controlActivity, out var controlTurns);
                var smoothPoints = new List<Vector3>(controls.Count * 4);
                var smoothActivity = new List<float[]>(controls.Count * 4);
                var smoothTurns = new List<float>(controls.Count * 4);
                SmoothTrajectory(controls, controlActivity, controlTurns, smoothPoints, smoothActivity, smoothTurns);
                var length = CalculateLength(smoothPoints) * 100f;
                var cost = length * 89748f;
                var score = HackathonRewardModel.CalculateScore(cost, length);
                for (var i = 1; i < smoothPoints.Count - 1; i++) if (IsBlocked(smoothPoints[i])) score += 1000f;
                if (episode == 0) initialRouteScore = score;

                if (score < bestScore)
                {
                    var reward = float.IsPositiveInfinity(bestScore) ? 0f : HackathonRewardModel.CalculateBestSoFarReward(bestScore, score, initialRouteScore);
                    dopamineSignal += Mathf.Max(0f, reward);
                    feedback = Mathf.Clamp01(dopamineSignal * 2500f);
                    bestScore = score;
                    bestPoints = smoothPoints;
                    bestActivity = smoothActivity;
                    bestTurns = smoothTurns;
                    routeLengthMeters = length;
                    estimatedCostRub = cost;
                }
                else
                {
                    feedback *= 0.88f;
                }
            }

            routeScore = bestScore;
            generatedRoute.AddRange(bestPoints);
            routeActivations.AddRange(bestActivity);
            routeTurns.AddRange(bestTurns);
        }

        private void GenerateControlTrajectory(int episode, float dopamine, out List<Vector3> controls, out List<float[]> activity, out List<float> turns)
        {
            controls = new List<Vector3> { heatSource };
            activity = new List<float[]>();
            turns = new List<float>();
            var current = heatSource;
            var heading = (consumer - heatSource).normalized;
            for (var decision = 0; decision < 24 && Vector3.Distance(current, consumer) > 1.65f; decision++)
            {
                var targetDirection = (consumer - current).normalized;
                var targetBearing = Mathf.Clamp(Vector3.SignedAngle(heading, targetDirection, Vector3.up) / 90f, -1f, 1f);
                var left = SenseClearance(current, Quaternion.AngleAxis(-42f, Vector3.up) * heading);
                var forward = SenseClearance(current, heading);
                var right = SenseClearance(current, Quaternion.AngleAxis(42f, Vector3.up) * heading);
                var turn = brain.DecideSteering(targetBearing, left, forward, right, dopamine, decision + episode * 31, out var neuralActivity);
                heading = Quaternion.AngleAxis(turn * 48f, Vector3.up) * heading;
                var next = current + heading.normalized * Mathf.Min(1.65f, Vector3.Distance(current, consumer));
                next.y = 0.035f;
                if (IsBlocked(next))
                {
                    heading = Quaternion.AngleAxis(right >= left ? 62f : -62f, Vector3.up) * heading;
                    next = current + heading.normalized * 1.35f;
                    next.y = 0.035f;
                }
                controls.Add(next);
                activity.Add(neuralActivity);
                turns.Add(turn);
                current = next;
            }
            controls.Add(consumer);
            activity.Add(activity.Count > 0 ? activity[activity.Count - 1] : new float[FlyWireConnectomeBrain.NeuronCount]);
            turns.Add(0f);
        }

        private static void SmoothTrajectory(List<Vector3> controls, List<float[]> controlActivity, List<float> controlTurns, List<Vector3> points, List<float[]> activity, List<float> turns)
        {
            const int samplesPerSpan = 4;
            points.Add(controls[0]);
            for (var span = 0; span < controls.Count - 1; span++)
            {
                var p0 = controls[Mathf.Max(0, span - 1)];
                var p1 = controls[span];
                var p2 = controls[span + 1];
                var p3 = controls[Mathf.Min(controls.Count - 1, span + 2)];
                for (var sample = 1; sample <= samplesPerSpan; sample++)
                {
                    var t = sample / (float)samplesPerSpan;
                    var t2 = t * t;
                    var t3 = t2 * t;
                    var point = 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
                    point.y = 0.035f;
                    points.Add(point);
                    activity.Add(controlActivity[Mathf.Min(span, controlActivity.Count - 1)]);
                    turns.Add(controlTurns[Mathf.Min(span, controlTurns.Count - 1)]);
                }
            }
        }

        private static float CalculateLength(List<Vector3> points)
        {
            var length = 0f;
            for (var i = 1; i < points.Count; i++) length += Vector3.Distance(points[i - 1], points[i]);
            return length;
        }

        private void BeginThinking()
        {
            phase = SimulationPhase.Thinking;
            phaseClock = 0f;
            segmentStart = generatedRoute[segmentIndex];
            segmentEnd = generatedRoute[segmentIndex + 1];
            currentTurn = routeTurns[Mathf.Min(segmentIndex, routeTurns.Count - 1)];
            PlaceFlyForPoint(segmentStart, (segmentEnd - segmentStart).normalized);
            pencilTip = segmentStart;
        }

        private void UpdateThinking()
        {
            CopyAutonomousActivity(Mathf.Repeat(phaseClock * 1.4f, 1f));
            if (phaseClock >= 0.12f)
            {
                phase = SimulationPhase.Drawing;
                phaseClock = 0f;
            }
        }

        private void UpdateDrawing()
        {
            var distance = Vector3.Distance(segmentStart, segmentEnd);
            var duration = Mathf.Max(0.1f, distance / 1.35f);
            var t = Mathf.Clamp01(phaseClock / duration);
            var eased = t * t * (3f - 2f * t);
            pencilTip = Vector3.Lerp(segmentStart, segmentEnd, eased);
            pencilTip.y = 0.035f;
            var direction = (segmentEnd - segmentStart).normalized;
            PlaceFlyForPoint(pencilTip, direction);
            if (drawnPoints.Count == 0 || Vector3.Distance(drawnPoints[drawnPoints.Count - 1], pencilTip) > 0.06f) AddDrawnPoint(pencilTip);
            CopyAutonomousActivity(Mathf.Repeat(t * 3f, 1f));
            if (t >= 1f)
            {
                segmentIndex++;
                if (segmentIndex >= generatedRoute.Count - 1)
                {
                    phase = SimulationPhase.Complete;
                    phaseClock = 0f;
                }
                else BeginThinking();
            }
        }

        private void CopyAutonomousActivity(float pulsePhase)
        {
            var snapshot = routeActivations[Mathf.Min(segmentIndex, routeActivations.Count - 1)];
            var pulse = 0.78f + Mathf.Sin(pulsePhase * Mathf.PI * 2f) * 0.22f;
            for (var i = 0; i < activations.Length; i++) activations[i] = Mathf.Clamp01(snapshot[i] * pulse + 0.035f);
        }

        private float SenseClearance(Vector3 origin, Vector3 direction)
        {
            const float maximum = 4.5f;
            for (var distance = 0.45f; distance <= maximum; distance += 0.45f)
            {
                if (IsBlocked(origin + direction.normalized * distance)) return distance / maximum;
            }
            return 1f;
        }

        private bool IsBlocked(Vector3 point)
        {
            if (point.x < -11.5f || point.x > 11.5f || point.z < -9.5f || point.z > 9.5f) return true;
            var planPoint = new Vector2(point.x, point.z);
            for (var i = 0; i < blockedAreas.Length; i++)
            {
                var expanded = new Rect(blockedAreas[i].xMin - 0.45f, blockedAreas[i].yMin - 0.45f, blockedAreas[i].width + 0.9f, blockedAreas[i].height + 0.9f);
                if (expanded.Contains(planPoint)) return true;
            }
            return false;
        }

        private void UpdateComplete()
        {
            for (var i = 0; i < activations.Length; i++) activations[i] = 0.35f + Mathf.Sin(simulationClock * 2f + i) * 0.18f;
            if (phaseClock >= 4f) ResetSimulation(true);
        }

        private void PlaceFlyForPoint(Vector3 point, Vector3 direction)
        {
            var side = Vector3.Cross(Vector3.up, direction).normalized;
            var desired = point - direction * 3.3f + side * 1.05f;
            desired.y = 0f;
            fly.position = desired;
            fly.rotation = Quaternion.LookRotation(direction, Vector3.up);
        }

        private void AnimateFly()
        {
            var walking = phase == SimulationPhase.Drawing;
            var cadence = simulationClock * 7.8f;
            for (var i = 0; i < legJoints.Count; i++)
            {
                var pose = legJoints[i];
                var sign = i % 2 == 0 ? 1f : -1f;
                var swing = walking ? Mathf.Sin(cadence + pose.Phase) * pose.Amplitude : Mathf.Sin(simulationClock * 1.7f + pose.Phase) * 1.2f;
                pose.Transform.localRotation = pose.BaseRotation * Quaternion.Euler(swing * 0.7f, 0f, swing * sign);
            }
            if (leftWing != null && rightWing != null)
            {
                var flutter = Mathf.Sin(simulationClock * 8f) * (walking ? 1.8f : 0.5f);
                leftWing.localRotation *= Quaternion.Euler(flutter * Time.deltaTime, 0f, 0f);
                rightWing.localRotation *= Quaternion.Euler(-flutter * Time.deltaTime, 0f, 0f);
            }
        }

        private void UpdatePencil()
        {
            var holder = drawingClaw.position;
            var target = pencilTip;
            target.y = 0.028f;
            var vector = target - holder;
            var length = Mathf.Max(0.35f, vector.magnitude);
            pencil.position = holder;
            pencil.rotation = Quaternion.FromToRotation(Vector3.down, vector.normalized);
            pencil.localScale = new Vector3(1f, length / 1.27f, 1f);
        }

        private void AnimateBrain()
        {
            var brightest = new List<int>(5);
            for (var i = 0; i < neuronRenderers.Count; i++)
            {
                var activation = Mathf.Clamp01(activations[i]);
                var color = Color.Lerp(new Color(0.02f, 0.16f, 0.17f), i % 5 == 0 ? Red : Cyan, activation);
                neuronMaterials[i].color = color * 0.72f;
                neuronMaterials[i].SetColor("_EmissionColor", color * (0.45f + activation * 2.1f));
                neuronRenderers[i].transform.localScale = Vector3.one * (0.13f + activation * 0.16f);
                if (activation > 0.12f && brightest.Count < 5) brightest.Add(i);
            }
            neuronText.text = "FLYWIRE v630 / АКТИВНЫЕ ID\n" + string.Join("   ", brightest.ConvertAll(index => brain.GetDisplayNeuronId(index).ToString().Substring(12)));
            brainRig.localRotation = Quaternion.Euler(5f + Mathf.Sin(simulationClock * 0.7f) * 2f, -8f + simulationClock * 3.2f, 0f);
        }

        private void UpdateCamera(float delta)
        {
            var direction = (segmentEnd - segmentStart).normalized;
            if (direction.sqrMagnitude < 0.1f) direction = Vector3.forward;
            var side = Vector3.Cross(Vector3.up, direction).normalized;
            var desired = fly.position - direction * 7.2f + side * 5.8f + Vector3.up * 6.7f;
            followCamera.transform.position = Vector3.Lerp(followCamera.transform.position, desired, delta * 2.5f);
            var focus = Vector3.Lerp(fly.position + Vector3.up * 0.8f, pencilTip, 0.43f);
            followCamera.transform.rotation = Quaternion.Slerp(followCamera.transform.rotation, Quaternion.LookRotation(focus - followCamera.transform.position), delta * 3.4f);
        }

        private void UpdateHud()
        {
            if (phase == SimulationPhase.Thinking) { statusText.text = "МОЗГ СТРОИТ ТРАЕКТОРИЮ"; statusText.color = Cyan; }
            else if (phase == SimulationPhase.Drawing) { statusText.text = "МУХА ЧЕРТИТ ТЕПЛОТРАССУ КАРАНДАШОМ"; statusText.color = Color.white; }
            else { statusText.text = "ПЛАН ТЕПЛОСЕТИ ГОТОВ"; statusText.color = Cyan; }
            metricsText.text =
                $"ПРИМЕР {scenarioIndex + 1:00}/{scenarioSources.Length:00}  {scenarioNames[scenarioIndex]}\n" +
                $"АВТОНОМНЫЙ ШАГ {Mathf.Min(segmentIndex + 1, generatedRoute.Count - 1):00}/{generatedRoute.Count - 1:00}\n" +
                $"поворот       {currentTurn * 48f:+0.0;-0.0;0.0}°\n" +
                $"до цели       {Vector3.Distance(pencilTip, consumer):0.0} м\n" +
                $"траектория    {routeLengthMeters:0.0} м\n" +
                $"стоимость     {estimatedCostRub / 1000000f:0.00} млн ₽\n" +
                $"S             {initialRouteScore:0.000} → {routeScore:0.000}\n" +
                $"дофамин       {dopamineSignal:+0.000000;0.000000}\n" +
                $"коннектом     {brain.ActualNeuronCount:N0} / {brain.ActualConnectionCount:N0}";
        }

        private void CaptureLegJoints()
        {
            var names = new[]
            {
                "coxa_T1_left", "femur_T1_left", "tibia_T1_left", "tarsus_T1_left", "coxa_T1_right", "femur_T1_right", "tibia_T1_right", "tarsus_T1_right",
                "coxa_T2_left", "femur_T2_left", "tibia_T2_left", "tarsus_T2_left", "coxa_T2_right", "femur_T2_right", "tibia_T2_right", "tarsus_T2_right",
                "coxa_T3_left", "femur_T3_left", "tibia_T3_left", "tarsus_T3_left", "coxa_T3_right", "femur_T3_right", "tibia_T3_right", "tarsus_T3_right"
            };
            for (var i = 0; i < names.Length; i++)
            {
                var joint = FindDescendant(detailedModel, names[i]);
                if (joint != null)
                {
                    var amplitude = names[i].Contains("T1_left") ? 4.5f : names[i].StartsWith("coxa", StringComparison.Ordinal) ? 5.5f : 8f;
                    legJoints.Add(new JointPose(joint, joint.localRotation, i * 1.37f, amplitude));
                }
            }
        }

        private void AddDrawnPoint(Vector3 point)
        {
            drawnPoints.Add(point);
            var index = drawnPoints.Count - 1;
            planOuter.positionCount = planInner.positionCount = drawnPoints.Count;
            planOuter.SetPosition(index, point);
            planInner.SetPosition(index, point + Vector3.up * 0.008f);
        }

        private IEnumerator CaptureProof()
        {
            yield return new WaitForSeconds(7f);
            var path = string.IsNullOrWhiteSpace(capturePath) ? Path.Combine(Application.dataPath, "..", "fly_heatnet_floor_proof.png") : capturePath;
            var fullPath = Path.GetFullPath(path);
            Directory.CreateDirectory(Path.GetDirectoryName(fullPath) ?? ".");
            ScreenCapture.CaptureScreenshot(fullPath, 1);
            yield return new WaitForSeconds(2f);
            File.WriteAllText(Path.ChangeExtension(fullPath, ".txt"), $"phase={phase}; mode=autonomous-smooth-trajectory; example={scenarioIndex + 1}/{scenarioSources.Length}; example_name={scenarioNames[scenarioIndex]}; segment={segmentIndex + 1}; route_samples={generatedRoute.Count}; length={routeLengthMeters:0.000}; cost={estimatedCostRub:0.00}; initial_S={initialRouteScore:0.000000}; best_S={routeScore:0.000000}; dopamine={dopamineSignal:0.000000}; body=TuragaLab/flybody; brain=FlyWire-v630; neurons={brain.ActualNeuronCount}; connections={brain.ActualConnectionCount}");
            Application.Quit(0);
        }

        private void ParseCommandLine()
        {
            var args = Environment.GetCommandLineArgs();
            for (var i = 0; i < args.Length; i++)
            {
                if (string.Equals(args[i], "--autotest", StringComparison.OrdinalIgnoreCase)) autoTest = true;
                else if (string.Equals(args[i], "--capture", StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length) capturePath = args[++i];
            }
        }

        private static Bounds CalculateBounds(Transform root)
        {
            var renderers = root.GetComponentsInChildren<Renderer>();
            var bounds = renderers.Length > 0 ? renderers[0].bounds : new Bounds(root.position, Vector3.zero);
            for (var i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);
            return bounds;
        }

        private static Transform FindDescendant(Transform root, string name)
        {
            if (root.name == name) return root;
            for (var i = 0; i < root.childCount; i++)
            {
                var result = FindDescendant(root.GetChild(i), name);
                if (result != null) return result;
            }
            return null;
        }

        private static void ConfigureFloorLine(LineRenderer line, float width, Material material)
        {
            line.useWorldSpace = true;
            line.alignment = LineAlignment.View;
            line.startWidth = line.endWidth = width;
            line.numCapVertices = line.numCornerVertices = 5;
            line.material = material;
            line.shadowCastingMode = ShadowCastingMode.Off;
            line.receiveShadows = false;
        }

        private static void CreateFloorRectangle(Vector3 center, Vector2 size, Material material)
        {
            var halfX = size.x * 0.5f;
            var halfY = size.y * 0.5f;
            const float y = 0.014f;
            CreateLine("Building outline", new[] { new Vector3(center.x - halfX, y, center.z - halfY), new Vector3(center.x + halfX, y, center.z - halfY), new Vector3(center.x + halfX, y, center.z + halfY), new Vector3(center.x - halfX, y, center.z + halfY), new Vector3(center.x - halfX, y, center.z - halfY) }, 0.045f, material);
        }

        private static Transform CreatePlanNode(string name, Vector3 position, Color color, float radius)
        {
            var node = CreatePrimitive(name, PrimitiveType.Cylinder, new Vector3(position.x, 0.035f, position.z), new Vector3(radius, 0.025f, radius), MakeMaterial(name, color * 0.65f, 0.25f, true, color));
            node.GetComponent<Renderer>().shadowCastingMode = ShadowCastingMode.Off;
            return node.transform;
        }

        private static GameObject CreateCone(string name, Transform parent, Vector3 localPosition, float radius, float height, Material material)
        {
            const int sides = 20;
            var vertices = new Vector3[sides + 2];
            vertices[0] = Vector3.up * (height * 0.5f);
            vertices[1] = Vector3.down * (height * 0.5f);
            for (var i = 0; i < sides; i++)
            {
                var angle = i * Mathf.PI * 2f / sides;
                vertices[i + 2] = new Vector3(Mathf.Cos(angle) * radius, -height * 0.5f, Mathf.Sin(angle) * radius);
            }
            var triangles = new int[sides * 6];
            for (var i = 0; i < sides; i++)
            {
                var next = (i + 1) % sides;
                var offset = i * 6;
                triangles[offset] = 0; triangles[offset + 1] = i + 2; triangles[offset + 2] = next + 2;
                triangles[offset + 3] = 1; triangles[offset + 4] = next + 2; triangles[offset + 5] = i + 2;
            }
            var mesh = new Mesh { name = name + " mesh", vertices = vertices, triangles = triangles };
            mesh.RecalculateNormals();
            var result = new GameObject(name);
            result.transform.SetParent(parent, false);
            result.transform.localPosition = localPosition;
            result.AddComponent<MeshFilter>().mesh = mesh;
            result.AddComponent<MeshRenderer>().material = material;
            return result;
        }

        private static GameObject CreatePrimitive(string name, PrimitiveType type, Vector3 position, Vector3 scale, Material material)
        {
            var result = GameObject.CreatePrimitive(type);
            result.name = name;
            result.transform.position = position;
            result.transform.localScale = scale;
            result.GetComponent<Renderer>().material = material;
            var collider = result.GetComponent<Collider>();
            if (collider != null) Destroy(collider);
            return result;
        }

        private static GameObject CreateChildPrimitive(string name, PrimitiveType type, Transform parent, Vector3 localPosition, Vector3 localScale, Material material)
        {
            var result = CreatePrimitive(name, type, Vector3.zero, localScale, material);
            result.transform.SetParent(parent, false);
            result.transform.localPosition = localPosition;
            result.transform.localScale = localScale;
            return result;
        }

        private static LineRenderer CreateLine(string name, Vector3[] points, float width, Material material)
        {
            var line = new GameObject(name).AddComponent<LineRenderer>();
            line.useWorldSpace = true;
            line.positionCount = points.Length;
            line.SetPositions(points);
            line.startWidth = line.endWidth = width;
            line.numCapVertices = line.numCornerVertices = 3;
            line.material = material;
            line.shadowCastingMode = ShadowCastingMode.Off;
            return line;
        }

        private static LineRenderer CreateLocalLine(Transform parent, string name, Vector3[] points, float width, Material material)
        {
            var line = CreateLine(name, points, width, material);
            line.useWorldSpace = false;
            line.transform.SetParent(parent, false);
            return line;
        }

        private static Text CreateText(Transform parent, string name, string value, Font font, int fontSize, TextAnchor alignment, Vector2 position, Vector2 size, Color color)
        {
            var textObject = new GameObject(name);
            textObject.transform.SetParent(parent, false);
            var text = textObject.AddComponent<Text>();
            text.text = value; text.font = font; text.fontSize = fontSize; text.alignment = alignment; text.color = color;
            text.horizontalOverflow = HorizontalWrapMode.Wrap; text.verticalOverflow = VerticalWrapMode.Overflow;
            var shadow = textObject.AddComponent<Shadow>();
            shadow.effectColor = new Color(0f, 0f, 0f, 0.85f); shadow.effectDistance = new Vector2(1.5f, -1.5f);
            var rect = text.rectTransform;
            rect.anchorMin = rect.anchorMax = new Vector2(0f, 1f); rect.pivot = new Vector2(0f, 1f); rect.anchoredPosition = position; rect.sizeDelta = size;
            return text;
        }

        private static Material MakeMaterial(string name, Color color, float metallic, bool emissive = false, Color emission = default)
        {
            var shader = Shader.Find("FlyHeatNet/GlowOpaque");
            if (shader == null) throw new InvalidOperationException("FlyHeatNet/GlowOpaque shader is missing from the player build.");
            var material = new Material(shader) { name = name, color = color };
            material.SetFloat("_Metallic", metallic); material.SetFloat("_Glossiness", 0.55f);
            material.SetColor("_EmissionColor", emissive ? (emission == default ? color : emission) : Color.black);
            return material;
        }

        private static Material MakeTransparentMaterial(string name, Color color)
        {
            var shader = Shader.Find("FlyHeatNet/GlowTransparent");
            if (shader == null) throw new InvalidOperationException("FlyHeatNet/GlowTransparent shader is missing from the player build.");
            var material = new Material(shader) { name = name, color = color };
            material.SetFloat("_Metallic", 0.08f); material.SetFloat("_Glossiness", 0.72f);
            material.SetColor("_EmissionColor", new Color(color.r, color.g, color.b, 1f) * 0.14f);
            return material;
        }

        private sealed class JointPose
        {
            public JointPose(Transform transform, Quaternion baseRotation, float phase, float amplitude) { Transform = transform; BaseRotation = baseRotation; Phase = phase; Amplitude = amplitude; }
            public Transform Transform { get; }
            public Quaternion BaseRotation { get; }
            public float Phase { get; }
            public float Amplitude { get; }
        }

    }
}
