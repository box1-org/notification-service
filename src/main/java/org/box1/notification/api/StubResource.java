package org.box1.notification.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

/**
 * Compatibility alias for {@link DeliveryFaultResource} during the transition: same roles, bounds and audit. Use
 * {@code /api/v1/delivery-faults}; this path is removed once no caller uses it.
 */
@Path("/api/v1/stub")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearer")
public class StubResource {
    private final DeliveryFaults faults;
    private final SecurityIdentity identity;

    public StubResource(DeliveryFaults faults, SecurityIdentity identity) {
        this.faults = faults;
        this.identity = identity;
    }

    @GET
    @RolesAllowed(ClientRoleAugmentor.READER)
    @Operation(summary = "Deprecated alias of GET /api/v1/delivery-faults", deprecated = true)
    public Views.StubState state() {
        return faults.state();
    }

    @POST
    @Path("/faults")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Deprecated alias of POST /api/v1/delivery-faults", deprecated = true)
    public Views.StubState set(Views.FaultRequest request) {
        return faults.set(request, Actor.of(identity));
    }

    @DELETE
    @Path("/faults")
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Deprecated alias of DELETE /api/v1/delivery-faults", deprecated = true)
    public Views.StubState clear() {
        return faults.clear(Actor.of(identity));
    }

    @GET
    @Path("/faults/audit")
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Deprecated alias of GET /api/v1/delivery-faults/audit", deprecated = true)
    public Views.Audit audit() {
        return faults.audit();
    }
}
