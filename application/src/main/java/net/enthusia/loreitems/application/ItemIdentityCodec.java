package net.enthusia.loreitems.application;

public interface ItemIdentityCodec<T> {
    int currentVersion();

    T writeIdentity(T item, LoreItemIdentity identity);

    T clearIdentity(T item);

    ItemIdentityReadResult readIdentity(T item);
}
