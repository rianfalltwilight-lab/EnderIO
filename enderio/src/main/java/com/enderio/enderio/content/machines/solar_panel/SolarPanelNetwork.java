package com.enderio.enderio.content.machines.solar_panel;

import com.enderio.core.common.graph.Network;
import com.mojang.datafixers.util.Pair;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SolarPanelNetwork extends Network<SolarPanelNetwork, SolarPanelNode> {

    private long totalEnergyStored;
    private Map<ISolarPanelTier, Integer> fullTierCapacities = Map.of();
    private boolean knownFull;

    public SolarPanelNetwork(SolarPanelNode initialNode) {
        super(initialNode);

        initialNode.makePrimaryNode();
    }

    private SolarPanelNetwork(List<SolarPanelNode> solarPanelNodes, List<Pair<SolarPanelNode, SolarPanelNode>> edges) {
        super(solarPanelNodes, edges);
    }

    private SolarPanelNetwork(List<SolarPanelNode> solarPanelNodes, IndexedEdgeList edges) {
        super(solarPanelNodes, edges);
    }

    private SolarPanelNetwork() {
        super();
    }

    @Override
    protected SolarPanelNetwork createEmpty() {
        return new SolarPanelNetwork();
    }

    void adjustStoredEnergy(long delta) {
        totalEnergyStored += delta;
        if (delta < 0) {
            forgetFullState();
        }
    }

    void forgetFullState() {
        knownFull = false;
    }

    void markFull() {
        if (fullTierCapacities.isEmpty()) {
            fullTierCapacities = new IdentityHashMap<>(3);
        } else {
            fullTierCapacities.clear();
        }
        for (var node : nodes()) {
            int capacity = fullTierCapacities.computeIfAbsent(node.tier(), ISolarPanelTier::getStorageCapacity);
            if (node.getEnergyStored() < capacity) {
                return; // A neighbor may have extracted energy during a setChanged notification.
            }
        }
        knownFull = true;
    }

    boolean isKnownFull() {
        if (knownFull) {
            // Tier capacities can change at runtime, including custom tiers supplied by addons.
            for (var entry : fullTierCapacities.entrySet()) {
                if (entry.getKey().getStorageCapacity() != entry.getValue()) {
                    forgetFullState();
                    break;
                }
            }
        }
        return knownFull;
    }

    public int getTotalEnergyStored() {
        return (int) Math.min(totalEnergyStored, Integer.MAX_VALUE);
    }

    public int getTotalMaxEnergyStored() {
        long capacity = 0;
        for (var node : nodes()) {
            capacity += node.getMaxEnergyStored();
        }
        return (int) Math.min(capacity, Integer.MAX_VALUE);
    }

    @Override
    protected void onNodeRemoved(SolarPanelNode node) {
        super.onNodeRemoved(node);

        // If we removed the 'primary', find a new one.
        if (node.isPrimaryNode()) {
            nodes().stream().findFirst().ifPresent(SolarPanelNode::makePrimaryNode);
        }
    }

    @Override
    protected void onGraphSplit(Set<SolarPanelNetwork> newGraphs) {
        super.onGraphSplit(newGraphs);
        newGraphs.forEach(n -> {
            n.nodes().stream().findFirst().ifPresent(SolarPanelNode::makePrimaryNode);
        });
    }
}
