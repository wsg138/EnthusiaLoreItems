package net.enthusia.loreitems.paper;

import java.util.Optional;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;

record EvidenceData(
        Optional<InstanceCurrentState> current,
        Page<InstanceObservation> observations,
        Page<InstanceAnomaly> anomalies) {}
