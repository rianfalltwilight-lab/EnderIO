package com.enderio.enderio.content.conduits.probe;

import com.enderio.core.common.util.TooltipUtil;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.config.IOConnectionConfig;
import com.enderio.enderio.content.conduits.ConduitLang;
import com.enderio.enderio.content.conduits.network.ConduitNetworkImpl;
import com.enderio.enderio.content.conduits.network.ConduitNodeImpl;
import com.enderio.enderio.content.conduits.type.item.ItemConduitNodeData;
import com.enderio.enderio.content.conduits.type.item.ItemTransferStatus;
import com.enderio.enderio.init.EIOConduitTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the report shown by the conduit probe: the size of a network and, for one connection, why it is or is not
 * moving anything.
 */
public final class ConduitDiagnostics {

    public static List<Component> describe(Holder<Conduit<?, ?>> conduit, ConduitNodeImpl node, @Nullable Direction side,
            long gameTime) {
        var lines = new ArrayList<Component>();
        var network = node.getNetwork();
        lines.add(conduit.value().description().copy().withStyle(ChatFormatting.AQUA));

        if (!network.conduitType().doesRequireNetworkCaches()) {
            lines.add(line(ConduitLang.PROBE_NODES, network.nodeCount()));
            return lines;
        }

        network.ensureCachesReady();
        lines.add(line(ConduitLang.PROBE_NETWORK, network.nodeCount(), network.extractConnections().size(),
                network.insertConnections().size()));

        if (side != null && node.isConnectedToBlock(side)) {
            describeConnection(lines, network, node, side, gameTime);
        }

        return lines;
    }

    private static void describeConnection(List<Component> lines, ConduitNetworkImpl network, ConduitNodeImpl node,
            Direction side, long gameTime) {
        if (!node.isTicking()) {
            lines.add(warning(ConduitLang.PROBE_NOT_TICKING.copy()));
            return;
        }

        if (!(node.getConnectionConfig(side) instanceof IOConnectionConfig config)) {
            return;
        }

        if (!config.isExtract()) {
            lines.add(line(ConduitLang.PROBE_EXTRACT_OFF));
            return;
        }

        if (!config.canExtract(node::hasRedstoneSignal)) {
            lines.add(warning(ConduitLang.PROBE_REDSTONE_INACTIVE.copy()));
            return;
        }

        var connection = new ConduitBlockConnection(node, side);
        lines.add(line(ConduitLang.PROBE_DESTINATIONS, network.insertConnectionsFrom(connection).size()));

        // Only item conduits record what happened during their last attempt.
        if (network.conduitType() != EIOConduitTypes.ITEM.get()) {
            return;
        }

        var data = node.getNodeData(ItemConduitNodeData.TYPE);
        var state = data == null ? null : data.extractState(side);
        var status = state == null ? ItemTransferStatus.NOT_CHECKED : state.status();

        if (status == ItemTransferStatus.MOVED) {
            lines.add(TooltipUtil.withArgs(ConduitLang.PROBE_MOVED, state.lastMoved()).withStyle(ChatFormatting.GREEN));
        } else {
            var text = ConduitLang.probeStatus(status);
            lines.add(status.isIdle() || status == ItemTransferStatus.NO_SOURCE ? warning(text.copy()) : text.copy());
        }

        if (state != null && state.ticksUntilAwake(gameTime) > 0) {
            lines.add(line(ConduitLang.PROBE_SLEEPING, (state.ticksUntilAwake(gameTime) + 19) / 20));
        }
    }

    private static Component line(Component text, Object... args) {
        return TooltipUtil.withArgs(text.copy(), args).withStyle(ChatFormatting.GRAY);
    }

    private static Component warning(net.minecraft.network.chat.MutableComponent text) {
        return text.withStyle(ChatFormatting.YELLOW);
    }

    private ConduitDiagnostics() {
    }
}
