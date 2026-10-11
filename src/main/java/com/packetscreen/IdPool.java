package com.packetscreen;

import java.util.BitSet;

final class IdPool {

    private final int min;
    private final int max;
    private final BitSet used;
    private int allocated;

    IdPool(int min, int max) {
        this.min = min;
        this.max = max;
        this.used = new BitSet(max - min + 1);
    }

    synchronized int[] allocate(int count) {
        if (max - min + 1 - allocated < count) {
            return null;
        }
        int[] ids = new int[count];
        int cursor = max - min;
        for (int i = 0; i < count; i++) {
            int bit = used.previousClearBit(cursor);
            used.set(bit);
            ids[i] = min + bit;
            cursor = bit - 1;
        }
        allocated += count;
        return ids;
    }

    synchronized void release(int[] ids) {
        for (int id : ids) {
            if (contains(id) && used.get(id - min)) {
                used.clear(id - min);
                allocated--;
            }
        }
    }

    boolean contains(int id) {
        return id >= min && id <= max;
    }
}
