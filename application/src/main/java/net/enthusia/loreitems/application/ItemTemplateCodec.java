package net.enthusia.loreitems.application;

public interface ItemTemplateCodec<T> {
    int currentVersion();

    EncodedItemTemplate encode(T item);

    T decode(EncodedItemTemplate encodedTemplate);
}
