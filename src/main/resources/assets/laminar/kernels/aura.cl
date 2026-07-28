/* Copyright (c) 2026 Izumi17-mc. All rights reserved. */
__kernel void calculate_aura(
    __global const uchar* grid,
    __global const float4* mobPositions,
    __global uchar* visibilityResults,
    float pEyeX,
    float pEyeY,
    float pEyeZ,
    int diameter,
    int activeCount
) {
    int gid = get_global_id(0);
    if (gid >= activeCount) return;

    float4 mobPos = mobPositions[gid];
    float3 start = mobPos.xyz;
    float3 end = (float3)(pEyeX, pEyeY, pEyeZ);

    float3 dir = end - start;
    float dist = length(dir);

    if (dist < 0.01f) {
        visibilityResults[gid] = 1;
        return;
    }

    dir = normalize(dir);
    float step_size = 0.25f;
    int steps = (int)(dist / step_size);

    uchar result = 2;

    for (int i = 0; i <= steps; i++) {
        float3 current = start + dir * (i * step_size);
        int x = (int)floor(current.x);
        int y = (int)floor(current.y);
        int z = (int)floor(current.z);

        if (x < 0 || x >= diameter || y < 0 || y >= diameter || z < 0 || z >= diameter) {
            result = 2;
            break;
        }

        int idx = (x * diameter * diameter) + (y * diameter) + z;

        if (grid[idx] == 255) {
            result = 0; /* Diblokir solid */
            break;
        }

        if (i == steps) {
            result = 1; /* Terlihat */
        }
    }

    visibilityResults[gid] = result;
}