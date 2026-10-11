package com.packetscreen;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class VersionAdapter {

    private final ServerVersion version;
    private final EntityType frameType;
    private final boolean glow;
    private final boolean invisible;
    private final int itemIndex;
    private final int rotationIndex;

    VersionAdapter(ServerVersion version, Settings settings) {
        this.version = version;
        this.glow = supportsGlowFrames() && settings.frameType != Settings.FrameType.ITEM_FRAME;
        this.frameType = glow ? EntityTypes.GLOW_ITEM_FRAME : EntityTypes.ITEM_FRAME;
        this.invisible = settings.invisibleFrames;
        if (version.isOlderThan(ServerVersion.V_1_9)) {
            itemIndex = 8;
            rotationIndex = 9;
        } else if (version.isOlderThan(ServerVersion.V_1_10)) {
            itemIndex = 5;
            rotationIndex = 6;
        } else if (version.isOlderThan(ServerVersion.V_1_14)) {
            itemIndex = 6;
            rotationIndex = 7;
        } else if (version.isOlderThan(ServerVersion.V_1_17)) {
            itemIndex = 7;
            rotationIndex = 8;
        } else if (version.isOlderThan(ServerVersion.V_1_21_6)) {
            itemIndex = 8;
            rotationIndex = 9;
        } else {
            itemIndex = 9;
            rotationIndex = 10;
        }
    }

    ServerVersion version() {
        return version;
    }

    boolean supportsVerticalFacing() {
        return version.isNewerThanOrEquals(ServerVersion.V_1_13);
    }

    boolean supportsGlowFrames() {
        return version.isNewerThanOrEquals(ServerVersion.V_1_17);
    }

    boolean supportsInvisibleFrames() {
        return version.isNewerThanOrEquals(ServerVersion.V_1_16);
    }

    boolean supportsWideMapIds() {
        return version.isNewerThanOrEquals(ServerVersion.V_1_13);
    }

    String describe() {
        String type = glow ? "glow item frames" : "item frames";
        return invisible && supportsInvisibleFrames() ? "invisible " + type : type;
    }

    boolean sameRendering(VersionAdapter other) {
        return other != null && other.glow == glow && other.invisible == invisible;
    }

    WrapperPlayServerSpawnEntity spawn(int entityId, int x, int y, int z, Facing facing) {
        int data = version.isOlderThan(ServerVersion.V_1_13) ? facing.horizontalData : facing.data;
        UUID uuid = new UUID(0x5053000000000000L | (entityId & 0xFFFFFFFFL), ~(long) entityId);
        return new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), frameType, new Vector3d(x, y, z),
                0f, 0f, 0f, data, Optional.of(Vector3d.zero()));
    }

    WrapperPlayServerEntityMetadata metadata(int entityId, int mapId, int rotation) {
        List<EntityData<?>> data = new ArrayList<>(3);
        if (invisible) {
            data.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));
        }
        ItemStack item = mapItem(mapId);
        if (version.isOlderThan(ServerVersion.V_1_9)) {
            data.add(new EntityData<>(itemIndex, EntityDataTypes.ITEMSTACK, item));
            data.add(new EntityData<>(rotationIndex, EntityDataTypes.BYTE, (byte) rotation));
        } else if (version.isOlderThan(ServerVersion.V_1_11)) {
            data.add(new EntityData<>(itemIndex, EntityDataTypes.OPTIONAL_ITEMSTACK, Optional.of(item)));
            data.add(new EntityData<>(rotationIndex, EntityDataTypes.INT, rotation));
        } else {
            data.add(new EntityData<>(itemIndex, EntityDataTypes.ITEMSTACK, item));
            data.add(new EntityData<>(rotationIndex, EntityDataTypes.INT, rotation));
        }
        return new WrapperPlayServerEntityMetadata(entityId, data);
    }

    private ItemStack mapItem(int mapId) {
        ItemStack.Builder builder = ItemStack.builder().type(ItemTypes.FILLED_MAP).amount(1);
        if (version.isOlderThan(ServerVersion.V_1_13)) {
            builder.legacyData(mapId);
        } else if (version.isOlderThan(ServerVersion.V_1_20_5)) {
            builder.nbt("map", new NBTInt(mapId));
        } else {
            builder.component(ComponentTypes.MAP_ID, mapId);
        }
        return builder.build();
    }
}
