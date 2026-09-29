using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace FlyHeatNet.Editor
{
    public static class BuildFlyHeatNet
    {
        [MenuItem("Fly HeatNet/Build Windows")]
        public static void BuildWindows()
        {
            PlayerSettings.companyName = "Eon Systems";
            PlayerSettings.productName = "Fly HeatNet 3D";
            PlayerSettings.defaultScreenWidth = 1280;
            PlayerSettings.defaultScreenHeight = 720;
            PlayerSettings.fullScreenMode = FullScreenMode.Windowed;
            PlayerSettings.resizableWindow = true;
            PlayerSettings.runInBackground = true;

            var output = Path.GetFullPath(Path.Combine(Application.dataPath, "..", "Build", "FlyHeatNet3D.exe"));
            Directory.CreateDirectory(Path.GetDirectoryName(output) ?? throw new InvalidOperationException("Build output directory is unavailable."));

            var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes = new[] { "Assets/Scenes/SampleScene.unity" },
                locationPathName = output,
                target = BuildTarget.StandaloneWindows64,
                options = BuildOptions.None
            });

            if (report.summary.result != BuildResult.Succeeded)
            {
                throw new InvalidOperationException($"Fly HeatNet build failed: {report.summary.result}");
            }

            var licenseDirectory = Path.Combine(Path.GetDirectoryName(output) ?? ".", "ThirdPartyLicenses");
            Directory.CreateDirectory(licenseDirectory);
            File.Copy("Assets/FlyHeatNet/ThirdParty/FlyBody/LICENSE.txt", Path.Combine(licenseDirectory, "FlyBody-Apache-2.0.txt"), true);
            File.Copy("Assets/FlyHeatNet/ThirdParty/FlyBody/NOTICE.md", Path.Combine(licenseDirectory, "FlyBody-NOTICE.md"), true);
            File.Copy("Assets/FlyHeatNet/ThirdParty/FlyWire/LICENSE.txt", Path.Combine(licenseDirectory, "Drosophila-Brain-Model-MIT.txt"), true);
            File.Copy("Assets/FlyHeatNet/ThirdParty/FlyWire/NOTICE.md", Path.Combine(licenseDirectory, "FlyWire-v630-NOTICE.md"), true);

            Debug.Log($"FLY_HEATNET_BUILD_OK {output} {report.summary.totalSize} bytes");
        }
    }
}
