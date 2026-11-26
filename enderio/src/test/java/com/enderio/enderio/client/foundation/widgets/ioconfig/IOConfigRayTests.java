package com.enderio.enderio.client.foundation.widgets.ioconfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class IOConfigRayTests {
    @Test
    void localRayMatchesMovingTheShapeForEntryInsideAndMisses() {
        var pos = new BlockPos(11, 70, -2);
        var origin = new Vector3f(11.25f, 70.75f, -1.25f);
        var center = new Vector3f(11.5f, 70.5f, -1.5f).sub(origin);
        var shape = Shapes.box(0.125, 0, 0, 0.875, 0.5, 1);
        for (Vec3 start : new Vec3[]{new Vec3(1.6, 1, -1), new Vec3(1.6, 1, 1), new Vec3(0, 0, -1)}) {
            var end = start.add(0, 0, 4);
            var original = shape.move(center.x, center.y, center.z).clip(start, end, new BlockPos(1, 1, 1));
            var local = IOConfigOverlay.raycast(pos, shape, origin, start, end);
            if (original == null) {
                assertNull(local);
            } else {
                assertNotNull(local);
                assertEquals(original.getDirection(), local.getDirection());
                assertEquals(original.isInside(), local.isInside());
            }
        }
    }

    @Test
    void equalDistancesKeepOriginalHitMapOrderIncludingResize() {
        var positions = new ArrayList<BlockPos>();
        var hits = new BlockHitResult[20];
        var original = new HashMap<BlockHitResult, BlockPos>();
        var eye = new Vec3(0, 0.5, 0.5);
        for (int i = 0; i < hits.length; i++) {
            var pos = new BlockPos(i == 0 ? -1 : i - 1, 0, 0);
            positions.add(pos);
            hits[i] = new BlockHitResult(Vec3.atCenterOf(pos), Direction.values()[i % 6], pos, false);
            original.put(hits[i], pos);
        }
        var expected = original.entrySet().stream().min(Comparator.comparingDouble(e -> e.getValue().distToCenterSqr(eye))).orElseThrow();
        var actual = IOConfigOverlay.selectTiedFace(positions, hits, eye, 0.25).orElseThrow();
        assertEquals(expected.getValue(), actual.blockPos());
        assertEquals(expected.getKey().getDirection(), actual.side());
    }
}
