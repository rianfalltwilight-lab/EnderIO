package com.enderio.enderio.content.conduits.network.pathing;

import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.connection.path.ConnectionPathProperty;
import com.enderio.enderio.api.conduits.network.node.ConduitNode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Finds shortest paths by hop count, in the graph's neighbor iteration order. */
public class BreadthFirstPathingStrategy implements ConduitPathingStrategy {
    private record Step(ConduitNode node, @Nullable Step parent, int length, Map<ConnectionPathProperty<?>, Object> properties,
                        @Nullable Map<ConnectionPathProperty<?>, Object> reduced) {}
    private record Route(int length, Map<ConnectionPathProperty<?>, Object> properties) {}

    @Override
    public Optional<ConduitConnectionPath> findPath(ConduitBlockConnection from, ConduitBlockConnection to, PathfindingContext context) {
        return pathsFrom(from.node(), Set.of(to.node()), context).findPath(from, to);
    }

    @Override
    public SourcePaths pathsFrom(ConduitNode source, Set<ConduitNode> targets, PathfindingContext context) {
        var remaining = new HashSet<>(targets);
        var visited = new HashSet<ConduitNode>();
        var queue = new ArrayDeque<Step>();
        var routes = new HashMap<ConduitNode, Route>();
        var root = visit(source, null, context);
        queue.add(root);
        visited.add(source);
        if (remaining.remove(source)) {
            routes.put(source, aggregateRoute(root));
        }

        while (!remaining.isEmpty() && !queue.isEmpty()) {
            var current = queue.remove();
            for (var neighbor : context.neighbors(current.node())) {
                if (!visited.add(neighbor)) {
                    continue;
                }
                var step = visit(neighbor, current, context);
                queue.add(step);
                if (remaining.remove(neighbor)) {
                    routes.put(neighbor, aggregateRoute(step));
                    if (remaining.isEmpty()) {
                        break;
                    }
                }
            }
        }

        return (from, to) -> {
            var route = routes.get(to.node());
            return route == null ? Optional.empty() : Optional.of(new ConduitConnectionPath(from, to, route.length(), route.properties()));
        };
    }

    private static Step visit(ConduitNode node, @Nullable Step parent, PathfindingContext context) {
        var properties = snapshotProperties(context.collectNodeProperties(node), parent == null);
        Map<ConnectionPathProperty<?>, Object> prefix = parent == null ? Map.of() : parent.reduced();
        return new Step(node, parent, parent == null ? 1 : parent.length() + 1, properties, extendPrefix(prefix, properties));
    }

    private static Map<ConnectionPathProperty<?>, Object> snapshotProperties(Map<ConnectionPathProperty<?>, Object> properties, boolean source) {
        if (properties.isEmpty()) {
            return Map.of();
        }
        if (properties.size() == 1) {
            var entry = properties.entrySet().iterator().next();
            if (!source && entry.getValue() == null) {
                return Collections.singletonMap(entry.getKey(), null);
            }
            return Map.of(entry.getKey(), entry.getValue());
        }
        // The original BFS rejected null at the source but passed null at later nodes to addon aggregators.
        return source ? Map.copyOf(properties) : new HashMap<>(properties);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static @Nullable Map<ConnectionPathProperty<?>, Object> extendPrefix(@Nullable Map<ConnectionPathProperty<?>, Object> prefix,
                                                                                 Map<ConnectionPathProperty<?>, Object> properties) {
        // Built-in item, fluid, energy and chemical paths have one minimum transfer-rate property.
        if (prefix == null || properties.isEmpty()) {
            return prefix;
        }
        if (properties.size() == 1) {
            var entry = properties.entrySet().iterator().next();
            if (entry.getKey() instanceof ConnectionPathProperty.Reducible property && entry.getValue() != null) {
                Object previous = prefix.get(property);
                if (previous != null) {
                    Object next = property.append(previous, entry.getValue());
                    return next == previous ? prefix : Map.of(property, next);
                }
                if (prefix.isEmpty()) {
                    return properties;
                }
            }
        }
        return null;
    }

    private static Route aggregateRoute(Step end) {
        if (end.reduced() != null) {
            return new Route(end.length(), end.reduced());
        }
        // Third-party aggregators see their values in source-to-destination order.
        var chain = new ArrayDeque<Step>();
        for (Step step = end; step != null; step = step.parent()) {
            chain.push(step);
        }
        var values = new HashMap<ConnectionPathProperty<?>, List<Object>>();
        for (var step : chain) {
            step.properties().forEach((property, value) -> values.computeIfAbsent(property, k -> new ArrayList<>()).add(value));
        }

        var properties = new HashMap<ConnectionPathProperty<?>, Object>();
        values.forEach((property, pathValues) -> properties.put(property, aggregateProperty(property, pathValues)));
        return new Route(end.length(), Map.copyOf(properties));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object aggregateProperty(ConnectionPathProperty property, List<Object> values) {
        return property.aggregate(values);
    }
}
