package net.enthusia.loreitems.paper;

import java.util.UUID;

record DuplicateChoice(UUID anomalyId, long stateRevision, long firstSeenAt) {}
