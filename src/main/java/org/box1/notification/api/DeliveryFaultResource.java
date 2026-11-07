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

/** Delivery fault control at the provider boundary (decision D-009). Replaces {@code /api/v1/stub}, which remains an alias. */
@Path("/api/v1/delivery-faults")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearer")
public class DeliveryFaultResource {
    private final DeliveryFaults faults;
    private final SecurityIdentity identity;

    public DeliveryFaultResource(DeliveryFaults faults, SecurityIdentity identity) {
        this.faults = faults;
        this.identity = identity;
    }

    @GET
    @RolesAllowed(ClientRoleAugmentor.READER)
    @Operation(summary = "Show the delivery provider, its active fault and whether fault control is open here")
    public Views.StubState state() {
        return faults.state();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Set a delivery fault for a bounded time (demo namespace only)")
    public Views.StubState set(Views.FaultRequest request) {
        return faults.set(request, Actor.of(identity));
    }

    @DELETE
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Clear the delivery fault")
    public Views.StubState clear() {
        return faults.clear(Actor.of(identity));
    }

    @GET
    @Path("/audit")
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Read the fault-control audit trail, newest first")
    public Views.Audit audit() {
        return faults.audit();
    }
}
