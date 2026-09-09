# TideBid

TideBid is a Java 21 distributed auction platform built around a verifiable bidding and transaction flow. It is designed as a portfolio project for reasoning about concurrency, money consistency, reliable events, real-time updates, and service boundaries—not as a real-money trading system.

The project is currently in the foundation stage. Four shared modules, six executable service skeletons, local middleware, registration, login, authenticated profile and virtual-wallet queries are available. Bidding, the frontend, and one-command lifecycle scripts are not implemented yet.

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

Every service keeps the shared configuration files below:

- `application.yml`: application name, port, Actuator, serialization, and logging defaults.
- `application-standalone.yml`: explicit switches for operation without Nacos.
- `application-nacos.yml`: remote configuration and service registration for infrastructure integration.

The account service additionally has `application-local-db.yml`. This profile keeps Nacos disabled but
connects to the Docker MySQL on port 13306 using `tidebid_account_app`. On startup, Flyway validates
and applies versioned files under `db/migration`; MyBatis-Plus uses the same application data source
for runtime persistence. `standalone` explicitly disables database and Flyway auto-configuration so
the no-infrastructure skeleton tests remain useful.

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

A successful request returns HTTP 201 and the canonical lowercase username. The same transaction
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

Stop and resume the same containers without deleting data:

```powershell
docker compose --env-file .env -f infra/compose.yaml stop
docker compose --env-file .env -f infra/compose.yaml start
```

`docker compose --env-file .env -f infra/compose.yaml down` removes the containers and project network but keeps
named volumes. Do not add `-v` unless you deliberately intend to erase the local MySQL, Redis,
and RocketMQ data. The one-command checked start/stop scripts are a later foundation milestone.

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

Nacos files keep expressions such as `${TIDEBID_ACCOUNT_DB_PASSWORD}` as placeholders; the importer
must never expand or upload their secret values. Java does not load `.env` automatically, so until
the checked startup scripts are implemented, load it into the current PowerShell process as shown
in the account database example above. Then start a service with the `nacos` profile:

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
services; checked one-command lifecycle scripts belong to a later foundation milestone.

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
