package org.box1.notification.api;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.NotFoundException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.box1.notification.delivery.FaultGate;
import org.box1.notification.store.StubStore;
import org.box1.notification.domain.RequestProblem;
import org.box1.notification.store.StoreException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps failures to RFC 9457 responses. Token, parser and driver messages never reach callers. */
public class ProblemMappers {
    private static final Logger LOG = Logger.getLogger(ProblemMappers.class);

    @jakarta.inject.Inject
    StubStore stubStore;

    @jakarta.inject.Inject
    FaultGate gate;

    @jakarta.inject.Inject
    SecurityIdentity identity;

    /** Requests to fault control refused before the handler ran are audited too (security review L1). */
    private void auditDenied(Actor actor, String outcome) {
        try {
            stubStore.auditRefusal(actor.name(), actor.subject(), "DENIED", null, null, outcome, gate.namespace());
        } catch (RuntimeException error) {
            LOG.warnv("Fault-control denial not audited: {0}", error.getClass().getSimpleName());
        }
    }

    @ServerExceptionMapper
    public Response problem(RequestProblem error) {
        return response(Problem.of(error.status(), error.title(), error.getMessage(), error.code()));
    }

    @ServerExceptionMapper
    public Response store(StoreException error) {
        LOG.error(error.getMessage());
        return response(Problem.of(503, "Service Unavailable", "The notification store is unavailable", "STORE_UNAVAILABLE"));
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException error) {
        return response(Problem.of(404, "Not Found", "No such resource", "NOT_FOUND"));
    }

    @ServerExceptionMapper
    public Uni<Response> unauthorized(UnauthorizedException error, UriInfo uri) {
        return audited(uri, "DENIED_UNAUTHENTICATED", ProblemMappers::unauthenticated);
    }

    @ServerExceptionMapper
    public Uni<Response> authenticationFailed(AuthenticationFailedException error, UriInfo uri) {
        return audited(uri, "DENIED_UNAUTHENTICATED", ProblemMappers::unauthenticated);
    }

    @ServerExceptionMapper
    public Uni<Response> forbidden(ForbiddenException error, UriInfo uri) {
        return audited(uri, "DENIED_FORBIDDEN", () -> response(Problem.of(403, "Forbidden",
                "The token does not carry the role this operation needs", "FORBIDDEN")));
    }

    /** Security refusals can be raised on the event loop; the audit insert is blocking, so it runs on a worker. */
    private Uni<Response> audited(UriInfo uri, String outcome, java.util.function.Supplier<Response> response) {
        String path = uri == null ? "" : uri.getPath();
        if (!path.startsWith("/api/v1/stub/faults") && !path.startsWith("/api/v1/delivery-faults")) {
            return Uni.createFrom().item(response.get());
        }
        Actor actor = Actor.of(identity);
        return Uni.createFrom().item(() -> {
            auditDenied(actor, outcome);
            return response.get();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private static Response unauthenticated() {
        return Response.fromResponse(response(Problem.of(401, "Unauthorized", "A valid bearer token is required",
                "UNAUTHENTICATED"))).header("WWW-Authenticate", "Bearer").build();
    }

    static Response response(Problem problem) {
        return Response.status(problem.status()).type(Problem.MEDIA_TYPE).entity(problem).build();
    }
}
