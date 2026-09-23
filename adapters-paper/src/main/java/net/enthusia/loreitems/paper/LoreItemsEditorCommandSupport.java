package net.enthusia.loreitems.paper;

final class LoreItemsEditorCommandSupport {
    private static final int CANCEL_ARGUMENT_COUNT = 2;

    private LoreItemsEditorCommandSupport() {}

    static boolean isCancel(String[] arguments) {
        return arguments.length == CANCEL_ARGUMENT_COUNT
                && "cancel".equalsIgnoreCase(arguments[1]);
    }
}
