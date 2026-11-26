package com.enderio.enderio.content.machines.capacitor_bank;

import com.google.common.base.Preconditions;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@EventBusSubscriber
public class CapacitorBankSavedData extends SavedData {

    public static final Codec<CapacitorBankSavedData> CODEC = CapacitorBankNetwork.CODEC.listOf()
        .xmap(CapacitorBankSavedData::new, CapacitorBankSavedData::snapshot);

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY_DATA = "Networks";
    private static final Factory<CapacitorBankSavedData> FACTORY = new Factory<>(CapacitorBankSavedData::new, CapacitorBankSavedData::load);

    private final Set<CapacitorBankNetwork> networks = new HashSet<>();
    @Nullable private List<CapacitorBankNetwork> snapshot;
    private final Map<BlockPos, CapacitorBankNode> unclaimedNodes = new HashMap<>();

    public static CapacitorBankSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "enderio_capacitor_bank_network");
    }

    public CapacitorBankSavedData() {}

    private CapacitorBankSavedData(List<CapacitorBankNetwork> networks) {
        this.networks.addAll(networks);
        for (CapacitorBankNetwork network : networks) {
            network.setOwner(this);
            for (CapacitorBankNode node : network.nodes()) {
                unclaimedNodes.put(node.getPos(), node);
            }
        }
    }

    private List<CapacitorBankNetwork> snapshot() {
        if (snapshot == null) {
            snapshot = List.copyOf(networks);
        }
        return snapshot;
    }

    @Nullable
    public CapacitorBankNode claimNode(BlockPos pos) {
        return unclaimedNodes.remove(pos);
    }

    void releaseNode(CapacitorBankNode node) {
        unclaimedNodes.put(node.getPos(), node);
    }

    public static void onNetworkCreated(ServerLevel level, CapacitorBankNetwork network) {
        get(level).add(network);
    }

    void add(CapacitorBankNetwork network) {
        Preconditions.checkArgument(network.isValid(), "New network is not valid!");
        if (networks.add(network)) {
            snapshot = null;
            network.setOwner(this);
            setDirty();
        }
    }

    public static void onNetworkDiscarded(ServerLevel level, CapacitorBankNetwork network) {
        get(level).discard(network);
    }

    void discard(CapacitorBankNetwork network) {
        if (networks.remove(network)) {
            snapshot = null;
            network.removeFromViewers();
            network.setOwner(null);
            setDirty();
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var previous = player.serverLevel().getServer().getLevel(event.getFrom());
            if (previous != null) {
                get(previous).networks.forEach(network -> network.forgetViewer(player));
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            get(serverLevel).tick(serverLevel);
        }
    }

    private void tick(ServerLevel serverLevel) {
        var tickNetworks = snapshot();
        for (CapacitorBankNetwork network : tickNetworks) {
            if (!network.isValid() || network.isEmpty()) {
                discard(network);
            } else {
                network.prepareEnergyToDistribute();
            }
        }

        for (var network : tickNetworks) {
            if (networks.contains(network)) {
                network.tick(serverLevel);
            }
        }

    }

    private static CapacitorBankSavedData load(CompoundTag compoundTag, HolderLookup.Provider provider) {
        var result = CODEC.parse(provider.createSerializationContext(NbtOps.INSTANCE), compoundTag.get(KEY_DATA));
        result.error().ifPresent(e -> LOGGER.error("Errors loading capacitor bank network data, some networks may be missing: {}", e.message()));
        return result.getPartialOrThrow();
    }

    @Override
    public CompoundTag save(CompoundTag compoundTag, HolderLookup.Provider provider) {
        compoundTag.put(KEY_DATA, CODEC.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), this).getOrThrow());
        return compoundTag;
    }
}
