using System;
using System.Collections.Generic;
using UnityEngine;

namespace FlyHeatNet
{
    public static class HackathonRewardModel
    {
        public static float CalculateScore(float calculatedCostRub, float newNetworkLengthMeters)
        {
            return 0.7f * (calculatedCostRub / 25000000f) + 0.3f * (newNetworkLengthMeters / 100f);
        }

        public static float CalculateBestSoFarReward(float previousBestScore, float nextBestScore, float initialScore)
        {
            return Mathf.Max(0f, previousBestScore - nextBestScore) / Mathf.Max(initialScore, 1f);
        }
    }

    public readonly struct DisplayConnection
    {
        public DisplayConnection(int source, int target, int synapses)
        {
            Source = source;
            Target = target;
            Synapses = synapses;
        }

        public int Source { get; }
        public int Target { get; }
        public int Synapses { get; }
    }

    public sealed class FlyWireConnectomeBrain
    {
        public const int NeuronCount = 28;
        private const float RestingPotential = -52f;
        private const float ThresholdPotential = -45f;
        private const float SynapseMillivolts = 0.275f;
        private const int SimulationTicks = 38;

        private static ConnectomeGraph sharedGraph;

        private readonly ConnectomeGraph graph;
        private readonly float[] voltage;
        private readonly float[] conductance;
        private readonly byte[] refractory;
        private readonly bool[] spikes;

        public FlyWireConnectomeBrain()
        {
            graph = sharedGraph ?? (sharedGraph = ConnectomeGraph.Load());
            voltage = new float[graph.NodeCount];
            conductance = new float[graph.NodeCount];
            refractory = new byte[graph.NodeCount];
            spikes = new bool[graph.NodeCount];
        }

        public int ActualNeuronCount => graph.NodeCount;
        public int ActualConnectionCount => graph.EdgeCount;
        public int LastPopulationSpikes { get; private set; }
        public IReadOnlyList<DisplayConnection> DisplayConnections => graph.DisplayConnections;

        public long GetDisplayNeuronId(int index) => graph.DisplayIds[index];

        public float DecideSteering(float targetBearing, float leftClearance, float forwardClearance, float rightClearance, float dopamine, int decisionIndex, out float[] activity)
        {
            var navigationSignal = new NavigationStimulus(
                targetBearing * 7f,
                (1f - forwardClearance) * 38f,
                (1f - Mathf.Max(leftClearance, rightClearance)) * 9f,
                1f - forwardClearance,
                Mathf.Max(leftClearance, rightClearance) * 10f);
            var neuralSignal = SimulateCandidate(navigationSignal, decisionIndex % 3, out activity);
            var obstacleTurn = (rightClearance - leftClearance) * (forwardClearance < 0.55f ? 1.15f : 0.45f);
            var learnedTargetGain = 0.68f + Mathf.Clamp01(dopamine) * 0.24f;
            var explorationGain = 0.34f - Mathf.Clamp01(dopamine) * 0.16f;
            return Mathf.Clamp(targetBearing * learnedTargetGain + obstacleTurn + neuralSignal * explorationGain, -1f, 1f);
        }

        private float SimulateCandidate(NavigationStimulus candidate, int candidateIndex, out float[] displayActivity)
        {
            Array.Fill(voltage, RestingPotential);
            Array.Clear(conductance, 0, conductance.Length);
            Array.Clear(refractory, 0, refractory.Length);
            Array.Clear(spikes, 0, spikes.Length);
            displayActivity = new float[NeuronCount];

            var features = new[]
            {
                Mathf.Clamp01(candidate.Length / 38f),
                Mathf.Clamp01(candidate.HeatLoss / 9f),
                Mathf.Clamp01(candidate.ConflictRisk),
                Mathf.Clamp01(candidate.PressureMargin / 10f),
                Mathf.Clamp01(Mathf.Abs(candidate.LateralOffset) / 7f),
                1f - Mathf.Clamp01(candidate.ConflictRisk * 0.7f + candidate.HeatLoss / 18f)
            };

            var bucketSpikes = new int[4];
            LastPopulationSpikes = 0;
            for (var tick = 0; tick < SimulationTicks; tick++)
            {
                PropagatePreviousSpikes();
                InjectFeatures(features, candidateIndex, tick);
                StepNeurons(displayActivity, bucketSpikes);
            }

            var totalBuckets = bucketSpikes[0] + bucketSpikes[1] + bucketSpikes[2] + bucketSpikes[3] + 1f;
            var neuralSignal = (bucketSpikes[0] - bucketSpikes[1] + bucketSpikes[2] * 0.55f - bucketSpikes[3] * 0.35f) / totalBuckets;
            for (var i = 0; i < displayActivity.Length; i++) displayActivity[i] = Mathf.Clamp01(displayActivity[i] / 5f);
            return Mathf.Clamp(neuralSignal, -1f, 1f);
        }

        private readonly struct NavigationStimulus
        {
            public NavigationStimulus(float lateralOffset, float length, float heatLoss, float conflictRisk, float pressureMargin)
            {
                LateralOffset = lateralOffset;
                Length = length;
                HeatLoss = heatLoss;
                ConflictRisk = conflictRisk;
                PressureMargin = pressureMargin;
            }

            public float LateralOffset { get; }
            public float Length { get; }
            public float HeatLoss { get; }
            public float ConflictRisk { get; }
            public float PressureMargin { get; }
        }

        private void PropagatePreviousSpikes()
        {
            for (var source = 0; source < spikes.Length; source++)
            {
                if (!spikes[source]) continue;
                for (var edge = graph.Offsets[source]; edge < graph.Offsets[source + 1]; edge++)
                {
                    conductance[graph.Postsynaptic[edge]] += graph.SynapseCounts[edge] * SynapseMillivolts;
                }
            }
        }

        private void InjectFeatures(float[] features, int candidateIndex, int tick)
        {
            const int activeInputsPerFeature = 4;
            for (var feature = 0; feature < features.Length; feature++)
            {
                var threshold = Mathf.RoundToInt(features[feature] * 150f);
                for (var member = 0; member < activeInputsPerFeature; member++)
                {
                    var hash = unchecked((uint)(candidateIndex * 73856093 ^ tick * 19349663 ^ feature * 83492791 ^ member * 265443576));
                    hash ^= hash >> 13;
                    hash *= 1274126177u;
                    if (hash % 1000u < threshold)
                    {
                        voltage[graph.InputNeurons[feature * graph.InputGroupSize + member]] += SynapseMillivolts * 250f;
                    }
                }
            }
        }

        private void StepNeurons(float[] displayActivity, int[] bucketSpikes)
        {
            for (var neuron = 0; neuron < graph.NodeCount; neuron++)
            {
                spikes[neuron] = false;
                conductance[neuron] *= 0.81873075f; // exp(-1 ms / 5 ms)
                if (refractory[neuron] > 0)
                {
                    refractory[neuron]--;
                    voltage[neuron] = RestingPotential;
                }
                else
                {
                    voltage[neuron] += (RestingPotential - voltage[neuron] + conductance[neuron]) / 20f;
                    if (voltage[neuron] > ThresholdPotential)
                    {
                        spikes[neuron] = true;
                        voltage[neuron] = RestingPotential;
                        conductance[neuron] = 0f;
                        refractory[neuron] = 3;
                        LastPopulationSpikes++;
                        bucketSpikes[(neuron * 1103515245 + 12345) & 3]++;
                    }
                }
            }

            for (var i = 0; i < graph.DisplayIndices.Length; i++)
            {
                if (spikes[graph.DisplayIndices[i]]) displayActivity[i] += 1f;
            }
        }

        private sealed class ConnectomeGraph
        {
            public int NodeCount;
            public int EdgeCount;
            public int InputGroupSize;
            public int[] DisplayIndices;
            public long[] DisplayIds;
            public int[] InputNeurons;
            public int[] Offsets;
            public int[] Postsynaptic;
            public short[] SynapseCounts;
            public DisplayConnection[] DisplayConnections;

            public static ConnectomeGraph Load()
            {
                var asset = Resources.Load<TextAsset>("FlyWireV630");
                if (asset == null) throw new InvalidOperationException("FlyWireV630.bytes is missing from Resources.");
                var bytes = asset.bytes;
                if (bytes.Length < 24 || bytes[0] != 'F' || bytes[1] != 'W' || bytes[2] != '6' || bytes[3] != '3')
                {
                    throw new InvalidOperationException("FlyWire v630 asset has an invalid header.");
                }

                var cursor = 4;
                var version = ReadInt(bytes, ref cursor);
                if (version != 1) throw new InvalidOperationException($"Unsupported FlyWire asset version {version}.");
                var graph = new ConnectomeGraph
                {
                    NodeCount = ReadInt(bytes, ref cursor),
                    EdgeCount = ReadInt(bytes, ref cursor)
                };
                var displayCount = ReadInt(bytes, ref cursor);
                graph.InputGroupSize = ReadInt(bytes, ref cursor);
                if (displayCount != NeuronCount) throw new InvalidOperationException($"Expected {NeuronCount} display neurons, got {displayCount}.");

                graph.DisplayIndices = new int[displayCount];
                graph.DisplayIds = new long[displayCount];
                for (var i = 0; i < displayCount; i++)
                {
                    graph.DisplayIndices[i] = ReadInt(bytes, ref cursor);
                    graph.DisplayIds[i] = ReadLong(bytes, ref cursor);
                }

                graph.InputNeurons = ReadIntArray(bytes, ref cursor, graph.InputGroupSize * 6);
                graph.Offsets = ReadIntArray(bytes, ref cursor, graph.NodeCount + 1);
                graph.Postsynaptic = ReadIntArray(bytes, ref cursor, graph.EdgeCount);
                graph.SynapseCounts = new short[graph.EdgeCount];
                Buffer.BlockCopy(bytes, cursor, graph.SynapseCounts, 0, graph.EdgeCount * sizeof(short));
                graph.DisplayConnections = graph.BuildDisplayConnections();
                Resources.UnloadAsset(asset);
                return graph;
            }

            private DisplayConnection[] BuildDisplayConnections()
            {
                var lookup = new Dictionary<int, int>(DisplayIndices.Length);
                for (var i = 0; i < DisplayIndices.Length; i++) lookup[DisplayIndices[i]] = i;
                var result = new List<DisplayConnection>();
                for (var source = 0; source < DisplayIndices.Length; source++)
                {
                    var sourceIndex = DisplayIndices[source];
                    for (var edge = Offsets[sourceIndex]; edge < Offsets[sourceIndex + 1]; edge++)
                    {
                        if (lookup.TryGetValue(Postsynaptic[edge], out var target))
                        {
                            result.Add(new DisplayConnection(source, target, SynapseCounts[edge]));
                        }
                    }
                }
                return result.ToArray();
            }

            private static int ReadInt(byte[] bytes, ref int cursor)
            {
                var value = BitConverter.ToInt32(bytes, cursor);
                cursor += sizeof(int);
                return value;
            }

            private static long ReadLong(byte[] bytes, ref int cursor)
            {
                var value = BitConverter.ToInt64(bytes, cursor);
                cursor += sizeof(long);
                return value;
            }

            private static int[] ReadIntArray(byte[] bytes, ref int cursor, int length)
            {
                var result = new int[length];
                Buffer.BlockCopy(bytes, cursor, result, 0, length * sizeof(int));
                cursor += length * sizeof(int);
                return result;
            }
        }
    }
}
