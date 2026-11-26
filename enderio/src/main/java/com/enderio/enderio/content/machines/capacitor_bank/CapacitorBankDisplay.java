package com.enderio.enderio.content.machines.capacitor_bank;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Arrays;
import java.util.WeakHashMap;

/** Client display dependencies. A face can consult bank blocks up to sixteen positions away. */
public final class CapacitorBankDisplay {
    private static final Map<Level, Index> LEVELS = new WeakHashMap<>();
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static final class Index {
        final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        final Long2IntOpenHashMap occluders = new Long2IntOpenHashMap();
    }

    private static final class Section {
        int banks;
        long revision;
    }

    private final Index index;
    private final Section section;
    private final BlockPos pos;
    private final Face[] faces = new Face[4];
    private long revision = -1;

    CapacitorBankDisplay(Level level, BlockPos pos) {
        this.pos = pos;
        index = LEVELS.computeIfAbsent(level, ignored -> new Index());
        section = index.sections.computeIfAbsent(SectionPos.asLong(pos), ignored -> new Section());
        section.banks++;
        for (var direction : HORIZONTAL) {
            index.occluders.addTo(pos.relative(direction).asLong(), 1);
        }
        changed();
    }

    void removed() {
        changed();
        for (var direction : HORIZONTAL) {
            long occluder = pos.relative(direction).asLong();
            if (index.occluders.addTo(occluder, -1) == 1) {
                index.occluders.remove(occluder);
            }
        }
        if (--section.banks == 0) {
            index.sections.remove(SectionPos.asLong(pos));
        }
    }

    void changed() {
        invalidate(index, pos);
    }

    public static void blockChanged(Level level, BlockPos pos) {
        var index = LEVELS.get(level);
        if (index != null && index.occluders.containsKey(pos.asLong())) {
            invalidate(index, pos);
        }
    }

    public static void chunkChanged(Level level, ChunkPos chunk) {
        var index = LEVELS.get(level);
        if (index == null || index.sections.isEmpty()) {
            return;
        }
        if (index.sections.size() <= 25 * (level.getMaxSection() - level.getMinSection())) {
            for (var iterator = index.sections.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
                var entry = iterator.next();
                long key = entry.getLongKey();
                if (Math.abs(SectionPos.x(key) - chunk.x) <= 2 && Math.abs(SectionPos.z(key) - chunk.z) <= 2) {
                    entry.getValue().revision++;
                }
            }
            return;
        }
        for (int x = chunk.x - 2; x <= chunk.x + 2; x++) {
            for (int z = chunk.z - 2; z <= chunk.z + 2; z++) {
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
                    var section = index.sections.get(SectionPos.asLong(x, y, z));
                    if (section != null) {
                        section.revision++;
                    }
                }
            }
        }
    }

    private static void invalidate(Index index, BlockPos changed) {
        // A bank may inspect the occluder in front of a display on the edge of its 16-block search.
        for (int x = (changed.getX() - 17) >> 4; x <= (changed.getX() + 17) >> 4; x++) {
            for (int z = (changed.getZ() - 17) >> 4; z <= (changed.getZ() + 17) >> 4; z++) {
                for (int y = (changed.getY() - 16) >> 4; y <= (changed.getY() + 16) >> 4; y++) {
                    var section = index.sections.get(SectionPos.asLong(x, y, z));
                    if (section != null) {
                        section.revision++;
                    }
                }
            }
        }
    }

    public record Face(DisplayMode mode, int barLength, Size size) {}
    public record Size(int x0, int y0, int x1, int y1) {
        public boolean ownsText() {
            return (-x0 == x1 || -x0 + 1 == x1) && (-y0 == y1 || -y0 + 1 == y1);
        }
    }
    private static final Size SINGLE = new Size(0, 0, 0, 0);
    private static final Face NONE = new Face(DisplayMode.NONE, 0, SINGLE);

    public long revision() {
        return section.revision;
    }

    public Face face(CapacitorBankBlockEntity bank, Direction facing) {
        if (revision != section.revision) {
            Arrays.fill(faces, null);
            revision = section.revision;
        }
        int i = facing.get2DDataValue();
        if (faces[i] == null) {
            faces[i] = compute(bank, facing);
        }
        return faces[i];
    }

    private static Face compute(CapacitorBankBlockEntity bank, Direction facing) {
        var mode = bank.getDisplayMode(facing);
        if (mode == DisplayMode.BAR) {
            int length = 0;
            if (relative(bank, facing, 0, -1) != DisplayMode.BAR) {
                length = 1;
                while (length < 16 && relative(bank, facing, 0, length) == DisplayMode.BAR) {
                    length++;
                }
            }
            return new Face(mode, length, SINGLE);
        }
        if (mode == DisplayMode.IO) {
            return new Face(mode, 0, findSize(bank, facing));
        }
        return NONE;
    }

    private static DisplayMode relative(CapacitorBankBlockEntity bank, Direction facing, int x, int y) {
        var pos = bank.getBlockPos().below(y).relative(facing.getClockWise(), -x);
        if (bank.getLevel().getBlockEntity(pos) instanceof CapacitorBankBlockEntity other) {
            return other.getDisplayMode(facing);
        }
        return DisplayMode.NONE;
    }

    private static Size findSize(CapacitorBankBlockEntity bank, Direction facing) {
        int left = 0, right = 0, top = 0, bottom = 0;
        while (right < 15 && relative(bank, facing, right + 1, 0) == DisplayMode.IO) right++;
        while (right - left < 15 && relative(bank, facing, left - 1, 0) == DisplayMode.IO) left--;
        while (bottom < 15 && relative(bank, facing, left, bottom + 1) == DisplayMode.IO) {
            if (!fullRow(bank, facing, left, right, bottom + 1)) return SINGLE;
            bottom++;
        }
        while (bottom - top < 15 && relative(bank, facing, left, top - 1) == DisplayMode.IO) {
            if (!fullRow(bank, facing, left, right, top - 1)) return SINGLE;
            top--;
        }
        for (int x = left; x <= right; x++) {
            if (relative(bank, facing, x, top - 1) == DisplayMode.IO || relative(bank, facing, x, bottom + 1) == DisplayMode.IO) return SINGLE;
        }
        for (int y = top; y <= bottom; y++) {
            if (relative(bank, facing, left - 1, y) == DisplayMode.IO || relative(bank, facing, right + 1, y) == DisplayMode.IO) return SINGLE;
        }
        return new Size(left, top, right, bottom);
    }

    private static boolean fullRow(CapacitorBankBlockEntity bank, Direction facing, int left, int right, int y) {
        for (int x = left + 1; x <= right; x++) {
            if (relative(bank, facing, x, y) != DisplayMode.IO) return false;
        }
        return true;
    }
}
