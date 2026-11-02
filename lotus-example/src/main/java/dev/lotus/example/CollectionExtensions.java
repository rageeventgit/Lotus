package dev.lotus.example;

import dev.lotus.Extension;
import dev.lotus.This;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Generic extensions. Declared on {@link Collection}, so they apply to every
 * List, Set, Queue, ... just like Kotlin extensions on a supertype.
 */
@Extension(Collection.class)
public final class CollectionExtensions {
    private CollectionExtensions() {
    }

    public static <T> T firstOrNull(@This Collection<T> collection) {
        return collection.isEmpty() ? null : collection.iterator().next();
    }

    public static <T> List<T> filtered(@This Collection<T> collection, Predicate<? super T> predicate) {
        List<T> result = new ArrayList<>();
        for (T item : collection) {
            if (predicate.test(item)) {
                result.add(item);
            }
        }
        return result;
    }
}
