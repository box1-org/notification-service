# notification-service

Operator notices for the Box1 order lifecycle ([box1-docs#16](https://github.com/box1-org/box1-docs/issues/16)).
The service consumes order and shipment events and turns the facts an operator would otherwise miss into notices for
named operator channels (`ops-fulfillment`, `ops-orders`). On the cluster it delivers each notice as a real SMTP
message to **MailHog**, the in-cluster sink ([box1-gitops `platform/mailhog`](https://github.com/box1-org/box1-gitops/tree/main/platform/mailhog)),
with bounded retries, and publishes each outcome to `box1.notification.events.v1`. `DELIVERED` means *accepted by
MailHog*; no message leaves the cluster. A `stub` provider (receipts only) remains for tests. **No customer recipients, contact data or
customer identifiers** exist anywhere in this service.

- Architecture and design decisions: [docs/architecture.md](docs/architecture.md)
- Operations, dead-letter replay, fault control and recovery: [docs/runbook.md](docs/runbook.md)
- API: `/q/openapi` (served), Swagger UI at `/q/swagger-ui`
- Event contracts: [box1-ops-contracts `NOTIFICATION.md`](https://github.com/box1-org/box1-ops-contracts/blob/main/NOTIFICATION.md)

## API

| Method and path | Role | Purpose |
|---|---|---|
| `GET /api/v1/notifications?orderId=ord_…` | `notification-reader` | An order's notices, newest first (max 100), with status, attempts, structured failure reason, sources and redacted receipt |
| `GET /api/v1/notifications/{notificationId}` | `notification-reader` | One notice |
| `GET /api/v1/delivery-faults` | `notification-reader` | The delivery provider, its active fault and whether fault control is open here |
| `POST /api/v1/delivery-faults` | `notification-fault-operator` | Set a bounded delivery fault (allowlisted namespace only) |
| `DELETE /api/v1/delivery-faults` | `notification-fault-operator` | Clear it |
| `GET /api/v1/delivery-faults/audit` | `notification-fault-operator` | The fault-control audit trail |
| `/api/v1/stub`, `/api/v1/stub/faults`, `/api/v1/stub/faults/audit` | as above | **Deprecated** aliases of the four paths above, kept during the transition |
| `/api/greetings`, `/api/info`, `/q/health/*`, `/q/openapi`, `/q/metrics` | none | Golden Path smoke endpoints ([#40](https://github.com/box1-org/box1-docs/issues/40)) |

Bearer tokens come from Keycloak realm `box1`, client `notification-service`. Errors are RFC 9457 Problem JSON.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `NOTIFY_EVENTS_FLOOR` | **required** | ISO instant. Events that occurred before it never become notices (first-start offset policy). Must not be in the future. |
| `QUARKUS_DATASOURCE_JDBC_URL`, `_USERNAME`, `_PASSWORD` | — | PostgreSQL; the username and password come from Secret `notification-service-postgres` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `box1-kafka-kafka-bootstrap.box1-kafka.svc:9092` on the cluster |
| `POD_NAMESPACE` | — | From the downward API; used only by the fault-control gate |
| `NOTIFY_PROVIDER` | `stub` | `mailhog` (the cluster setting) or `stub` |
| `NOTIFY_SMTP_HOST` / `NOTIFY_SMTP_PORT` | `mailhog.mailhog.svc.cluster.local` / `1025` | MailHog SMTP; no auth, no TLS (cluster-internal) |
| `NOTIFY_SMTP_FROM` | `box1-notifications@box1.local` | Sender |
| `NOTIFY_SMTP_RECIPIENT_DOMAIN` | `box1.local` | Each channel goes to `<channel>@<domain>`, e.g. `ops-orders@box1.local` |
| `NOTIFY_SMTP_TIMEOUT_MILLIS` | `1500` | SMTP connect/read/write timeout, 100–30000 |
| `NOTIFY_STUB_FAULTS_ENABLED` | `false` | Enables the delivery fault-control gate (name kept from v1) |
| `NOTIFY_STUB_FAULT_NAMESPACES` | empty | Comma-separated namespaces where fault control is allowed |
| `NOTIFY_STUB_MAX_TTL_SECONDS` | `900` | Fault TTL cap; 10–900, larger values stop startup |
| `NOTIFY_DELIVERY_MAX_ATTEMPTS` | `6` | 1–10 |
| `NOTIFY_DELIVERY_BASE_BACKOFF_SECONDS` / `_MAX_BACKOFF_SECONDS` | `2` / `60` | Backoff doubles from the base up to the cap (≤ 300) |
| `NOTIFY_PROVIDER_TIMEOUT_MILLIS` | `2000` | Provider call timeout |
| `NOTIFY_DLQ_REPLAY_ENABLED` | `false` | Replay the dead-letter topic inside this service (runbook) |
| `NOTIFY_CHECK_DLQ_TOPIC` | `true` | Refuse to start if Kafka is reachable and the dead-letter topic is missing |
| `NOTIFY_DELIVERY_BATCH_SIZE` | `10` | Batch × provider timeout must be under the lease |
| `NOTIFY_AUTH_CONSOLE_CLIENT_ID` | empty | Accept the operator console's forwarded token (reader only) when set |
| `NOTIFY_AUTH_CONSOLE_READER_ROLES` | `console-viewer,console-operator` | Console roles that grant reading |
| `NOTIFY_JWT_AUDIENCES` | `notification-service` | Accepted `aud` values; add the console client when it is accepted |
| `NOTIFY_JWT_ISSUER`, `NOTIFY_JWKS_URL` | realm `box1` | Token issuer and keys |
| `NOTIFY_CHANNEL_*` | see `application.properties` | Channel per notice type; must match `ops-<name>` |
| `NOTIFY_RETENTION_*` | 30 / 30 / 7 days | Terminal notices (≥ 15), processed events (≥ 15), published outbox rows |

Invalid safety limits stop the application at startup rather than running with them.

## Tests

```sh
./mvnw verify                                   # what CI runs: unit, security, contract tests, Checkstyle, SpotBugs
# Real PostgreSQL and Kafka (Testcontainers), plus the packaged-app restart test:
export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')  # Podman only
export TESTCONTAINERS_RYUK_DISABLED=true                                                              # Podman only
./mvnw verify -Pcontainers -DskipITs=false
```

Contract tests fetch `box1-ops-contracts` at the commit pinned in `pom.xml` (`contracts.ref`); no schema is copied here.

## Template notes

A Quarkus REST service on OpenShift

Owner: `user:default/mike4263`. Quarkus 3.40.1.1, Java 21, Maven 3.9.16.

Optional integrations selected at creation: PostgreSQL = `false`, Kafka = `true`. PostgreSQL was added afterwards
without a Secret in Git (see the runbook).
The integrations add their Quarkus extensions and GitOps resources. PostgreSQL creates a
service-owned Deployment, Service, PVC, and Secret placeholder; replace its password before
syncing. Kafka uses the separately managed shared `box1-kafka` Strimzi cluster; service
onboarding does not create a `KafkaTopic`. The broker address is
`box1-kafka-kafka-bootstrap.box1-kafka.svc:9092`.

## Local development

Install JDK 21 and use the included Maven wrapper:

```sh
./mvnw quarkus:dev
./mvnw verify -DskipITs=false -Dgit.ref="$(git rev-parse HEAD)"
curl http://localhost:8080/api/greetings?name=Ada
curl http://localhost:8080/api/info
```

Verification runs unit tests, HTTP tests, packaged JVM integration tests,
Checkstyle, and SpotBugs/FindSecBugs. `/api/info` includes the immutable Git SHA
embedded in the image; local builds default to `unknown` unless supplied above.
Java package `org.box1` is deliberately stable across generated services.

## First deployment

Create the service in Developer Hub. Its task tracks repository creation,
validated development onboarding, platform readiness, initial CI, image promotion,
and the verified first deployment. When the task completes, its output links to
source, catalog, initial pipeline, Argo CD, and the running service. No manual
onboarding merge or extra source commit is required.

If onboarding fails, the task reports the failed state and diagnostic link.
Submit the same name, owner, visibility, and description to resume the recorded
workflow. The repository and development resources are reused. A concurrent
request for the same service is rejected while the first task holds its lease.
The initial CI run is named `onb-notification-service-<revision>`; a retried failed
build has a numbered suffix. Completed builds are reused.

Route: https://notification-service-dev.apps.ocp.box1.org

## Delivery and configuration

PRs validate without registry credentials. Main releases use centrally managed
Tasks from `box1-gitops/platform/pipelines/tasks`, resolved from `box1-ci`.
GitHub checks link to cluster PipelineRuns named `notification-service-validate-*`
and `notification-service-release-*`. Pipelines as Code and Developer Hub use
separate GitHub Apps, both installed for all `box1-org` repositories.

Jib creates JVM images from a digest-pinned UBI 9 / Java 21 runtime without a
Docker daemon. Images use `registry.apps.ocp.box1.org/box1-ci/notification-service:<SHA>`;
OpenShift pulls by digest through the internal registry service.

After onboarding, the GitOps repository is the authoritative deployment source.
Change the Route, probes, resources, or
`GREETING_PREFIX` there. The default smoke contract expects `Hello from box1`.
The ConfigMap also sets JVM container memory limits; runtime has no API token.

Endpoints: `/api/greetings`, `/api/info`, `/q/health/live`, `/q/health/ready`,
`/q/health/started`, `/q/openapi`, `/q/metrics`.

The development promotion policy is automated after checks. Other environments
need explicit platform configuration and approval. Static analysis is included;
no external dependency CVE database scan is implied.

## API documentation and endpoint testing

Developer Hub populates **Provides APIs** with `notification-service-api`.
Open the API's **Definition** tab to browse the deployed OpenAPI contract and use
**Try it out**. The catalog reads `https://notification-service-dev.apps.ocp.box1.org/q/openapi`,
so the API entity becomes available after the first successful deployment.
Swagger UI is also available at `https://notification-service-dev.apps.ocp.box1.org/q/swagger-ui/`.
The production contract targets the public HTTPS Route; override `PUBLIC_API_URL`
when deploying behind another public address. CORS allows the Developer Hub
origin to make tester requests without cookies. API authentication, if added,
should be declared in the OpenAPI contract and supplied through **Authorize**.

## Documentation

Developer Hub publishes `docs/` on the service's **Docs** tab using TechDocs.
List new pages in `mkdocs.yml`.
