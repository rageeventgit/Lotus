package com.acme.api;

import dev.lotus.Extension;
import dev.lotus.This;

import java.util.Collection;

@Extension(Collection.class)
public final class CollectionApiExtensions {
    public static <T> T firstOrNull(@This Collection<T> collection) {
        return collection.isEmpty() ? null : collection.iterator().next();
    }
}
