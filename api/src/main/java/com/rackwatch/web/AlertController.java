package com.rackwatch.web;

import com.rackwatch.model.AlertState;
import com.rackwatch.service.AlertService;
import com.rackwatch.web.dto.AckRequest;
import com.rackwatch.web.dto.AlertResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/alerts and POST /api/v1/alerts/{id}/ack. The report
 * endpoint lives in ReportController (GET /api/v1/reports/readings.xlsx). */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertResponse> listAlerts(
            @RequestParam(defaultValue = "OPEN") AlertState state) {
        return alerts.listAlerts(state);
    }

    @PostMapping("/{id}/ack")
    public AlertResponse acknowledge(
            @PathVariable long id,
            @RequestBody @Valid AckRequest request) {
        return alerts.acknowledge(id, request.ackedBy());
    }
}
