package com.enderio.enderio.content.conduits.network;

import com.enderio.core.common.graph.Network;
import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.ConduitType;
import com.enderio.enderio.api.conduits.connection.config.IOConnectionConfig;
import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.connection.path.ConnectionPathProperty;
import com.enderio.enderio.api.conduits.connection.path.PriorityConnectionPathComparator;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.network.ConduitNetworkContext;
import com.enderio.enderio.api.conduits.network.ConduitNetworkContextType;
import com.enderio.enderio.api.conduits.network.node.ConduitNode;
import com.enderio.enderio.content.conduits.network.pathing.ConduitPathingStrategy;
import com.enderio.enderio.content.conduits.network.pathing.PathfindingContext;
import com.enderio.enderio.content.conduits.network.pathing.BreadthFirstPathingStrategy;
import com.enderio.enderio.init.EIOConduitTypes;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConduitNetworkImpl extends Network<ConduitNetworkImpl, ConduitNodeImpl> implements ConduitNetwork, PathfindingContext {

    //Public for testing
    public static final Codec<ConduitNetworkImpl> LEGACY_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Conduit.CODEC.fieldOf("conduit").forGetter(i -> null),
                    ConduitNetworkContext.GENERIC_CODEC.optionalFieldOf("context")
                            .forGetter(i -> i.context == null || !i.context.type().isPersistent() ? Optional.empty()
                                    : Optional.of(i.context)))
            .and(graphCodec(instance, ConduitNodeImpl.CODEC))
            .apply(instance, ConduitNetworkImpl::new));

    //Public for testing
    public static final Codec<ConduitNetworkImpl> NEW_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(ConduitType.CODEC.fieldOf("conduit_type").forGetter(i -> i.conduitType),
                    ConduitNetworkContext.GENERIC_CODEC.optionalFieldOf("context")
                            .forGetter(i -> i.context == null || !i.context.type().isPersistent() ? Optional.empty()
                                    : Optional.of(i.context)))
            .and(graphCodec(instance, ConduitNodeImpl.CODEC))
            .apply(instance, ConduitNetworkImpl::new));

    public static final Codec<ConduitNetworkImpl> CODEC = Codec.withAlternative(NEW_CODEC, LEGACY_CODEC);

    private final ConduitType<?, ?> conduitType;
    
    private final ConduitPathingStrategy pathfinder = new BreadthFirstPathingStrategy();

    @Nullable
    private ConduitNetworkContext<?> context;
    
    // TODO: Not final because onNodeAdded is called by super so we need to initialize on use
    //       I might be able to make minor changes to the Network class to fix this.
    private final Map<Holder<Conduit<?, ?>>, Integer> nodeCountByConduit = Maps.newHashMap();

    // Caches
    private final boolean supportsCaching;

    private boolean shouldRebuildCache = true;
    private boolean haveConnectionsChanged = true;
    private final Set<ConduitNodeImpl> dirtyNodes = Sets.newHashSet();

    private final Multimap<Long, ConduitNodeImpl> nodesByChunkPos = HashMultimap.create();

    private final Set<ConduitNodeImpl> tickingNodes = Sets.newHashSet();

    // TODO: Separate this into a list and a multimap so we can sort all endpointConnections?
    private final SetMultimap<ConduitNodeImpl, ConduitBlockConnection> endpointConnections = HashMultimap.create();
    private final Map<ConduitBlockConnection, List<ConduitConnectionPath>> accessiblePathsByConnection = Maps
            .newHashMap();

    private final List<ConduitBlockConnection> insertConnections = Lists.newArrayList();
    private final List<ConduitBlockConnection> extractConnections = Lists.newArrayList();

    private final Set<DyeColor> allChannels = Sets.newHashSet();
    private final ListMultimap<DyeColor, ConduitBlockConnection> insertConnectionsByChannel = ArrayListMultimap
            .create();
    private final ListMultimap<DyeColor, ConduitBlockConnection> extractConnectionsByChannel = ArrayListMultimap
            .create();

    private final Map<ConduitBlockConnection, List<ConduitConnectionPath>> extractConnectionsByInsert = Maps
            .newHashMap();
    private final Map<ConduitBlockConnection, List<ConduitConnectionPath>> insertConnectionsByExtract = Maps
            .newHashMap();

    private final ListMultimap<Integer, Holder<Conduit<?, ?>>> tickableConduits = ArrayListMultimap.create();
    private boolean areTickableConduitsValid = false;

    @Nullable
    private Consumer<ConduitNetworkImpl> onChunkCoverageChanged = null;

    @Nullable private ObjLongConsumer<ConduitNetworkImpl> onSingleChunkChanged;
    private boolean persistentDataChanged;

    void markSaveDirty() {
        persistentDataChanged = true;
    }

    boolean hasUnsavedData() {
        if (persistentDataChanged || context != null && context.type().isPersistent()) {
            return true;
        }
        for (var node : nodes()) {
            if (node.hasUnsavedData()) {
                return true;
            }
        }
        return false;
    }

    void acknowledgeSave() {
        persistentDataChanged = false;
        for (var node : nodes()) {
            node.acknowledgeSave();
        }
    }

    public ConduitNetworkImpl(ConduitType<?, ?> conduitType, ConduitNodeImpl initialNode) {
        super(initialNode);
        this.conduitType = conduitType;
        this.supportsCaching = conduitType.doesRequireNetworkCaches();
        recomputeNodeCounts();
    }

    // TODO: Public for legacy deserialization
    public ConduitNetworkImpl(ConduitType<?, ?> conduitType, Optional<ConduitNetworkContext<?>> context,
                              List<ConduitNodeImpl> nodes, IndexedEdgeList edges) {
        super(nodes, edges);
        this.conduitType = conduitType;
        this.context = context.orElse(null);
        this.supportsCaching = conduitType.doesRequireNetworkCaches();
        recomputeNodeCounts();
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private ConduitNetworkImpl(Holder<Conduit<?, ?>> conduit, Optional<ConduitNetworkContext<?>> context,
                               List<ConduitNodeImpl> nodes, IndexedEdgeList edges) {
        super(nodes, edges);
        this.conduitType = conduit.value().type();
        this.context = context.orElse(null);
        this.supportsCaching = conduitType.doesRequireNetworkCaches();
        this.nodes().forEach(n -> n.setConduitIfNull(conduit));
        recomputeNodeCounts();
    }

    protected ConduitNetworkImpl(ConduitType<?, ?> conduitType) {
        this.conduitType = conduitType;
        this.supportsCaching = conduitType.doesRequireNetworkCaches();
    }

    @Override
    public ConduitType<?, ?> conduitType() {
        return conduitType;
    }

    @Override
    public Set<Holder<Conduit<?, ?>>> conduits() {
        if (nodeCountByConduit == null) {
            return Set.of();
        }

        return Collections.unmodifiableSet(nodeCountByConduit.keySet());
    }

    @Override
    public List<Holder<Conduit<?, ?>>> getTickableConduits(long gameTime, int tickOffset) {
        if (!areTickableConduitsValid && nodeCountByConduit != null) {
            tickableConduits.clear();

            // Check all conduits to see if they can tick
            for (var conduit : nodeCountByConduit.keySet()) {
                for (int i = 0; i < 20; i++) {
                    if (i % conduitType().getTickRate(conduit) == 0) {
                        tickableConduits.put(i, conduit);
                    }
                }
            }

            areTickableConduitsValid = true;
        }

        return tickableConduits.get(Ints.saturatedCast((gameTime + tickOffset) % 20));
    }

    // region Chunk Tracking

    public Set<Long> allChunks() {
        return nodesByChunkPos.keySet();
    }

    public void setOnChunkCoverageChanged(Consumer<ConduitNetworkImpl> onChunkCoverageChanged) {
        this.onChunkCoverageChanged = onChunkCoverageChanged;
        this.onSingleChunkChanged = null;
    }

    void setChunkTrackingListeners(@Nullable Consumer<ConduitNetworkImpl> allChunks,
            @Nullable ObjLongConsumer<ConduitNetworkImpl> singleChunk) {
        onChunkCoverageChanged = allChunks;
        onSingleChunkChanged = singleChunk;
    }

    private void notifyChunkChanged(long chunk) {
        if (onSingleChunkChanged != null) {
            onSingleChunkChanged.accept(this, chunk);
        } else if (onChunkCoverageChanged != null) {
            onChunkCoverageChanged.accept(this);
        }
    }

    // endregion

    // region Queries

    @Override
    public int nodeCount(Holder<Conduit<?, ?>> conduit) {
        return nodeCountByConduit.getOrDefault(conduit, 0);
    }

    private void recomputeNodeCounts() {
        nodeCountByConduit.clear();
        for (var node : nodes()) {
            nodeCountByConduit.compute(node.conduit(), (k, count) -> count == null ? 1 : count + 1);
        }
    }

    // These are unfortunately necessary for the IConduitNetwork interface.
    @Override
    public boolean contains(ConduitNode node) {
        if (node instanceof ConduitNodeImpl typedNode) {
            return contains(typedNode);
        }

        return false;
    }

    @Override
    public Set<? extends ConduitNode> neighbors(ConduitNode node) {
        if (node instanceof ConduitNodeImpl typedNode) {
            return neighbors(typedNode);
        }

        return Set.of();
    }

    public Collection<ConduitNodeImpl> tickingNodes() {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return Collections.unmodifiableCollection(tickingNodes);
    }

    public Collection<ConduitNodeImpl> blockEndpoints() {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return Collections.unmodifiableCollection(endpointConnections.keySet());
    }

    public Collection<ConduitBlockConnection> blockConnections() {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return Collections.unmodifiableCollection(endpointConnections.values());
    }

    // This is sorted
    public List<ConduitConnectionPath> blockConnectionsAccessibleFrom(ConduitBlockConnection connection) {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return accessiblePathsByConnection.getOrDefault(connection, List.of());
    }

    public Set<DyeColor> allChannels() {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return allChannels;
    }

    public List<ConduitBlockConnection> insertConnections() {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return Collections.unmodifiableList(insertConnections);
    }

    public List<ConduitBlockConnection> insertConnections(DyeColor color) {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return insertConnectionsByChannel.get(color);
    }

    // This is sorted
    public List<ConduitConnectionPath> extractConnectionsFrom(ConduitBlockConnection insertConnection) {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return extractConnectionsByInsert.getOrDefault(insertConnection, List.of());
    }

    public List<ConduitBlockConnection> extractConnections() {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return Collections.unmodifiableList(extractConnections);
    }

    public List<ConduitBlockConnection> extractConnections(DyeColor color) {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return extractConnectionsByChannel.get(color);
    }

    // This is sorted
    public List<ConduitConnectionPath> insertConnectionsFrom(ConduitBlockConnection extractConnection) {
        ensureNotDiscarded();
        Preconditions.checkState(supportsCaching, "This conduit does not support caching");
        return insertConnectionsByExtract.getOrDefault(extractConnection, List.of());
    }

    // endregion

    // region Context

    public boolean hasContext(ConduitNetworkContextType<?> type) {
        ensureNotDiscarded();
        return context != null && context.type() == type;
    }

    @SuppressWarnings("unchecked")
    public <C extends ConduitNetworkContext<C>> @Nullable C getContext(ConduitNetworkContextType<C> type) {
        ensureNotDiscarded();
        if (context != null && context.type() == type) {
            return (C) context;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public <C extends ConduitNetworkContext<C>> C getOrCreateContext(ConduitNetworkContextType<C> type) {
        ensureNotDiscarded();
        if (context != null && context.type() == type) {
            return (C) context;
        }

        markSaveDirty();
        context = type.factory().get();
        return (C) context;
    }

    // endregion

    // region Cache Updates

    /**
     * Call this before ticking the network to ensure caches are up-to-date.
     * We use this to defer cache rebuilds to the last possible moment to ensure network mutations are less expensive.
     */
    public void ensureCachesReady() {
        ensureNotDiscarded();

        // Shouldn't be called, but can't hurt to be safe.
        if (!supportsCaching) {
            return;
        }

        if (shouldRebuildCache) {
            rebuildCache();
        } else {
            for (var node : dirtyNodes) {
                if (node.isTicking()) {
                    // Update nodes by removing and adding again.
                    if (tickingNodes.contains(node)) {
                        removeTickingNode(node);
                    }

                    addTickingNode(node);
                } else if (tickingNodes.contains(node)) {
                    removeTickingNode(node);
                }
            }

            dirtyNodes.clear();
        }

        if (haveConnectionsChanged) {
            updateChannelList();
            sortConnectionLists();
        }
    }

    public void onNodeUpdated(ConduitNodeImpl node) {
        if (supportsCaching && !shouldRebuildCache) {
            dirtyNodes.add(node);
        }
    }

    public void onChunkTickStatusChanged(long chunk) {
        if (!supportsCaching || shouldRebuildCache) {
            return;
        }

        dirtyNodes.addAll(nodesByChunkPos.get(chunk));
    }

    // endregion

    // region Caching Logic

    private record PathPair(ConduitConnectionPath forward, ConduitConnectionPath reverse) {
        PathPair(ConduitConnectionPath path) {
            this(path, path.start().equals(path.end()) ? path : path.reverse());
        }
    }

    private void addTickingNode(ConduitNodeImpl node) {
        tickingNodes.add(node);
        var connections = new ArrayList<ConduitBlockConnection>();
        for (var side : Direction.values()) {
            if (node.isConnectedToBlock(side)) {
                connections.add(new ConduitBlockConnection(node, side));
            }
        }
        if (connections.isEmpty()) {
            return;
        }

        Set<ConduitNode> targets = Sets.newHashSet(endpointConnections.keySet());
        targets.add(node);
        var sourcePaths = pathfinder.pathsFrom(node, targets, this);
        for (var connection : connections) {
            addConnection(node, connection, sourcePaths);
        }
        haveConnectionsChanged = true;
    }

    private void addConnection(ConduitNodeImpl node, ConduitBlockConnection connection, ConduitPathingStrategy.SourcePaths sourcePaths) {
        endpointConnections.put(node, connection);
        var accessible = new ArrayList<ConduitConnectionPath>(endpointConnections.size());
        accessiblePathsByConnection.put(connection, accessible);
        Map<ConduitBlockConnection, PathPair> paths = Maps.newHashMap();
        for (var peer : endpointConnections.values()) {
            if (peer.equals(connection)) {
                continue;
            }
            sourcePaths.findPath(connection, peer).ifPresent(path -> {
                var pair = new PathPair(path);
                paths.put(peer, pair);
                accessible.add(pair.forward());
                accessiblePathsByConnection.get(peer).add(pair.reverse());
            });
        }

        if (!(node.getConnectionConfig(connection.connectionSide()) instanceof IOConnectionConfig config)) {
            return;
        }
        boolean canInsert = config.canInsert(node::hasRedstoneSignal);
        boolean canExtract = config.canExtract(node::hasRedstoneSignal);
        if (canInsert) {
            insertConnections.add(connection);
            insertConnectionsByChannel.put(config.insertChannel(), connection);
        }
        if (canExtract) {
            extractConnections.add(connection);
            extractConnectionsByChannel.put(config.extractChannel(), connection);
        }
        if (canInsert && canExtract && config.insertChannel() == config.extractChannel()) {
            sourcePaths.findPath(connection, connection).ifPresent(path -> paths.put(connection, new PathPair(path)));
        }
        if (canInsert) {
            for (var peer : extractConnectionsByChannel.get(config.insertChannel())) {
                var pair = paths.get(peer);
                if (pair != null) {
                    appendPath(extractConnectionsByInsert, pair.forward());
                    appendPath(insertConnectionsByExtract, pair.reverse());
                }
            }
        }
        if (canExtract) {
            for (var peer : insertConnectionsByChannel.get(config.extractChannel())) {
                var pair = paths.get(peer);
                if (pair != null && !peer.equals(connection)) {
                    appendPath(insertConnectionsByExtract, pair.forward());
                    appendPath(extractConnectionsByInsert, pair.reverse());
                }
            }
        }
    }

    private static void appendPath(Map<ConduitBlockConnection, List<ConduitConnectionPath>> index, ConduitConnectionPath path) {
        index.computeIfAbsent(path.start(), k -> new ArrayList<>()).add(path);
    }
    
    // region PathfindingContext Implementation
    
    @Override
    public Map<ConnectionPathProperty<?>, Object> collectNodeProperties(ConduitNode node) {
        Map<ConnectionPathProperty<?>, Object> values = Maps.newHashMap();
        node.conduit().value().collectNodePathProperties(node, values::put);
        return values;
    }

    // endregion

    private void removeTickingNode(ConduitNodeImpl node) {
        if (!tickingNodes.remove(node)) {
            return;
        }

        var removed = endpointConnections.removeAll(node);
        if (removed.isEmpty()) {
            return;
        }
        for (var connection : removed) {
            accessiblePathsByConnection.remove(connection);

            insertConnections.remove(connection);
            extractConnections.remove(connection);
            for (var color : DyeColor.values()) {
                insertConnectionsByChannel.remove(color, connection);
                extractConnectionsByChannel.remove(color, connection);
            }

            extractConnectionsByInsert.remove(connection);
            insertConnectionsByExtract.remove(connection);
        }

        // The public path lists are mutable, so one endpoint's list cannot identify all its peers.
        Predicate<ConduitConnectionPath> touchesRemoved = path -> removed.contains(path.end()) || removed.contains(path.start());
        accessiblePathsByConnection.values().forEach(paths -> paths.removeIf(touchesRemoved));
        extractConnectionsByInsert.values().forEach(paths -> paths.removeIf(touchesRemoved));
        insertConnectionsByExtract.values().forEach(paths -> paths.removeIf(touchesRemoved));
        haveConnectionsChanged = true;
    }

    private void updateChannelList() {
        allChannels.clear();
        allChannels.addAll(insertConnectionsByChannel.keySet());
        allChannels.addAll(extractConnectionsByChannel.keySet());
    }

    private void sortConnectionLists() {
        var basicConnectionComparator = conduitType.connectionComparator();
        if (basicConnectionComparator != null) {
            insertConnections.sort(basicConnectionComparator);
            extractConnections.sort(basicConnectionComparator);
        }

        var pathComparator = conduitType.connectionPathComparator();
        if (endpointConnections.size() > 2 && pathComparator instanceof PriorityConnectionPathComparator byPriority
            && (conduitType == EIOConduitTypes.ITEM.get() || conduitType == EIOConduitTypes.FLUID.get() || conduitType == EIOConduitTypes.ENERGY.get())) {
            // Built-in priorities read the endpoint's configuration. It stays fixed throughout this cache update.
            Map<ConduitBlockConnection, Integer> priorities = Maps.newHashMapWithExpectedSize(endpointConnections.size());
            for (var endpoint : endpointConnections.values()) {
                priorities.put(endpoint, byPriority.priorityGetter().apply(endpoint));
            }
            pathComparator = new PriorityConnectionPathComparator(priorities::get, byPriority.fallbackComparator());
        }

        for (var connectionsList : accessiblePathsByConnection.values()) {
            connectionsList.sort(pathComparator);
        }

        for (var connectionsList : extractConnectionsByInsert.values()) {
            connectionsList.sort(pathComparator);
        }

        for (var connectionsList : insertConnectionsByExtract.values()) {
            connectionsList.sort(pathComparator);
        }

        haveConnectionsChanged = false;
    }

    /**
     * Called whenever a network is created, split or merged.
     * For other network mutations, partial cache modifications are supported.
     */
    private void rebuildCache() {
        // Clear partial update lists
        dirtyNodes.clear();

        // Clear all caches
        areTickableConduitsValid = false;

        nodesByChunkPos.clear();
        tickingNodes.clear();
        endpointConnections.clear();
        accessiblePathsByConnection.clear();
        insertConnections.clear();
        extractConnections.clear();
        insertConnectionsByChannel.clear();
        extractConnectionsByChannel.clear();
        insertConnectionsByExtract.clear();
        extractConnectionsByInsert.clear();

        // Add each ticking node into the caches.
        for (var node : nodes()) {
            // Put nodes into the position map.
            addNodeToPositionMaps(node, true);

            if (node.isTicking()) {
                addTickingNode(node);
            }
        }

        // Populate channel set
        updateChannelList();

        // Sort all lists
        sortConnectionLists();

        // Fire chunk coverage update
        if (onChunkCoverageChanged != null) {
            onChunkCoverageChanged.accept(this);
        }

        // Rebuild complete
        shouldRebuildCache = false;
        haveConnectionsChanged = false;
    }

    private void addNodeToPositionMaps(ConduitNodeImpl node, boolean isRebuild) {
        // Put nodes into the position map.
        long chunk = ChunkPos.asLong(node.pos());
        boolean isNewChunk = !nodesByChunkPos.containsKey(chunk);
        nodesByChunkPos.put(chunk, node);

        if (!isRebuild && isNewChunk) {
            notifyChunkChanged(chunk);
        }
    }

    private void removeNodeFromPositionMaps(ConduitNodeImpl node) {
        // Put nodes into the position map.
        long chunk = ChunkPos.asLong(node.pos());
        nodesByChunkPos.remove(chunk, node);

        boolean isRemovedChunk = !nodesByChunkPos.containsKey(chunk);
        if (isRemovedChunk) {
            notifyChunkChanged(chunk);
        }
    }

    // endregion

    // region Network Impl

    @Override
    protected ConduitNetworkImpl createEmpty() {
        return new ConduitNetworkImpl(conduitType);
    }

    public static void connectAdjacentNodes(ConduitNodeImpl node, ConduitNodeImpl neighbor, Consumer<ConduitNetworkImpl> onDiscard) {
        var local = node.getNetwork();
        var adjacent = neighbor.getNetwork();
        if (local != adjacent && local.nodeCount() == 1 && adjacent.nodeCount() > 1
            && local.context == null && adjacent.context == null && node.isLoaded() && !hasEndpoint(node)) {
            // A plain new segment has neither existing routes nor context whose direction must be preserved.
            adjacent.connect(neighbor, node, onDiscard);
        } else {
            local.connect(node, neighbor, onDiscard);
        }
    }

    private static boolean hasEndpoint(ConduitNodeImpl node) {
        for (var side : Direction.values()) {
            if (node.isConnectedToBlock(side)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onNodeAdded(ConduitNodeImpl node) {
        markSaveDirty();
        // Always recompute what conduits can tick - it's cheap to do.
        areTickableConduitsValid = false;

        // Increment node count if loaded (constructor will initialize the values)
        if (nodeCountByConduit != null) {
            nodeCountByConduit.compute(node.conduit(), (k, count) -> count == null ? 1 : count + 1);
        }

        // If called during super constructor
        // TODO: Review this behaviour...
        if (nodesByChunkPos == null) {
            return;
        }

        if (shouldRebuildCache) {
            return;
        }

        addNodeToPositionMaps(node, false);
        dirtyNodes.add(node);
    }

    @Override
    protected void onNodeRemoved(ConduitNodeImpl node) {
        markSaveDirty();
        // Always recompute what conduits can tick - it's cheap to do.
        areTickableConduitsValid = false;

        if (shouldRebuildCache) {
            // A split has already recomputed the surviving nodes and excluded the removed node.
            recomputeNodeCounts();
            return;
        }

        nodeCountByConduit.compute(node.conduit(), (k, count) -> count == null || count == 1 ? null : count - 1);
        removeNodeFromPositionMaps(node);
        if (!shouldRebuildCache) {
            dirtyNodes.add(node);
        }
    }

    @Override
    protected void onNodeRemoved(ConduitNodeImpl node, int degree) {
        if (degree > 1) {
            shouldRebuildCache = true;
        }
        super.onNodeRemoved(node, degree);
    }

    @Override
    protected void onNodesConnected(ConduitNodeImpl first, ConduitNodeImpl second, boolean added) {
        super.onNodesConnected(first, second, added);
        if (added) {
            markSaveDirty();
            // A new leaf cannot shorten any existing path. A shortcut between old nodes can.
            if (supportsCaching && !shouldRebuildCache && neighbors(first).size() > 1 && neighbors(second).size() > 1) {
                shouldRebuildCache = true;
            }
        }
    }

    @Override
    protected void onNodesDisconnected(ConduitNodeImpl first, ConduitNodeImpl second, boolean removed) {
        super.onNodesDisconnected(first, second, removed);
        if (removed) {
            markSaveDirty();
            shouldRebuildCache = true;
        }
    }

    @Override
    protected void onMerged(ConduitNetworkImpl other) {
        markSaveDirty();
        if (context != null && other.context != null) {
            context = context.mergeWith(other.castContext());
        } else if (context == null && other.context != null) {
            context = other.context;
        }

        if (context == null && other.nodeCount() == 1) {
            onNodeAdded(other.nodes().iterator().next());
            return;
        }

        // Fully recompute node counts
        recomputeNodeCounts();

        // The cache will need to be rebuilt
        shouldRebuildCache = true;
    }

    private <Z extends ConduitNetworkContext<Z>> Z castContext() {
        // noinspection unchecked
        return (Z) Objects.requireNonNull(context);
    }

    @Override
    protected void onGraphSplit(Set<ConduitNetworkImpl> newNetworks) {
        markSaveDirty();
        shouldRebuildCache = true;

        // Fully recompute node counts
        recomputeNodeCounts();

        if (context == null) {
            return;
        }

        // Collect all networks for splitting
        var allNetworks = Stream.concat(Stream.of(this), newNetworks.stream()).collect(Collectors.toSet());

        // Handle the new graphs first
        for (var newNetwork : newNetworks) {
            newNetwork.context = context.split(newNetwork, allNetworks);
            if (newNetwork.context == context) {
                throw new IllegalStateException("Splitting context for a network of '" + EnderIORegistries.CONDUIT_TYPE.getKey(conduitType)
                        + "' resulted in the same context for multiple networks.");
            }

            newNetwork.shouldRebuildCache = true;
        }

        var newContext = context.split(this, allNetworks);
        if (newContext == context) {
            throw new IllegalStateException("Splitting context for a network of '" + EnderIORegistries.CONDUIT_TYPE.getKey(conduitType)
                    + "' resulted in the same context for multiple networks.");
        }

        context = newContext;
    }

    // endregion

    public void addCrashInfo(CrashReportCategory category) {
        category.setDetail("ShouldRebuildCache", shouldRebuildCache);
        category.setDetail("HaveConnectionsChanged", haveConnectionsChanged);
        category.setDetail("NodeCount", nodeCount());
        category.setDetail("TickingNodeCount", tickingNodes.size());
        category.setDetail("NodesByChunkPos", nodesByChunkPos.size());
        category.setDetail("DirtyNodes", dirtyNodes.size());
        category.setDetail("AllChannels", allChannels.size());
        category.setDetail("InsertConnections", insertConnections.size());
        category.setDetail("ExtractConnections", extractConnections.size());
    }
}
