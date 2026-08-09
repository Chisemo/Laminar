/* Copyright (c) 2026 Izumi17-mc. All rights reserved. */
#define AIR_BASE_COST   0.25f
#define LIQUID_COST     1.2f
#define LAVA_COST       3.5f
#define SOLID_COST      5.0f

__kernel void calculate_acoustics(
    __global const uchar* costField,
    __global float* acousticResults,
    const int size,
    const int soundSlotIdx,
    const float srcX, const float srcY, const float srcZ,
    const float playX, const float playY, const float playZ,
    const float maxRange,
    const float airDecay,
    const float materialPenetration)
{
    int gid = get_global_id(0);
    int lid = get_local_id(0);

    if (lid >= 16) return;

    __local float local_distances[16];
    local_distances[lid] = 0.0f;

    barrier(CLK_LOCAL_MEM_FENCE);

    if (lid == 0) {
        float3 start_pos = (float3)(playX, playY, playZ);
        float3 end_pos   = (float3)(srcX, srcY, srcZ);

        float3 ray_delta = end_pos - start_pos;
        float  max_dist  = length(ray_delta);
        float  occlusion;

        if (max_dist <= 0.1f) {
            occlusion = 1.0f;
        } else if (maxRange <= 0.1f) {
            occlusion = 0.0f;
        } else {
            float3 ray_dir     = normalize(ray_delta);
            float  step_size   = 0.5f;
            float  penetration = max(materialPenetration, 0.05f);

            float pathCost = max_dist * airDecay * AIR_BASE_COST;

            for (float dist = step_size; dist < max_dist; dist += step_size) {
                float3 ray_pos = start_pos + ray_dir * dist;

                int vx = (int)floor(ray_pos.x);
                int vy = (int)floor(ray_pos.y);
                int vz = (int)floor(ray_pos.z);

                if (vx < 0 || vx >= size || vy < 0 || vy >= size || vz < 0 || vz >= size) {
                    break;
                }

                int idx = (vx * size * size) + (vy * size) + vz;
                uchar c = costField[idx];

                if (c == 255) {
                    pathCost += step_size * (SOLID_COST / penetration);
                } else if (c == 254) {
                    pathCost += step_size * (LAVA_COST / penetration);
                } else if (c == 25) {
                    pathCost += step_size * (LIQUID_COST / penetration);
                }

                if (pathCost >= maxRange) {
                    pathCost = maxRange;
                    break;
                }
            }

            float t = clamp(pathCost / maxRange, 0.0f, 1.0f);
            occlusion = 1.0f - (t * t * (3.0f - 2.0f * t)); 
        }

        acousticResults[soundSlotIdx * 2] = occlusion;
    }
    else {
        // Fibonacci Sphere (Sisa 15 thread) — reverb probe
        float i        = (float)lid;
        float z        = 1.0f - (i / 15.0f) * 2.0F;
        float radius   = sqrt(1.0f - z * z);
        float theta    = i * 2.39996322978f;

        float3 ray_dir = normalize((float3)(cos(theta) * radius, sin(theta) * radius, z));

        float step_size         = 0.5f;
        float distance_traveled = 0.0f;
        bool hit_wall           = false; // PERBAIKAN: Sensor penanda benturan fisik dinding

        for (int step = 1; step <= 96; step++) {
            float t        = step * step_size;
            float3 ray_pos = (float3)(srcX, srcY, srcZ) + ray_dir * t;

            int vx = (int)floor(ray_pos.x);
            int vy = (int)floor(ray_pos.y);
            int vz = (int)floor(ray_pos.z);

            if (vx < 0 || vx >= size || vy < 0 || vy >= size || vz < 0 || vz >= size) {
                break; // Keluar dari grid voxel (Lolos bebas ke langit terbuka)
            }

            int idx = (vx * size * size) + (vy * size) + vz;
            if (costField[idx] == 255) {
                hit_wall = true; // Secara fisik menabrak rintangan padat/dinding gua
                break;
            }

            distance_traveled = t;
        }

        // JIKA MENABRAK TEMBOK: Catat jarak benturannya untuk gema fisis
        // JIKA LOLOS KE LANGIT: Gema ditiadakan karena gelombang suara lepas bebas
        if (hit_wall) {
            local_distances[lid] = distance_traveled;
        } else {
            local_distances[lid] = 0.0f;
        }
    }

    barrier(CLK_LOCAL_MEM_FENCE);

    if (lid == 0) {
        float total_reverb_dist = 0.0f;
        for (int r = 1; r < 16; r++) {
            total_reverb_dist += local_distances[r];
        }

        float avg_room_size = total_reverb_dist / 15.0f;
        float reverb = avg_room_size / 24.0f;
        if (reverb > 1.0f) reverb = 1.0f;
        if (reverb < 0.05f) reverb = 0.0f;

        acousticResults[soundSlotIdx * 2 + 1] = reverb;
    }
}