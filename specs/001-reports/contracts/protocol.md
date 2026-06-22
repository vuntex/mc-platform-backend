# Contract — plugin-protocol (Reports)

Package `com.mcplatform.protocol.report`, **JDK-only** (Prinzip 4 — kein Spring/JSON/jOOQ). Records sind
reine Daten; JSON-Mapping passiert in Plugin/Backend, nie hier. Wire-Format über den bestehenden
`MessageEnvelope`/`MessageCodec`.

## Channels
```java
public final class ReportChannels {
    public static final String CHANGED = Channels.of("report", "changed"); // "mc:report:changed"
}
```

## Endpoints (EndpointDescriptor, im etablierten Muster)
```java
public final class ReportEndpoints {
    public static final EndpointDescriptor<CreateReportRequest, ReportResponse> CREATE =
        new EndpointDescriptor<>(HttpMethod.POST, "/api/reports",
            CreateReportRequest.class, ReportResponse.class);

    public static final EndpointDescriptor<Void, ReportResponse[]> LIST_OPEN =
        new EndpointDescriptor<>(HttpMethod.GET, "/api/reports/open",
            Void.class, ReportResponse[].class);

    public static final EndpointDescriptor<ChangeStatusRequest, ReportResponse> CHANGE_STATUS =
        new EndpointDescriptor<>(HttpMethod.POST, "/api/reports/{id}/status",
            ChangeStatusRequest.class, ReportResponse.class);
}
```
*(`LIST_OPEN` trägt den `?staff=`-Query-Parameter clientseitig; der EndpointDescriptor beschreibt nur
den Pfad — wie bei `PunishmentEndpoints.LIST_TEMPLATES`.)*

## DTOs (records)
```java
record ChatMessage(UUID sender, String text, long timestampEpochMilli) {}

record CreateReportRequest(UUID reporter, UUID target, String category, String detail,
                           List<ChatMessage> chatContext) {}   // chatContext darf null/leer sein

record ChangeStatusRequest(String newStatus, UUID handledBy) {}

record ReportResponse(UUID id, UUID reporter, UUID target, String category, String detail,
                      String status, long createdAtEpochMilli,
                      UUID lastHandledBy, long lastStatusChangeAtEpochMilli,
                      List<ChatMessage> chatContext, long version) {}
```
> `List<ChatMessage>` in einem Record ist JDK-only — keine JSON-Lib nötig. Die Serialisierung
> übernimmt Jackson in api-rest/Plugin.

## Live-Event
```java
record ReportChangedEvent(
    UUID reportId, UUID reporter, UUID target,
    String category, String status,
    String changeType,           // "CREATED" | "STATUS_CHANGED"
    long timestampEpochMilli) {} // KEIN Chat-Kontext (FR-015)
```

### ReportChangedEventCodec implements MessageCodec<ReportChangedEvent>
- `messageType()` → `"report.changed"`
- Wire (Pipe-delimited, String-Felder URL-encoded — wie PunishmentChangedEventCodec), 7 Felder:
  `reportId|reporter|target|category|status|changeType|timestampEpochMilli`
- Envelope: `v1|report.changed|<payload>`

## Einziger geteilter Eingriff
`PlatformProtocol.create()` um `ReportChangedEventCodec.INSTANCE` ergänzen (eine Zeile) — sonst nichts.
Danach `:plugin-protocol:publishToMavenLocal`, im Plugin `build --refresh-dependencies`.
