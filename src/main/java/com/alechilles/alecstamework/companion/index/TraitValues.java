package com.alechilles.alecstamework.companion.index;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;

/**
 * The trait values of a {@link CompanionSummary}: an unmodifiable map held as two parallel
 * arrays, because every record in the index keeps one and a {@code LinkedHashMap} of four
 * boxed values costs about four times as much. Iteration is in the order of the map it was
 * copied from, which keeps saved files stable. Lookups scan the ids; a companion has a
 * handful of traits. Equality, hash code and text are those of any {@link Map}.
 *
 * <p>Immutable after construction, so it is safe to share across threads.</p>
 */
final class TraitValues extends AbstractMap<String, Double> {
    private static final TraitValues EMPTY = new TraitValues(new String[0], new double[0]);

    private final String[] ids;
    private final double[] values;

    private TraitValues(String[] ids, double[] values) {
        this.ids = ids;
        this.values = values;
    }

    /**
     * Returns {@code traits} itself when it is already compact, otherwise a copy in its
     * iteration order. A null id or value is rejected.
     */
    @Nonnull
    static TraitValues copyOf(@Nonnull Map<String, Double> traits) {
        if (traits instanceof TraitValues compact) {
            return compact;
        }
        int size = traits.size();
        if (size == 0) {
            return EMPTY;
        }
        String[] ids = new String[size];
        double[] values = new double[size];
        int count = 0;
        for (Map.Entry<String, Double> trait : traits.entrySet()) {
            if (count == size) {
                throw new IllegalArgumentException("traits changed while being copied");
            }
            ids[count] = Objects.requireNonNull(trait.getKey(), "trait id");
            values[count] = Objects.requireNonNull(trait.getValue(), "trait value");
            count++;
        }
        if (count != size) {
            throw new IllegalArgumentException("traits changed while being copied");
        }
        return new TraitValues(ids, values);
    }

    @Override
    public int size() {
        return ids.length;
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOf(key) >= 0;
    }

    @Override
    public Double get(Object key) {
        int index = indexOf(key);
        return index < 0 ? null : values[index];
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super Double> action) {
        Objects.requireNonNull(action, "action");
        for (int i = 0; i < ids.length; i++) {
            action.accept(ids[i], values[i]);
        }
    }

    @Override
    public Set<Map.Entry<String, Double>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return ids.length;
            }

            @Override
            public Iterator<Map.Entry<String, Double>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < ids.length;
                    }

                    @Override
                    public Map.Entry<String, Double> next() {
                        if (next >= ids.length) {
                            throw new NoSuchElementException();
                        }
                        Map.Entry<String, Double> entry = Map.entry(ids[next], values[next]);
                        next++;
                        return entry;
                    }
                };
            }
        };
    }

    private int indexOf(Object key) {
        if (key != null) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(key)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
