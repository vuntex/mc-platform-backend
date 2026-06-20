package com.mcplatform.protocol.core;

import java.util.Objects;

/**
 * Generic, framework-free description of one backend REST endpoint: the HTTP method, a path template
 * with {@code {placeholder}} segments, and the request/response payload types. Lets a REST client
 * (and the backend) reference endpoints by constant instead of scattering magic path strings and
 * guessing (de)serialization types. A new feature plugs in by declaring its own {@code XEndpoints}
 * constants built from this descriptor.
 *
 * <p>{@code requestType} is {@link Void} for endpoints without a request body; {@code responseType}
 * is {@link Void} for endpoints with no response body (e.g. 204).
 *
 * @param <REQ> request body type ({@link Void} if none)
 * @param <RES> response body type ({@link Void} if none)
 */
public record EndpointDescriptor<REQ, RES>(
        HttpMethod method,
        String pathTemplate,
        Class<REQ> requestType,
        Class<RES> responseType) {

    public EndpointDescriptor {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(responseType, "responseType");
        if (!pathTemplate.startsWith("/")) {
            throw new IllegalArgumentException("pathTemplate must start with '/': " + pathTemplate);
        }
    }

    /**
     * Fills the {@code {placeholder}} segments left-to-right with {@code pathVars} and returns a
     * concrete path. The number of vars must match the number of placeholders.
     */
    public String expand(Object... pathVars) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        int varIndex = 0;
        while (true) {
            int open = pathTemplate.indexOf('{', cursor);
            if (open < 0) {
                out.append(pathTemplate, cursor, pathTemplate.length());
                break;
            }
            int close = pathTemplate.indexOf('}', open);
            if (close < 0) {
                throw new IllegalArgumentException("malformed path template: " + pathTemplate);
            }
            if (varIndex >= pathVars.length) {
                throw new IllegalArgumentException("missing path var for template: " + pathTemplate);
            }
            out.append(pathTemplate, cursor, open).append(pathVars[varIndex++]);
            cursor = close + 1;
        }
        if (varIndex != pathVars.length) {
            throw new IllegalArgumentException(
                    "expected " + varIndex + " path vars but got " + pathVars.length + " for template: " + pathTemplate);
        }
        return out.toString();
    }
}
