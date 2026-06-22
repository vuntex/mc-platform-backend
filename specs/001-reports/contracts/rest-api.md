# Contract — REST API (Reports)

Konsistent zu `PunishmentController`/`PunishmentExceptionHandler`. JSON via Spring/Jackson in `api-rest`
(der Contract `plugin-protocol` bleibt JSON-frei). Timestamps als epoch-milli (`long`).

## Endpunkte

### 1. Report erstellen
`POST /api/reports`
```jsonc
// CreateReportRequest
{
  "reporter": "uuid",
  "target":   "uuid",
  "category": "CHEATING|BELEIDIGUNG|SPAM_WERBUNG|TEAMING_BUG_ABUSE|SONSTIGES",
  "detail":   "string (1..256)",
  "chatContext": [                       // optional, darf fehlen/leer sein
    { "sender": "uuid", "text": "string", "timestampEpochMilli": 0 }
  ]
}
```
→ `200 OK` `ReportResponse` (auch bei Dedupe-Treffer: bestehender offener Report, transparent).
Fehler: `422` (Self-Report, leeres/zu langes Detail, ungültige Kategorie, Chat zu groß, unbekanntes
Ziel), `429` (Cooldown), `400` (Body kaputt).
*Erstellen ist nicht permission-gated (Default erlaubt).*

### 2. Offene Reports auflisten (Team)
`GET /api/reports/open?staff={uuid}`
→ `200 OK` `ReportResponse[]` (Status ∈ {OPEN, IN_PROGRESS}, älteste zuerst).
Fehler: `403` (kein `report.view`).

### 3. Status ändern (Team)
`POST /api/reports/{id}/status`
```jsonc
// ChangeStatusRequest
{ "newStatus": "IN_PROGRESS|RESOLVED|REJECTED", "handledBy": "uuid" }
```
→ `200 OK` `ReportResponse`.
Fehler: `403` (kein `report.handle`), `404` (Report unbekannt), `409` (ungültiger Übergang ODER
OCC-Konflikt bei konkurrierender Änderung).

## ReportResponse
```jsonc
{
  "id": "uuid", "reporter": "uuid", "target": "uuid",
  "category": "…", "detail": "…", "status": "…",
  "createdAtEpochMilli": 0,
  "lastHandledBy": "uuid|null", "lastStatusChangeAtEpochMilli": 0,   // 0 = noch kein Wechsel
  "chatContext": [ { "sender": "uuid", "text": "…", "timestampEpochMilli": 0 } ],
  "version": 0
}
```

## Status-Code-Mapping (ReportExceptionHandler — NUR report-eigene Exceptions)
| Exception | HTTP | error-code |
|---|---|---|
| `ReportValidationException` | 422 | `report_invalid` |
| `InvalidStatusTransitionException` | 409 | `report_conflict` |
| OCC-Konflikt (Repo) | 409 | `report_conflict` |
| `ReportNotFoundException` | 404 | `report_not_found` |
| `ReportCooldownException` | 429 | `report_cooldown` |
| `PermissionDeniedException` | 403 | *(bestehendes GLOBALES Mapping — NICHT re-deklarieren)* |
| `IllegalArgumentException` | 400 | *(bestehendes Economy-Mapping)* |
