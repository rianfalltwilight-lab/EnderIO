package com.enderio.enderio.content.conduits.network;

import com.enderio.core.common.graph.Network;
import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.ConduitType;
import com.enderio.enderio.api.conduits.network.ConduitNetworkContext;
import com.enderio.enderio.api.conduits.network.ConduitNetworkContextType;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@EventBusSubscriber
public class ConduitNetworkSavedData extends SavedData {

    public static final Codec<ConduitNetworkSavedData> CODEC = ConduitNetworkImpl.CODEC.listOf()
            .xmap(ConduitNetworkSavedData::new, ConduitNetworkSavedData::getNetworks);

    private final Multimap<ConduitType<?, ?>, ConduitNetworkImpl> networks = HashMultimap.create();

    private final Long2ObjectOpenHashMap<Set<ConduitNetworkImpl>> networksByChunk = new Long2ObjectOpenHashMap<>();
    private final Map<ConduitNetworkImpl, LongSet> chunksByNetwork = Maps.newHashMap();
    // 0 = unknown, 1 = not ticking, 2 = ticking.
    private final Long2ByteOpenHashMap tickingChunksMap = new Long2ByteOpenHashMap();

    private final Map<ConduitType<?, ?>, Map<BlockPos, ConduitNodeImpl>> unloadedNodes = Maps.newHashMap();

    private static final String KEY_NEW_DATA = "Networks";
    private static final Factory<ConduitNetworkSavedData> FACTORY = new Factory<>(ConduitNetworkSavedData::new, ConduitNetworkSavedData::load);

    public static ConduitNetworkSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "enderio_conduit_network");
    }

    public ConduitNetworkSavedData() {
    }

    private ConduitNetworkSavedData(List<ConduitNetworkImpl> networks) {
        for (ConduitNetworkImpl network : networks) {
            // If a world has been loaded from 8.0.7 into 8.2.9 and then updated further, the conduit data has become corrupted.
            // In this instance, we'll have to discard the network and have it get repaired.
            // ConstantValue issues are because conduit() is supposed to be non null.
            //noinspection ConstantValue
            boolean anyNodesMissingConduit = network.nodes().stream().anyMatch(n -> n.conduit() == null);
            //noinspection ConstantValue
            if (anyNodesMissingConduit) {
                LOGGER.warn("Skipping network with damaged conduit data.");
                continue;
            }

            network.setChunkTrackingListeners(this::onNetworkChunksChanged, this::onNetworkChunkChanged);
            network.acknowledgeSave();
            this.networks.put(network.conduitType(), network);

            for (var node : network.nodes()) {
                unloadedNodes.computeIfAbsent(network.conduitType(), c -> Maps.newHashMap()).put(node.pos(), node);
            }
        }
    }

    private static ConduitNetworkSavedData load(CompoundTag nbt, HolderLookup.Provider lookupProvider) {
        // TODO: 1.22 - remove support for the legacy graph format.
        if (nbt.contains(KEY_GRAPHS)) {
            var migrated = loadLegacy(nbt, lookupProvider);
            migrated.setDirty();
            return migrated;
        }

        var result = CODEC.parse(lookupProvider.createSerializationContext(NbtOps.INSTANCE), nbt.get(KEY_NEW_DATA));
        result.error().ifPresent(e -> LOGGER.error("Errors loading conduit network data, some networks may be missing: {}", e.message()));
        return result.getPartialOrThrow();
    }

    private List<ConduitNetworkImpl> getNetworks() {
        return networks.values().stream().filter(n -> n.isValid() && !n.isEmpty()).toList();
    }

    @Override
    public CompoundTag save(CompoundTag compoundTag, HolderLookup.Provider provider) {
        compoundTag.put(KEY_NEW_DATA,
                CODEC.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), this).getOrThrow());
        return compoundTag;
    }

    @Nullable
    public ConduitNodeImpl claimNode(Holder<Conduit<?, ?>> conduit, BlockPos pos) {
        var conduitMap = unloadedNodes.get(conduit.value().type());
        if (conduitMap == null) {
            LOGGER.warn("Conduit data is missing!");
            return null;
        }

        if (!conduitMap.containsKey(pos)) {
            LOGGER.warn("Conduit data is missing node at {}", pos);
            return null;
        }

        var node = conduitMap.remove(pos);
        if (node.conduit() != conduit) {
            // Trust block entity as source of truth *just* because it's easier to rebuild a node than it is to reconcile the block entity's data.
            LOGGER.warn("Conduit mismatch at {}: {} (network) vs {} (block entity). Dumping data, node will be rebuilt.", pos, node.conduit(), conduit);
            return null;
        }

        return node;
    }

    public void returnNode(Holder<Conduit<?, ?>> conduit, BlockPos pos, ConduitNodeImpl node) {
        unloadedNodes.computeIfAbsent(conduit.value().type(), c -> Maps.newHashMap()).put(pos, node);
    }

    public static void onNetworkCreated(ServerLevel level, ConduitNetworkImpl network) {
        get(level).onNetworkCreated(network);
    }

    private void onNetworkCreated(ConduitNetworkImpl network) {
        Preconditions.checkArgument(network.isValid(), "New network is not valid!");
        networks.put(network.conduitType(), network);
        onNetworkChunksChanged(network);
        network.setChunkTrackingListeners(this::onNetworkChunksChanged, this::onNetworkChunkChanged);
        setDirty();
    }

    public static void onNetworkDiscarded(ServerLevel level, ConduitNetworkImpl network) {
        Preconditions.checkArgument(network.isDiscarded(), "Network is not discarded!");
        get(level).onNetworkDiscarded(network);
    }

    private void onNetworkDiscarded(ConduitNetworkImpl network) {
        // Allow empty or discarded networks here
        networks.remove(network.conduitType(), network);

        stopTracking(network);
    }

    private void stopTracking(ConduitNetworkImpl network) {
        var oldChunks = chunksByNetwork.remove(network);
        if (oldChunks != null) {
            oldChunks.forEach((long chunk) -> removeFromChunk(network, chunk));
        }
        network.setChunkTrackingListeners(null, null);
        setDirty();
    }

    private void removeFromChunk(ConduitNetworkImpl network, long chunk) {
        var covering = networksByChunk.get(chunk);
        if (covering != null && covering.remove(network) && covering.isEmpty()) {
            networksByChunk.remove(chunk);
            tickingChunksMap.remove(chunk);
        }
    }

    private void onNetworkChunkChanged(ConduitNetworkImpl network, long chunk) {
        if (network.allChunks().contains(chunk)) {
            chunksByNetwork.computeIfAbsent(network, ignored -> new LongOpenHashSet()).add(chunk);
            networksByChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(network);
        } else {
            var known = chunksByNetwork.get(network);
            if (known != null) {
                known.remove(chunk);
            }
            removeFromChunk(network, chunk);
        }
    }

    private void onNetworkChunksChanged(ConduitNetworkImpl network) {
        var current = network.allChunks();
        var known = chunksByNetwork.computeIfAbsent(network, ignored -> new LongOpenHashSet());
        var previous = known.iterator();
        while (previous.hasNext()) {
            long chunk = previous.nextLong();
            if (!current.contains(chunk)) {
                previous.remove();
                removeFromChunk(network, chunk);
            }
        }
        for (long chunk : current) {
            if (known.add(chunk)) {
                networksByChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(network);
            }
        }
    }

    @Override
    public boolean isDirty() {
        if (super.isDirty()) {
            return true;
        }
        // This check also runs on save/unload. Extension data without change notifications stays saveable.
        for (var network : networks.values()) {
            if (!network.isValid() || network.isEmpty() || network.hasUnsavedData()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setDirty(boolean dirty) {
        super.setDirty(dirty);
        if (!dirty) {
            for (var network : networks.values()) {
                if (network.isValid()) {
                    network.acknowledgeSave();
                }
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
        var currentNetworks = networks.values().iterator();
        while (currentNetworks.hasNext()) {
            var network = currentNetworks.next();
            if (!network.isValid() || network.isEmpty()) {
                currentNetworks.remove();
                stopTracking(network);
            }
        }

        for (var it = networksByChunk.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            long chunk = entry.getLongKey();
            // hasChunk can change even without a chunk unload event (GH-1177).
            boolean ticking = serverLevel.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) && serverLevel.shouldTickBlocksAt(chunk);
            byte status = (byte) (ticking ? 2 : 1);
            if (tickingChunksMap.get(chunk) != status) {
                tickingChunksMap.put(chunk, status);
                for (var network : entry.getValue()) {
                    network.onChunkTickStatusChanged(chunk);
                }
            }
        }

        for (var conduitType : networks.keySet()) {
            // Ensure caches are ready for any networks that require them
            if (conduitType.doesRequireNetworkCaches()) {
                for (var network : networks.get(conduitType)) {
                    network.ensureCachesReady();
                }
            }

            // Skip non-ticking graphs.
            var ticker = conduitType.ticker();
            if (ticker == null) {
                continue;
            }

            // TODO: GH-1269
            int conduitId = EnderIORegistries.CONDUIT_TYPE.getId(conduitType);
            for (var network : networks.get(conduitType)) {
                try {
                    ticker.tick(serverLevel, network, conduitId);
                } catch (Throwable t) {
                    var report = CrashReport.forThrowable(t, "Ticking conduit network");
                    var category = report.addCategory(EnderIORegistries.CONDUIT_TYPE.getKey(conduitType) + " network being ticked");
                    network.addCrashInfo(category);
                    throw new ReportedException(report);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            get(serverLevel).tickingChunksMap.remove(event.getChunk().getPos().toLong());
        }
    }

    // region Legacy Serialization

    private static final Logger LOGGER = LogUtils.getLogger();

    // TODO: 1.22 - Remove

    private static final String KEY_GRAPHS = "Graphs";
    private static final String KEY_TYPE = "Type";
    private static final String KEY_GRAPH_OBJECTS = "GraphObjects";
    private static final String KEY_GRAPH_CONNECTIONS = "GraphConnections";
    private static final String KEY_GRAPH_CONTEXT = "GraphContext";

    // TODO: Write it.

    private static ConduitNetworkSavedData loadLegacy(CompoundTag nbt, HolderLookup.Provider lookupProvider) {
        var savedData = new ConduitNetworkSavedData();

        ListTag graphsTag = nbt.getList(KEY_GRAPHS, Tag.TAG_COMPOUND);
        for (Tag tag : graphsTag) {
            CompoundTag typedGraphTag = (CompoundTag) tag;
            ResourceKey<Conduit<?, ?>> conduitKey = ResourceKey.create(EnderIORegistries.Keys.CONDUIT,
                    ResourceLocation.parse(typedGraphTag.getString(KEY_TYPE)));

            var registry = lookupProvider.lookupOrThrow(EnderIORegistries.Keys.CONDUIT);

            Optional<Holder.Reference<Conduit<?, ?>>> conduit = registry.get(conduitKey);

            if (conduit.isPresent()) {
                ListTag graphsForTypeTag = typedGraphTag.getList(KEY_GRAPHS, Tag.TAG_COMPOUND);
                deserializeGraphs(lookupProvider, conduit.get(), graphsForTypeTag, savedData);
            } else {
                LOGGER.warn("Skipping graph for missing conduit: {}", conduitKey);
            }
        }

        return savedData;
    }

    private static void deserializeGraphs(HolderLookup.Provider lookupProvider, Holder<Conduit<?, ?>> conduit,
            ListTag graphs, ConduitNetworkSavedData savedData) {
        for (Tag tag1 : graphs) {
            CompoundTag graphTag = (CompoundTag) tag1;

            ListTag graphObjectsTag = graphTag.getList(KEY_GRAPH_OBJECTS, Tag.TAG_COMPOUND);
            ListTag graphConnectionsTag = graphTag.getList(KEY_GRAPH_CONNECTIONS, Tag.TAG_COMPOUND);

            // Skip any graphs which have no objects in them, they should not have been
            // saved.
            if (graphObjectsTag.isEmpty()) {
                // TODO: Warning or something?
                continue;
            }

            // Load all nodes
            List<ConduitNodeImpl> nodes = new ArrayList<>();
            for (int i = 0; i < graphObjectsTag.size(); i++) {
                CompoundTag nodeTag = graphObjectsTag.getCompound(i);
                var node = ConduitNodeImpl.CODEC.decode(lookupProvider.createSerializationContext(NbtOps.INSTANCE), nodeTag)
                        .getOrThrow()
                        .getFirst();

                node.setConduitIfNull(conduit);

                nodes.add(node);
            }

            // Load all connection indices
            List<Pair<Integer, Integer>> connections = new ArrayList<>();
            for (Tag tag2 : graphConnectionsTag) {
                CompoundTag connectionTag = (CompoundTag) tag2;
                connections.add(new Pair<>(connectionTag.getInt("0"), connectionTag.getInt("1")));
            }

            // Load network context
            ConduitNetworkContext<?> context = null;
            if (graphTag.contains(KEY_GRAPH_CONTEXT)) {
                context = loadNetworkContext(lookupProvider, graphTag.getCompound(KEY_GRAPH_CONTEXT));
            }

            // Create network
            var network = new ConduitNetworkImpl(conduit.value().type(), Optional.ofNullable(context), nodes,
                    new Network.IndexedEdgeList(connections));

            // Ensure the nodes are all valid
            if (nodes.stream().anyMatch(n -> !n.isValid())) {
                LOGGER.error(
                        "Node(s) are still invalid after loading the network. Please report this issue to Ender IO, loading cannot continue.");
                throw new IllegalStateException("Graph was null after loading the conduit network");
            }

            // Hook onto chunk coverage change event
            network.setChunkTrackingListeners(savedData::onNetworkChunksChanged, savedData::onNetworkChunkChanged);

            savedData.networks.put(network.conduitType(), network);

            for (var node : network.nodes()) {
                savedData.unloadedNodes.computeIfAbsent(network.conduitType(), c -> Maps.newHashMap())
                        .put(node.pos(), node);
            }
        }
    }

    @Nullable
    private static ConduitNetworkContext<?> loadNetworkContext(HolderLookup.Provider lookupProvider,
            CompoundTag contextTag) {
        ResourceLocation serializerKey = ResourceLocation.parse(contextTag.getString("Type"));
        ConduitNetworkContextType<?> contextType = Objects.requireNonNull(
                EnderIORegistries.CONDUIT_NETWORK_CONTEXT_TYPE.get(serializerKey),
                "Unable to find conduit network context type with key " + serializerKey);

        if (contextType.codec() == null) {
            return null;
        }

        // TODO: We're using getOrThrow a lot for conduits. Should definitely make more
        // robust/flexible.
        CompoundTag data = contextTag.getCompound("Data");
        var ops = lookupProvider.createSerializationContext(NbtOps.INSTANCE);
        return contextType.codec().decode(ops, ops.getMap(data).getOrThrow()).getOrThrow();
    }

    // endregion
}
