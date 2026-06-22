package com.mcplatform.protocol.report;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;

/**
 * The report REST endpoints as constants, so clients reference them by name instead of hard-coding paths.
 * A report references two players, so it is a flat {@code /api/reports} resource (not nested under a
 * player). {@code LIST_OPEN} carries the {@code ?staff=<uuid>} query parameter client-side.
 */
public final class ReportEndpoints {

    /** POST create a report. */
    public static final EndpointDescriptor<CreateReportRequest, ReportResponse> CREATE =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/reports",
                    CreateReportRequest.class, ReportResponse.class);

    /** GET the open report list (team). */
    public static final EndpointDescriptor<Void, ReportResponse[]> LIST_OPEN =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/reports/open", Void.class, ReportResponse[].class);

    /** POST change a report's status by id. */
    public static final EndpointDescriptor<ChangeStatusRequest, ReportResponse> CHANGE_STATUS =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/reports/{id}/status",
                    ChangeStatusRequest.class, ReportResponse.class);

    private ReportEndpoints() {}
}
