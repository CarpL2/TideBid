import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { cpus, platform, release, totalmem } from 'node:os';
import { dirname, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

const requireFromWeb = createRequire(resolve('web/package.json'));
const BenchmarkWebSocket = requireFromWeb('ws');

const MODES = new Set(['bid', 'realtime', 'all']);

function parseArguments(argv) {
  const result = { mode: 'all', config: '.runtime/benchmark.json', output: '', dryRun: false };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--dry-run') result.dryRun = true;
    else if (value === '--mode') result.mode = argv[++index];
    else if (value === '--config') result.config = argv[++index];
    else if (value === '--output') result.output = argv[++index];
    else throw new Error(`Unknown argument: ${value}`);
  }
  if (!MODES.has(result.mode)) throw new Error('--mode must be bid, realtime, or all.');
  if (!result.config) throw new Error('--config requires a path.');
  return result;
}

function requireInteger(value, name, minimum, maximum) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} through ${maximum}.`);
  }
  return value;
}

function moneyToCents(value, name) {
  if (typeof value !== 'string' || !/^\d{1,17}(?:\.\d{1,2})?$/.test(value)) {
    throw new Error(`${name} must be a positive decimal string with at most two fraction digits.`);
  }
  const [whole, fraction = ''] = value.split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (cents <= 0n) throw new Error(`${name} must be positive.`);
  return cents;
}

function centsToMoney(cents) {
  const whole = cents / 100n;
  const fraction = String(cents % 100n).padStart(2, '0');
  return `${whole}.${fraction}`;
}

function validateConfig(raw) {
  if (!raw || raw.version !== 1) throw new Error('Benchmark config version must be 1.');
  let gateway;
  try { gateway = new URL(raw.gatewayBaseUri); } catch { throw new Error('gatewayBaseUri must be an absolute URL.'); }
  if (!['http:', 'https:'].includes(gateway.protocol) || gateway.search || gateway.hash) {
    throw new Error('gatewayBaseUri must be an HTTP(S) URL without query or fragment.');
  }
  if (!/^\d+$/.test(String(raw.auctionId)) || BigInt(raw.auctionId) <= 0n) {
    throw new Error('auctionId must be a positive integer string.');
  }
  if (typeof raw.auctionTitlePrefix !== 'string' || raw.auctionTitlePrefix.length < 6) {
    throw new Error('auctionTitlePrefix must contain at least six characters.');
  }
  if (!Array.isArray(raw.actors) || raw.actors.length < 2 || raw.actors.length > 100) {
    throw new Error('actors must contain 2 through 100 dedicated benchmark accounts.');
  }
  for (const [index, actor] of raw.actors.entries()) {
    if (!actor || typeof actor.username !== 'string' || !actor.username.startsWith('benchmark_')) {
      throw new Error(`actors[${index}].username must start with benchmark_.`);
    }
    if (typeof actor.password !== 'string' || actor.password.length < 8) {
      throw new Error(`actors[${index}].password must contain at least eight characters.`);
    }
  }
  const bid = raw.bid ?? {};
  const realtime = raw.realtime ?? {};
  return {
    version: 1,
    gatewayBaseUri: gateway.toString().replace(/\/$/, ''),
    auctionId: String(raw.auctionId),
    auctionTitlePrefix: raw.auctionTitlePrefix,
    actors: raw.actors.map(({ username, password }) => ({ username, password })),
    bid: {
      requests: requireInteger(bid.requests, 'bid.requests', 1, 10000),
      concurrency: requireInteger(bid.concurrency, 'bid.concurrency', 1, 200),
      incrementCents: moneyToCents(bid.increment, 'bid.increment'),
      timeoutMs: requireInteger(bid.timeoutMs, 'bid.timeoutMs', 1000, 120000)
    },
    realtime: {
      connections: requireInteger(realtime.connections, 'realtime.connections', 1, raw.actors.length),
      timeoutMs: requireInteger(realtime.timeoutMs, 'realtime.timeoutMs', 1000, 120000)
    }
  };
}

async function api(config, path, { method = 'GET', authorization = '', requestId = '', body, timeoutMs = 10000 } = {}) {
  const headers = { 'X-Trace-Id': `bench-${crypto.randomUUID().replaceAll('-', '').slice(0, 20)}` };
  if (authorization) headers.Authorization = authorization;
  if (requestId) headers['X-Request-Id'] = requestId;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const started = performance.now();
  let response;
  try {
    response = await fetch(`${config.gatewayBaseUri}${path}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs)
    });
  } catch (error) {
    return { status: 0, code: 'TRANSPORT_ERROR', durationMs: performance.now() - started, error: error.message };
  }
  const durationMs = performance.now() - started;
  let payload;
  try { payload = await response.json(); } catch { payload = null; }
  return { status: response.status, code: payload?.code ?? 'INVALID_RESPONSE', data: payload?.data, durationMs };
}

async function loginActors(config) {
  const actors = [];
  for (const actor of config.actors) {
    const response = await api(config, '/api/auth/login', {
      method: 'POST', requestId: `bench-login-${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`,
      body: actor, timeoutMs: config.bid.timeoutMs
    });
    if (response.status !== 200 || response.code !== 'SUCCESS' || !response.data?.accessToken) {
      throw new Error(`Login failed for dedicated actor ${actor.username}: HTTP ${response.status}, code ${response.code}.`);
    }
    actors.push({ username: actor.username, authorization: `${response.data.tokenType} ${response.data.accessToken}` });
  }
  return actors;
}

async function detail(config, authorization) {
  const response = await api(config, `/api/auctions/${config.auctionId}`, {
    authorization, timeoutMs: config.bid.timeoutMs
  });
  if (response.status !== 200 || response.code !== 'SUCCESS') {
    throw new Error(`Benchmark auction query failed: HTTP ${response.status}, code ${response.code}.`);
  }
  return response.data;
}

async function assertDedicatedFixture(config, actors) {
  for (const actor of actors) {
    const value = await detail(config, actor.authorization);
    if (!String(value.title ?? '').startsWith(config.auctionTitlePrefix)) {
      throw new Error(`Safety check rejected auction ${config.auctionId}: title must start with ${JSON.stringify(config.auctionTitlePrefix)}.`);
    }
    if (value.sessionStatus !== 'OPEN') {
      throw new Error(`Benchmark auction must be OPEN, actual status is ${value.sessionStatus}.`);
    }
    if (value.myRegistration?.status !== 'REGISTERED') {
      throw new Error(`Dedicated actor ${actor.username} is not REGISTERED for benchmark auction ${config.auctionId}.`);
    }
  }
}

function percentile(values, quantile) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return Number(sorted[Math.ceil(quantile * sorted.length) - 1].toFixed(2));
}

function latencySummary(values) {
  return {
    count: values.length,
    minMs: values.length ? Number(Math.min(...values).toFixed(2)) : null,
    p50Ms: percentile(values, 0.50), p95Ms: percentile(values, 0.95), p99Ms: percentile(values, 0.99),
    maxMs: values.length ? Number(Math.max(...values).toFixed(2)) : null
  };
}

async function placeBid(config, actor, amount, requestId) {
  return api(config, '/api/bids', {
    method: 'POST', authorization: actor.authorization, requestId,
    body: { auctionId: config.auctionId, amount }, timeoutMs: config.bid.timeoutMs
  });
}

async function runBidBenchmark(config, actors, runId) {
  const before = await detail(config, actors[0].authorization);
  const firstCents = moneyToCents(String(before.minimumNextBid), 'auction.minimumNextBid');
  const results = new Array(config.bid.requests);
  let cursor = 0;
  const started = performance.now();
  await Promise.all(Array.from({ length: Math.min(config.bid.concurrency, config.bid.requests) }, async () => {
    while (true) {
      const index = cursor++;
      if (index >= config.bid.requests) return;
      const amount = centsToMoney(firstCents + BigInt(index) * config.bid.incrementCents);
      results[index] = await placeBid(
        config, actors[index % actors.length], amount, `bench-${runId}-${String(index).padStart(5, '0')}`
      );
    }
  }));
  const elapsedMs = performance.now() - started;
  const after = await detail(config, actors[0].authorization);
  const statusCounts = {};
  const codeCounts = {};
  let acceptedBidRecords = 0;
  for (const result of results) {
    statusCounts[result.status] = (statusCounts[result.status] ?? 0) + 1;
    codeCounts[result.code] = (codeCounts[result.code] ?? 0) + 1;
    if (result.status === 200 && result.code === 'SUCCESS') {
      acceptedBidRecords += Array.isArray(result.data?.acceptedBids) ? result.data.acceptedBids.length : 0;
    }
  }
  const bidCountDelta = Number(after.bidCount) - Number(before.bidCount);
  if (bidCountDelta !== acceptedBidRecords) {
    throw new Error(`Bid consistency check failed: bidCount delta ${bidCountDelta}, accepted records ${acceptedBidRecords}.`);
  }
  return {
    parameters: {
      requests: config.bid.requests, concurrency: config.bid.concurrency,
      actorCount: actors.length, increment: centsToMoney(config.bid.incrementCents)
    },
    elapsedMs: Number(elapsedMs.toFixed(2)),
    throughputRequestsPerSecond: Number((config.bid.requests * 1000 / elapsedMs).toFixed(2)),
    latency: latencySummary(results.map((value) => value.durationMs)),
    statusCounts, codeCounts, acceptedBidRecords, bidCountDelta,
    initialBidCount: Number(before.bidCount), finalBidCount: Number(after.bidCount)
  };
}

class SocketObserver {
  constructor(socket) {
    this.socket = socket;
    this.messages = [];
    this.waiters = [];
    socket.addEventListener('message', (event) => {
      let message;
      try { message = JSON.parse(String(event.data)); } catch { return; }
      this.messages.push({ message, receivedAt: performance.now() });
      for (const waiter of [...this.waiters]) {
        if (waiter.predicate(message)) {
          this.waiters.splice(this.waiters.indexOf(waiter), 1);
          clearTimeout(waiter.timer);
          waiter.resolve({ message, receivedAt: performance.now() });
        }
      }
    });
  }

  waitFor(predicate, timeoutMs, label = 'message') {
    const existing = this.messages.find((entry) => predicate(entry.message));
    if (existing) return Promise.resolve(existing);
    return new Promise((resolvePromise, rejectPromise) => {
      const waiter = { predicate, resolve: resolvePromise, timer: null };
      waiter.timer = setTimeout(() => {
        this.waiters = this.waiters.filter((value) => value !== waiter);
        rejectPromise(new Error(`WebSocket ${label} timeout after ${timeoutMs} ms.`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }
}

async function openSocket(config, actor, lastSequenceNo) {
  const ticketResponse = await api(config, '/api/realtime/tickets', {
    method: 'POST', authorization: actor.authorization, timeoutMs: config.realtime.timeoutMs
  });
  if (ticketResponse.status !== 200 || ticketResponse.code !== 'SUCCESS' || !ticketResponse.data?.ticket) {
    throw new Error(`Realtime ticket issue failed: HTTP ${ticketResponse.status}, code ${ticketResponse.code}.`);
  }
  const gateway = new URL(config.gatewayBaseUri);
  gateway.protocol = gateway.protocol === 'https:' ? 'wss:' : 'ws:';
  gateway.pathname = '/ws/auctions';
  gateway.search = new URLSearchParams({ ticket: ticketResponse.data.ticket }).toString();
  const openedAt = performance.now();
  const socket = new BenchmarkWebSocket(gateway, { origin: 'http://127.0.0.1:5173' });
  const observer = new SocketObserver(socket);
  await new Promise((resolvePromise, rejectPromise) => {
    const timer = setTimeout(() => rejectPromise(new Error('WebSocket open timeout.')), config.realtime.timeoutMs);
    socket.addEventListener('open', () => { clearTimeout(timer); resolvePromise(); }, { once: true });
    socket.addEventListener('error', () => { clearTimeout(timer); rejectPromise(new Error('WebSocket handshake failed.')); }, { once: true });
  });
  const connected = await observer.waitFor(
    (message) => message.type === 'CONNECTED', config.realtime.timeoutMs, 'CONNECTED'
  );
  const subscribeStarted = performance.now();
  socket.send(JSON.stringify({
    type: 'SUBSCRIBE', protocolVersion: 1,
    requestId: `bench-sub-${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`,
    payload: { auctionId: config.auctionId, lastSequenceNo }
  }));
  const snapshot = await observer.waitFor(
    (message) => message.type === 'SNAPSHOT', config.realtime.timeoutMs, 'SNAPSHOT'
  );
  return {
    socket, observer,
    connectMs: connected.receivedAt - openedAt,
    snapshotMs: snapshot.receivedAt - subscribeStarted,
    lastSequenceNo: Number(snapshot.message.payload.lastSequenceNo)
  };
}

async function runRealtimeBenchmark(config, actors, runId) {
  const selected = actors.slice(0, config.realtime.connections);
  const connections = [];
  try {
    for (const actor of selected) connections.push(await openSocket(config, actor, 0));
    const current = await detail(config, actors[0].authorization);
    const bidStarted = performance.now();
    const bid = await placeBid(
      config, actors[0], String(current.minimumNextBid), `bench-${runId}-realtime`
    );
    if (bid.status !== 200 || bid.code !== 'SUCCESS') {
      throw new Error(`Realtime trigger bid failed: HTTP ${bid.status}, code ${bid.code}.`);
    }
    const sequenceNo = Number(bid.data.lastSequenceNo);
    const eventPromises = connections.map((connection) => connection.observer.waitFor(
      (message) => message.type === 'BID_ACCEPTED' &&
        String(message.payload.auctionId) === config.auctionId &&
        Number(message.payload.sequenceNo) === sequenceNo,
      config.realtime.timeoutMs, `BID_ACCEPTED sequence ${sequenceNo}`
    ));
    const events = await Promise.all(eventPromises);
    connections[0].socket.close(1000, 'benchmark reconnect');
    const recoveryStarted = performance.now();
    const recovered = await openSocket(config, actors[0], 0);
    recovered.socket.close(1000, 'benchmark complete');
    return {
      parameters: { connections: connections.length, actorCount: actors.length },
      connectLatency: latencySummary(connections.map((value) => value.connectMs)),
      initialSnapshotLatency: latencySummary(connections.map((value) => value.snapshotMs)),
      broadcastLatency: latencySummary(events.map((entry) => entry.receivedAt - bidStarted)),
      recoverySnapshotMs: Number((performance.now() - recoveryStarted).toFixed(2)),
      recoveredLastSequenceNo: recovered.lastSequenceNo,
      triggeredSequenceNo: sequenceNo,
      recoveryCoveredTriggeredSequence: recovered.lastSequenceNo >= sequenceNo
    };
  } finally {
    for (const connection of connections) {
      if (connection.socket.readyState === BenchmarkWebSocket.OPEN) connection.socket.close(1000, 'benchmark cleanup');
    }
  }
}

function markdown(result) {
  const lines = [
    '# TideBid local performance baseline', '',
    `- Recorded at: ${result.recordedAt}`, `- Git commit: ${result.gitCommit}`,
    `- Host: ${result.environment.platform} ${result.environment.release}`,
    `- CPU: ${result.environment.cpuModel} (${result.environment.logicalCpuCount} logical cores)`,
    `- Memory: ${result.environment.totalMemoryGiB} GiB`, `- Node.js: ${result.environment.node}`,
    `- Mode: ${result.mode}`, `- Auction: ${result.auctionId} (dedicated benchmark fixture)`, '',
    '> This is a single-machine engineering baseline, not a production capacity commitment.', ''
  ];
  if (result.bid) {
    lines.push('## Concurrent bid baseline', '',
      `- Parameters: ${result.bid.parameters.requests} requests, concurrency ${result.bid.parameters.concurrency}, ${result.bid.parameters.actorCount} actors`,
      `- Throughput: ${result.bid.throughputRequestsPerSecond} requests/s`,
      `- Latency: p50 ${result.bid.latency.p50Ms} ms, p95 ${result.bid.latency.p95Ms} ms, p99 ${result.bid.latency.p99Ms} ms`,
      `- HTTP status distribution: \`${JSON.stringify(result.bid.statusCounts)}\``,
      `- API code distribution: \`${JSON.stringify(result.bid.codeCounts)}\``,
      `- Consistency: accepted bid records ${result.bid.acceptedBidRecords}, bidCount delta ${result.bid.bidCountDelta}`, '');
  }
  if (result.realtime) {
    lines.push('## Realtime and recovery baseline', '',
      `- Connections: ${result.realtime.parameters.connections}`,
      `- Connect p95: ${result.realtime.connectLatency.p95Ms} ms`,
      `- Initial Snapshot p95: ${result.realtime.initialSnapshotLatency.p95Ms} ms`,
      `- BID_ACCEPTED broadcast p95: ${result.realtime.broadcastLatency.p95Ms} ms`,
      `- Reconnect and Snapshot: ${result.realtime.recoverySnapshotMs} ms`,
      `- Snapshot covered trigger sequence: ${result.realtime.recoveryCoveredTriggeredSequence}`, '');
  }
  return `${lines.join('\n')}\n`;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const configPath = resolve(args.config);
  const config = validateConfig(JSON.parse(await readFile(configPath, 'utf8')));
  if (args.dryRun) {
    console.log(`[PASS] Benchmark configuration is structurally valid: ${configPath}`);
    console.log('[PASS] Dry run performed no login, bid, WebSocket, Docker, or database operation.');
    return;
  }
  const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  const outputDirectory = resolve(args.output || `.runtime/benchmarks/${runId}`);
  const actors = await loginActors(config);
  await assertDedicatedFixture(config, actors);
  const result = {
    schemaVersion: 1, recordedAt: new Date().toISOString(), mode: args.mode,
    auctionId: config.auctionId,
    gitCommit: (() => { try { return execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], { encoding: 'utf8' }).trim(); } catch { return 'unavailable'; } })(),
    environment: {
      platform: platform(), release: release(), node: process.version,
      cpuModel: cpus()[0]?.model ?? 'unknown', logicalCpuCount: cpus().length,
      totalMemoryGiB: Number((totalmem() / 1024 ** 3).toFixed(2))
    }
  };
  if (args.mode === 'bid' || args.mode === 'all') result.bid = await runBidBenchmark(config, actors, runId);
  if (args.mode === 'realtime' || args.mode === 'all') result.realtime = await runRealtimeBenchmark(config, actors, runId);
  await mkdir(outputDirectory, { recursive: true });
  await Promise.all([
    writeFile(resolve(outputDirectory, 'result.json'), `${JSON.stringify(result, null, 2)}\n`, 'utf8'),
    writeFile(resolve(outputDirectory, 'report.md'), markdown(result), 'utf8')
  ]);
  console.log(`[PASS] Benchmark completed. Results: ${outputDirectory}`);
  if (result.bid) console.log(`[PASS] Bid consistency: accepted=${result.bid.acceptedBidRecords}, delta=${result.bid.bidCountDelta}.`);
  if (result.realtime) console.log(`[PASS] Realtime recovery covered sequence: ${result.realtime.recoveryCoveredTriggeredSequence}.`);
}

main().catch((error) => {
  console.error(`[FAIL] ${error.message}`);
  process.exitCode = 1;
});
