# TideBid

TideBid is a Java 21 distributed auction platform built around a verifiable bidding and transaction flow. It is designed as a portfolio project for reasoning about concurrency, money consistency, reliable events, real-time updates, and service boundaries—not as a real-money trading system.

The project currently implements the realtime-proxy stage on top of the reliable-trade flow: account security, private auction assets,
review, deposit-backed registration, concurrent manual bidding, reliable closing, virtual-fund
settlement, winning orders, simulated payment, payment timeout and seller credit all run end to end.
Transactional Outbox/Inbox processing and database reconciliation provide recovery across duplicate
delivery, process restarts and Broker outages. Realtime WebSocket delivery, proxy bidding and bounded
anti-sniping are implemented in stage 04; AI inference remains the stage 05 scope.

## Core flow

1. A seller creates an auction item and submits it for review.
2. A bidder registers for the auction and locks a virtual deposit.
3. Valid bids are decided by the database with optimistic concurrency control.
4. Accepted bids commit with a reliable Outbox event; Realtime consumes the event and broadcasts it through Redis-backed WebSocket fanout, while HTTP/MySQL remains final truth.
5. Closing produces a reliable event, creates an order, and releases or captures virtual deposits.
6. The winner pays any remaining virtual amount; timeout and seller credit converge asynchronously.

## Modules

| Module | Responsibility |
| --- | --- |
| `gateway-service` | Routing, JWT verification, CORS, request tracing, and rate limiting |
| `account-service` | Users, roles, virtual wallets, ledgers, and deposits |
| `auction-service` | Items, review, enrollment, bids, and auction state |
| `trade-service` | Winning orders, simulated payment, and payment timeout |
| `realtime-service` | One-time ticket authentication, WebSocket subscriptions, snapshot recovery, RocketMQ consumption and Redis multi-instance fanout |
| `ai-service` | Stage 05 health-check skeleton; model inference is not implemented yet |
| `common/*` | Stable response, security, web, and cross-service contract modules |
| `web` | Vue 3 user and administration interface |

## Technology baseline

- Java 21 and Maven multi-module builds
- Spring Boot 3.5, Spring Cloud 2025, and Spring Cloud Alibaba 2025
- MySQL 8.4, Redis 7, RocketMQ 5, Nacos 3, MyBatis-Plus, and Flyway
- Vue 3, TypeScript, Vite, pnpm, and Element Plus
- Alibaba Cloud OSS and Qwen VL as optional integrations

## Development prerequisites

Install the following tools before running the completed stage 04 local environment:

- JDK 21
- Maven 3.9 or newer
- Node.js 24 and pnpm
- Docker Desktop with Docker Compose v2

Verify the local toolchain in PowerShell:

```powershell
java -version
mvn -version
node --version
pnpm --version
docker version
docker compose version
```

This repository intentionally requires Node 24. If `node --version` still reports Node 18 and
NVM for Windows is installed, switch before running frontend commands:

```powershell
nvm list available
nvm install 24.21.0
nvm use 24.21.0
node --version
```

The version must be at least 24.12 and lower than 25. Switching the active NVM version affects
other terminals and Node projects on the machine, so choose it explicitly rather than relying on
an IDE's cached runtime.

Build and test the modules currently in the repository:

```powershell
mvn verify
```

Install and verify the frontend from `web`:

```powershell
Set-Location web
pnpm install --frozen-lockfile
pnpm lint
pnpm type-check
pnpm test
pnpm build
```

With the host applications running, start the frontend with `pnpm dev` and open
`http://127.0.0.1:5173`. All account, auction, order and administration requests use the Vite
`/api` proxy to Gateway port 9000; the browser does not call a business service directly.

The browser stores the demonstration Access Token in `sessionStorage`, so refreshing the same tab
restores the session and closing the tab clears it. This is a local portfolio-project tradeoff, not
a production security recommendation: an XSS payload running in the page could still read the
Token. A 401 from an authenticated request clears the session and returns the user to login.

## Run the complete local stack

After creating `.env`, start the middleware and all host applications from the repository root:

```powershell
.\scripts\infra-up.ps1
.\scripts\start-apps.ps1
```

`start-apps.ps1` validates Java 21, Maven 3.9+, Node 24, pnpm 11, required application values
and all seven host ports. Required values include the Account, Auction and Trade database passwords,
the RocketMQ Proxy endpoint, and a
32-to-512-character internal service Token. When `TIDEBID_OSS_ENABLED=true`, the script also checks
that the AccessKey ID/Secret, HTTP(S) Endpoint, region and Bucket are complete and structurally valid;
when it is false, cloud credentials remain optional. It packages the Java modules without rerunning
tests, validates the JWT key pair, imports the seven managed Nacos Data IDs, and starts Account,
Auction, Trade, Realtime, AI, Gateway, then Vue. Each process must pass its HTTP readiness check
before the next dependency starts. If the JARs are already current, use the faster development path:

```powershell
.\scripts\start-apps.ps1 -SkipBuild
```

Validate the local application configuration and toolchain without building or starting processes:

```powershell
.\scripts\start-apps.ps1 -CheckOnly
.\scripts\check-nacos-registrations.ps1
```

The registration check logs into the local Nacos Admin API without printing the access token and
requires exactly one healthy `TIDEBID_GROUP` instance for Gateway 9000 and services 9101-9105.
For the phase 04 multi-instance drill, start a second Realtime process on port 9204 and check both
registrations with:

```powershell
.\scripts\realtime-instance.ps1 -Action Start
.\scripts\check-nacos-registrations.ps1 -ExpectedRealtimeInstances 2
.\scripts\realtime-instance.ps1 -Action Stop -AcknowledgeImpact
```

The secondary process uses the same Nacos service name, Redis fan-out and RocketMQ consumer group;
its PID and logs stay under the ignored `.runtime/realtime-instances/` directory. To rehearse the
Redis failure path without deleting containers or volumes, use `redis-outage.ps1` with `Status`,
`Suspend -AcknowledgeImpact`, and `Resume`.

PIDs are stored in ignored `.runtime/apps/processes.json`; stdout and stderr are separated under
`.runtime/apps/logs/<timestamp>/`. Repeating the start command recognizes the same healthy recorded
processes and does not create duplicates. An occupied port that does not belong to the manifest
fails before build or startup.

Verify the running account flow through Gateway with a fresh test account:

```powershell
.\scripts\smoke.ps1
```

By default, the smoke test checks Gateway health, then uses a unique random username to register,
log in, read the current profile, and read the current wallet. It verifies lowercase username
normalization, identity consistency, the exact `USER` role, and balances of `10000.00` available and
`0.00` frozen. Each step prints its trace ID, but generated passwords and access tokens are never
printed. Any HTTP or assertion failure throws with the failing step and trace ID, so
`powershell -File` and CI receive a nonzero exit code. A different local Gateway can be selected
explicitly:

```powershell
.\scripts\smoke.ps1 -GatewayBaseUri 'http://127.0.0.1:9000'
```

Run the phase 02 auction-core flow after enabling a real private OSS Bucket and the local development
administrator in `.env`, then restarting the applications so they receive those settings:

```powershell
.\scripts\smoke.ps1 -AuctionCore
```

This extended flow creates a unique seller and two unique buyers, uploads a generated one-pixel PNG
through a presigned OSS PUT, creates and submits an auction, logs in as the configured administrator
to approve it, registers both buyers, replays one registration, and verifies each wallet changed from
`10000.00/0.00` to `9950.00/50.00` exactly once. It waits for the scheduled start, submits two buyer
bids, replays the first bid with the same request ID, then checks the final MySQL-backed price,
minimum next bid, bid count, ordering and bidder-relative identity in history. The generated users,
auction and bound image intentionally remain as inspectable demonstration data. The script never
prints administrator credentials, Access Tokens, OSS credentials, or complete presigned URLs. On
Windows the OSS PUT uses the bundled `curl.exe`/Schannel path; the signed URL and required headers
are supplied through curl standard input rather than command-line arguments, and the temporary image
body under ignored `.runtime/smoke/` is removed immediately after the request.

Run the phase 03 sold-auction and payment flow with the same private OSS and development administrator
configuration:

```powershell
.\scripts\smoke.ps1 -ReliableTrade
```

`-ReliableTrade` includes the full phase 02 flow and runs three deliberately short auctions. The
first closes sold, captures the winning `50.00` deposit, releases the losing deposit, pays the
`60.00` tail twice with one request ID, and verifies one payment plus the seller's full `110.00`
credit. The second has two registered buyers but no bids; it must close `CLOSED_UNSOLD`, create no
order, and release both deposits. The third closes sold but remains unpaid; it must become
`PAYMENT_TIMEOUT`, release the loser, forfeit only the winner's `50.00` deposit, and credit only that
amount to the seller.

The checked `.env.example` sets `TIDEBID_TRADE_PAYMENT_WINDOW=2m` so local timeout acceptance is
practical, while the application keeps a production-safe `30m` default whenever that variable is
absent. Restart the applications after changing it. `-ReliableTrade` validates the configured value
before creating data. Use `-ReliableTradeCoverage Sold` or `SoldAndUnsold` for a shorter diagnostic
run; the default `All` is the final acceptance path. `-ReliableTradeTimeoutSeconds` controls each
eventual-consistency wait. Every run uses new users and business data; any failed assertion exits
nonzero.

Run the stage 04 realtime-proxy smoke flow after the same local stack is running:

```powershell
.\scripts\smoke.ps1 -RealtimeProxy
```

This includes the auction setup and, after the auction opens, requests a one-time ticket through
Gateway, performs a WebSocket upgrade with the allowed local Origin, subscribes with sequence 0,
and verifies both `CONNECTED` and a MySQL-backed `SNAPSHOT`. The script does not print the ticket,
JWT, internal service token or complete WebSocket URL. It is a protocol/route smoke check, not a
replacement for the two-browser proxy, Redis outage and dual-Realtime-instance drills in the stage
04 checklist.

Stop only the application processes recorded by this checkout, then optionally stop middleware:

```powershell
.\scripts\stop-apps.ps1
.\scripts\infra-down.ps1
```

`stop-apps.ps1` validates both the PID and its command marker before terminating that process tree,
removes the PID manifest, and preserves logs. It does not scan for or kill unrelated Java or Node
processes. `infra-down.ps1` preserves all containers and named volumes.

For a normal development restart, keep the middleware running, stop the recorded applications, and
restart from existing build outputs when source files have not changed:

```powershell
.\scripts\stop-apps.ps1
.\scripts\start-apps.ps1 -SkipBuild
.\scripts\smoke.ps1
```

If startup or smoke verification fails, use the reported component, step, port, and trace ID first.
Application logs are under `.runtime/apps/logs/<timestamp>/`; middleware status and logs are available
through these commands:

```powershell
docker compose --env-file .env -f infra/compose.yaml ps
docker compose --env-file .env -f infra/compose.yaml logs <service>
```

For a compact application and realtime-middleware status summary, use:

```powershell
.\scripts\status.ps1
.\scripts\status.ps1 -AssertHealthy
```

The status script prints only health/state labels. It does not print JWTs, tickets, internal service
tokens, OSS credentials or other secret values.

A port-conflict failure is intentional: stop the known owner or choose the correct environment
instead of allowing the script to terminate an unrecorded process.
For dependency changes, omit `-SkipBuild`; for Nacos configuration changes, rerun `start-apps.ps1`
after stopping the applications because managed configuration refresh is intentionally disabled.

## Run an individual service

Build from the repository root, then run any executable JAR in a separate terminal:

```powershell
mvn clean verify
java -jar services/gateway-service/target/gateway-service.jar
```

Open `http://127.0.0.1:9000/actuator/health` to see `{"status":"UP"}`, or
`http://127.0.0.1:9000/actuator/info` to see the application name. Stop the process with Ctrl+C.
If your local Maven environment uses a custom settings file, pass it using `mvn -s <path-to-settings.xml> clean verify`.

| Service | Executable JAR | Application name |
| --- | --- | --- |
| Gateway | `services/gateway-service/target/gateway-service.jar` | `tidebid-gateway` |
| Account | `services/account-service/target/account-service.jar` | `tidebid-account` |
| Auction | `services/auction-service/target/auction-service.jar` | `tidebid-auction` |
| Trade | `services/trade-service/target/trade-service.jar` | `tidebid-trade` |
| Realtime | `services/realtime-service/target/realtime-service.jar` | `tidebid-realtime` |
| AI | `services/ai-service/target/ai-service.jar` | `tidebid-ai` |

The default profile is `standalone`. It disables Nacos configuration and registration, needs no
Docker or cloud keys, and binds each service to loopback (`127.0.0.1`). A healthy standalone
service does not imply database, broker, authentication, or end-to-end readiness.

Every service keeps the shared configuration files below:

- `application.yml`: application name, port, Actuator, serialization, and logging defaults.
- `application-standalone.yml`: explicit switches for operation without Nacos.
- `application-nacos.yml`: remote configuration and service registration for infrastructure integration.

The account and auction services additionally have `application-local-db.yml`. This profile keeps
Nacos disabled but connects to Docker MySQL on port 13306 with each service's isolated database user.
Auction calls Account directly at `http://127.0.0.1:9101` in this mode; override it with
`TIDEBID_ACCOUNT_BASE_URL` when the Account address differs. In the `nacos` profile the URL remains
unset and Feign resolves `tidebid-account` through service discovery. On startup, Flyway validates
and applies versioned files under `db/migration`; MyBatis-Plus uses the same application data source
for runtime persistence. `standalone` explicitly disables database and Flyway auto-configuration so
the no-infrastructure skeleton tests remain useful.

Auction migration V4 performs a one-time replay of already-published closing outcomes and deposit
settlement requests. This covers upgrades where phase 02 auctions closed before the new Account or
Trade consumer group first came online. Existing consumers absorb the repeated event IDs through
their Inbox, while previously missed auctions receive their orders and Hold settlement without
cross-Schema reads or manual business-row edits.

Account persistence entities stay under `account.infrastructure.persistence` and are never API
contracts. Tables with one `BIGINT` primary key use MyBatis-Plus application-generated IDs; the
`user_role` composite key uses explicit SQL instead of unsafe `...ById` methods. Insert timestamps
and initial versions are filled centrally, while `user_account` and `wallet_account` updates use a
version predicate so stale writes affect zero rows.

Only `health` and `info` are exposed through Actuator; the discovery page, `env`, and `beans`
are not exposed. Actuator keeps its standard response format. Unknown application paths return a
404 JSON error with `code`, `message`, `data`, and `traceId`.
In the `nacos` profile, the gateway is the business API entry point. It uses explicit `lb://`
routes for the five downstream Nacos services; automatic `/service-name/**` discovery routes stay
disabled. Registration and login are anonymous, while other `/api/**` and `/ws/**` paths require a
valid TideBid access token. The gateway removes client-supplied internal identity headers before
rebuilding them from verified claims. Downstream services still verify the forwarded Token.
Visiting `/` or another unknown non-business path returns 404.

Gateway CORS allows only `http://localhost:5173` and `http://127.0.0.1:5173`, does not enable
credentialed cross-origin requests, and exposes only the trace response header. Registration and
login use separate Redis fixed-window counters keyed by the TCP peer address. The default 60-second
window permits 5 registration requests and 10 login requests; rejected requests return JSON 429
with `Retry-After`, and ordinary business reads do not consume either counter. The increment and
TTL initialization run atomically in Redis Lua. If Redis is unavailable, these two security-sensitive
entry points return JSON 503 instead of silently bypassing the limit.

Gateway failures use the same public JSON envelope as downstream MVC services. An unknown route is
404, a missing service instance or downstream network failure is 503, and an unexpected non-network
failure is a sanitized 500. Every public response exposes exactly one trusted `X-Trace-Id`, even when
the downstream service also returns that header. Gateway and MVC completion logs contain only the
trace ID, method, path, and status; query strings, authorization headers, bodies, and transport details
are not logged by this access record.

Gateway uses WebFlux/Netty and its own reactive error/trace handling. MVC services obtain their
trace filter and exception advice from `common-web` auto-configuration.
The application tests start real HTTP servers on random ports in `standalone` mode.
Endpoints under `/_test/` exist only in test code and are not packaged in application JARs.

## Local middleware

The Compose environment contains MySQL, Redis, Nacos, one RocketMQ NameServer, Broker and Proxy,
and RocketMQ Dashboard. All images use fixed patch versions, all published ports bind only to
`127.0.0.1`, and persistent state is stored in named Docker volumes.

TideBid publishes its MySQL container on `127.0.0.1:13306` so it can coexist with an existing
Windows MySQL installation on `localhost:3306`. In DataGrip, keep old projects on port 3306 and
create a separate TideBid data source on port 13306. Containers still reach this database as
`mysql:3306`; only access from the Windows host uses 13306.

Compose runs three short-lived initialization jobs. `mysql-bootstrap` creates the four business
schemas and the four restricted service accounts after MySQL becomes healthy, then verifies that
each account can write only its own schema. `rocketmq-volume-init` gives new named volumes to the
non-root RocketMQ user before RocketMQ starts. After the Broker is healthy, `rocketmq-bootstrap`
idempotently creates the three normal event topics, the delay-command topic and six consumer
groups before the Proxy accepts application traffic. All three jobs finish as `Exited (0)`; that
status is expected. The standalone Proxy has a bounded restart policy because the Broker can open
its TCP port shortly before it finishes registering with the NameServer.

A DataGrip connection using local root credentials on port 13306 can inspect every TideBid schema.
Application services will not use root: `tidebid_account_app`, `tidebid_auction_app`,
`tidebid_trade_app`, and `tidebid_ai_app` are each limited to the matching schema. Gateway and the
realtime service do not own a MySQL schema.

Create the ignored local environment file before the first start:

```powershell
Copy-Item .env.example .env
```

Replace every `change-me` value in `.env`. `TIDEBID_NACOS_AUTH_TOKEN` must be Base64 for at least
32 random bytes. One way to generate it in PowerShell is:

```powershell
$tokenBytes = New-Object byte[] 48
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($tokenBytes)
[Convert]::ToBase64String($tokenBytes)
$random.Dispose()
```

The printed value belongs only in the ignored `.env`. Use different random values for
`TIDEBID_NACOS_AUTH_IDENTITY_KEY` and `TIDEBID_NACOS_AUTH_IDENTITY_VALUE`, and use strong, distinct
local passwords for the MySQL root account, four service database accounts, Redis, and Nacos.

Generate the local RS256 key pair referenced by `.env`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/generate-jwt-keys.ps1
```

The command requires Java 21 and creates a 3072-bit PKCS#8 private key plus an X.509 public key
under `.runtime/keys/`. Both files are ignored by Git. Running it again validates that the files
still form a pair and leaves them unchanged; `-Force` deliberately rotates the pair and invalidates
tokens signed by the old private key. Do not use `-Force` as a routine startup step. Only the
account service will read the private key, while the gateway and downstream verifiers use the
public key.

Start and validate the infrastructure from the repository root:

```powershell
.\scripts\infra-up.ps1
```

The script checks Docker Desktop, validates required non-placeholder `.env` values and the Nacos
Base64 token, runs Compose, and observes container state until all seven long-running services are
healthy. `mysql-bootstrap`, `rocketmq-volume-init` and `rocketmq-bootstrap` must instead finish as
`Exited (0)`. It never deletes containers or named volumes. Use `-TimeoutSeconds 600` on a slow
first image pull. Verify the persisted RocketMQ Topic types and Consumer Group settings at any time:

```powershell
.\scripts\check-rocketmq-topology.ps1
```

Inspect messaging and trade state without selecting event payloads, credentials, user details or
presigned URLs:

```powershell
.\scripts\outbox-status.ps1
.\scripts\outbox-status.ps1 -AssertHealthy
```

The first command reports per-service Outbox backlog/DEAD counts, safe error codes, Inbox counts,
auction/Hold/order/payment status totals and pending seller settlement totals. `-AssertHealthy`
additionally exits nonzero when any due Outbox row or DEAD message remains; future delayed commands
are not treated as backlog.

For a controlled local Broker outage drill, use only the scoped helper below. It addresses the
`rocketmq-broker` service from this repository's Compose file, never removes a container or volume,
and requires an explicit acknowledgement before interruption:

```powershell
.\scripts\rocketmq-outage.ps1 -Action Status
.\scripts\rocketmq-outage.ps1 -Action Suspend -AcknowledgeImpact
.\scripts\rocketmq-outage.ps1 -Action Resume
```

`Resume` delegates to the idempotent infrastructure startup and then revalidates every persisted
Topic and Consumer Group. Always run it even when a drill assertion fails. A repeatable
Outbox/database-fallback exercise uses two terminals:

```powershell
# Terminal A: pause immediately before the first bid and again after both bids commit.
.\scripts\smoke.ps1 -ReliableTrade -ReliableTradeCoverage Sold `
    -ReliableTradeAuctionDurationSeconds 180 `
    -PauseBeforeFirstBid -PauseAfterSecondBid

# Terminal B at the first checkpoint:
.\scripts\outbox-status.ps1
.\scripts\rocketmq-outage.ps1 -Action Suspend -AcknowledgeImpact
# Return to A and type CONTINUE to submit both bids. At the second checkpoint inspect the due backlog,
# leave the Broker down past endAt if testing the Auction database fallback, then restore it:
.\scripts\outbox-status.ps1
.\scripts\rocketmq-outage.ps1 -Action Resume
.\scripts\outbox-status.ps1 -AssertHealthy
# Return to A and type CONTINUE; the normal sold/payment assertions must still finish exactly once.
```

This drill deliberately changes local runtime availability and creates normal smoke business data;
it does not mutate database rows by hand. ACK-before-mark, consumer rollback and poison/DLQ cases
remain automated integration-test/final-acceptance exercises rather than unsafe production-style
injection endpoints.

To verify the payment-deadline database fallback, keep `TIDEBID_TRADE_PAYMENT_WINDOW=2m`, run the
full smoke with a checkpoint, and suspend only the Broker when the timeout order reaches
`PENDING_PAYMENT`:

```powershell
# Terminal A
.\scripts\smoke.ps1 -ReliableTrade -ReliableTradeCoverage All `
    -ReliableTradeTimeoutSeconds 300 -PauseAfterTimeoutPending

# Terminal B after the checkpoint printed by Terminal A
.\scripts\rocketmq-outage.ps1 -Action Suspend -AcknowledgeImpact
# Wait past the printed paymentDeadline. The order must become PAYMENT_TIMEOUT while seller
# settlement remains PENDING because the credit event cannot yet be delivered.
.\scripts\outbox-status.ps1
.\scripts\rocketmq-outage.ps1 -Action Resume
.\scripts\outbox-status.ps1 -AssertHealthy
# Return to Terminal A and type CONTINUE; final wallet and COMPLETED settlement assertions continue.
```

To reproduce an Account-success/Trade-unknown recovery with real processes, use the two guarded
application checkpoints. The helper calls only Account's localhost-only internal API, reads the
service token from `.env` without printing it, and verifies every identifier in the response:

```powershell
# Terminal A
.\scripts\smoke.ps1 -ReliableTrade -ReliableTradeCoverage Sold `
    -ReliableTradeTimeoutSeconds 300 `
    -PauseBeforeSoldPayment -PauseAfterSoldPaymentUnknown

# Terminal B at the first checkpoint; then return to A and type CONTINUE.
.\scripts\app-outage.ps1 -Service account -Action Suspend -AcknowledgeImpact

# Terminal B at the second checkpoint. Copy the five values printed by Terminal A.
.\scripts\app-outage.ps1 -Service trade -Action Suspend -AcknowledgeImpact
.\scripts\app-outage.ps1 -Service account -Action Resume
.\scripts\account-debit-drill.ps1 `
    -PaymentNo '<paymentNo>' -UserId '<buyerId>' -OrderId '<orderId>' `
    -Amount 60.00 -RequestId '<requestId>' -AcknowledgeImpact

# Trade is still stopped, so Account has one durable debit while Trade remains UNKNOWN.
# Restore the complete application set from an empty application-process state.
.\scripts\stop-apps.ps1
.\scripts\start-apps.ps1 -SkipBuild
.\scripts\outbox-status.ps1 -AssertHealthy

# Return to Terminal A and type CONTINUE. It must finish PAID/COMPLETED and wallet assertions.
```

The stable `paymentNo` is the idempotency key. Replaying the debit cannot add another
`wallet_debit` or `wallet_ledger` row; after restart, Trade queries that same number before deciding
whether any retry is safe. `app-outage.ps1` checks the PID manifest and exact command marker before
stopping a process, and `Resume` updates the manifest so the normal stop script still owns it.

To verify seller settlement while Account is unavailable, hold the credit request in Trade Outbox
by stopping the Broker before payment. Payment still uses Account's synchronous internal API, so
the order becomes `PAID/PENDING` without waiting for messaging:

```powershell
# Terminal B before starting the smoke. Keep this terminal open for the drill.
# The longer window leaves room for the Broker's graceful local shutdown.
$env:TIDEBID_TRADE_PAYMENT_WINDOW = '5m'
.\scripts\app-outage.ps1 -Service trade -Action Suspend -AcknowledgeImpact
.\scripts\app-outage.ps1 -Service trade -Action Resume

# Terminal A
.\scripts\smoke.ps1 -ReliableTrade -ReliableTradeCoverage Sold `
    -ReliableTradeTimeoutSeconds 300 `
    -PauseBeforeSoldPayment -PauseAfterSoldPaymentPendingSettlement

# Terminal B at the first checkpoint; then return to A and type CONTINUE.
.\scripts\rocketmq-outage.ps1 -Action Suspend -AcknowledgeImpact

# Terminal B at the second checkpoint. Stop Account before releasing the credit request.
.\scripts\app-outage.ps1 -Service account -Action Suspend -AcknowledgeImpact
.\scripts\rocketmq-outage.ps1 -Action Resume
.\scripts\outbox-status.ps1
.\scripts\seller-credit-status.ps1 -OrderId '<orderId>' -ExpectedState Pending
.\scripts\app-outage.ps1 -Service account -Action Resume
.\scripts\seller-credit-status.ps1 -OrderId '<orderId>' -ExpectedState Completed
.\scripts\outbox-status.ps1 -AssertHealthy

# Return to Terminal A and type CONTINUE. It verifies COMPLETED and the seller's exact final balance.

# Terminal B after Terminal A passes. Restore the value from .env.
Remove-Item Env:TIDEBID_TRADE_PAYMENT_WINDOW
.\scripts\app-outage.ps1 -Service trade -Action Suspend -AcknowledgeImpact
.\scripts\app-outage.ps1 -Service trade -Action Resume
```

The Account consumer may receive the request more than once after recovery. The stable `creditNo`,
the unique `wallet_credit.order_id`, Inbox deduplication and ledger business number prevent duplicate
seller balance changes. Always restore both Account and the Broker if the drill is interrupted.

Run the bounded local poison-message drill to verify real Broker retries and DLQ routing. The script
uses a unique UUID key, sends invalid JSON only to the seller-credit subscription, temporarily uses
two short retries, and restores the configured 16-retry group baseline in a `finally` block:

```powershell
.\scripts\rocketmq-dlq-drill.ps1 -AcknowledgeImpact
```

Success requires at least three rejected deliveries with the same RocketMQ message ID, a matching
record in `%DLQ%tidebid-account-credit-v1`, and a passing topology check after restoration. The
invalid message cannot reach the Inbox or any wallet mutation because envelope decoding fails first.

Use a completed paid order from the seller-settlement drill to reproduce the other Outbox ambiguity:
the Broker accepted an event but the producer crashed before marking its local row. This guarded
local script reopens only that order's published seller-credit lease, preserving its event and
payload, and waits for the normal Trade publisher to reclaim it:

```powershell
.\scripts\outbox-ack-drill.ps1 -OrderId '<completed-orderId>' -AcknowledgeImpact
```

It passes only when the Topic gains another copy with the same `eventId`, Outbox attempts increase
by exactly one, and Account's Inbox count, wallet credit, ledger count, balance and wallet version
all remain unchanged.

Finally, use the same completed order to exercise a real consumer transaction rollback. The guarded
script temporarily lowers the lock wait for new local MySQL sessions, restarts Account, locks the
seller wallet, and publishes a contract-valid request with a new `eventId` but the same credit intent:

```powershell
.\scripts\consumer-rollback-drill.ps1 -OrderId '<completed-orderId>' -AcknowledgeImpact
```

The first delivery inserts Inbox and then times out waiting for the wallet lock, so the whole local
transaction must roll back. The script confirms that the new Inbox row is absent and all financial
state is unchanged before releasing the lock. It then requires the Broker retry to commit exactly
one Inbox row without duplicating the existing credit, ledger, result Outbox, balance or wallet
version. A `finally` block restores the original MySQL lock wait and restarts Account even on failure.

After the drill, audit the latest application run without printing any matched secret value:

```powershell
.\scripts\audit-runtime-logs.ps1
# Or inspect a specific preserved run:
.\scripts\audit-runtime-logs.ps1 -LogDirectory .runtime/apps/logs/<timestamp>
```

The audit compares logs with configured sensitive environment values and detects bearer
credentials, OSS signature query parameters and private-key material. It exits nonzero and reports
only the affected file and finding category when a leak is detected.

The first pull is large because Nacos and RocketMQ are Java images. Nacos is available at
`http://127.0.0.1:8080`, and RocketMQ Dashboard at `http://127.0.0.1:8088`. Host Java applications
use the RocketMQ Proxy endpoint `127.0.0.1:8081`, not the Broker's internal Docker hostname.

To run the account service with its local database from PowerShell, first load the ignored `.env`
into the current process and select the `local` profile:

```powershell
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $name, $value = $line.Split('=', 2)
        Set-Item -Path "Env:$name" -Value $value
    }
}
java -jar services/account-service/target/account-service.jar --spring.profiles.active=local-db
```

The first database-enabled start creates `flyway_schema_history`, `user_account`, `user_role`,
`wallet_account`, and `wallet_ledger`. Later starts validate the checksum and leave version 1
unchanged. Once version 1 has been applied, change the schema by adding a new migration such as
`V2__describe_change.sql`; do not edit the applied `V1` file.

With both `gateway-service` and `account-service` running under the `nacos` profile, exercise the
registration endpoint through the public gateway on port 9000:

```powershell
$headers = @{ 'X-Request-Id' = 'readme-register-01' }
$body = @{
    username = 'Demo_User'
    nickname = 'Demo User'
    password = 'ChangeMe-123'
} | ConvertTo-Json
Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:9000/api/auth/register' `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $body
```

A successful request returns HTTP 201 and the canonical lowercase username. Public `userId` fields
and the JWT `userId` claim are decimal strings so browser clients do not lose 64-bit integer
precision. The same transaction
creates the `USER` role, a `10000.00` virtual wallet, and its initialization ledger. Reusing the
same username (including a case-only variant) returns HTTP 409.

The registered account can then log in through the same service. Login applies the same lowercase
username normalization and returns an RS256 Bearer access token with a two-hour lifetime:

```powershell
$loginHeaders = @{ 'X-Request-Id' = 'readme-login-001' }
$loginBody = @{
    username = 'DEMO_USER'
    password = 'ChangeMe-123'
} | ConvertTo-Json
$loginResponse = Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:9000/api/auth/login' `
    -Headers $loginHeaders `
    -ContentType 'application/json' `
    -Body $loginBody
$loginResponse.data | Select-Object tokenType, expiresIn
```

An unknown username and an incorrect password deliberately return the same 401 response. A disabled
account with the correct password returns 403. Treat `accessToken` as a secret: do not print it in
logs, paste it into issue reports, or commit it to Git.

Use the returned Token through port 9000 to read the authenticated account and virtual wallet.
Neither endpoint accepts a user ID from the client:

```powershell
$authHeaders = @{
    Authorization = "$($loginResponse.data.tokenType) $($loginResponse.data.accessToken)"
    'X-Trace-Id' = 'readme-self-0001'
}
$currentUser = Invoke-RestMethod -Method Get `
    -Uri 'http://127.0.0.1:9000/api/users/me' `
    -Headers $authHeaders
$currentWallet = Invoke-RestMethod -Method Get `
    -Uri 'http://127.0.0.1:9000/api/wallets/me' `
    -Headers $authHeaders
$currentUser.data
$currentWallet.data
```

`account-service` verifies the Token again instead of trusting client-supplied internal identity
headers. Missing, expired, malformed, or tampered Tokens return the same JSON 401 response.

An optional development administrator can be created when `account-service` starts. The bootstrap is
disabled by default. Add the following values to the ignored `.env`, choose your own strong password,
load the file into the PowerShell process, and then start `account-service` with `local-db` or `nacos`:

```dotenv
TIDEBID_DEV_ADMIN_ENABLED=true
TIDEBID_DEV_ADMIN_USERNAME=tidebid_admin
TIDEBID_DEV_ADMIN_PASSWORD=replace-with-a-strong-local-password
TIDEBID_DEV_ADMIN_NICKNAME=TideBid Administrator
```

The first enabled start creates one normal account aggregate with both `USER` and `ADMIN` roles.
Later starts are read-only and require the same password to match the stored BCrypt hash. A colliding
ordinary account, a disabled administrator, missing roles, or a different password makes startup
fail instead of silently promoting an account or resetting credentials. Disabling the bootstrap does
not delete an administrator already created.

After logging in with that account, check its authorization through the gateway:

```powershell
$adminLoginHeaders = @{ 'X-Request-Id' = 'readme-admin-login' }
$adminLoginBody = @{
    username = $env:TIDEBID_DEV_ADMIN_USERNAME
    password = $env:TIDEBID_DEV_ADMIN_PASSWORD
} | ConvertTo-Json
$adminLoginResponse = Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:9000/api/auth/login' `
    -Headers $adminLoginHeaders `
    -ContentType 'application/json' `
    -Body $adminLoginBody
$adminHeaders = @{
    Authorization = "$($adminLoginResponse.data.tokenType) $($adminLoginResponse.data.accessToken)"
    'X-Trace-Id' = 'readme-admin-0001'
}
Invoke-RestMethod -Method Get `
    -Uri 'http://127.0.0.1:9000/api/admin/access-check' `
    -Headers $adminHeaders
```

The access check validates `ADMIN` in the signed Token and reads the current role again from MySQL;
it does not implement an auction administration feature.

Nacos 3 no longer supplies a default administrator password. On a fresh volume, the configuration
import command below initializes the `nacos` administrator from `TIDEBID_NACOS_PASSWORD`. On later
runs it logs in normally and updates the same namespace and Data IDs.

Stop the same containers without deleting data:

```powershell
.\scripts\infra-down.ps1
.\scripts\infra-up.ps1
```

The stop script uses `docker compose stop`, verifies that no project container remains running,
honors the per-service Compose shutdown windows, and deliberately exposes no volume-deletion option.
A manual `docker compose down` removes the containers and project network but keeps named volumes.
Do not add `-v` unless you deliberately intend to erase the local MySQL, Redis, and RocketMQ data.

## Nacos configuration import and integration profile

After Nacos is healthy, import the repository-managed configuration templates:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/import-nacos-config.ps1
```

The command reads the ignored `.env`, waits for the Nacos readiness endpoint, creates namespace
`tidebid-dev` when necessary, and publishes `tidebid-common.yml` plus six service-level Data IDs to
group `TIDEBID_GROUP`. Every publish is read back and compared byte-for-byte after newline
normalization. Running the command again updates the same entries instead of creating duplicates.

Configuration ownership is deliberately split:

| Source | Owns |
| --- | --- |
| Repository `application*.yml` | application name, port, profile wiring, and Nacos import addresses |
| `infra/nacos/configs/*.yml` published to Nacos | shared operational settings and non-secret service settings |
| Ignored `.env` / process environment | passwords, Nacos credentials, JWT key paths, OSS credentials, and model keys |

Stage 03 keeps the RocketMQ Proxy endpoint, request timeout and Outbox scan/lease/backoff limits in
`tidebid-common.yml`. Account, Auction and Trade service Data IDs contain only their own Topic and
Consumer Group names. The local Broker permits at most 72 hours of delayed delivery, while the
application uses a conservative 48-hour safe horizon; commands farther away remain in MySQL until
they enter that window. `start-apps.ps1 -CheckOnly` validates these managed values and rejects URI
schemes, invalid ports, missing topology entries or messaging configuration accidentally placed in
the Gateway Data ID without printing the configured endpoint.

Nacos files keep expressions such as `${TIDEBID_ACCOUNT_DB_PASSWORD}` as placeholders; the importer
must never expand or upload their secret values. Java does not load `.env` automatically; the
checked `start-apps.ps1` script loads it for its child processes without printing secrets. To run
one service manually instead, load the file into the current shell as shown in the account database
example above, then start it with the `nacos` profile:

```powershell
java -jar services/gateway-service/target/gateway-service.jar --spring.profiles.active=nacos
```

Because all Java services currently bind only to the Windows host loopback interface,
`TIDEBID_NACOS_DISCOVERY_IP` defaults to `127.0.0.1`. This keeps the registered address consistent
with the actual listener instead of allowing a multi-network-adapter machine to select a Docker or
WSL virtual adapter. Override it when the application topology moves away from host-local processes.

The imports use `spring.config.import` without `optional:`; configuration read/parse failures
must not be silently ignored. This SDK can merely warn when a Data ID is empty, so actual
configuration presence and loading must also be checked during infrastructure integration.
Automatic refresh is disabled in the imports; restart the process after changing configuration.
The host-local development topology has been verified with all six services registered as healthy
instances on ports 9000 and 9101-9105. The importer itself still does not start application
services; `start-apps.ps1` composes the importer, key preparation, process startup and health checks.

Reference: [Gateway 4.3 starter](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/starter.html),
[Gateway load-balancer behavior](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/global-filters.html),
[Gateway CORS configuration](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/cors-configuration.html),
[Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/3.5/redis/scripting.html),
[Spring Boot executable JAR packaging](https://docs.spring.io/spring-boot/3.5/maven-plugin/packaging.html),
[Nacos 3 Docker deployment](https://github.com/nacos-group/nacos-docker), and
[RocketMQ Docker Compose template](https://github.com/apache/rocketmq-docker/blob/master/templates/docker-compose/rmq5-docker-compose.yml).

## Local ports

| Component | Port |
| --- | ---: |
| Gateway | 9000 |
| Account service | 9101 |
| Auction service | 9102 |
| Trade service | 9103 |
| Realtime service | 9104 |
| AI service | 9105 |
| Web development server | 5173 |
| MySQL (host / container) | 13306 / 3306 |
| Redis | 6379 |
| Nacos console / server | 8080 / 8848 / 9848 |
| RocketMQ NameServer / Broker / Proxy | 9876 / 10911 / 8081 |
| RocketMQ Dashboard | 8088 |

## Local cold-start order

The checked lifecycle scripts now implement this order:

1. Copy `.env.example` to `.env` and replace local passwords.
2. Run `infra-up.ps1`. Compose first initializes the MySQL schemas and users, then initializes the
   RocketMQ volume and required topics/groups; the script waits for MySQL, Redis, Nacos, NameServer,
   Broker, Proxy and Dashboard health plus successful one-time initialization jobs.
3. Run `start-apps.ps1`; only after middleware is ready does it build JARs, prepare RS256 keys and
   import the managed Nacos configuration.
4. The script starts Account, Auction, Trade, Realtime, AI, Gateway and Vue in dependency order,
   requiring every process to pass its readiness endpoint before moving on.
5. Run `smoke.ps1`, `smoke.ps1 -AuctionCore`, or `smoke.ps1 -ReliableTrade` for the desired acceptance
   depth. None of these lifecycle commands deletes containers or named volumes.

## Security notes

- Do not commit `.env`, JWT keys, cloud credentials, model API keys, logs, or runtime files.
- All balances are virtual and must not be used for real financial transactions.
- AI and OSS integrations are optional; missing cloud credentials must not break the core auction flow.
