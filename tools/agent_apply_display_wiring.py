from pathlib import Path

path = Path('plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java')
text = path.read_text()
replacements = [
    (
        'import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;\n',
        'import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;\n'
        'import net.enthusia.loreitems.application.DisplayItemObservationUseCase;\n',
    ),
    (
        'import net.enthusia.loreitems.application.PersistingDirectDeliveryExecutionUseCase;\n',
        'import net.enthusia.loreitems.application.PersistingDirectDeliveryExecutionUseCase;\n'
        'import net.enthusia.loreitems.application.PersistingDisplayItemObservationUseCase;\n',
    ),
    (
        'import net.enthusia.loreitems.paper.PaperDirectDeliveryWorker;\n',
        'import net.enthusia.loreitems.paper.PaperDirectDeliveryWorker;\n'
        'import net.enthusia.loreitems.paper.PaperDisplayItemListener;\n',
    ),
    (
        'import net.enthusia.loreitems.sqlite.SQLiteDirectDeliveryRepository;\n',
        'import net.enthusia.loreitems.sqlite.SQLiteDirectDeliveryRepository;\n'
        'import net.enthusia.loreitems.sqlite.SQLiteDisplayItemObservationStore;\n',
    ),
    (
        '    private final AtomicReference<VoidLossUseCase> voidLossDelegate =\n'
        '            new AtomicReference<>(unavailableVoidLossUseCase());\n',
        '    private final AtomicReference<VoidLossUseCase> voidLossDelegate =\n'
        '            new AtomicReference<>(unavailableVoidLossUseCase());\n'
        '    private final AtomicReference<DisplayItemObservationUseCase> displayObservationDelegate =\n'
        '            new AtomicReference<>(unavailableDisplayItemObservationUseCase());\n',
    ),
    (
        '    private volatile PaperTrackedItemProtectionListener protectionListener;\n'
        '    private volatile boolean stopping;\n',
        '    private volatile PaperTrackedItemProtectionListener protectionListener;\n'
        '    private volatile PaperDisplayItemListener displayItemListener;\n'
        '    private volatile boolean stopping;\n',
    ),
    ('        activateProtectionListener();\n', '        activateProtectionListeners();\n'),
    (
        '    private void activateProtectionListener() {\n'
        '        try {\n'
        '            PaperTrackedItemProtectionListener listener =\n'
        '                    new PaperTrackedItemProtectionListener(\n'
        '                            this,\n'
        '                            voidLossDelegate::get,\n'
        '                            configuration.get().current().mutationBudgetPerTick());\n'
        '            listener.start();\n'
        '            protectionListener = listener;\n'
        '        } catch (RuntimeException exception) {\n'
        '            getLogger().log(\n'
        '                    java.util.logging.Level.SEVERE,\n'
        '                    "Could not start tracked-item protection listeners.",\n'
        '                    exception);\n'
        '        }\n'
        '    }\n',
        '    private void activateProtectionListeners() {\n'
        '        PaperTrackedItemProtectionListener protection = null;\n'
        '        PaperDisplayItemListener display = null;\n'
        '        try {\n'
        '            int mutationBudget = configuration.get().current().mutationBudgetPerTick();\n'
        '            protection = new PaperTrackedItemProtectionListener(\n'
        '                    this,\n'
        '                    voidLossDelegate::get,\n'
        '                    mutationBudget);\n'
        '            display = new PaperDisplayItemListener(\n'
        '                    this,\n'
        '                    displayObservationDelegate::get,\n'
        '                    mutationBudget);\n'
        '            protection.start();\n'
        '            display.start();\n'
        '            protectionListener = protection;\n'
        '            displayItemListener = display;\n'
        '        } catch (RuntimeException exception) {\n'
        '            if (display != null) {\n'
        '                display.close();\n'
        '            }\n'
        '            if (protection != null) {\n'
        '                protection.close();\n'
        '            }\n'
        '            getLogger().log(\n'
        '                    java.util.logging.Level.SEVERE,\n'
        '                    "Could not start tracked-item protection listeners.",\n'
        '                    exception);\n'
        '        }\n'
        '    }\n',
    ),
    (
        '            voidLossDelegate.set(unavailableVoidLossUseCase());\n'
        '        }\n'
        '        PaperTrackedItemProtectionListener listener = protectionListener;\n',
        '            voidLossDelegate.set(unavailableVoidLossUseCase());\n'
        '            displayObservationDelegate.set(unavailableDisplayItemObservationUseCase());\n'
        '        }\n'
        '        PaperDisplayItemListener display = displayItemListener;\n'
        '        if (display != null) {\n'
        '            display.close();\n'
        '        }\n'
        '        PaperTrackedItemProtectionListener listener = protectionListener;\n',
    ),
    (
        '        VoidLossUseCase voidLossUseCase = new PersistingVoidLossUseCase(\n'
        '                new SQLiteVoidLossStore(runtime),\n'
        '                clock,\n'
        '                Duration.ofSeconds(loaded.deliveryClaimLeaseSeconds()));\n'
        '        if (publishWritableServices(\n',
        '        VoidLossUseCase voidLossUseCase = new PersistingVoidLossUseCase(\n'
        '                new SQLiteVoidLossStore(runtime),\n'
        '                clock,\n'
        '                Duration.ofSeconds(loaded.deliveryClaimLeaseSeconds()));\n'
        '        DisplayItemObservationUseCase displayObservationUseCase =\n'
        '                new PersistingDisplayItemObservationUseCase(\n'
        '                        new SQLiteDisplayItemObservationStore(runtime),\n'
        '                        clock);\n'
        '        if (publishWritableServices(\n',
    ),
    (
        '                adoptHeldItemUseCase,\n'
        '                voidLossUseCase)) {\n',
        '                adoptHeldItemUseCase,\n'
        '                voidLossUseCase,\n'
        '                displayObservationUseCase)) {\n',
    ),
    (
        '                    "Durable storage is active; definition creation, adoption, queued direct "\n'
        '                            + "delivery, protection, and terminal void loss are available.");\n',
        '                    "Durable storage is active; definition creation, adoption, queued direct "\n'
        '                            + "delivery, protection, display observations, and terminal void loss "\n'
        '                            + "are available.");\n',
    ),
    (
        '            AdoptHeldItemUseCase adoptHeldItemUseCase,\n'
        '            VoidLossUseCase voidLossUseCase) {\n',
        '            AdoptHeldItemUseCase adoptHeldItemUseCase,\n'
        '            VoidLossUseCase voidLossUseCase,\n'
        '            DisplayItemObservationUseCase displayObservationUseCase) {\n',
    ),
    (
        '        Objects.requireNonNull(voidLossUseCase, "voidLossUseCase");\n'
        '        synchronized (lifecycleLock) {\n',
        '        Objects.requireNonNull(voidLossUseCase, "voidLossUseCase");\n'
        '        Objects.requireNonNull(displayObservationUseCase, "displayObservationUseCase");\n'
        '        synchronized (lifecycleLock) {\n',
    ),
    (
        '            voidLossDelegate.set(voidLossUseCase);\n'
        '            return true;\n',
        '            voidLossDelegate.set(voidLossUseCase);\n'
        '            displayObservationDelegate.set(displayObservationUseCase);\n'
        '            return true;\n',
    ),
    (
        '            voidLossDelegate.set(unavailableVoidLossUseCase());\n'
        '            return true;\n',
        '            voidLossDelegate.set(unavailableVoidLossUseCase());\n'
        '            displayObservationDelegate.set(unavailableDisplayItemObservationUseCase());\n'
        '            return true;\n',
    ),
    (
        '    private static String safeMessage(Exception exception) {\n',
        '    private static DisplayItemObservationUseCase unavailableDisplayItemObservationUseCase() {\n'
        '        return request -> CompletableFuture.completedFuture(\n'
        '                DisplayItemObservationUseCase.Result.of(\n'
        '                        DisplayItemObservationUseCase.Status.SERVICE_UNAVAILABLE,\n'
        '                        "Durable storage is unavailable; display evidence was not changed."));\n'
        '    }\n\n'
        '    private static String safeMessage(Exception exception) {\n',
    ),
]
for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Expected one match, found {count}: {old[:80]!r}')
    text = text.replace(old, new, 1)
path.write_text(text)
