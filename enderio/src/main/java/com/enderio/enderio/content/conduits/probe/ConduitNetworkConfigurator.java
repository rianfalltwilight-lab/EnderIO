package com.enderio.enderio.content.conduits.probe;

import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.config.ConnectionConfig;
import com.enderio.enderio.api.conduits.connection.config.IOConnectionConfig;
import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

public final class ConduitNetworkConfigurator {

    /**
     * @param clicked the bundle whose networks are configured
     * @param copied configurations by the registry name of the conduit they were copied from
     * @return how many connections were changed
     */
    public static int apply(ConduitBundleBlockEntity clicked, Map<ResourceLocation, ConnectionConfig> copied, Player player) {
        var level = clicked.getLevel();
        if (level == null) {
            return 0;
        }

        int changed = 0;
        var touched = new HashSet<ConduitBundleBlockEntity>();
        for (var conduit : clicked.getConduits()) {
            var source = copied.get(ResourceLocation.parse(conduit.getRegisteredName()));
            var network = clicked.getConduitNode(conduit).getNetwork();
            if (!(source instanceof IOConnectionConfig sourceIO) || !network.conduitType().doesRequireNetworkCaches()) {
                continue;
            }

            network.ensureCachesReady();
            // Changing a config updates the network's caches, so walk a copy.
            for (ConduitBlockConnection connection : new ArrayList<>(network.blockConnections())) {
                var pos = connection.node().pos();
                if (!level.mayInteract(player, pos) || !(level.getBlockEntity(pos) instanceof ConduitBundleBlockEntity bundle)) {
                    continue;
                }

                var nodeConduit = bundle.getCompatibleConduit(conduit);
                if (nodeConduit == null) {
                    continue;
                }

                var current = bundle.getConnectionConfig(nodeConduit, connection.connectionSide());
                if (!(current instanceof IOConnectionConfig currentIO) || current.type() != source.type()
                        || currentIO.isInsert() != sourceIO.isInsert() || currentIO.isExtract() != sourceIO.isExtract()
                        || current.equals(source)) {
                    continue;
                }

                bundle.setConnectionConfig(nodeConduit, connection.connectionSide(), source);
                touched.add(bundle);
                changed++;
            }
        }

        for (var bundle : touched) {
            bundle.setChanged();
            bundle.updateShape();
        }

        return changed;
    }

    private ConduitNetworkConfigurator() {
    }
}
