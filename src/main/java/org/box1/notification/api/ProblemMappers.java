package org.box1.notification.api;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.box1.notification.domain.RequestProblem;
import org.box1.notification.store.StoreException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps failures to RFC 9457 responses. Token, parser and driver messages never reach callers. */
public class ProblemMappers {
    private static final Logger LOG = Logger.getLogger(ProblemMappers.class);

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
    public Response unauthorized(UnauthorizedException error) {
        return unauthenticated();
    }

    @ServerExceptionMapper
    public Response authenticationFailed(AuthenticationFailedException error) {
        return unauthenticated();
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException error) {
        return response(Problem.of(403, "Forbidden", "The token does not carry the role this operation needs", "FORBIDDEN"));
    }

    private static Response unauthenticated() {
        return Response.fromResponse(response(Problem.of(401, "Unauthorized", "A valid bearer token is required",
                "UNAUTHENTICATED"))).header("WWW-Authenticate", "Bearer").build();
    }

    static Response response(Problem problem) {
        return Response.status(problem.status()).type(Problem.MEDIA_TYPE).entity(problem).build();
    }
}
