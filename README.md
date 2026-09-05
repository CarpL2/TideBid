# TideBid

TideBid is a Java 21 distributed auction platform built around a verifiable bidding and transaction flow. It is designed as a portfolio project for reasoning about concurrency, money consistency, reliable events, real-time updates, and service boundaries—not as a real-money trading system.

The project is currently in the foundation stage. Four shared modules and six executable service skeletons are available. Each service exposes health and application information; registration, login, wallets, bidding, frontend, and infrastructure scripts are not implemented yet.

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
[Spring Boot executable JAR packaging](https://docs.spring.io/spring-boot/3.5/maven-plugin/packaging.html).

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
| MySQL | 3306 |
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
