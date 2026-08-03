package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreInstanceId;

public interface ObservationRepository {
    CompletionStage<InstanceObservation> append(InstanceObservation observation);

    CompletionStage<Optional<InstanceObservation>> findById(long observationId);

    CompletionStage<Page<InstanceObservation>> listByInstance(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<Page<InstanceObservation>> listByLocation(
            LocationDescriptor.Type locationType,
            String locationKey,
            PageRequest request);
}
