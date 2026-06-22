package com.mcplatform.protocol.report;

import java.util.UUID;

/** Request body to change a report's status. {@code newStatus} is one of the canonical status values. */
public record ChangeStatusRequest(String newStatus, UUID handledBy) {
}
