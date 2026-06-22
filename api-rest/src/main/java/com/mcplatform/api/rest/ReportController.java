package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.ReportMapper;
import com.mcplatform.application.report.ReportService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportId;
import com.mcplatform.protocol.report.ChangeStatusRequest;
import com.mcplatform.protocol.report.CreateReportRequest;
import com.mcplatform.protocol.report.ReportResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for reports. Creating is open to any player (no permission gate); a report is a pure
 * accusation and never produces a punishment. Dedupe (same reporter+target open) returns the existing
 * report transparently with 200.
 */
@RestController
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/api/reports")
    public ReportResponse create(@RequestBody CreateReportRequest request) {
        Report r = reports.create(
                PlayerId.of(request.reporter()),
                PlayerId.of(request.target()),
                ReportMapper.parseCategory(request.category()),
                request.detail(),
                ReportMapper.chatContext(request.chatContext()));
        return ReportMapper.response(r);
    }

    /** Open report list for the team ({@code ?staff=<uuid>}); 403 without {@code report.view}. */
    @GetMapping("/api/reports/open")
    public ReportResponse[] open(@RequestParam UUID staff) {
        return reports.listOpen(PlayerId.of(staff)).stream()
                .map(ReportMapper::response)
                .toArray(ReportResponse[]::new);
    }

    /** Change a report's status; 403 without {@code report.handle}, 404 unknown, 409 illegal/conflicting. */
    @PostMapping("/api/reports/{id}/status")
    public ReportResponse changeStatus(@PathVariable UUID id, @RequestBody ChangeStatusRequest request) {
        Report r = reports.changeStatus(
                ReportId.of(id),
                ReportMapper.parseStatus(request.newStatus()),
                PlayerId.of(request.handledBy()));
        return ReportMapper.response(r);
    }
}
