-- Phase 03 introduced Account and Trade consumers after some upgraded phase 02 auctions had
-- already published their terminal outcomes. Requeue those durable facts once so new consumer
-- groups can establish their Inbox state; existing consumers absorb the replay by event_id.
UPDATE `auction_outbox`
SET `status` = 'PENDING',
    `next_attempt_at` = UTC_TIMESTAMP(6),
    `lease_owner` = NULL,
    `lease_token` = NULL,
    `lease_until` = NULL,
    `published_at` = NULL,
    `last_error_code` = NULL,
    `updated_at` = UTC_TIMESTAMP(6)
WHERE `status` = 'PUBLISHED'
  AND `event_type` IN (
      'auction.closed-sold',
      'auction.closed-unsold',
      'deposit.settlement-requested'
  );
