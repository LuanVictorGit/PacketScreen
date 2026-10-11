package com.packetscreen;

import java.util.Locale;

enum Facing {
    NORTH(0, 0, -1, 2, 2, new int[]{-1, 0, 0}, new int[]{0, -1, 0}),
    SOUTH(0, 0, 1, 0, 3, new int[]{1, 0, 0}, new int[]{0, -1, 0}),
    WEST(-1, 0, 0, 1, 4, new int[]{0, 0, 1}, new int[]{0, -1, 0}),
    EAST(1, 0, 0, 3, 5, new int[]{0, 0, -1}, new int[]{0, -1, 0}),
    UP(0, 1, 0, -1, 1, new int[]{1, 0, 0}, new int[]{0, 0, 1}),
    DOWN(0, -1, 0, -1, 0, new int[]{1, 0, 0}, new int[]{0, 0, -1});

    final int normalX;
    final int normalY;
    final int normalZ;
    final int horizontalData;
    final int data;
    private final int[][][] bases = new int[4][][];

    Facing(int normalX, int normalY, int normalZ, int horizontalData, int data, int[] right, int[] down) {
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.horizontalData = horizontalData;
        this.data = data;
        int[] r = right;
        int[] d = down;
        for (int rotation = 0; rotation < 4; rotation++) {
            bases[rotation] = new int[][]{r, d};
            int[] next = new int[]{-r[0], -r[1], -r[2]};
            r = d;
            d = next;
        }
    }

    boolean vertical() {
        return normalY != 0;
    }

    int[] right(int rotation) {
        return bases[rotation & 3][0];
    }

    int[] down(int rotation) {
        return bases[rotation & 3][1];
    }

    int uprightRotation(Facing top) {
        for (int rotation = 0; rotation < 4; rotation++) {
            int[] d = down(rotation);
            if (-d[0] == top.normalX && -d[1] == top.normalY && -d[2] == top.normalZ) {
                return rotation;
            }
        }
        return 0;
    }

    Facing opposite() {
        return fromNormal(-normalX, -normalY, -normalZ);
    }

    static Facing fromNormal(int x, int y, int z) {
        for (Facing facing : values()) {
            if (facing.normalX == x && facing.normalY == y && facing.normalZ == z) {
                return facing;
            }
        }
        return null;
    }

    static Facing fromYaw(float yaw) {
        switch ((int) Math.floor(yaw / 90.0 + 0.5) & 3) {
            case 0:
                return SOUTH;
            case 1:
                return WEST;
            case 2:
                return NORTH;
            default:
                return EAST;
        }
    }

    static Facing parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
