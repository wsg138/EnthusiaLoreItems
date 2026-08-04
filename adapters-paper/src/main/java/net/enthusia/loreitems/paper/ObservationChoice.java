package net.enthusia.loreitems.paper;

import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;

record ObservationChoice(
        long observationId,
        LocationDescriptor location,
        InstanceObservation.Confidence confidence,
        String source,
        long observedAt) {}
