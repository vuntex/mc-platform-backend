# Contract — plugin-protocol: Web-Auth-Bridge

**Rein additiv, JDK-only.** Neue Klassen unter `com.mcplatform.protocol.webauth`. **Kein**
`MessageCodec`, **kein** `Channel`, **kein** Eintrag in `PlatformProtocol.create()` — dieses Feature hat
keinen Live-/Pub-Sub-Pfad (R7). Der publizierte POM bleibt **ohne** `<dependencies>`.

## DTOs (Records, reine Daten — kein JSON-Framework, kein Hash, kein email/username)

### `TokenResponse`
```text
record TokenResponse(String token, String purpose, long expiresAtEpochMilli)
```
- `token`: der Rohtoken (≥128 Bit, URL-safe), den das Plugin in den klickbaren Link einbaut.
- `purpose`: `"LINK"` | `"RESET"` (String — protocol bleibt enum-frei am Wire).
- **Niemals** ein Passwort-Hash, **niemals** `email`/`username`.

### `RedeemRequest`
```text
record RedeemRequest(String token, String password)
```
- Vom Webinterface gesendet. `password` ist Klartext (über TLS), wird backend-seitig sofort gehasht.

## Endpunkt-Beschreibung (`WebAuthEndpoints`, über `EndpointDescriptor`)

```text
public final class WebAuthEndpoints {
    // Plugin → Backend (kennt die UUID): Token anfordern
    REQUEST_LINK  = EndpointDescriptor<Void, TokenResponse>(POST, "/api/players/{uuid}/web-auth/link-token",  Void, TokenResponse)
    REQUEST_RESET = EndpointDescriptor<Void, TokenResponse>(POST, "/api/players/{uuid}/web-auth/reset-token", Void, TokenResponse)
    // Webinterface → Backend: Token einlösen (flach, kein uuid — Token trägt die Identität)
    REDEEM        = EndpointDescriptor<RedeemRequest, Void>(POST, "/api/web-auth/redeem", RedeemRequest, Void)
}
```
- `expand({uuid})` füllt die Pfadvariable JDK-only (wie `EconomyEndpoints`/`ReportEndpoints`).
- **Kein `UNLINK`** in Slice 1 (Q1, verschoben).

## Bestätigung: kein Eingriff in geteilten Routing-Code
- `plugin-protocol/.../protocol/PlatformProtocol.java` bleibt **byte-identisch** — es wird **keine**
  Codec-Zeile ergänzt (es gibt keinen Codec).
- `protocol/core` (`MessageEnvelope`/`MessageCodec`/`MessageProtocol`/`Channels`) wird **nicht** berührt.
- Einziger geteilter Zuwachs: die obigen **neuen** Dateien — additiv, kein Edit.

## Publish-Workflow
Nach Hinzufügen der Klassen: `:plugin-protocol:publishToMavenLocal` (Backend), dann im Plugin-Repo
`build --refresh-dependencies`. POM-Check: weiterhin **kein** `<dependencies>`-Block.

## Plugin-Seite (nur contract-seitig — separates Repo)
- `feature.web` implementiert das `Feature`-Interface, registriert sich in der `FeatureRegistry`
  (ein Anstecken). Commands `/web link`, `/web resetPassword` rufen `REQUEST_LINK`/`REQUEST_RESET` über
  den generischen `BackendClient`; aus `TokenResponse.token` baut eine **Adventure-Component** den
  klickbaren `open_url`-Link (kein Abtippen, FR-025). **Kein** `unlink`-Command (Q1), **kein**
  Transport-/EventBus-Eingriff (kein Live-Pfad).
