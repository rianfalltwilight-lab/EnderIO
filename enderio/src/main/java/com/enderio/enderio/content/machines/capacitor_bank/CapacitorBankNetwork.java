package com.enderio.enderio.content.machines.capacitor_bank;

import com.enderio.core.common.graph.Network;
import com.enderio.enderio.api.io.RedstoneControl;
import com.enderio.enderio.foundation.network.packets.ClientBoundRemoveCapacitorBankPacket;
import com.enderio.enderio.foundation.network.packets.ClientBoundSyncCapacitorBankPacket;
import com.enderio.enderio.foundation.network.packets.ClientboundCapacitorBankValuesPacket;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CapacitorBankNetwork extends Network<CapacitorBankNetwork, CapacitorBankNode> {

    public static final int AVERAGE_IO_OVER_X_TICKS = 10;

    public static final Codec<CapacitorBankNetwork> CODEC = RecordCodecBuilder.create(i ->
        i.group(Codec.LONG.fieldOf("energy").forGetter(c -> c.totalEnergyStored),
                RedstoneControl.CODEC.fieldOf("redstone").forGetter(c -> c.redstoneControl))
            .and(graphCodec(i, CapacitorBankNode.CODEC))
            .apply(i, CapacitorBankNetwork::new));

    private final UUID uuid = UUID.randomUUID();
    private RedstoneControl redstoneControl = RedstoneControl.ALWAYS_ACTIVE;

    private long totalEnergyStored;
    private long totalMaxEnergyStored;
    private long energyToDistribute;
    @Nullable private CapacitorBankSavedData owner;
    @Nullable private List<BlockPos> positions;
    @Nullable private List<ChunkPos> chunks;
    @Nullable private List<PushTarget> targets;
    private long targetRevision;
    private record View(List<BlockPos> topology, ClientboundCapacitorBankValuesPacket values) {}
    private final Map<ServerPlayer, View> viewers = new HashMap<>();
    @Nullable private ClientboundCapacitorBankValuesPacket lastValues;
    private long lastSync;

    //Map to keep track if IO for each position and side
    private final Map<SidedPos, Long> energyIO = new HashMap<>();

    public CapacitorBankNetwork(CapacitorBankNode initialNode) {
        super(initialNode);
        totalMaxEnergyStored = initialNode.getMaxEnergyStored();
    }

    public CapacitorBankNetwork(long totalEnergyStored, RedstoneControl redstone, List<CapacitorBankNode> nodes, IndexedEdgeList edges) {
        super(nodes, edges);
        this.totalEnergyStored = totalEnergyStored;
        this.totalMaxEnergyStored = nodes.stream().mapToLong(CapacitorBankNode::getCapacity).sum(); //Load all nodes with the last know capacity
        this.redstoneControl = redstone;
    }

    public CapacitorBankNetwork() {
        super();
    }

    @Override
    protected CapacitorBankNetwork createEmpty() {
        return new CapacitorBankNetwork();
    }

    public long getTotalEnergyStored() {
        return totalEnergyStored;
    }

    public long getTotalMaxEnergyStored() {
        return totalMaxEnergyStored;
    }

    public void prepareEnergyToDistribute() {
        this.energyToDistribute = getTotalEnergyStored();
    }

    private long getEnergyToDistribute() {
        return energyToDistribute;
    }

    public List<BlockPos> positions() {
        if (positions == null) {
            positions = nodes().stream().map(CapacitorBankNode::getPos).toList();
        }
        return positions;
    }

    void setOwner(@Nullable CapacitorBankSavedData owner) {
        this.owner = owner;
    }

    private void changed() {
        if (owner != null) {
            owner.setDirty();
        }
    }

    private void topologyChanged() {
        positions = null;
        chunks = null;
        targetsChanged();
        changed();
    }

    void targetsChanged() {
        targetRevision++;
        targets = null;
    }

    public long getAddedEnergy() {
        long added = 0;
        for (long io : energyIO.values()) {
            if (io > 0) {
                added += io;
            }
        }
        return added;
    }

    public long getSendEnergy() {
        long send = 0;
        for (long io : energyIO.values()) {
            if (io < 0) {
                send -= io;
            }
        }
        return send;
    }

    public void reset(long time) {
        if (lastSync != time) {
            lastSync = time;
            energyIO.clear();
        }
    }

    public RedstoneControl getRedstoneControl() {
        return redstoneControl;
    }

    public void setRedstoneControl(RedstoneControl redstoneControl) {
        if (this.redstoneControl != redstoneControl) {
            changed();
        }
        this.redstoneControl = redstoneControl;
        for (CapacitorBankNode n : nodes()) {
            if (n.hasBlockEntity()) {
                n.getBlockEntity().setRedstoneControl(redstoneControl);
            }
        }
    }

    public boolean isRedstoneBlocked() {
        return switch (redstoneControl) {
            case ALWAYS_ACTIVE -> false;
            case ACTIVE_WITH_SIGNAL -> {
                for (CapacitorBankNode n : nodes()) {
                    if (n.hasBlockEntity() && !n.getBlockEntity().isRedstoneBlocked()) {
                        yield false;
                    }
                }
                yield true;
            }
            case ACTIVE_WITHOUT_SIGNAL -> {
                for (CapacitorBankNode n : nodes()) {
                    if (n.hasBlockEntity() && n.getBlockEntity().isRedstoneBlocked()) {
                        yield true;
                    }
                }
                yield false;
            }
            case NEVER_ACTIVE -> true;
        };
    }

    public int receiveEnergy(BlockPos pos, @Nullable Direction side, int amount, boolean simulate) {
        long energyBefore = getTotalEnergyStored();
        long energyAfter = Math.min(energyBefore + amount, getTotalMaxEnergyStored());
        int result = Math.toIntExact(Mth.clamp(energyAfter - energyBefore, 0, Integer.MAX_VALUE));

        if (!simulate && energyBefore != energyAfter) {
            this.totalEnergyStored = energyAfter;
            changed();
            if (side != null) {
                energyIO.merge(new SidedPos(pos, side), (long) result, Long::sum);
            }
        }

        return result;
    }

    public int extractEnergy(BlockPos pos, @Nullable Direction side, int amount, boolean simulate) {
        long extracted = Math.min(getTotalEnergyStored(), amount);

        if (!simulate && extracted != 0) {
            this.totalEnergyStored -= extracted;
            changed();
            if (side != null) {
                energyIO.merge(new SidedPos(pos, side), -extracted, Long::sum);
            }
        }

        return Math.toIntExact(extracted);
    }

    /**
     * Returns the energy for a node that is removed. The energy is taken from the network at this point
     * @param tier the Capacitor bank tier
     * @return The energy for the capacitor bank
     */
    public int getEnergyForNode(CapacitorTier tier) {
        long energy = getTotalEnergyStored() / (nodes().size() + 1);
        int toExtract = Math.toIntExact(Math.min(tier.getStorageCapacity(), energy));
        this.totalEnergyStored -= toExtract;
        if (toExtract != 0) {
            changed();
        }
        return toExtract;
    }

    int energyForRemoval(CapacitorBankNode node) {
        long remainingCapacity = totalMaxEnergyStored - node.getCapacity();
        long equalShare = Math.min(node.getCapacity(), totalEnergyStored / nodeCount());
        return Math.toIntExact(Math.max(equalShare, totalEnergyStored - remainingCapacity));
    }

    int takeEnergyForRemoval(CapacitorBankNode node) {
        return extractEnergy(node.getPos(), null, energyForRemoval(node), false);
    }

    public void tick(ServerLevel serverLevel) {
        distributeEnergy();

        //Update client IO
        if (serverLevel.getGameTime() % AVERAGE_IO_OVER_X_TICKS == 0) {
            var players = serverLevel.players();
            syncViewers(players.isEmpty() ? Set.of() : findViewers(players, serverLevel.getChunkSource().chunkMap));
            this.reset(serverLevel.getGameTime());
        }
    }

    Set<ServerPlayer> findViewers(List<ServerPlayer> players, ChunkMap chunkMap) {
        if (chunks == null) {
            chunks = positions().stream().map(ChunkPos::new).distinct().toList();
        }
        Set<ServerPlayer> result = new HashSet<>();
        for (var chunk : chunks) {
            result.addAll(chunkMap.getPlayers(chunk, false));
        }
        for (var player : players) {
            if (player.containerMenu instanceof CapacitorBankMenu menu && menu.getBlockEntity().belongsTo(this)) {
                result.add(player);
            }
        }
        return result;
    }

    void syncViewers(Set<ServerPlayer> current) {
        viewers.keySet().removeIf(player -> {
            if (current.contains(player)) {
                return false;
            }
            if (!player.hasDisconnected()) {
                PacketDistributor.sendToPlayer(player, new ClientBoundRemoveCapacitorBankPacket(uuid));
            }
            return true;
        });

        var values = new ClientboundCapacitorBankValuesPacket(uuid, totalEnergyStored, totalMaxEnergyStored,
            getAddedEnergy() / AVERAGE_IO_OVER_X_TICKS, getSendEnergy() / AVERAGE_IO_OVER_X_TICKS);
        lastValues = values;
        if (current.isEmpty()) {
            return;
        }
        var view = new View(positions(), values);
        for (var player : current) {
            var previous = viewers.get(player);
            if (previous == null || previous.topology() != view.topology()) {
                sendFull(player, view);
            } else if (!values.equals(previous.values())) {
                if (NetworkRegistry.hasChannel(player.connection, ClientboundCapacitorBankValuesPacket.TYPE.id())) {
                    PacketDistributor.sendToPlayer(player, values);
                    viewers.put(player, view);
                } else {
                    sendFull(player, view);
                }
            }
        }
    }

    void syncBeforeOpeningMenu(ServerPlayer player) {
        var previous = viewers.get(player);
        if (previous == null || previous.topology() != positions()) {
            var values = new ClientboundCapacitorBankValuesPacket(uuid, totalEnergyStored, totalMaxEnergyStored,
                lastValues == null ? 0 : lastValues.added(), lastValues == null ? 0 : lastValues.send());
            sendFull(player, new View(positions(), values));
        }
    }

    private void sendFull(ServerPlayer player, View view) {
        var values = view.values();
        PacketDistributor.sendToPlayer(player, new ClientBoundSyncCapacitorBankPacket(uuid, values.storedEnergy(),
            values.capacity(), values.added(), values.send(), view.topology()));
        viewers.put(player, view);
    }

    void forgetViewer(ServerPlayer player) {
        if (viewers.remove(player) != null && !player.hasDisconnected()) {
            PacketDistributor.sendToPlayer(player, new ClientBoundRemoveCapacitorBankPacket(uuid));
        }
    }

    void removeFromViewers() {
        for (var player : viewers.keySet()) {
            if (!player.hasDisconnected()) {
                PacketDistributor.sendToPlayer(player, new ClientBoundRemoveCapacitorBankPacket(uuid));
            }
        }
        viewers.clear();
    }

    private long distributeEnergyEvenlyBetween(long availableEnergy, List<PushTarget> receivers) {
        // Abort if we have no valid pairs
        if (receivers.isEmpty()) {
            return 0;
        }

        // Distribute evenly
        long energyRemaining = availableEnergy;
        int toShareWith = receivers.size();

        for (var target : receivers) {
            var sided = target.sided();
            IEnergyStorage receiver = sided.storage();
            // If we have too little energy left, just give it to the first handler that will accept it all
            long shareAmount;
            if (energyRemaining <= toShareWith) {
                shareAmount = energyRemaining;
            } else {
                shareAmount = energyRemaining / toShareWith;
            }

            int inserted = receiver.receiveEnergy(Math.clamp(shareAmount, 0, Integer.MAX_VALUE), false);
            if (inserted != 0) {
                this.energyIO.merge(sided.sidedPos(), -(long) inserted, Long::sum);
            }
            energyRemaining -= inserted;

            toShareWith--;
            if (energyRemaining <= 0) {
                break;
            }
        }

        return availableEnergy - energyRemaining;
    }

    public void distributeEnergy() {
        long available = Math.min(getEnergyToDistribute(), getTotalEnergyStored());
        if (available <= 0 || isRedstoneBlocked()) {
            return; //Don't do anything if redstone is blocked
        }

        long transferred = distributeEnergyEvenlyBetween(available, orderedTargets());
        if (transferred != 0) {
            totalEnergyStored -= transferred;
            changed();
        }
    }

    private record PushTarget(CapacitorBankBlockEntity.SidedEnergy sided, int originalIndex) {
        int freeSpace() {
            return sided.storage().getMaxEnergyStored() - sided.storage().getEnergyStored();
        }
    }

    private static final Comparator<PushTarget> TARGET_ORDER = Comparator.comparingInt(PushTarget::freeSpace)
        .thenComparingInt(PushTarget::originalIndex);

    private List<PushTarget> orderedTargets() {
        List<PushTarget> snapshot = targets;
        if (snapshot == null) {
            long revision = targetRevision;
            var collected = new ArrayList<PushTarget>();
            for (var node : createNodeList()) {
                if (node.hasBlockEntity()) {
                    for (var sided : node.getBlockEntity().getValidPushTargets()) {
                        collected.add(new PushTarget(sided, collected.size()));
                    }
                }
            }
            snapshot = collected;
            if (revision == targetRevision) {
                targets = snapshot;
            }
        }
        // Third-party energy buffers can change without invalidating their capability.
        // Keep the original tie order and retain a stable list if a receiver reenters distribution.
        for (int i = 1; i < snapshot.size(); i++) {
            if (TARGET_ORDER.compare(snapshot.get(i - 1), snapshot.get(i)) > 0) {
                var sorted = new ArrayList<>(snapshot);
                sorted.sort(TARGET_ORDER);
                if (targets == snapshot) {
                    targets = sorted;
                }
                return sorted;
            }
        }
        return snapshot;
    }

    public void markDirty() {
        nodes().forEach(CapacitorBankNode::markDirty);
    }

    public UUID getUuid() {
        return uuid;
    }

    @Override
    protected void onNodeRemoved(CapacitorBankNode node) {
        totalMaxEnergyStored = Mth.clamp(totalMaxEnergyStored - node.getCapacity(), 0, Long.MAX_VALUE);
        totalEnergyStored = Mth.clamp(totalEnergyStored, 0, totalMaxEnergyStored);
        topologyChanged();
        if (isEmpty() && owner != null) {
            owner.discard(this);
        }
    }

    @Override
    protected void onNodeAdded(CapacitorBankNode node) {
        totalMaxEnergyStored = Mth.clamp(totalMaxEnergyStored + node.getCapacity(), 0, Long.MAX_VALUE);
        topologyChanged();
    }

    @Override
    protected void onNodesConnected(CapacitorBankNode first, CapacitorBankNode second, boolean added) {
        if (added) {
            changed();
        }
    }

    @Override
    protected void onNodesDisconnected(CapacitorBankNode first, CapacitorBankNode second, boolean removed) {
        if (removed) {
            changed();
        }
    }

    public void init(CapacitorBankNode node) {
        int currentCapacity = node.getMaxEnergyStored();
        if (node.getCapacity() != currentCapacity) {
            totalMaxEnergyStored = Mth.clamp(totalMaxEnergyStored - node.getCapacity() + currentCapacity, 0, Long.MAX_VALUE);
            changed();
        }
        targetsChanged();
    }

    @Override
    protected void onMerged(CapacitorBankNetwork other) {
        this.totalMaxEnergyStored += other.totalMaxEnergyStored;
        this.totalEnergyStored = Mth.clamp(this.totalEnergyStored + other.totalEnergyStored, 0, totalMaxEnergyStored);
        other.totalEnergyStored = 0;
        other.setRedstoneControl(this.redstoneControl);
        other.markDirty();
        if (other.owner != null) {
            other.owner.discard(other);
        }
        topologyChanged();
    }

    @Override
    protected void onGraphSplit(Set<CapacitorBankNetwork> newGraphs) {
        // During node removal the graph has already dropped the node, but onNodeRemoved runs after this hook.
        long capacity = nodes().stream().mapToLong(CapacitorBankNode::getCapacity).sum();
        for (var network : newGraphs) {
            capacity += network.totalMaxEnergyStored;
        }
        long energy = Math.min(totalEnergyStored, capacity);
        for (var network : newGraphs) {
            network.setRedstoneControl(redstoneControl);
            network.markDirty();
            if (owner != null) {
                owner.add(network);
            }
            long share = proportionalShare(energy, network.totalMaxEnergyStored, capacity);
            network.totalEnergyStored = share;
            energy -= share;
            capacity -= network.totalMaxEnergyStored;
            totalMaxEnergyStored -= network.totalMaxEnergyStored;
        }
        totalEnergyStored = energy;
        topologyChanged();
    }

    private static long proportionalShare(long energy, long part, long total) {
        if (part == 0 || energy == 0) {
            return 0;
        }
        if (energy <= Long.MAX_VALUE / part) {
            return energy * part / total;
        }
        return BigInteger.valueOf(energy).multiply(BigInteger.valueOf(part)).divide(BigInteger.valueOf(total)).longValueExact();
    }

    public record SidedPos(BlockPos pos, @Nullable Direction side) {}
}
