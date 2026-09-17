-- TideBid read-only Outbox diagnostics.
-- Run this file with a MySQL account that can read the three service schemas.
-- It never changes business data and does not expose an HTTP management endpoint.

SELECT 'auction' AS service,
       SUM(status IN ('PENDING', 'PUBLISHING')) AS backlog,
       SUM(status = 'DEAD') AS dead_messages,
       MIN(CASE WHEN status IN ('PENDING', 'PUBLISHING') THEN deliver_at END) AS oldest_unpublished_at,
       COALESCE(MAX(CASE
           WHEN status IN ('PENDING', 'PUBLISHING') AND deliver_at <= UTC_TIMESTAMP(6)
           THEN TIMESTAMPDIFF(SECOND, deliver_at, UTC_TIMESTAMP(6))
           ELSE 0 END), 0) AS oldest_due_lag_seconds
FROM tidebid_auction.auction_outbox
UNION ALL
SELECT 'account',
       SUM(status IN ('PENDING', 'PUBLISHING')),
       SUM(status = 'DEAD'),
       MIN(CASE WHEN status IN ('PENDING', 'PUBLISHING') THEN deliver_at END),
       COALESCE(MAX(CASE
           WHEN status IN ('PENDING', 'PUBLISHING') AND deliver_at <= UTC_TIMESTAMP(6)
           THEN TIMESTAMPDIFF(SECOND, deliver_at, UTC_TIMESTAMP(6))
           ELSE 0 END), 0)
FROM tidebid_account.account_outbox
UNION ALL
SELECT 'trade',
       SUM(status IN ('PENDING', 'PUBLISHING')),
       SUM(status = 'DEAD'),
       MIN(CASE WHEN status IN ('PENDING', 'PUBLISHING') THEN deliver_at END),
       COALESCE(MAX(CASE
           WHEN status IN ('PENDING', 'PUBLISHING') AND deliver_at <= UTC_TIMESTAMP(6)
           THEN TIMESTAMPDIFF(SECOND, deliver_at, UTC_TIMESTAMP(6))
           ELSE 0 END), 0)
FROM tidebid_trade.trade_outbox;

SELECT 'auction' AS service, status, event_type, COUNT(*) AS messages, MIN(created_at) AS oldest_created_at
FROM tidebid_auction.auction_outbox
WHERE status <> 'PUBLISHED'
GROUP BY status, event_type
UNION ALL
SELECT 'account', status, event_type, COUNT(*), MIN(created_at)
FROM tidebid_account.account_outbox
WHERE status <> 'PUBLISHED'
GROUP BY status, event_type
UNION ALL
SELECT 'trade', status, event_type, COUNT(*), MIN(created_at)
FROM tidebid_trade.trade_outbox
WHERE status <> 'PUBLISHED'
GROUP BY status, event_type
ORDER BY service, status, event_type;
