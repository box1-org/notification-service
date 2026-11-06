package org.box1.notification.api;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.box1.notification.domain.RequestProblem;
import org.box1.notification.store.NotificationStore;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

/** Operator read API (decision D-007). Needs {@code notification-reader}. */
@Path("/api/v1/notifications")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(ClientRoleAugmentor.READER)
@SecurityRequirement(name = "bearer")
public class NotificationResource {
    static final int MAX_RESULTS = 100;
    private static final Pattern ORDER = Pattern.compile("^ord_[A-Za-z0-9_-]{1,124}$");
    private static final Pattern NOTIFICATION = Pattern.compile("^ntf_[0-9a-f]{26}$");

    private final NotificationStore store;

    public NotificationResource(NotificationStore store) {
        this.store = store;
    }

    @GET
    @Operation(summary = "List an order's operator notices, newest first, with attempts, failure reason and receipt")
    public Views.NotificationList byOrder(@QueryParam("orderId") String orderId) {
        if (orderId == null || !ORDER.matcher(orderId).matches()) {
            throw new RequestProblem(400, "Bad Request", "INVALID_ORDER_ID", "orderId is required and must match ord_<id>");
        }
        List<Views.NotificationView> views = store.findByOrder(orderId, MAX_RESULTS).stream().map(this::view).toList();
        return new Views.NotificationList(orderId, views.size(), views);
    }

    @GET
    @Path("/{notificationId}")
    @Operation(summary = "Read one operator notice")
    public Views.NotificationView one(@PathParam("notificationId") String notificationId) {
        if (!NOTIFICATION.matcher(notificationId).matches()) {
            throw new RequestProblem(400, "Bad Request", "INVALID_NOTIFICATION_ID", "notificationId must match ntf_<id>");
        }
        return store.find(notificationId).map(this::view)
                .orElseThrow(() -> new RequestProblem(404, "Not Found", "NOT_FOUND", "No such notification"));
    }

    private Views.NotificationView view(NotificationStore.Row row) {
        return Views.NotificationView.of(row, store.sources(row.notificationId()), store.attempts(row.notificationId()),
                store.receipt(row.notificationId()).orElse(null));
    }
}
