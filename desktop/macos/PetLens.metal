#include <metal_stdlib>
using namespace metal;

struct LensVertex {
    float4 position [[position]];
    float2 uv;
};

struct LensParameters {
    float4 desktopRect;
    float time;
    float transition;
    uint pose;
};

vertex LensVertex petLensVertex(uint index [[vertex_id]]) {
    float2 uv = float2((index << 1) & 2, index & 2);
    return { float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0), uv };
}

static float4 over(float4 background, float shade, float opacity) {
    return float4(float3(shade) * opacity, opacity) + background * (1.0 - opacity);
}

static float circle(float2 point, float radius) {
    return 1.0 - smoothstep(radius - 0.5, radius + 0.5, length(point));
}

fragment float4 petLensFragment(LensVertex input [[stage_in]],
                               texture2d<float> desktop [[texture(0)]],
                               constant LensParameters &params [[buffer(0)]]) {
    constexpr sampler screenSampler(coord::normalized, address::clamp_to_edge, filter::linear);
    const float t = params.time, k = params.transition;
    const float2 d = input.uv - 0.5;
    const float r = max(length(d), 0.0001);
    const float hole = 0.23, outer = 0.495;
    if (r >= outer) return float4(0.0);

    float spread = 12.95, size = 1.0, squash = 1.0, glow = 0.0, dim = 1.0, wind = 1.0;
    float bob = sin(t * 1.6) * 2.0;
    float2 wing = float2(1.0, 0.0);
    switch (params.pose) {
    case 1: { // 待命
        spread += sin(t * 1.6) * 0.6;
        float phase = fmod(t, 5.2);
        if (phase < 0.16) squash -= sin(phase / 0.16 * M_PI_F) * 0.85;
        float turn = fmod(t, 9.0) / 1.3;
        if (turn < 1.0) {
            float angle = (1.0 - cos(turn * M_PI_F)) * M_PI_F;
            wing = float2(cos(angle), sin(angle) * 0.6);
            spread = 12.95 + sin(turn * M_PI_F) * 2.0;
        }
        break;
    }
    case 2: // 录音
        spread += k * (4.0 + sin(t * 7.0));
        size += k * 0.06;
        glow = k * (0.55 + 0.3 * sin(t * 7.0));
        bob = 0.0;
        wind = 0.8;
        break;
    case 3: { // 思考
        float angle = t * 3.2;
        wing = float2(cos(angle), sin(angle) * 0.55);
        spread = 14.0;
        size = 0.94;
        wind = 2.0;
        break;
    }
    case 4: { // 朗读
        float voice = fabs(sin(t * 9.0)) * 0.6 + fabs(sin(t * 5.3 + 1.0)) * 0.4;
        size += k * 0.10 * voice;
        spread += k * 2.0 * voice;
        glow = k * 0.25 * voice;
        wind = 1.2 + 0.4 * voice;
        break;
    }
    default: // 休眠
        dim = 0.45;
        squash = 0.18;
        bob = sin(t * 0.8) * 1.2;
        spread = 12.0;
        wind = 0.5;
        break;
    }

    // 沿用 Windows 像素着色器的径向偏移、旋转和外缘衰减。
    float falloff = smoothstep(0.0, 1.0, (outer - r) / (outer - hole));
    float from = r - 0.8 * hole * hole / r * falloff;
    float angle = (0.75 + 0.3 * sin(t * 0.6)) * wind * falloff * falloff;
    float c = cos(angle), s = sin(angle);
    float2 rotated = float2(d.x * c - d.y * s, d.x * s + d.y * c);
    float2 source = params.desktopRect.xy + (0.5 + rotated * (from / r)) * params.desktopRect.zw;
    float edge = smoothstep(0.0, 1.0, (outer - r) / (outer * 0.22));
    float opacity = edge * step(hole * 0.55, r);
    float3 light = all(source >= 0.0) && all(source <= 1.0) ? desktop.sample(screenSampler, source).rgb : float3(0.0);
    float4 result = float4(light * opacity, opacity);

    float2 face = d * 200.0 - float2(0.0, bob);
    float radius = length(face);
    result = over(result, 1.0, glow * (119.0 / 255.0) * saturate((60.0 - radius) / 30.0));
    result = over(result, 4.0 / 255.0, 1.0 - smoothstep(44.8, 50.4, radius));
    float arcs = max(circle(face - spread * wing, 18.0), circle(face + spread * wing, 18.0));
    result = over(result, 242.0 / 255.0, dim * arcs);
    result = over(result, 4.0 / 255.0, dim * circle(face, 23.3 * size));
    return over(result, 242.0 / 255.0, dim * circle(float2(face.x, face.y / squash), 18.0 * size));
}
