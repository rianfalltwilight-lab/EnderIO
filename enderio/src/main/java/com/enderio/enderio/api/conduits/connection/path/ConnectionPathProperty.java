package com.enderio.enderio.api.conduits.connection.path;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * Represents a connection path property, and explains how values from an entire path should be aggregated.
 * Provides some helpers for creating integer properties.
 * @param <T> the value type of the property
 */
@ApiStatus.AvailableSince("8.1.0")
public class ConnectionPathProperty<T> {
    private final Function<List<T>, Optional<T>> aggregator;
    private final T defaultValue;

    public ConnectionPathProperty(Function<List<T>, Optional<T>> aggregator, T defaultValue) {
        this.aggregator = aggregator;
        this.defaultValue = defaultValue;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public T aggregate(List<T> values) {
        return aggregator.apply(values).orElse(defaultValue);
    }

    /** Built-in properties for which reducing a prefix produces the same result as reducing the whole path. */
    @ApiStatus.Internal
    public static final class Reducible<T> extends ConnectionPathProperty<T> {
        private final BinaryOperator<T> operator;

        Reducible(BinaryOperator<T> operator, T defaultValue) {
            super(values -> values.stream().reduce(operator), defaultValue);
            this.operator = operator;
        }

        public T append(T prefix, T value) {
            return operator.apply(prefix, value);
        }
    }

    // region Integer Helpers

    public static ConnectionPathProperty<Integer> minInt(int defaultValue) {
        return new Reducible<>(BinaryOperator.minBy(Integer::compare), defaultValue);
    }

    public static ConnectionPathProperty<Integer> maxInt(int defaultValue) {
        return new ConnectionPathProperty<>(values -> values.stream().max(Integer::compare), defaultValue);
    }

    public static ConnectionPathProperty<Integer> sumInt(int defaultValue) {
        return new ConnectionPathProperty<>(values -> {
            if (values.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(values.stream().reduce(0, Integer::sum));
        }, defaultValue);
    }

    public static ConnectionPathProperty<Integer> avgInt(int defaultValue) {
        return new ConnectionPathProperty<>(values -> {
            if (values.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(values.stream().reduce(0, Integer::sum) / values.size());
        }, defaultValue);
    }

    // endregion

    // region Long Helpers

    public static ConnectionPathProperty<Long> minLong(long defaultValue) {
        return new Reducible<>(BinaryOperator.minBy(Long::compare), defaultValue);
    }

    public static ConnectionPathProperty<Long> maxLong(long defaultValue) {
        return new ConnectionPathProperty<>(values -> values.stream().max(Long::compare), defaultValue);
    }

    public static ConnectionPathProperty<Long> sumLong(long defaultValue) {
        return new ConnectionPathProperty<>(values -> {
            if (values.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(values.stream().reduce(0L, Long::sum));
        }, defaultValue);
    }

    public static ConnectionPathProperty<Long> avgLong(long defaultValue) {
        return new ConnectionPathProperty<>(values -> {
            if (values.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(values.stream().reduce(0L, Long::sum) / values.size());
        }, defaultValue);
    }

    // endregion
}
