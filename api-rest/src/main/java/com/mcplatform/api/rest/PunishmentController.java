package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.PunishmentMapper;
import com.mcplatform.application.punishment.PunishmentService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentId;
import com.mcplatform.protocol.punishment.IssueFromTemplateRequest;
import com.mcplatform.protocol.punishment.IssueRequest;
import com.mcplatform.protocol.punishment.PunishmentResponse;
import com.mcplatform.protocol.punishment.RevokeRequest;
import com.mcplatform.protocol.punishment.TemplateResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for punishments (web interface / admin / plugin). Permission checks are
 * backend-authoritative and live in the service, before any write. State changes publish a
 * {@code PunishmentChangedEvent} on {@code mc:punishment:changed}.
 */
@RestController
public class PunishmentController {

    private final PunishmentService punishments;
    private final Clock clock;

    public PunishmentController(PunishmentService punishments, Clock clock) {
        this.punishments = punishments;
        this.clock = clock;
    }

    @GetMapping("/api/players/{uuid}/punishments/active")
    public PunishmentResponse[] active(@PathVariable UUID uuid) {
        Instant now = clock.instant();
        return punishments.activeFor(PlayerId.of(uuid)).stream()
                .map(p -> PunishmentMapper.punishmentResponse(p, now))
                .toArray(PunishmentResponse[]::new);
    }

    @PostMapping("/api/players/{uuid}/punishments")
    public PunishmentResponse issue(@PathVariable UUID uuid, @RequestBody IssueRequest request) {
        Punishment p = punishments.issue(
                PlayerId.of(uuid),
                PunishmentMapper.parseType(request.type()),
                PunishmentMapper.requireReason(request.reason()),
                PunishmentMapper.duration(request.durationMillis()),
                PlayerId.of(request.issuedBy()),
                PunishmentMapper.txId(request.transactionId()),
                PunishmentMapper.sourceOr(request.source(), "WEB"));
        return PunishmentMapper.punishmentResponse(p, clock.instant());
    }

    @PostMapping("/api/players/{uuid}/punishments/from-template")
    public PunishmentResponse issueFromTemplate(@PathVariable UUID uuid,
            @RequestBody IssueFromTemplateRequest request) {
        Punishment p = punishments.issueFromTemplate(
                PlayerId.of(uuid),
                request.templateKey(),
                request.reason(),
                PlayerId.of(request.issuedBy()),
                PunishmentMapper.txId(request.transactionId()),
                PunishmentMapper.sourceOr(request.source(), "WEB:template"));
        return PunishmentMapper.punishmentResponse(p, clock.instant());
    }

    @PostMapping("/api/punishments/{id}/revoke")
    public PunishmentResponse revoke(@PathVariable UUID id, @RequestBody RevokeRequest request) {
        Punishment p = punishments.revoke(
                PunishmentId.of(id),
                PlayerId.of(request.revokedBy()),
                PunishmentMapper.requireReason(request.reason()),
                PunishmentMapper.txId(request.transactionId()),
                PunishmentMapper.sourceOr(request.source(), "WEB"));
        return PunishmentMapper.punishmentResponse(p, clock.instant());
    }

    /** All templates with a per-template {@code canApply} flag for the querying member ({@code ?staff=}). */
    @GetMapping("/api/punishments/templates")
    public TemplateResponse[] templates(@RequestParam UUID staff) {
        return punishments.listTemplates(PlayerId.of(staff)).stream()
                .map(PunishmentMapper::templateResponse)
                .toArray(TemplateResponse[]::new);
    }
}
