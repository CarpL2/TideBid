# TideBid

TideBid is a Java 21 distributed auction platform built around a verifiable bidding and transaction flow. It is designed as a portfolio project for reasoning about concurrency, money consistency, reliable events, real-time updates, and service boundaries—not as a real-money trading system.

The project is currently in the foundation stage. Four shared modules, six executable service skeletons, and a local middleware Compose definition are available. Each service exposes health and application information; registration, login, wallets, bidding, frontend, and one-command lifecycle scripts are not implemented yet.

## Core flow

1. A seller creates an auction item and submits it for review.
2. A bidder registers for the auction and locks a virtual deposit.
3. Valid bids are decided by the database with optimistic concurrency control.
4. Bid changes are broadcast to subscribed clients in real time.
5. Closing produces a reliable event, creates an order, and releases or deducts virtual funds.
6. An optional AI assistant can propose item metadata from images without blocking the auction flow.

## Modules

| Module | Responsibility |
| --- | --- |
| `gateway-service` | Routing, JWT verification, CORS, request tracing, and rate limiting |
| `account-service` | Users, roles, virtual wallets, ledgers, and deposits |
| `auction-service` | Items, review, enrollment, bids, and auction state |
| `trade-service` | Winning orders, simulated payment, and payment timeout |
| `realtime-service` | WebSocket sessions, subscriptions, and bid broadcasts |
| `ai-service` | Optional image understanding and listing suggestions |
| `common/*` | Stable response, security, web, and cross-service contract modules |
| `web` | Vue 3 user and administration interface |

## Technology baseline

- Java 21 and Maven multi-module builds
- Spring Boot 3.5, Spring Cloud 2025, and Spring Cloud Alibaba 2025
- MySQL 8.4, Redis 7, RocketMQ 5, Nacos 3, MyBatis-Plus, and Flyway
- Vue 3, TypeScript, Vite, pnpm, and Element Plus
- Alibaba Cloud OSS and Qwen VL as optional integrations

## Development prerequisites

Install the following tools before running the completed foundation environment:

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

Build and test the modules currently in the repository:

```powershell
mvn verify
```

## Run a service skeleton

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
Docker or cloud keys, and binds each service to loopback (`127.0.0.1`). A healthy skeleton does
not imply database, broker, authentication, or end-to-end readiness.

Each service has three configuration files:

- `application.yml`: application name, port, Actuator, serialization, and logging defaults.
- `application-standalone.yml`: explicit switches for operation without Nacos.
- `application-nacos.yml`: remote configuration and service registration for infrastructure integration.

Only `health` and `info` are exposed through Actuator; the discovery page, `env`, and `beans`
are not exposed. Actuator keeps its standard response format. Unknown application paths return a
404 JSON error with `code`, `message`, `data`, and `traceId`.
The gateway currently has no business routes or JWT enforcement; visiting `/` returns 404.

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

Compose runs two short-lived initialization jobs. `mysql-bootstrap` creates the four business
schemas and the four restricted service accounts after MySQL becomes healthy, then verifies that
each account can write only its own schema. `rocketmq-volume-init` gives new named volumes to the
non-root RocketMQ user before RocketMQ starts. Both jobs finish as `Exited (0)`; that status is
expected. The standalone Proxy has a bounded restart policy because the Broker can open its TCP
port shortly before it finishes registering with the NameServer.

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

Validate and start the infrastructure from the repository root:

```powershell
docker compose --env-file .env -f infra/compose.yaml config --quiet
docker compose --env-file .env -f infra/compose.yaml up -d
docker compose --env-file .env -f infra/compose.yaml ps
```

The first pull is large because Nacos and RocketMQ are Java images. Wait until every long-running
service is healthy before using the consoles. Nacos is available at `http://127.0.0.1:8080`, and RocketMQ
Dashboard at `http://127.0.0.1:8088`. Host Java applications use the RocketMQ Proxy endpoint
`127.0.0.1:8081`, not the Broker's internal Docker hostname.

Nacos 3 no longer supplies a default administrator password. On a fresh volume the console asks
to initialize the `nacos` administrator. The idempotent initialization and configuration import
script will be implemented in the Nacos integration milestone; `TIDEBID_NACOS_PASSWORD` is the
password reserved for that step.

Stop and resume the same containers without deleting data:

```powershell
docker compose --env-file .env -f infra/compose.yaml stop
docker compose --env-file .env -f infra/compose.yaml start
```

`docker compose --env-file .env -f infra/compose.yaml down` removes the containers and project network but keeps
named volumes. Do not add `-v` unless you deliberately intend to erase the local MySQL, Redis,
and RocketMQ data. The one-command checked start/stop scripts are a later foundation milestone.

## Nacos integration profile (infrastructure still pending)

Once Nacos is running, create namespace ID `tidebid-dev` and group `TIDEBID_GROUP`, containing
`tidebid-common.yml` plus one `tidebid-<service>.yml` per service (for example, `tidebid-account.yml`).
Set `TIDEBID_NACOS_SERVER_ADDR`, `TIDEBID_NACOS_NAMESPACE`, `TIDEBID_NACOS_USERNAME`, and
`TIDEBID_NACOS_PASSWORD` in the process environment before starting:

```powershell
java -jar services/gateway-service/target/gateway-service.jar --spring.profiles.active=nacos
```

The imports use `spring.config.import` without `optional:`; configuration read/parse failures
must not be silently ignored. This SDK can merely warn when a Data ID is empty, so actual
configuration presence and loading must also be checked during infrastructure integration.
Automatic refresh is disabled in the imports; restart the process after changing configuration.
The configuration import script and live registration checks will be supplied with the infrastructure milestone.
Java does not automatically load the repository's `.env`; the startup scripts for that are still pending.

Reference: [Gateway 4.3 starter](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/starter.html),
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

## Foundation startup order

The following is the intended startup order. Exact scripts and runnable commands will be added with the corresponding implementation:

1. Copy `.env.example` to `.env` and replace local passwords.
2. Start MySQL, Redis, Nacos, and RocketMQ with Docker Compose.
3. Import shared and service-level configuration into the `tidebid-dev` Nacos namespace.
4. Generate local RS256 development keys.
5. Start the six Java services.
6. Start the Vue development server.
7. Run the smoke test for registration, login, profile, and wallet queries.

## Security notes

- Do not commit `.env`, JWT keys, cloud credentials, model API keys, logs, or runtime files.
- All balances are virtual and must not be used for real financial transactions.
- AI and OSS integrations are optional; missing cloud credentials must not break the core auction flow.
