/* Copyright (c) 2026 Izumi17-mc. All rights reserved. */
__kernel void initialize_integration_field(
    __global uint* integrationField,
    const int size)
{
    int gid = get_global_id(0);
    int total_cells = size * size * size;
    if (gid >= total_cells) return;

    integrationField[gid] = 999999;
}
__kernel void solve_integration_field(
    __global const uchar* costField,
    __global uint* integrationField,
    const int size,
    const int targetIdx,
    volatile __global int* convergenceFlag)
{
    int gid = get_global_id(0);
    int total_cells = size * size * size;
    if (gid >= total_cells) return;

    if (gid == targetIdx) {
        integrationField[gid] = 0;
        return;
    }

    if (costField[gid] == 255) {
        integrationField[gid] = 999999;
        return;
    }

    int x = gid / (size * size);
    int y = (gid / size) % size;
    int z = gid % size;

    uint currentVal = integrationField[gid];
    uint minNeighborVal = currentVal;

    int neighbors[6] = {
        (x > 0) ? gid - (size * size) : -1,
        (x < size - 1) ? gid + (size * size) : -1,
        (y > 0) ? gid - size : -1,
        (y < size - 1) ? gid + size : -1,
        (z > 0) ? gid - 1 : -1,
        (z < size - 1) ? gid + 1 : -1
    };

    for (int i = 0; i < 6; i++) {
        int nid = neighbors[i];
        if (nid != -1 && costField[nid] != 255) {
            uint nVal = integrationField[nid];
            if (nVal < minNeighborVal) {
                minNeighborVal = nVal;
            }
        }
    }

    uint stepCost = (uint)costField[gid];

    if (minNeighborVal + stepCost < currentVal) {
        integrationField[gid] = minNeighborVal + stepCost;

        atomic_xchg(convergenceFlag, 1);
    }
}

__kernel void generate_flow_field(
    __global const uint* integrationField,
    __global char* vectorField,
    const int size)
{
    int gid = get_global_id(0);
    int total_cells = size * size * size;
    if (gid >= total_cells) return;

    int x = gid / (size * size);
    int y = (gid / size) % size;
    int z = gid % size;

    uint currentVal = integrationField[gid];

    if (currentVal >= 999999) {
        vectorField[gid * 3] = 0;
        vectorField[gid * 3 + 1] = 0;
        vectorField[gid * 3 + 2] = 0;
        return;
    }

    uint west_val = (x > 0) ? integrationField[gid - (size * size)] : currentVal;
    uint east_val = (x < size - 1) ? integrationField[gid + (size * size)] : currentVal;
    char dx = 0;
    if (west_val < east_val && west_val < currentVal) dx = -1;
    else if (east_val < west_val && east_val < currentVal) dx = 1;

    uint back_val = (z > 0) ? integrationField[gid - 1] : currentVal;
    uint front_val = (z < size - 1) ? integrationField[gid + 1] : currentVal;
    char dz = 0;
    if (back_val < front_val && back_val < currentVal) dz = -1;
    else if (front_val < back_val && front_val < currentVal) dz = 1;

    char dy = 0;
    if (dx != 0 || dz != 0) {
        uint down_val = (y > 0) ? integrationField[gid - size] : currentVal;
        uint up_val = (y < size - 1) ? integrationField[gid + size] : currentVal;
        if (down_val < up_val && down_val < currentVal) dy = -1;
        else if (up_val < down_val && up_val < currentVal) dy = 1;
    }

    vectorField[gid * 3] = dx;
    vectorField[gid * 3 + 1] = dy;
    vectorField[gid * 3 + 2] = dz;
}