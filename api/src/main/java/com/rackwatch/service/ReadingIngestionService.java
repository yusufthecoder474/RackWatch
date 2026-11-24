package com.rackwatch.service;

import com.rackwatch.model.Reading;
import com.rackwatch.model.Sensor;
import com.rackwatch.model.SensorKind;
import com.rackwatch.repository.ReadingBatchInserter;
import com.rackwatch.repository.SensorRepository;
import com.rackwatch.web.dto.IngestionResult;
import com.rackwatch.web.dto.ReadingRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ingests one batch of readings. The whole batch is validated first:
 * an unknown sensor id or a value that does not match the sensor kind
 * rejects the entire batch with 400 and stores nothing. Valid batches
 * are stored with a single ON CONFLICT DO NOTHING batch insert, so
 * re-sent readings are skipped, not duplicated. */
@Service
public class ReadingIngestionService {

    private final SensorRepository sensors;
    private final ReadingBatchInserter inserter;
    private final List<ReadingPostProcessor> postProcessors;

    public ReadingIngestionService(
            SensorRepository sensors,
            ReadingBatchInserter inserter,
            List<ReadingPostProcessor> postProcessors) {
        this.sensors = sensors;
        this.inserter = inserter;
        this.postProcessors = postProcessors;
    }

    @Transactional
    public IngestionResult ingest(List<ReadingRequest> requests) {
        List<Reading> readings = new ArrayList<>(requests.size());
        for (ReadingRequest request : requests) {
            Sensor sensor = sensors.findById(request.sensorId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown sensor id: " + request.sensorId()));
            validateValueMatchesKind(sensor, request);
            readings.add(new Reading(sensor, request.ts(), request.tempC(), request.rhPct()));
        }
        int inserted = inserter.insertBatch(readings);
        for (ReadingPostProcessor postProcessor : postProcessors) {
            postProcessor.afterInsert(readings);
        }
        return new IngestionResult(requests.size(), inserted, requests.size() - inserted);
    }

    /** A TEMP sensor sends temp_c only; a HUMIDITY sensor sends rh_pct
     * only. The DTO's exactly-one check already guarantees one value is
     * present; this checks it is the right one for this sensor. A
     * mismatch is a client error (400), not something to store and
     * guess about later. */
    private void validateValueMatchesKind(Sensor sensor, ReadingRequest request) {
        if (sensor.getKind() == SensorKind.TEMP) {
            if (request.tempC() == null || request.rhPct() != null) {
                throw new IllegalArgumentException(
                        "Sensor " + sensor.getId() + " is a TEMP sensor: send temp_c only");
            }
        } else {
            if (request.rhPct() == null || request.tempC() != null) {
                throw new IllegalArgumentException(
                        "Sensor " + sensor.getId() + " is a HUMIDITY sensor: send rh_pct only");
            }
        }
    }
}
