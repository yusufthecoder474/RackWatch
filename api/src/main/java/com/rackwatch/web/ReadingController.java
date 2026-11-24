package com.rackwatch.web;

import com.rackwatch.service.ReadingIngestionService;
import com.rackwatch.service.ReadingQueryService;
import com.rackwatch.web.dto.IngestionResult;
import com.rackwatch.web.dto.ReadingRequest;
import com.rackwatch.web.dto.ReadingResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/readings — batch ingestion from the daemon (POST) and
 * history reads for the dashboard chart (GET). The batch size limit
 * (max 100) is enforced here by @Size; the 64 KiB body limit is
 * enforced earlier by the request-size filter (Part 2, security
 * package), which counts bytes actually read. */
@RestController
@RequestMapping("/api/v1/readings")
@Validated
public class ReadingController {

    private final ReadingIngestionService ingestion;
    private final ReadingQueryService query;

    public ReadingController(ReadingIngestionService ingestion, ReadingQueryService query) {
        this.ingestion = ingestion;
        this.query = query;
    }

    @PostMapping
    public IngestionResult ingest(
            @RequestBody @Size(min = 1, max = 100,
                    message = "Batch must contain 1 to 100 readings")
            List<@Valid ReadingRequest> requests) {
        return ingestion.ingest(requests);
    }

    /** History for one sensor in [from, to], oldest first, at most
     * 1,000 readings (the most recent ones in the range — see
     * ReadingQueryService). Both bounds are optional: omitted, the
     * window is the last 24 hours. */
    @GetMapping
    public List<ReadingResponse> history(
            @RequestParam String sensorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return query.history(sensorId, from, to);
    }
}
