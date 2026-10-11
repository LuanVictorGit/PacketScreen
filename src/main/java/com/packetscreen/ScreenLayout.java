package com.packetscreen;

final class ScreenLayout {

    static final Facing[] SIDES = {Facing.SOUTH, Facing.WEST, Facing.NORTH, Facing.EAST};
    private static final double KEEP_SIDE = 0.6;

    final TileSet tiles;
    final String world;
    final int anchorX;
    final int anchorY;
    final int anchorZ;
    final Facing facing;
    final int rotation;
    final int[] blockX;
    final int[] blockY;
    final int[] blockZ;
    final double centerX;
    final double centerY;
    final double centerZ;
    final double radius;

    ScreenLayout(TileSet tiles, String world, int anchorX, int anchorY, int anchorZ, Facing facing, int rotation) {
        this.tiles = tiles;
        this.world = world;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.facing = facing;
        this.rotation = rotation & 3;
        int[] right = facing.right(this.rotation);
        int[] down = facing.down(this.rotation);
        int count = tiles.size();
        blockX = new int[count];
        blockY = new int[count];
        blockZ = new int[count];
        for (int row = 0; row < tiles.rows; row++) {
            int up = tiles.rows - 1 - row;
            for (int column = 0; column < tiles.columns; column++) {
                int index = row * tiles.columns + column;
                blockX[index] = anchorX + column * right[0] - up * down[0];
                blockY[index] = anchorY + column * right[1] - up * down[1];
                blockZ[index] = anchorZ + column * right[2] - up * down[2];
            }
        }
        int last = count - 1;
        centerX = (blockX[0] + blockX[last]) / 2.0 + 0.5;
        centerY = (blockY[0] + blockY[last]) / 2.0 + 0.5;
        centerZ = (blockZ[0] + blockZ[last]) / 2.0 + 0.5;
        radius = Math.sqrt(tiles.columns * tiles.columns + tiles.rows * tiles.rows) / 2.0;
    }

    boolean matches(String world, int x, int y, int z, Facing facing, int rotation) {
        return this.world.equals(world) && anchorX == x && anchorY == y && anchorZ == z
                && this.facing == facing && this.rotation == (rotation & 3);
    }

    ScreenLayout[] turns() {
        ScreenLayout[] turns = new ScreenLayout[SIDES.length];
        double x = centerX - 0.5;
        double y = centerY - 0.5;
        double z = centerZ - 0.5;
        double across = (tiles.columns - 1) / 2.0;
        double up = (tiles.rows - 1) / 2.0;
        for (int side = 0; side < SIDES.length; side++) {
            Facing turnFacing = facing;
            int turnRotation = rotation;
            if (facing.vertical()) {
                Facing ahead = SIDES[side].opposite();
                turnRotation = facing.uprightRotation(facing == Facing.UP ? ahead : ahead.opposite());
            } else {
                turnFacing = SIDES[side];
            }
            if (turnFacing == facing && turnRotation == rotation) {
                turns[side] = this;
                continue;
            }
            int[] right = turnFacing.right(turnRotation);
            int[] down = turnFacing.down(turnRotation);
            turns[side] = new ScreenLayout(tiles, world, (int) Math.round(x - across * right[0] + up * down[0]),
                    (int) Math.round(y - across * right[1] + up * down[1]),
                    (int) Math.round(z - across * right[2] + up * down[2]), turnFacing, turnRotation);
        }
        return turns;
    }

    int side(int current, double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (current >= 0 && (length < 1e-6
                || (dx * SIDES[current].normalX + dz * SIDES[current].normalZ) / length >= KEEP_SIDE)) {
            return current;
        }
        if (length < 1e-6) {
            for (int side = 0; side < SIDES.length; side++) {
                if (SIDES[side] == facing) {
                    return side;
                }
            }
            return 0;
        }
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? 3 : 1;
        }
        return dz > 0 ? 0 : 2;
    }
}
