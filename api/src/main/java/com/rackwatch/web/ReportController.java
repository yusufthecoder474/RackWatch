package com.rackwatch.web;

import com.rackwatch.service.ReportService;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/reports/readings.xlsx?from=...&to=... — the readings in
 * the date range as an .xlsx file. Both bounds are required and the
 * range is bounded (at most 31 days, at most 10,000 readings — see
 * ReportService); an out-of-bounds request is a 400. The endpoint
 * needs the API key like every /api route, so the dashboard downloads
 * it with fetch + a blob, not a plain link (a link cannot send the
 * X-API-Key header). */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping(value = "/readings.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> readingsReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        byte[] bytes = reports.buildReport(from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("rackwatch-readings.xlsx")
                                .build()
                                .toString())
                .contentType(XLSX)
                .body(bytes);
    }
}
