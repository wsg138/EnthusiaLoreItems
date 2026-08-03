package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.LoreInstanceId;

public interface CurrentStateRepository {
    CompletionStage<Void> create(InstanceCurrentState currentState);

    CompletionStage<Optional<InstanceCurrentState>> findByInstance(
            LoreInstanceId instanceId);

    CompletionStage<Boolean> compareAndSet(
            LoreInstanceId instanceId,
            long expectedStateRevision,
            InstanceCurrentState targetState);
}
