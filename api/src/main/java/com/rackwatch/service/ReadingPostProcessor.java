package com.rackwatch.service;

import com.rackwatch.model.Reading;
import java.util.List;

/** A step that runs after a batch of readings has been stored, in the
 * same transaction. Implemented in Part 3 by the alert-evaluation step;
 * until then no bean implements it and ingestion simply stores readings.
 * This is the seam between "store the data" (Part 2) and "react to the
 * data" (Part 3). */
public interface ReadingPostProcessor {

    void afterInsert(List<Reading> readings);
}
