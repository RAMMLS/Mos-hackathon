Shader "FlyHeatNet/GlowTransparent"
{
    Properties
    {
        _Color ("Color", Color) = (1,1,1,0.5)
        _EmissionColor ("Emission", Color) = (0,0,0,0)
        _Metallic ("Metallic", Range(0,1)) = 0
        _Glossiness ("Smoothness", Range(0,1)) = 0.5
    }
    SubShader
    {
        Tags { "Queue"="Transparent" "RenderType"="Transparent" }
        LOD 200
        ZWrite Off

        CGPROGRAM
        #pragma surface surf Standard alpha:fade
        #pragma target 3.0

        fixed4 _Color;
        fixed4 _EmissionColor;
        half _Metallic;
        half _Glossiness;

        struct Input
        {
            float2 uv_MainTex;
        };

        void surf(Input input, inout SurfaceOutputStandard output)
        {
            output.Albedo = _Color.rgb;
            output.Metallic = _Metallic;
            output.Smoothness = _Glossiness;
            output.Emission = _EmissionColor.rgb;
            output.Alpha = _Color.a;
        }
        ENDCG
    }
    FallBack "Transparent/Diffuse"
}
