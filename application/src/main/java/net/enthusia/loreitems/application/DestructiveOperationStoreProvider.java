package net.enthusia.loreitems.application;

/** Exposes a co-located destructive store without coupling Paper adapters to SQLite. */
public interface DestructiveOperationStoreProvider {
    DestructiveOperationStore destructiveOperationStore();
}
