# Runbook

Namespace `notification-service-dev`. Desired state:
[box1-workloads-dev `workloads/notification-service/`](https://github.com/box1-org/box1-workloads-dev/tree/main/workloads/notification-service).
Do not `oc apply` or `oc patch` desired state; change it through a PR.

## Prerequisites (one time)

1. **Database Secret.** Create it out of band, before the workloads change that adds PostgreSQL syncs. It is not in Git,
   and Argo CD does not manage it.

   ```sh
   oc -n notification-service-dev create secret generic notification-service-postgres \
     --from-literal=POSTGRESQL_DATABASE=notification_service \
     --from-literal=POSTGRESQL_USER=notification_service \
     --from-literal=POSTGRESQL_PASSWORD="$(openssl rand -base64 24)"
   ```

   Never paste the value into a ticket, log or document. PostgreSQL reads it only at first initialization. Rotating it
   later needs `ALTER ROLE`, then a Secret update and a restart of both pods. Vault replaces this later (box1-docs#41).

2. **Dead-letter topic** `box1.notification.dlq.v1` (box1-gitops `platform/kafka/topics/notification-dlq.yaml`).
   `box1-kafka` syncs manually: sync that resource only, with prune off. The service must not consume on the cluster
   before the topic exists.

3. **Keycloak client** `notification-service` (box1-gitops `platform/identity/keycloak/realm/`). After the sync, check
   that roles `notification-reader` and `notification-fault-operator` exist on the client. Assign them to people in the
   Keycloak admin console.

## Health

- Readiness covers PostgreSQL. Kafka is deliberately excluded, so a broker outage keeps the API up and grows the outbox.
- Alert on `box1_notification_oldest_pending_age_seconds` above about 120 s. Delivery normally finishes in about a
  minute, including all retries.
- Alert on `box1_outbox_oldest_pending_age_seconds` growing (broker down), and on
  `box1_notification_failed_total{category="RETRIES_EXHAUSTED"}` increasing.
- To get an operator token, use the CLI PKCE login against client `notification-service`, the same flow as
  openshell-cli. Then query:

  ```sh
  curl -H "Authorization: Bearer $TOKEN" \
    "https://notification-service-dev.apps.ocp.box1.org/api/v1/notifications?orderId=ord_..."
  ```

## First start and the event floor

`NOTIFY_EVENTS_FLOOR` (Deployment `env`) is required. Events that occurred before it are acknowledged with no notice,
which stops a new consumer group from replaying a week of order history, or two weeks of fulfillment history, into
notices.

- To deliberately re-notify a period, lower the floor in a reviewed PR. Existing notices are not duplicated (dedupe key).
- To start fresh, raise the floor.

Never set it in the future: the application refuses to start.

## Dead-letter replay

Records the consumer could not process are in `box1.notification.dlq.v1`, unchanged. Headers carry
`dead-letter-reason` (fixed text naming the rule), `dead-letter-topic`, `dead-letter-partition` and
`dead-letter-offset`.

1. Inspect without committing offsets:

   ```sh
   oc -n box1-kafka exec -it box1-kafka-<broker-pod> -- bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 --topic box1.notification.dlq.v1 --from-beginning \
     --property print.key=true --property print.headers=true --timeout-ms 10000
   ```

2. Fix the cause: a producer bug, a misrouted record, or a service fix that has since been deployed. Record who replays
   what and why in the incident or issue.
3. Re-produce the **unchanged key and value** to the topic named in `dead-letter-topic`, or to the correct topic if the
   record was misrouted:

   ```sh
   oc -n box1-kafka exec -i box1-kafka-<broker-pod> -- bin/kafka-console-producer.sh \
     --bootstrap-server localhost:9092 --topic <topic> --property parse.key=true --property "key.separator=<TAB>"
   ```

4. Replay is duplicate-safe. The same `eventId` is a no-op, and the same fact maps to the existing notice.

This procedure was exercised on a local broker by `ScenariosTest.poisonRecordsReachTheDeadLetterTopicUnchangedAndReplay`.
A cluster replay needs a person with Kafka access and is not automated.

## Stub fault control

Fault control is off in the deployment (`NOTIFY_STUB_FAULTS_ENABLED=false`, empty allowlist). Each of the following
needs the user's approval:

- enabling it in the demo namespace the user names, through a workloads PR;
- each fault run.

```sh
# 503 for the next 3 provider calls, ending after at most 120 s
curl -X POST -H "Authorization: Bearer $FAULT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"mode":"TRANSIENT_503","ttlSeconds":120,"failCount":3}' https://<route>/api/v1/stub/faults
curl -H "Authorization: Bearer $TOKEN" https://<route>/api/v1/stub              # active fault and gate
curl -X DELETE -H "Authorization: Bearer $FAULT_TOKEN" https://<route>/api/v1/stub/faults
curl -H "Authorization: Bearer $FAULT_TOKEN" https://<route>/api/v1/stub/faults/audit
```

- **Refusals:**
  - `FAULTS_DISABLED`, `NAMESPACE_UNKNOWN` or `NAMESPACE_NOT_ALLOWLISTED` → 403.
  - Out-of-bounds TTL, mode, delay or `failCount` → 400.
- Every refusal is audited.
- A fault always ends on its TTL. A sweep records `EXPIRED` within seconds.

## Recovery

| Situation | Effect | Action |
|---|---|---|
| Pod restart or rollout | Pending retries resume from the database; nothing is delivered twice | None |
| PostgreSQL down | Not ready; consumers hold their position; no delivery | Restore the database pod; consumption resumes |
| Kafka down | API and delivery continue; outbox grows; consumption pauses | Restore Kafka; the outbox drains |
| Notice `FAILED` | Visible through the API with a structured reason | Fix the provider. There is no automatic re-send: a new fact makes a new notice |
| Bad release | — | Revert the promotion commit in box1-workloads-dev (digest rollback). Flyway V1 creates new tables only |

## Backup

The data is lab-disposable operator notices. If it must be kept, take a `pg_dump --format=custom` of
`notification_service` before destructive work, as `box1-ops-contracts/postgres/STORAGE-BACKUP-RESTORE.md` describes.
The PVC has `Prune=false,Delete=false`.
