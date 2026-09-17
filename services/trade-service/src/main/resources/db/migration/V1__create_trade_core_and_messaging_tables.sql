CREATE TABLE `trade_order`
(
    `id`                         BIGINT        NOT NULL COMMENT 'Application-generated order ID',
    `order_no`                   VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable public order number',
    `auction_id`                 BIGINT        NOT NULL COMMENT 'Unique source Auction session ID',
    `item_id`                    BIGINT        NOT NULL COMMENT 'Source item ID snapshot',
    `winning_bid_id`             BIGINT        NOT NULL COMMENT 'Winning bid ID snapshot',
    `seller_id`                  BIGINT        NOT NULL COMMENT 'Seller user ID snapshot',
    `buyer_id`                   BIGINT        NOT NULL COMMENT 'Winning buyer user ID snapshot',
    `item_title`                 VARCHAR(80)   NOT NULL COMMENT 'Immutable item title snapshot',
    `winner_hold_no`             VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Winner deposit hold business number',
    `final_price`                DECIMAL(19,2) NOT NULL COMMENT 'Immutable auction final price',
    `captured_deposit_amount`    DECIMAL(19,2) NULL COMMENT 'Deposit applied to this order after settlement',
    `payable_amount`             DECIMAL(19,2) NULL COMMENT 'Remaining buyer payment amount',
    `status`                     VARCHAR(24)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order payment state',
    `payment_deadline`           DATETIME(6)   NULL COMMENT 'UTC remaining-payment deadline',
    `paid_at`                    DATETIME(6)   NULL COMMENT 'UTC paid terminal time',
    `timed_out_at`               DATETIME(6)   NULL COMMENT 'UTC payment-timeout terminal time',
    `seller_settlement_status`   VARCHAR(16)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED, PENDING or COMPLETED',
    `seller_credit_no`           VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Stable seller credit business number',
    `seller_receivable_amount`   DECIMAL(19,2) NULL COMMENT 'Amount eventually credited to seller',
    `seller_credited_at`         DATETIME(6)   NULL COMMENT 'UTC seller credit completion time',
    `version`                    BIGINT        NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `auction_closed_at`          DATETIME(6)   NOT NULL COMMENT 'Immutable source auction close time',
    `created_at`                 DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`                 DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_trade_order_order_no` UNIQUE (`order_no`),
    CONSTRAINT `uk_trade_order_auction_id` UNIQUE (`auction_id`),
    CONSTRAINT `uk_trade_order_seller_credit_no` UNIQUE (`seller_credit_no`),
    KEY `idx_trade_order_buyer_created` (`buyer_id`, `created_at`, `id`),
    KEY `idx_trade_order_seller_created` (`seller_id`, `created_at`, `id`),
    KEY `idx_trade_order_payment_deadline` (`status`, `payment_deadline`, `id`),
    KEY `idx_trade_order_seller_settlement` (`seller_settlement_status`, `updated_at`, `id`),
    CONSTRAINT `chk_trade_order_ids`
        CHECK (`id` > 0 AND `auction_id` > 0 AND `item_id` > 0 AND `winning_bid_id` > 0
            AND `seller_id` > 0 AND `buyer_id` > 0 AND `seller_id` <> `buyer_id`),
    CONSTRAINT `chk_trade_order_business_numbers`
        CHECK (REGEXP_LIKE(`order_no`, '^[A-Za-z0-9][A-Za-z0-9:_-]{0,63}$', 'c')
            AND REGEXP_LIKE(`winner_hold_no`, '^[A-Za-z0-9][A-Za-z0-9:_-]{0,63}$', 'c')
            AND (`seller_credit_no` IS NULL OR REGEXP_LIKE(`seller_credit_no`, '^[A-Za-z0-9][A-Za-z0-9:_-]{0,63}$', 'c'))),
    CONSTRAINT `chk_trade_order_title` CHECK (CHAR_LENGTH(TRIM(`item_title`)) BETWEEN 1 AND 80),
    CONSTRAINT `chk_trade_order_amounts`
        CHECK (`final_price` > 0.00
            AND (`captured_deposit_amount` IS NULL OR `captured_deposit_amount` >= 0.00)
            AND (`payable_amount` IS NULL OR `payable_amount` >= 0.00)
            AND (`seller_receivable_amount` IS NULL OR `seller_receivable_amount` > 0.00)
            AND ((`captured_deposit_amount` IS NULL AND `payable_amount` IS NULL)
                OR (`captured_deposit_amount` IS NOT NULL AND `payable_amount` IS NOT NULL
                    AND `captured_deposit_amount` + `payable_amount` = `final_price`))),
    CONSTRAINT `chk_trade_order_status`
        CHECK (`status` IN ('PENDING_DEPOSIT', 'PENDING_PAYMENT', 'PAYMENT_PROCESSING', 'PAID', 'PAYMENT_TIMEOUT')),
    CONSTRAINT `chk_trade_order_settlement_status`
        CHECK (`seller_settlement_status` IN ('NOT_REQUIRED', 'PENDING', 'COMPLETED')),
    CONSTRAINT `chk_trade_order_state_shape`
        CHECK ((`status` = 'PENDING_DEPOSIT'
                    AND `captured_deposit_amount` IS NULL AND `payable_amount` IS NULL
                    AND `payment_deadline` IS NULL AND `paid_at` IS NULL AND `timed_out_at` IS NULL
                    AND `seller_settlement_status` = 'NOT_REQUIRED' AND `seller_credit_no` IS NULL
                    AND `seller_receivable_amount` IS NULL AND `seller_credited_at` IS NULL)
            OR (`status` IN ('PENDING_PAYMENT', 'PAYMENT_PROCESSING')
                    AND `captured_deposit_amount` IS NOT NULL AND `payable_amount` > 0.00
                    AND `payment_deadline` IS NOT NULL AND `paid_at` IS NULL AND `timed_out_at` IS NULL
                    AND `seller_settlement_status` = 'NOT_REQUIRED' AND `seller_credit_no` IS NULL
                    AND `seller_receivable_amount` IS NULL AND `seller_credited_at` IS NULL)
            OR (`status` = 'PAID'
                    AND `captured_deposit_amount` IS NOT NULL AND `payable_amount` IS NOT NULL
                    AND ((`payable_amount` = 0.00 AND `payment_deadline` IS NULL)
                        OR (`payable_amount` > 0.00 AND `payment_deadline` IS NOT NULL))
                    AND `paid_at` IS NOT NULL AND `timed_out_at` IS NULL
                    AND `seller_settlement_status` IN ('PENDING', 'COMPLETED')
                    AND `seller_credit_no` IS NOT NULL AND `seller_receivable_amount` = `final_price`)
            OR (`status` = 'PAYMENT_TIMEOUT'
                    AND `captured_deposit_amount` IS NOT NULL AND `payable_amount` > 0.00
                    AND `payment_deadline` IS NOT NULL AND `paid_at` IS NULL AND `timed_out_at` IS NOT NULL
                    AND `seller_settlement_status` IN ('PENDING', 'COMPLETED')
                    AND `seller_credit_no` IS NOT NULL
                    AND `seller_receivable_amount` = `captured_deposit_amount`)),
    CONSTRAINT `chk_trade_order_settlement_shape`
        CHECK ((`seller_settlement_status` <> 'COMPLETED' AND `seller_credited_at` IS NULL)
            OR (`seller_settlement_status` = 'COMPLETED' AND `seller_credited_at` IS NOT NULL)),
    CONSTRAINT `chk_trade_order_timeline`
        CHECK (`auction_closed_at` <= `created_at` AND `created_at` <= `updated_at`
            AND (`payment_deadline` IS NULL OR `payment_deadline` > `created_at`)
            AND (`paid_at` IS NULL OR (`paid_at` >= `created_at` AND `paid_at` <= `updated_at`))
            AND (`timed_out_at` IS NULL OR (`timed_out_at` >= `payment_deadline` AND `timed_out_at` <= `updated_at`))
            AND (`seller_credited_at` IS NULL OR `seller_credited_at` <= `updated_at`)),
    CONSTRAINT `chk_trade_order_version` CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Trade order aggregate and immutable auction snapshot';

CREATE TABLE `payment_attempt`
(
    `id`                  BIGINT        NOT NULL COMMENT 'Application-generated payment attempt ID',
    `payment_no`          VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable Account debit number',
    `order_id`            BIGINT        NOT NULL COMMENT 'Trade order ID',
    `buyer_id`            BIGINT        NOT NULL COMMENT 'Authenticated buyer ID',
    `request_id`          VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Client idempotency request ID',
    `amount`              DECIMAL(19,2) NOT NULL COMMENT 'Server-calculated payable amount',
    `status`              VARCHAR(16)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PROCESSING, UNKNOWN, SUCCEEDED or REJECTED',
    `failure_code`        VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Stable safe rejection code',
    `recovery_count`      INT           NOT NULL DEFAULT 0 COMMENT 'Completed unknown-result recovery attempts',
    `next_recovery_at`    DATETIME(6)   NULL COMMENT 'UTC earliest next recovery time',
    `lease_owner`         VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Current recovery worker instance',
    `lease_token`         CHAR(36)      CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Unique token for current recovery claim',
    `lease_until`         DATETIME(6)   NULL COMMENT 'UTC recovery claim expiration',
    `completed_at`        DATETIME(6)   NULL COMMENT 'UTC definite result time',
    `created_at`          DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`          DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_payment_attempt_payment_no` UNIQUE (`payment_no`),
    CONSTRAINT `uk_payment_attempt_buyer_request` UNIQUE (`buyer_id`, `request_id`),
    KEY `idx_payment_attempt_order_created` (`order_id`, `created_at`, `id`),
    KEY `idx_payment_attempt_recovery_scan` (`status`, `next_recovery_at`, `id`),
    KEY `idx_payment_attempt_lease_scan` (`status`, `lease_until`, `id`),
    CONSTRAINT `fk_payment_attempt_order`
        FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_payment_attempt_ids` CHECK (`id` > 0 AND `order_id` > 0 AND `buyer_id` > 0),
    CONSTRAINT `chk_payment_attempt_business_numbers`
        CHECK (REGEXP_LIKE(`payment_no`, '^[A-Za-z0-9][A-Za-z0-9:_-]{0,63}$', 'c')
            AND REGEXP_LIKE(`request_id`, '^[A-Za-z0-9][A-Za-z0-9:_-]{7,63}$', 'c')),
    CONSTRAINT `chk_payment_attempt_amount` CHECK (`amount` > 0.00),
    CONSTRAINT `chk_payment_attempt_status` CHECK (`status` IN ('PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'REJECTED')),
    CONSTRAINT `chk_payment_attempt_recovery_count` CHECK (`recovery_count` >= 0),
    CONSTRAINT `chk_payment_attempt_lease_tuple`
        CHECK ((`lease_owner` IS NULL AND `lease_token` IS NULL AND `lease_until` IS NULL)
            OR (`lease_owner` IS NOT NULL AND `lease_token` IS NOT NULL AND `lease_until` IS NOT NULL)),
    CONSTRAINT `chk_payment_attempt_lease_token`
        CHECK (`lease_token` IS NULL OR REGEXP_LIKE(`lease_token`, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT `chk_payment_attempt_state_shape`
        CHECK ((`status` = 'PROCESSING' AND `failure_code` IS NULL AND `completed_at` IS NULL)
            OR (`status` = 'UNKNOWN' AND `failure_code` IS NULL AND `completed_at` IS NULL AND `next_recovery_at` IS NOT NULL)
            OR (`status` = 'SUCCEEDED' AND `failure_code` IS NULL AND `completed_at` IS NOT NULL
                    AND `next_recovery_at` IS NULL AND `lease_owner` IS NULL)
            OR (`status` = 'REJECTED' AND `failure_code` IS NOT NULL AND `completed_at` IS NOT NULL
                    AND `next_recovery_at` IS NULL AND `lease_owner` IS NULL)),
    CONSTRAINT `chk_payment_attempt_failure_code`
        CHECK (`failure_code` IS NULL OR REGEXP_LIKE(`failure_code`, '^[A-Z][A-Z0-9_]{0,63}$', 'c')),
    CONSTRAINT `chk_payment_attempt_timeline`
        CHECK (`created_at` <= `updated_at`
            AND (`next_recovery_at` IS NULL OR `next_recovery_at` >= `created_at`)
            AND (`completed_at` IS NULL OR (`completed_at` >= `created_at` AND `completed_at` <= `updated_at`)))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Idempotent buyer payment attempts and unknown-result recovery';

CREATE TABLE `trade_outbox`
(
    `id` BIGINT NOT NULL,
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `aggregate_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `aggregate_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `schema_version` INT NOT NULL,
    `topic` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `tag` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `message_key` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `payload` JSON NOT NULL,
    `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `deliver_at` DATETIME(6) NOT NULL,
    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    `attempt_count` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME(6) NOT NULL,
    `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `lease_token` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `lease_until` DATETIME(6) NULL,
    `published_at` DATETIME(6) NULL,
    `last_error_code` VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_trade_outbox_event_id` UNIQUE (`event_id`),
    KEY `idx_trade_outbox_pending_scan` (`status`, `next_attempt_at`, `deliver_at`, `id`),
    KEY `idx_trade_outbox_lease_scan` (`status`, `lease_until`, `deliver_at`, `id`),
    KEY `idx_trade_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `created_at`, `id`),
    CONSTRAINT `chk_trade_outbox_id` CHECK (`id` > 0),
    CONSTRAINT `chk_trade_outbox_event_id`
        CHECK (REGEXP_LIKE(`event_id`, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')
            AND `event_id` <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT `chk_trade_outbox_text`
        CHECK (CHAR_LENGTH(`aggregate_type`) BETWEEN 1 AND 64 AND CHAR_LENGTH(`aggregate_id`) BETWEEN 1 AND 64
            AND CHAR_LENGTH(`event_type`) BETWEEN 1 AND 96 AND CHAR_LENGTH(`topic`) BETWEEN 1 AND 128
            AND CHAR_LENGTH(`tag`) BETWEEN 1 AND 64 AND `tag` = `event_type` AND `message_key` = `event_id`),
    CONSTRAINT `chk_trade_outbox_schema_version` CHECK (`schema_version` > 0),
    CONSTRAINT `chk_trade_outbox_payload_hash` CHECK (REGEXP_LIKE(`payload_hash`, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT `chk_trade_outbox_status` CHECK (`status` IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT `chk_trade_outbox_attempt_count` CHECK (`attempt_count` >= 0),
    CONSTRAINT `chk_trade_outbox_lease_tuple`
        CHECK ((`lease_owner` IS NULL AND `lease_token` IS NULL AND `lease_until` IS NULL)
            OR (`lease_owner` IS NOT NULL AND `lease_token` IS NOT NULL AND `lease_until` IS NOT NULL)),
    CONSTRAINT `chk_trade_outbox_lease_token`
        CHECK (`lease_token` IS NULL OR REGEXP_LIKE(`lease_token`, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT `chk_trade_outbox_state_shape`
        CHECK ((`status` = 'PENDING' AND `published_at` IS NULL AND `lease_owner` IS NULL)
            OR (`status` = 'PUBLISHING' AND `published_at` IS NULL AND `lease_owner` IS NOT NULL)
            OR (`status` = 'PUBLISHED' AND `published_at` IS NOT NULL AND `lease_owner` IS NULL)
            OR (`status` = 'DEAD' AND `published_at` IS NULL AND `lease_owner` IS NULL)),
    CONSTRAINT `chk_trade_outbox_last_error`
        CHECK (`last_error_code` IS NULL OR CHAR_LENGTH(`last_error_code`) BETWEEN 1 AND 96)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Trade service transactional outbox';

CREATE TABLE `trade_inbox`
(
    `id` BIGINT NOT NULL,
    `consumer_name` VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `schema_version` INT NOT NULL,
    `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `processed_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_trade_inbox_consumer_event` UNIQUE (`consumer_name`, `event_id`),
    KEY `idx_trade_inbox_processed` (`processed_at`, `id`),
    CONSTRAINT `chk_trade_inbox_id` CHECK (`id` > 0),
    CONSTRAINT `chk_trade_inbox_consumer_name` CHECK (CHAR_LENGTH(`consumer_name`) BETWEEN 1 AND 96),
    CONSTRAINT `chk_trade_inbox_event_id`
        CHECK (REGEXP_LIKE(`event_id`, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')
            AND `event_id` <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT `chk_trade_inbox_event_type` CHECK (CHAR_LENGTH(`event_type`) BETWEEN 1 AND 96),
    CONSTRAINT `chk_trade_inbox_schema_version` CHECK (`schema_version` > 0),
    CONSTRAINT `chk_trade_inbox_payload_hash` CHECK (REGEXP_LIKE(`payload_hash`, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Trade service idempotent consumer inbox';
