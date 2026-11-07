package com.extantape.waystation;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;

/** One gate, as stored in gates.yml. (x, y, z) is the block the middle of its bottom edge stands in. */
final class Gate {
    final String id;
    String world;
    int x, y, z;
    BlockFace facing;
    String destination;
    String label;   // null: use the destination's label
    String look;    // null: use the destination's look
    boolean enabled = true;
    long restUntil; // when this gate may be moved again, in epoch milliseconds
    String link;    // id of the paired gate, or null
    java.util.List<String> palette = java.util.List.of(); // blocks sampled from the destination world
    String group;   // the destination's world group when last seen, so a dead gate keeps its platform

    Gate(String id, String world, int x, int y, int z, BlockFace facing, String destination) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
        this.destination = destination;
    }

    /** Fargates lead to another server, named after this prefix, instead of to a world. */
    static final String FARGATE_PREFIX = "fargate:";

    boolean isFargate() {
        return destination.startsWith(FARGATE_PREFIX);
    }

    String fargateName() {
        return destination.substring(FARGATE_PREFIX.length());
    }

    /** True when the plane of the gate runs east-west (you walk through it going north or south). */
    boolean alongX() {
        return facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
    }

    /** The trigger area: one block deep, as wide and tall as the opening. */
    BoundingBox trigger(Settings.Look look) {
        double half = look.width() / 2.0;
        double cx = x + 0.5, cz = z + 0.5;
        return alongX()
            ? new BoundingBox(cx - half, y, cz - 0.5, cx + half, y + look.height(), cz + 0.5)
            : new BoundingBox(cx - 0.5, y, cz - half, cx + 0.5, y + look.height(), cz + half);
    }
}
