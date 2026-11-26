package com.enderio.enderio.content.machines.solar_panel;

import com.enderio.core.common.graph.INetworkNode;
import com.enderio.enderio.foundation.io.TransferUtil;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class SolarPanelNode implements INetworkNode<SolarPanelNetwork, SolarPanelNode> {
    private final SolarPanelBlockEntity blockEntity;
    private SolarPanelNetwork network;
    private boolean isPrimaryNode;

    private int energyStored;

    public SolarPanelNode(SolarPanelBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
        this.network = new SolarPanelNetwork(this);
    }

    public boolean isPrimaryNode() {
        return isPrimaryNode;
    }

    public void makePrimaryNode() {
        isPrimaryNode = true;
    }

    public int getEnergyStored() {
        return energyStored;
    }

    public void setEnergyStored(int energyStored) {
        if (network != null) {
            network.adjustStoredEnergy((long) energyStored - this.energyStored);
        }
        this.energyStored = energyStored;
    }

    ISolarPanelTier tier() {
        return blockEntity.tier();
    }

    public int getMaxEnergyStored() {
        return tier().getStorageCapacity();
    }

    /**
     * Lossy on purpose, adding energy by generation when we're solar just means we lose the leftovers.
     */
    private int addEnergy(int energy) {
        int energyToInsert = Math.min(getMaxEnergyStored() - getEnergyStored(), energy);
        if (energyToInsert <= 0) {
            return 0;
        }
        setEnergyStored(energyStored + energyToInsert);
        blockEntity.setChanged();
        return energyToInsert;
    }

    public void addEnergyToNetwork(int energy) {
        if (energy <= 0 || (network != null && network.isKnownFull())) {
            return;
        }
        int remaining = energy - addEnergy(energy);
        if (remaining <= 0) {
            return;
        }
        for (var node : network.nodes()) {
            if (node != this) {
                remaining -= node.addEnergy(remaining);
                if (remaining == 0) {
                    return;
                }
            }
        }
        network.markFull();
    }

    public int extractEnergy(int maxExtract, boolean simulate) {
        int energyExtracted = Math.max(0, Math.min(getEnergyStored(), maxExtract));
        if (!simulate && energyExtracted > 0) {
            setEnergyStored(energyStored - energyExtracted);
            blockEntity.setChanged();
        }

        return energyExtracted;
    }

    public void distributeEnergy() {
        if (!isPrimaryNode) {
            throw new IllegalStateException("Cannot distribute energy from non-primary node");
        }

        int available = network.getTotalEnergyStored();
        if (available <= 0) {
            return;
        }
        Set<IEnergyStorage> validTargets = new HashSet<>();
        for (var node : network.nodes()) {
            validTargets.addAll(node.blockEntity.getValidPushTargets());
        }

        int transferred = TransferUtil.distributeEnergyEvenlyBetween(available, validTargets);
        if (transferred == 0) {
            return;
        }

        // Extract from the entire network
        int toExtract = transferred;
        for (var node : network.nodes()) {
            int extracted = node.extractEnergy(toExtract, false);
            toExtract -= extracted;

            if (toExtract <= 0) {
                break;
            }
        }
    }

    @Override
    public boolean isValid() {
        return network != null;
    }

    @Override
    public SolarPanelNetwork getNetwork() {
        return Objects.requireNonNull(network);
    }

    @Override
    public void setNetwork(@Nullable SolarPanelNetwork network) {
        if (this.network != null) {
            this.network.adjustStoredEnergy(-(long) energyStored);
            this.network.forgetFullState();
        }
        this.network = network;
        if (network != null) {
            network.adjustStoredEnergy(energyStored);
            network.forgetFullState();
            this.isPrimaryNode = false;
        }
    }
}
