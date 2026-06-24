# plugin-protocol-Contract — JWT-Login-Session (additiv, JDK-only)

Alle Ergänzungen liegen in `plugin-protocol.webauth`, sind **reine Daten-Records** (kein JSON, kein Spring),
und der publizierte POM bleibt **ohne `<dependencies>`**. **Kein `MessageCodec`, kein `Channel`** — dieser Slice
hat keinen Pub/Sub-Pfad. **`PlatformProtocol.create()` wird NICHT angefasst** (verifizierbar: keine Codec-
Registrierung). Nach Änderung: `:plugin-protocol:publishToMavenLocal`, dann im Plugin-Repo
`build --refresh-dependencies` (hier ohne Plugin-Konsument relevant erst später).

## Neue DTOs

```java
package com.mcplatform.protocol.webauth;

/** Login-Eingabe (Webinterface → Backend). Klartext-Passwort nur im Transit, nie persistiert. */
public record LoginRequest(String username, String password) {}

/**
 * Login-/Refresh-Antwort. Trägt das Access-Token + Ablaufzeiten. Das Refresh-Token erscheint BEWUSST NICHT
 * hier — es reist ausschließlich im httpOnly-Cookie (XSS-Schutz). refreshExpiresAtEpochMilli sagt dem Client
 * nur, wann ein Re-Login fällig wird.
 */
public record TokenPairResponse(String accessToken, long accessExpiresAtEpochMilli, long refreshExpiresAtEpochMilli) {}
```

- **Kein `RefreshRequest`**: Refresh/Logout lesen das Token aus dem Cookie → kein Request-Body-DTO. (Bewusste
  Abweichung vom ursprünglichen Prompt-Wortlaut; begründet durch die httpOnly-Cookie-Transport-Entscheidung —
  das Refresh-Token soll in **keinem** JS-lesbaren Body/Response stehen.)

## WebAuthEndpoints — additive Konstanten (bestehender `EndpointDescriptor`-Stil)

```java
/** POST Login: MC-Name + Passwort → Access-Token (+ Refresh als httpOnly-Cookie). */
public static final EndpointDescriptor<LoginRequest, TokenPairResponse> LOGIN =
        new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/login",
                LoginRequest.class, TokenPairResponse.class);

/** POST Refresh: Refresh-Cookie → neues Access-Token (rotiert das Refresh-Cookie). */
public static final EndpointDescriptor<Void, TokenPairResponse> REFRESH =
        new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/session/refresh",
                Void.class, TokenPairResponse.class);

/** POST Logout: entwertet das vorgelegte Refresh-Token (204, idempotent). */
public static final EndpointDescriptor<Void, Void> LOGOUT =
        new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/session/logout",
                Void.class, Void.class);
```

Die bestehenden `REQUEST_LINK`/`REQUEST_RESET`/`REDEEM` bleiben unverändert.

## Tests (plugin-protocol, rein-JDK)
- `WebAuthEndpointsTest` erweitern: `expand`/Methode/Request-/Response-Typ für `LOGIN`/`REFRESH`/`LOGOUT`.
- JSON-Feldnamen-Contract der neuen DTOs wird (wie bei der Bridge) im `app`-Modul per `@JsonTest` geprüft (das
  protocol-Modul bleibt JSON-frei).
