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

2. **Dead-letter topic** `box1.notification.dlq.v1`. The service refuses to start if Kafka is reachable and the topic is
   missing (`NOTIFY_CHECK_DLQ_TOPIC`). The topic is defined in box1-gitops `platform/kafka/topics/notification-dlq.yaml`.
   `box1-kafka` syncs manually: sync that resource only, with prune off. The service must not consume on the cluster
   before the topic exists.

3. **Keycloak client** `notification-service` (box1-gitops `platform/identity/keycloak/realm/`). After the sync, check
   that roles `notification-reader` and `notification-fault-operator` exist on the client. Assign them to people in the
   Keycloak admin console.

## Health

- Readiness covers PostgreSQL. Kafka is deliberately excluded, so a broker outage keeps the API up and grows the outbox.
- No ServiceMonitor or PrometheusRule is deployed yet (box1-docs#22), so these are the signals to watch, not configured alerts:
- `box1_notification_oldest_pending_age_seconds` above about 120 s. Delivery normally finishes in about a
  minute, including all retries.
- `box1_notification_outbox_oldest_pending_age_seconds` growing (broker down), and
  `box1_notification_failed_total{category="RETRIES_EXHAUSTED"}` increasing (pre-registered at zero).
- `box1_notification_metrics_snapshot_age_seconds` above about 60 s: the pending gauges are stale because the database is
  unreachable.
- Consumer lag: the Kafka client metric `kafka_consumer_fetch_manager_records_lag_max` (not verified on the cluster).
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

- Skipped events leave no dedupe record and are counted only as `box1_notification_events_consumed_total{outcome="skipped_before_floor"}`.
- **Re-notifying a period needs more than lowering the floor**, because the group's committed offsets are already past
  those events. Each step below needs approval:
  1. Scale the Deployment to 0 replicas through a box1-workloads-dev PR. `oc scale` would be reverted by selfHeal.
  2. Reset the group's offsets. This is group-scoped, so other services are unaffected:
     `bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group notification --reset-offsets --to-datetime <ISO> --topic box1.order.events.v1 --topic box1.fulfillment.events.v1 --execute`.
  3. Lower `NOTIFY_EVENTS_FLOOR` and restore the replicas in the same PR.

  Only events still inside topic retention (7 days for orders, 14 for fulfillment) can come back. Existing notices are
  not duplicated, because the dedupe key is the fact.
- To start fresh, raise the floor.
- The floor in the workloads PR was set when the PR was written. Set it to the merge time when approving, unless notices
  for the events in between are wanted.

Never set it in the future: the application refuses to start.

## Dead-letter replay

Records the consumer could not process are in `box1.notification.dlq.v1`, unchanged: the key and value are byte-exact.
SmallRye adds the headers `dead-letter-reason` (fixed text naming the rule), `dead-letter-cause`,
`dead-letter-exception-class-name`, `dead-letter-topic`, `dead-letter-partition` and `dead-letter-offset`.

Event types the service does not know are **not** dead-lettered. They are acknowledged and counted as
`outcome="ignored_unknown_type"`. A known type on the wrong topic is dead-lettered as a routing error.

1. Inspect without committing offsets:

   ```sh
   oc -n box1-kafka exec -it box1-kafka-box1-kafka-0 -- bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 --topic box1.notification.dlq.v1 --from-beginning \
     --property print.key=true --property print.headers=true --timeout-ms 10000
   ```

2. Fix the cause, for example a service fix that has since been deployed or a producer routing error. Record who replays
   what and why in the incident or issue.
3. Replay inside this service only. **Do not re-produce to `box1.order.events.v1` or `box1.fulfillment.events.v1`**:
   every other consumer group on those topics would receive the record again, and producing to topics this service does
   not own needs the user's approval.
   - Set `NOTIFY_DLQ_REPLAY_ENABLED=true` in the Deployment `env` (box1-workloads-dev PR).
   - The `dlq-replay` channel reads the dead-letter topic with its own group, `notification-replay`, from where it last
     stopped. It applies each record here and accepts any type the service knows, whatever topic it came from.
   - A record that is still poison is logged, counted as `type="replay", outcome="still_poison"` and skipped. It stays in
     the dead-letter topic and is never dead-lettered again.
4. Once `box1_notification_events_consumed_total{type="replay"}` stops increasing, switch replay off with another PR.
5. Replay is duplicate-safe. The same `eventId` is a no-op, and the same fact maps to the existing notice.

This was exercised on a local broker by `ReplayTest` (a misrouted event replayed once; garbage stayed dead-lettered;
nothing produced to the shared topics). It has not been exercised on the cluster.

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
- Every refusal is audited with the actor's username and subject. That includes requests refused before the handler:
  `DENIED_FORBIDDEN` for a token without the role, `DENIED_UNAUTHENTICATED` for a missing or invalid one.
- Not audited: bodies rejected while being read (malformed JSON, unknown fields, over 4 KiB). They return 400 or 413 and
  change nothing.
- A fault always ends on its TTL. A sweep records `EXPIRED` within seconds.

## Recovery

| Situation | Effect | Action |
|---|---|---|
| Pod restart or rollout | Pending retries resume from the database; nothing is delivered twice | None |
| PostgreSQL down | Not ready; consumers hold their record, even through a shutdown, and it is redelivered after restart; no delivery | Restore the database pod; consumption resumes |
| Kafka down | API and delivery continue; outbox grows; consumption pauses | Restore Kafka; the outbox drains |
| Notice `FAILED` | Visible through the API with a structured reason | Fix the provider. There is no automatic re-send: a new fact makes a new notice |
| Bad release | — | Revert the promotion commit in box1-workloads-dev (digest rollback). Flyway V1 creates new tables only |

## Backup

The data is lab-disposable operator notices. If it must be kept, take a `pg_dump --format=custom` of
`notification_service` before destructive work, as `box1-ops-contracts/postgres/STORAGE-BACKUP-RESTORE.md` describes.
The PVC has `Prune=false,Delete=false`.
