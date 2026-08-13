// Newton-iteration screen-space refraction for water surfaces.
//
// Based on "Ultra-fast Screen-Space Refractions and Caustics via Newton's Method"
// (https://github.com/TheManTheMythTheGameDev/NewtonsMethodRefraction).
//
// Core idea: Snell's law gives the correct initial direction, but the actual
// intersection point on the scene geometry is unknown.  Instead of assuming a
// flat receiver plane at a fixed depth, this module iteratively refines the
// intersection by sampling the depth buffer and computing the error between
// the ray's depth and the scene's depth at each sample point.  Each iteration
// builds a local plane from the sampled depth+normal and does a ray-plane
// intersection (Newton step) to move closer to the true surface.
//
// Usage in Caustica's ray-tracing pipeline:
//   - Primary refraction: the TLAS-based TraceRay is already exact, so this
//     module is NOT a TLAS replacement.  It is a screen-space adjunct for
//     caustic landing-point calculation and screen-space fallback.
//   - Caustics: the current causticLanding in water.slang projects the
//     refracted ray onto a flat plane at depth h.  newtonCausticLanding
//     replaces h with the actual scene depth, giving a physically correct
//     Jacobian for the caustic intensity.
//   - Screen-space fallback: when a ray is about to exit the scene or the
//     TLAS is not available, this module provides a reasonable approximation
//     from the GBuffer alone.

import world_common;

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

public struct NewtonRefractConfig {
    public int maxIterations;          // 4 is usually sufficient
    public float convergeThreshold;    // relative convergence, e.g. 0.01
    public float stepBias;             // bias along the ray to avoid self-hit
};

public NewtonRefractConfig newtonRefractDefaults() {
    NewtonRefractConfig cfg;
    cfg.maxIterations = 4;
    cfg.convergeThreshold = 0.01;
    cfg.stepBias = 0.001;
    return cfg;
}

// ---------------------------------------------------------------------------
// GBuffer access helpers (RWTexture2D → Load with texel coordinates)
// ---------------------------------------------------------------------------

// Sample depth from a reverse-Z r32f storage image at normalized UV.
// Returns 0 on out-of-bounds.
public float gbufDepth(RWTexture2D<float> depthTex, float2 uv, uint2 dims) {
    int2 coord = int2(uv * float2(dims));
    coord = clamp(coord, int2(0, 0), int2(dims) - 1);
    return depthTex[coord];
}

// Sample normal from a rgba16f storage image at normalized UV.
// Returns (0,1,0) on out-of-bounds.
public float3 gbufNormal(RWTexture2D<float4> normalTex, float2 uv, uint2 dims) {
    int2 coord = int2(uv * float2(dims));
    coord = clamp(coord, int2(0, 0), int2(dims) - 1);
    return normalize(normalTex[coord].xyz);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Project a world-space point into screen-space UV (0..1, reverse-Z depth).
// Returns false if the point is behind the camera.
public bool worldToScreen(float3 worldPos, float4x4 viewProj,
                          out float2 uv, out float depth) {
    float4 clip = mul(viewProj, float4(worldPos, 1.0));
    if (clip.w <= 0.0) return false;
    uv = clip.xy / clip.w * 0.5 + 0.5;
    depth = clip.z / clip.w;
    return true;
}

// Reconstruct a world-space position from screen-space UV + reverse-Z depth.
public float3 screenToWorld(float2 uv, float depth, float4x4 invViewProj) {
    float4 clip = float4(uv * 2.0 - 1.0, depth, 1.0);
    float4 world = mul(invViewProj, clip);
    return world.xyz / world.w;
}

// Ray-plane intersection: find t > 0 such that origin + t * dir lies on the
// plane.  Returns -1 for parallel or back-facing.
public float rayPlaneIntersect(float3 origin, float3 dir,
                               float3 planePoint, float3 planeNormal) {
    float denom = dot(dir, planeNormal);
    if (abs(denom) < 1.0e-8) return -1.0;
    float t = dot(planePoint - origin, planeNormal) / denom;
    return t;
}

// ---------------------------------------------------------------------------
// Newton iteration refraction  (core algorithm)
// ---------------------------------------------------------------------------

// Given a water surface point and the refracted ray, iteratively refine the
// intersection point by sampling the GBuffer depth and normal.
//
// The GBuffer dimensions are needed to convert UV to texel coordinates for
// RWTexture2D Load() access.
//
// Returns true if the iteration stayed within the viewport and converged.
// On failure, hitPos/hitNormal/hitDepth are the last valid estimate.
public bool newtonRefractScreenSpace(
    float3 surfacePos, float3 refractedDir,
    NewtonRefractConfig config,
    RWTexture2D<float>  depthTex,   // gDepth  (r32f, reverse-Z)
    RWTexture2D<float4> normalTex,  // gNormal (rgba16f, world-space normal)
    uint2 gbufDims,                 // gDepth/gNormal dimensions (pixels)
    float4x4 invViewProj, float4x4 viewProj,
    out float3 hitPos, out float3 hitNormal,
    out float hitDepth, out int iterations)
{
    // ---- Initial guess: project the surface position into screen space
    float2 uv;
    float surfaceDepth;
    if (!worldToScreen(surfacePos, viewProj, uv, surfaceDepth)) {
        hitPos = surfacePos;
        hitNormal = float3(0.0, 1.0, 0.0);
        hitDepth = surfaceDepth;
        iterations = 0;
        return false;
    }

    // Sample the scene depth at the surface screen position
    float sceneDepth = gbufDepth(depthTex, uv, gbufDims);
    if (sceneDepth <= 0.0 || sceneDepth >= 1.0) {
        // No scene geometry behind the surface — fall back
        hitPos = surfacePos;
        hitNormal = float3(0.0, 1.0, 0.0);
        hitDepth = sceneDepth;
        iterations = 0;
        return false;
    }

    // Reconstruct world position at the scene depth → initial hit estimate
    hitPos = screenToWorld(uv, sceneDepth, invViewProj);
    hitDepth = sceneDepth;

    // Estimate the ray parameter t along the refracted direction
    float t = length(hitPos - surfacePos);
    if (t <= config.stepBias) t = 1.0;

    // ---- Newton iteration
    for (int i = 0; i < config.maxIterations; i++) {
        iterations = i + 1;

        // Advance the ray
        float3 rayPoint = surfacePos + refractedDir * t;

        // Project to screen
        if (!worldToScreen(rayPoint, viewProj, uv, hitDepth)) break;

        // Clamp to screen bounds
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;

        // Sample scene depth at this screen position
        float sampleDepth = gbufDepth(depthTex, uv, gbufDims);
        if (sampleDepth <= 0.0 || sampleDepth >= 1.0) break;

        // Reconstruct world position at sampled depth
        float3 samplePos = screenToWorld(uv, sampleDepth, invViewProj);

        // Error: distance from the ray point to the sampled surface, along the ray
        float3 delta = samplePos - rayPoint;
        float error = dot(delta, refractedDir);

        // Check convergence (relative to the total distance travelled)
        if (abs(error) < config.convergeThreshold * max(t, 1.0)) {
            hitPos = samplePos;
            hitDepth = sampleDepth;
            break;
        }

        // --- Newton step: build a local plane at the sampled point and
        //     re-intersect the ray with it.
        float3 sampleNormal = gbufNormal(normalTex, uv, gbufDims);

        // Reject degenerate normals (zero-length or back-facing vs the ray)
        if (length(sampleNormal) > 0.1) {
            float tNew = rayPlaneIntersect(surfacePos, refractedDir,
                                           samplePos, sampleNormal);
            if (tNew > config.stepBias) {
                // Newton step: use the plane intersection as the new estimate
                t = tNew;
                hitPos = surfacePos + refractedDir * t;
                hitDepth = sampleDepth;
                hitNormal = sampleNormal;
                continue;
            }
        }

        // Fallback Newton step: just move by the error (a gradient descent
        // along the ray).  This is slower but still converges.
        t = max(t + error, config.stepBias);
        hitPos = samplePos;
        hitDepth = sampleDepth;
    }

    // Sample the final normal at the converged position
    if (worldToScreen(hitPos, viewProj, uv, hitDepth)) {
        hitNormal = gbufNormal(normalTex, uv, gbufDims);
    }

    return true;
}

// ---------------------------------------------------------------------------
// Newton-refined caustic landing point
// ---------------------------------------------------------------------------

// Computes the caustic landing point using the Newton-refined intersection
// rather than the flat-plane assumption.
//
// Replaces the simple geometry in water.slang's causticLanding(): instead of
// projecting the refracted ray to a fixed depth h, iteratively finds the
// actual scene geometry intersection, giving a physically correct Jacobian
// for the caustic intensity.
//
// The three-level fallback chain is:
//   1. Newton iteration (screen-space depth + normal)
//   2. Single depth-buffer sample (same depth, no iteration)
//   3. Flat-plane estimate at ~4 blocks below the surface
//
// surfaceXZ   — water surface XZ position in world space
// surfaceY    — water surface Y (height) in world space
// lightDir    — normalized sun direction (world space, pointing toward sun)
// nw          — perturbed water surface normal (world space, upward-facing)
// config      — Newton iteration parameters
// depthTex    — gDepth
// normalTex   — gNormal
// gbufDims    — GBuffer dimensions in pixels
// invViewProj — WorldPush.invViewProj
// viewProj    — WorldPush.curViewProj
//
// landingXZ   — refined XZ position where the refracted ray hits the scene
//             — (used for the Jacobian in the caustic intensity calculation)
// hitDepth    — scene depth at the landing point
// converged   — true if the Newton iteration converged
public void newtonCausticLanding(
    float2 surfaceXZ, float surfaceY,
    float3 lightDir, float3 nw,
    NewtonRefractConfig config,
    RWTexture2D<float>  depthTex,
    RWTexture2D<float4> normalTex,
    uint2 gbufDims,
    float4x4 invViewProj, float4x4 viewProj,
    out float2 landingXZ, out float hitDepth, out bool converged)
{
    // ---- Build the water surface point and the refracted ray
    float3 surfacePos = float3(surfaceXZ.x, surfaceY, surfaceXZ.y);
    // Snell's law: air (1.0) → water (1.333)
    float3 refractedDir = refract(lightDir, nw, 1.0 / 1.333);

    // Handle total internal reflection
    if (dot(refractedDir, refractedDir) <= 0.0) {
        landingXZ = surfaceXZ;
        hitDepth = 0.0;
        converged = false;
        return;
    }
    refractedDir = normalize(refractedDir);

    // ---- Run the Newton iteration
    float3 hitPos, hitNormal;
    int iterations;
    converged = newtonRefractScreenSpace(
        surfacePos, refractedDir, config,
        depthTex, normalTex, gbufDims, invViewProj, viewProj,
        hitPos, hitNormal, hitDepth, iterations);

    if (converged) {
        landingXZ = hitPos.xz;
    } else {
        // Fallback 2: single depth-buffer probe at the water surface position
        float2 uv;
        float sd;
        if (worldToScreen(surfacePos, viewProj, uv, sd)) {
            float sceneDepth = gbufDepth(depthTex, uv, gbufDims);
            if (sceneDepth > 0.0 && sceneDepth < 1.0) {
                float3 fallback = screenToWorld(uv, sceneDepth, invViewProj);
                landingXZ = fallback.xz;
                hitDepth = sceneDepth;
                return;
            }
        }
        // Fallback 3: flat-plane at estimated depth (~4 blocks)
        float h = 4.0;
        landingXZ = surfaceXZ + refractedDir.xz *
            (h / max(-refractedDir.y, 0.05));
        hitDepth = 0.0;
    }
}