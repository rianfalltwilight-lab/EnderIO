package com.enderio.enderio.content.conduits;

import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConnectionStatus;
import com.enderio.enderio.api.conduits.bundle.AddConduitResult;
import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Upgrades the rest of a run of conduits after its first segment was upgraded by hand while sneaking.
 */
public final class ConduitRunUpgrade {
    public static final int MAX_SEGMENTS = 256;

    /** Positions of the connected segments that still hold {@code old}, nearest first. Call before upgrading. */
    public static List<BlockPos> connectedRun(ConduitBundleBlockEntity start, Holder<Conduit<?, ?>> old) {
        var level = start.getLevel();
        var found = new ArrayList<BlockPos>();
        var visited = new HashSet<BlockPos>();
        var queue = new ArrayDeque<ConduitBundleBlockEntity>();
        queue.add(start);
        visited.add(start.getBlockPos());

        while (level != null && !queue.isEmpty() && found.size() < MAX_SEGMENTS) {
            var bundle = queue.poll();
            for (Direction side : Direction.values()) {
                if (bundle.getConnectionStatus(old, side) != ConnectionStatus.CONNECTED_CONDUIT) {
                    continue;
                }

                var pos = bundle.getBlockPos().relative(side);
                if (visited.add(pos) && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ConduitBundleBlockEntity next
                        && next.hasConduitStrict(old)) {
                    found.add(pos);
                    queue.add(next);
                }
            }
        }

        return found.size() > MAX_SEGMENTS ? found.subList(0, MAX_SEGMENTS) : found;
    }

    /** @return how many of the segments were upgraded, limited by the items held. */
    public static int upgrade(List<BlockPos> run, Holder<Conduit<?, ?>> conduit, Holder<Conduit<?, ?>> old, ItemStack stack,
            Player player) {
        var level = player.level();
        boolean creative = player.getAbilities().instabuild;
        int upgraded = 0;
        for (var pos : run) {
            if (!creative && stack.isEmpty()) {
                break;
            }

            if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, Direction.UP, stack)
                    || !(level.getBlockEntity(pos) instanceof ConduitBundleBlockEntity bundle) || !bundle.hasConduitStrict(old)
                    || !bundle.canAddConduit(conduit)) {
                continue;
            }

            if (bundle.addConduit(conduit, null, player) instanceof AddConduitResult.Upgrade(Holder<Conduit<?, ?>> replaced)) {
                upgraded++;
                if (!creative) {
                    stack.shrink(1);
                    player.getInventory().placeItemBackInInventory(ConduitBlockItem.getStackFor(replaced, 1));
                }
            }
        }

        return upgraded;
    }

    private ConduitRunUpgrade() {
    }
}
