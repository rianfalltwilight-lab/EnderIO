package com.enderio.enderio.content.conduits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class ConduitLine {
    public static final int MAX_LENGTH = 64;

    /**
     * An axis-aligned route from start to end, both included: the longest axis first, then the others.
     */
    public static List<BlockPos> route(BlockPos start, BlockPos end) {
        var axes = Arrays.stream(Direction.Axis.values())
                .sorted(Comparator.comparingInt((Direction.Axis axis) -> -Math.abs(end.get(axis) - start.get(axis))))
                .toList();

        var route = new ArrayList<BlockPos>();
        var current = start;
        route.add(current);
        for (var axis : axes) {
            int delta = end.get(axis) - current.get(axis);
            var step = Direction.fromAxisAndDirection(axis,
                    delta >= 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            for (int i = 0; i < Math.abs(delta); i++) {
                current = current.relative(step);
                route.add(current);
            }
        }

        return route;
    }

    private ConduitLine() {
    }
}
