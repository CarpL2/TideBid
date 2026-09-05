# TideBid

TideBid is a Java 21 distributed auction platform built around a verifiable bidding and transaction flow. It is designed as a portfolio project for reasoning about concurrency, money consistency, reliable events, real-time updates, and service boundaries—not as a real-money trading system.

The project is currently in the foundation stage. The Maven parent and four shared modules are implemented and verified; deployable services, local infrastructure, frontend, and startup scripts are still in progress.

## Core flow

1. A seller creates an auction item and submits it for review.
2. A bidder registers for the auction and locks a virtual deposit.
3. Valid bids are decided by the database with optimistic concurrency control.
4. Bid changes are broadcast to subscribed clients in real time.
5. Closing produces a reliable event, creates an order, and releases or deducts virtual funds.
6. An optional AI assistant can propose item metadata from images without blocking the auction flow.

## Planned modules

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
