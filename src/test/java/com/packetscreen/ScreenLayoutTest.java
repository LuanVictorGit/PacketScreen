package com.packetscreen;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenLayoutTest {

    private static ScreenLayout layout(Facing facing, int rotation, int columns, int rows) {
        TileSet tiles = new TileSet(columns, rows, new int[columns * rows], new int[columns * rows], new int[columns * rows], new int[3]);
        return new ScreenLayout(tiles, "world", 10, 64, 20, facing, rotation);
    }

    private static int[] block(ScreenLayout layout, int column, int row) {
        int index = row * layout.tiles.columns + column;
        return new int[]{layout.blockX[index], layout.blockY[index], layout.blockZ[index]};
    }

    @Test
    void southFacingGrowsEastAndUpFromTheBottomLeft() {
        ScreenLayout layout = layout(Facing.SOUTH, 0, 3, 2);
        assertArrayEquals(new int[]{10, 64, 20}, block(layout, 0, 1));
        assertArrayEquals(new int[]{12, 64, 20}, block(layout, 2, 1));
        assertArrayEquals(new int[]{10, 65, 20}, block(layout, 0, 0));
        assertArrayEquals(new int[]{12, 65, 20}, block(layout, 2, 0));
    }

    @Test
    void wallFacingsGrowToTheViewersRight() {
        assertArrayEquals(new int[]{8, 64, 20}, block(layout(Facing.NORTH, 0, 3, 2), 2, 1));
        assertArrayEquals(new int[]{10, 64, 18}, block(layout(Facing.EAST, 0, 3, 2), 2, 1));
        assertArrayEquals(new int[]{10, 64, 22}, block(layout(Facing.WEST, 0, 3, 2), 2, 1));
    }

    @Test
    void floorScreensPointTheirTopNorthWithoutRotation() {
        ScreenLayout layout = layout(Facing.UP, 0, 3, 2);
        assertArrayEquals(new int[]{10, 64, 20}, block(layout, 0, 1));
        assertArrayEquals(new int[]{10, 64, 19}, block(layout, 0, 0));
        assertArrayEquals(new int[]{12, 64, 20}, block(layout, 2, 1));
    }

    @Test
    void ceilingScreensPointTheirTopSouthWithoutRotation() {
        ScreenLayout layout = layout(Facing.DOWN, 0, 2, 2);
        assertArrayEquals(new int[]{10, 64, 21}, block(layout, 0, 0));
    }

    @Test
    void rotationTurnsTheImageClockwiseAroundTheAnchor() {
        ScreenLayout layout = layout(Facing.SOUTH, 1, 2, 2);
        assertArrayEquals(new int[]{10, 64, 20}, block(layout, 0, 1));
        assertArrayEquals(new int[]{10, 63, 20}, block(layout, 1, 1));
        assertArrayEquals(new int[]{11, 64, 20}, block(layout, 0, 0));
        assertEquals(1, layout.rotation);
    }

    @Test
    void uprightRotationMatchesTheRequestedTop() {
        assertEquals(0, Facing.UP.uprightRotation(Facing.NORTH));
        assertEquals(1, Facing.UP.uprightRotation(Facing.EAST));
        assertEquals(2, Facing.UP.uprightRotation(Facing.SOUTH));
        assertEquals(3, Facing.UP.uprightRotation(Facing.WEST));
        assertEquals(0, Facing.DOWN.uprightRotation(Facing.SOUTH));
        assertEquals(0, Facing.NORTH.uprightRotation(Facing.UP));
    }

    @Test
    void yawMapsToTheFacedDirection() {
        assertEquals(Facing.SOUTH, Facing.fromYaw(0));
        assertEquals(Facing.WEST, Facing.fromYaw(90));
        assertEquals(Facing.NORTH, Facing.fromYaw(180));
        assertEquals(Facing.EAST, Facing.fromYaw(-90));
        assertEquals(Facing.EAST, Facing.fromYaw(270));
        assertEquals(Facing.NORTH, Facing.SOUTH.opposite());
    }

    @Test
    void centerAndRadiusCoverTheWholeScreen() {
        ScreenLayout layout = layout(Facing.SOUTH, 0, 4, 2);
        assertEquals(12.0, layout.centerX, 1e-9);
        assertEquals(65.0, layout.centerY, 1e-9);
        assertEquals(Math.sqrt(20) / 2, layout.radius, 1e-9);
    }

    @Test
    void footprintCoversTheWholeBoxOfTheLayout() {
        ScreenLayout layout = layout(Facing.EAST, 1, 4, 3);
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < layout.blockX.length; i++) {
            expected.add(ScreenManager.key(layout.blockX[i], layout.blockY[i], layout.blockZ[i]));
        }
        assertEquals(expected, ScreenManager.footprint(10, 64, 20, Facing.EAST, 1, 4, 2.25));
    }

    @Test
    void footprintsDetectOverlapsOnly() {
        Set<Long> left = ScreenManager.footprint(-13, -60, -5, Facing.SOUTH, 0, 3, 2);
        Set<Long> right = ScreenManager.footprint(-10, -60, -5, Facing.SOUTH, 0, 3, 2);
        Set<Long> above = ScreenManager.footprint(-13, -58, -5, Facing.SOUTH, 0, 3, 2);
        Set<Long> shifted = ScreenManager.footprint(-11, -59, -5, Facing.SOUTH, 0, 3, 2);
        Set<Long> mirrored = ScreenManager.footprint(13, -60, 5, Facing.SOUTH, 0, 3, 2);
        assertEquals(6, left.size());
        assertTrue(Collections.disjoint(left, right));
        assertTrue(Collections.disjoint(left, above));
        assertTrue(Collections.disjoint(left, mirrored));
        assertFalse(Collections.disjoint(left, shifted));
        assertFalse(Collections.disjoint(left, ScreenManager.footprint(-13, -60, -5, Facing.SOUTH, 0, 1, 1)));
    }

    @Test
    void hologramTurnsKeepTheCenterAndFaceEverySide() {
        ScreenLayout layout = layout(Facing.SOUTH, 0, 3, 2);
        ScreenLayout[] turns = layout.turns();
        assertEquals(layout, turns[0]);
        assertEquals(Facing.WEST, turns[1].facing);
        assertEquals(Facing.NORTH, turns[2].facing);
        assertEquals(Facing.EAST, turns[3].facing);
        for (ScreenLayout turn : turns) {
            assertEquals(layout.centerY, turn.centerY, 1e-9);
            assertTrue(Math.abs(turn.centerX - layout.centerX) <= 1.0, "x " + turn.centerX);
            assertTrue(Math.abs(turn.centerZ - layout.centerZ) <= 1.0, "z " + turn.centerZ);
        }
    }

    @Test
    void floorHologramsRotateTheImageForEachSide() {
        ScreenLayout layout = layout(Facing.UP, 0, 2, 2);
        ScreenLayout[] turns = layout.turns();
        Set<Integer> rotations = new HashSet<>();
        for (ScreenLayout turn : turns) {
            assertEquals(Facing.UP, turn.facing);
            assertEquals(layout.centerX, turn.centerX, 1e-9);
            assertEquals(layout.centerZ, turn.centerZ, 1e-9);
            rotations.add(turn.rotation);
        }
        assertEquals(4, rotations.size());
    }

    @Test
    void sideFollowsTheViewerWithoutFlickering() {
        ScreenLayout layout = layout(Facing.SOUTH, 0, 1, 1);
        double x = layout.centerX;
        double z = layout.centerZ;
        assertEquals(0, layout.side(-1, x, z + 5));
        assertEquals(3, layout.side(-1, x + 5, z));
        assertEquals(1, layout.side(-1, x - 5, z));
        assertEquals(2, layout.side(-1, x, z - 5));
        assertEquals(0, layout.side(0, x + Math.tan(Math.toRadians(50)) * 5, z + 5));
        assertEquals(3, layout.side(0, x + Math.tan(Math.toRadians(60)) * 5, z + 5));
    }
}
