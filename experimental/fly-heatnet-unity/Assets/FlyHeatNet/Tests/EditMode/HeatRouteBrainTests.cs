using NUnit.Framework;

namespace FlyHeatNet.Tests
{
    public sealed class FlyWireConnectomeBrainTests
    {
        [Test]
        public void DecideSteering_ProducesBoundedCommandAndMeasuredActivity()
        {
            var brain = new FlyWireConnectomeBrain();

            var steering = brain.DecideSteering(0.35f, 0.9f, 0.4f, 0.2f, 0.1f, 7, out var activity);

            Assert.That(steering, Is.InRange(-1f, 1f));
            Assert.That(activity, Has.Length.EqualTo(FlyWireConnectomeBrain.NeuronCount));
            Assert.That(activity, Has.All.InRange(0f, 1f));
        }

        [Test]
        public void LoadsPublishedFlyWireV630Topology()
        {
            var brain = new FlyWireConnectomeBrain();

            Assert.That(brain.ActualNeuronCount, Is.EqualTo(127400));
            Assert.That(brain.ActualConnectionCount, Is.EqualTo(14687178));
            Assert.That(brain.DisplayConnections, Is.Not.Empty);
            Assert.That(brain.GetDisplayNeuronId(0), Is.GreaterThan(720000000000000000L));
        }

        [Test]
        public void HackathonReward_UsesBestSoFarScoreImprovement()
        {
            var initial = HackathonRewardModel.CalculateScore(180000000f, 1800f);
            var improved = HackathonRewardModel.CalculateScore(160000000f, 1650f);
            var reward = HackathonRewardModel.CalculateBestSoFarReward(initial, improved, initial);

            Assert.That(improved, Is.LessThan(initial));
            Assert.That(reward, Is.GreaterThan(0f));
            Assert.That(HackathonRewardModel.CalculateBestSoFarReward(improved, initial, initial), Is.Zero);
        }
    }
}
