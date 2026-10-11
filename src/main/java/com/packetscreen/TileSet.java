package com.packetscreen;

final class TileSet {

    final int columns;
    final int rows;
    final int[] mapIds;
    final int[] entityIds;
    final int[] backEntityIds;
    final int[] backMapIds;

    TileSet(int columns, int rows, int[] mapIds, int[] entityIds, int[] backEntityIds, int[] backMapIds) {
        this.columns = columns;
        this.rows = rows;
        this.mapIds = mapIds;
        this.entityIds = entityIds;
        this.backEntityIds = backEntityIds;
        this.backMapIds = backMapIds;
    }

    int size() {
        return mapIds.length;
    }

    boolean matches(int columns, int rows) {
        return this.columns == columns && this.rows == rows;
    }
}
