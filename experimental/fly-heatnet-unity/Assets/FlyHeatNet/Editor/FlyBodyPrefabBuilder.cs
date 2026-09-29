using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Xml.Linq;
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;

namespace FlyHeatNet.Editor
{
    public static class FlyBodyPrefabBuilder
    {
        private const string SourceRoot = "Assets/FlyHeatNet/ThirdParty/FlyBody";
        private const string MeshRoot = SourceRoot + "/Meshes";
        private const string GeneratedRoot = SourceRoot + "/Generated";
        private const string MaterialRoot = GeneratedRoot + "/Materials";
        private const string PrefabPath = "Assets/FlyHeatNet/Resources/DetailedDrosophila.prefab";

        [MenuItem("Fly HeatNet/Rebuild Detailed Fly Prefab")]
        public static void BuildPrefab()
        {
            Directory.CreateDirectory(MaterialRoot);
            Directory.CreateDirectory(Path.GetDirectoryName(PrefabPath) ?? throw new InvalidOperationException("Prefab directory is unavailable."));
            AssetDatabase.Refresh(ImportAssetOptions.ForceSynchronousImport);

            var document = XDocument.Load(Path.Combine(SourceRoot, "fruitfly.xml"));
            var mujoco = document.Root ?? throw new InvalidOperationException("fruitfly.xml has no root element.");
            var meshFiles = mujoco.Element("asset")?
                .Elements("mesh")
                .ToDictionary(element => RequiredAttribute(element, "name"), element => RequiredAttribute(element, "file"))
                ?? throw new InvalidOperationException("fruitfly.xml has no mesh registry.");
            var materials = BuildMaterials(mujoco);

            var prefabRoot = new GameObject("Detailed Drosophila (FlyBody)");
            var coordinateFrame = new GameObject("MuJoCo coordinate frame").transform;
            coordinateFrame.SetParent(prefabRoot.transform, false);
            coordinateFrame.localRotation = Quaternion.Euler(-90f, 0f, 0f);

            var worldBody = mujoco.Element("worldbody") ?? throw new InvalidOperationException("fruitfly.xml has no worldbody.");
            var rendererCount = 0;
            long triangleCount = 0;
            foreach (var body in worldBody.Elements("body"))
            {
                BuildBody(body, coordinateFrame, meshFiles, materials, ref rendererCount, ref triangleCount);
            }

            PrefabUtility.SaveAsPrefabAsset(prefabRoot, PrefabPath);
            UnityEngine.Object.DestroyImmediate(prefabRoot);
            AssetDatabase.SaveAssets();

            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(PrefabPath);
            var bounds = CalculateBounds(prefab);
            Debug.Log($"FLY_BODY_PREFAB_OK renderers={rendererCount} triangles={triangleCount} bounds={bounds.size}");
        }

        private static void BuildBody(
            XElement bodyElement,
            Transform parent,
            IReadOnlyDictionary<string, string> meshFiles,
            IReadOnlyDictionary<string, Material> materials,
            ref int rendererCount,
            ref long triangleCount)
        {
            var body = new GameObject(RequiredAttribute(bodyElement, "name")).transform;
            body.SetParent(parent, false);
            body.localPosition = ParseVector(bodyElement.Attribute("pos")?.Value, Vector3.zero);
            body.localRotation = ParseRotation(bodyElement);

            foreach (var geom in bodyElement.Elements("geom"))
            {
                var meshName = geom.Attribute("mesh")?.Value;
                if (string.IsNullOrWhiteSpace(meshName))
                {
                    continue;
                }

                if (!meshFiles.TryGetValue(meshName, out var meshFile))
                {
                    throw new InvalidOperationException($"Mesh '{meshName}' is not registered in fruitfly.xml.");
                }

                var modelPath = $"{MeshRoot}/{meshFile}";
                var model = AssetDatabase.LoadAssetAtPath<GameObject>(modelPath);
                var mesh = model != null ? model.GetComponentInChildren<MeshFilter>()?.sharedMesh : null;
                if (mesh == null)
                {
                    throw new InvalidOperationException($"Unity could not import '{modelPath}'.");
                }

                var meshObject = new GameObject(geom.Attribute("name")?.Value ?? meshName);
                meshObject.transform.SetParent(body, false);
                meshObject.transform.localPosition = ParseVector(geom.Attribute("pos")?.Value, Vector3.zero);
                meshObject.transform.localRotation = ParseRotation(geom);
                meshObject.transform.localScale = Vector3.one * 0.1f;
                meshObject.AddComponent<MeshFilter>().sharedMesh = mesh;

                var materialName = geom.Attribute("material")?.Value ?? "body";
                if (!materials.TryGetValue(materialName, out var material))
                {
                    material = materials["body"];
                }

                var renderer = meshObject.AddComponent<MeshRenderer>();
                renderer.sharedMaterial = material;
                renderer.shadowCastingMode = materialName == "membrane" ? ShadowCastingMode.Off : ShadowCastingMode.On;
                renderer.receiveShadows = materialName != "membrane";
                rendererCount++;
                triangleCount += mesh.GetIndexCount(0) / 3;
            }

            foreach (var childBody in bodyElement.Elements("body"))
            {
                BuildBody(childBody, body, meshFiles, materials, ref rendererCount, ref triangleCount);
            }
        }

        private static Dictionary<string, Material> BuildMaterials(XElement mujoco)
        {
            var result = new Dictionary<string, Material>(StringComparer.Ordinal);
            var asset = mujoco.Element("asset") ?? throw new InvalidOperationException("fruitfly.xml has no asset section.");
            foreach (var source in asset.Elements("material"))
            {
                var name = RequiredAttribute(source, "name");
                var rgba = ParseColor(source.Attribute("rgba")?.Value, Color.white);
                var transparent = rgba.a < 0.99f;
                var shader = Shader.Find(transparent ? "FlyHeatNet/GlowTransparent" : "FlyHeatNet/GlowOpaque");
                if (shader == null)
                {
                    throw new InvalidOperationException("Fly HeatNet shaders must compile before building the fly prefab.");
                }

                var path = $"{MaterialRoot}/{name}.mat";
                var material = AssetDatabase.LoadAssetAtPath<Material>(path);
                if (material == null)
                {
                    material = new Material(shader) { name = name };
                    AssetDatabase.CreateAsset(material, path);
                }
                else
                {
                    material.shader = shader;
                }

                material.color = rgba;
                material.SetFloat("_Metallic", name == "red" || name == "black" ? 0.28f : 0.05f);
                material.SetFloat("_Glossiness", name == "membrane" ? 0.82f : 0.48f);
                material.SetColor("_EmissionColor", name == "red" ? new Color(0.12f, 0.002f, 0f) : Color.black);
                EditorUtility.SetDirty(material);
                result[name] = material;
            }

            return result;
        }

        private static Bounds CalculateBounds(GameObject prefab)
        {
            var instance = PrefabUtility.InstantiatePrefab(prefab) as GameObject;
            if (instance == null)
            {
                return default;
            }

            var renderers = instance.GetComponentsInChildren<Renderer>();
            var bounds = renderers.Length > 0 ? renderers[0].bounds : default;
            for (var i = 1; i < renderers.Length; i++)
            {
                bounds.Encapsulate(renderers[i].bounds);
            }
            UnityEngine.Object.DestroyImmediate(instance);
            return bounds;
        }

        private static Quaternion ParseRotation(XElement element)
        {
            var quaternion = element.Attribute("quat")?.Value;
            if (!string.IsNullOrWhiteSpace(quaternion))
            {
                var values = ParseFloats(quaternion, 4);
                return new Quaternion(values[1], values[2], values[3], values[0]).normalized;
            }

            var euler = element.Attribute("euler")?.Value;
            if (!string.IsNullOrWhiteSpace(euler))
            {
                return Quaternion.Euler(ParseVector(euler, Vector3.zero) * Mathf.Rad2Deg);
            }

            return Quaternion.identity;
        }

        private static Vector3 ParseVector(string value, Vector3 fallback)
        {
            if (string.IsNullOrWhiteSpace(value))
            {
                return fallback;
            }

            var values = ParseFloats(value, 3);
            return new Vector3(values[0], values[1], values[2]);
        }

        private static Color ParseColor(string value, Color fallback)
        {
            if (string.IsNullOrWhiteSpace(value))
            {
                return fallback;
            }

            var values = ParseFloats(value, 4);
            return new Color(values[0], values[1], values[2], values[3]);
        }

        private static float[] ParseFloats(string value, int expected)
        {
            var pieces = value.Split((char[])null, StringSplitOptions.RemoveEmptyEntries);
            if (pieces.Length != expected)
            {
                throw new FormatException($"Expected {expected} numbers, got '{value}'.");
            }

            return pieces.Select(piece => float.Parse(piece, NumberStyles.Float, CultureInfo.InvariantCulture)).ToArray();
        }

        private static string RequiredAttribute(XElement element, string name)
        {
            return element.Attribute(name)?.Value ?? throw new FormatException($"<{element.Name}> is missing '{name}'.");
        }
    }
}
