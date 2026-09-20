ALTER TABLE `auction_session`
    ADD COLUMN `original_end_at` DATETIME(6) NULL COMMENT 'Immutable UTC end time before anti-sniping extensions' AFTER `end_at`,
    ADD COLUMN `extension_count` INT NOT NULL DEFAULT 0 COMMENT 'Successful anti-sniping extensions' AFTER `original_end_at`;

UPDATE `auction_session`
SET `original_end_at` = `end_at`
WHERE `original_end_at` IS NULL;

ALTER TABLE `auction_session`
    MODIFY COLUMN `original_end_at` DATETIME(6) NOT NULL COMMENT 'Immutable UTC end time before anti-sniping extensions',
    ADD CONSTRAINT `chk_auction_session_original_time_range`
        CHECK (`original_end_at` > `start_at`),
    ADD CONSTRAINT `chk_auction_session_extended_end`
        CHECK (`end_at` >= `original_end_at`),
    ADD CONSTRAINT `chk_auction_session_extension_count`
        CHECK (`extension_count` >= 0);

CREATE TABLE `auction_proxy_bid`
(
    `id`          BIGINT        NOT NULL COMMENT 'Application-generated proxy rule ID',
    `auction_id`  BIGINT        NOT NULL COMMENT 'Auction session ID',
    `bidder_id`   BIGINT        NOT NULL COMMENT 'Owner account ID',
    `max_amount`  DECIMAL(19,2) NOT NULL COMMENT 'Private maximum amount; never exposed publicly',
    `status`      VARCHAR(16)   NOT NULL COMMENT 'ACTIVE or DISABLED',
    `priority`    BIGINT        NOT NULL COMMENT 'Monotonic tie-breaking priority; lower wins',
    `version`     BIGINT        NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `disabled_at` DATETIME(6)   NULL COMMENT 'UTC explicit disable time',
    `created_at`  DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`  DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_proxy_bid_auction_bidder` UNIQUE (`auction_id`, `bidder_id`),
    KEY `idx_auction_proxy_bid_competition` (`auction_id`, `status`, `max_amount` DESC, `priority`, `id`),
    CONSTRAINT `fk_auction_proxy_bid_session`
        FOREIGN KEY (`auction_id`) REFERENCES `auction_session` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_auction_proxy_bid_bidder_id`
        CHECK (`bidder_id` > 0),
    CONSTRAINT `chk_auction_proxy_bid_max_amount`
        CHECK (`max_amount` > 0.00),
    CONSTRAINT `chk_auction_proxy_bid_status`
        CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `chk_auction_proxy_bid_priority`
        CHECK (`priority` > 0),
    CONSTRAINT `chk_auction_proxy_bid_version`
        CHECK (`version` >= 0),
    CONSTRAINT `chk_auction_proxy_bid_disabled_state`
        CHECK ((`status` = 'ACTIVE' AND `disabled_at` IS NULL)
            OR (`status` = 'DISABLED' AND `disabled_at` IS NOT NULL)),
    CONSTRAINT `chk_auction_proxy_bid_timestamps`
        CHECK (`updated_at` >= `created_at` AND (`disabled_at` IS NULL OR `disabled_at` >= `created_at`))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Private per-user proxy bid rule';

CREATE TABLE `auction_bid_command`
(
    `id`                BIGINT        NOT NULL COMMENT 'Application-generated command ID',
    `auction_id`        BIGINT        NOT NULL COMMENT 'Auction session ID',
    `actor_id`          BIGINT        NOT NULL COMMENT 'Authenticated account ID',
    `request_id`        VARCHAR(48)   NOT NULL COMMENT 'Caller idempotency key',
    `command_type`      VARCHAR(24)   NOT NULL COMMENT 'MANUAL_BID, UPSERT_PROXY or DISABLE_PROXY',
    `payload_hash`      CHAR(64)      NOT NULL COMMENT 'Lowercase SHA-256 of normalized command payload',
    `status`            VARCHAR(16)   NOT NULL COMMENT 'PROCESSING or SUCCEEDED',
    `result_bid_count`  INT           NULL COMMENT 'Number of public bids emitted by a completed command',
    `result_price`      DECIMAL(19,2) NULL COMMENT 'Final public price returned by a completed command',
    `result_leading`    BOOLEAN       NULL COMMENT 'Whether actor leads after a completed command',
    `first_sequence_no` BIGINT        NULL COMMENT 'First public sequence emitted by this command',
    `last_sequence_no`  BIGINT        NULL COMMENT 'Last public sequence emitted by this command',
    `created_at`        DATETIME(6)   NOT NULL COMMENT 'UTC command creation time',
    `completed_at`      DATETIME(6)   NULL COMMENT 'UTC successful completion time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_bid_command_actor_request` UNIQUE (`actor_id`, `request_id`),
    KEY `idx_auction_bid_command_auction_created` (`auction_id`, `created_at`, `id`),
    CONSTRAINT `fk_auction_bid_command_session`
        FOREIGN KEY (`auction_id`) REFERENCES `auction_session` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_auction_bid_command_actor_id`
        CHECK (`actor_id` > 0),
    CONSTRAINT `chk_auction_bid_command_type`
        CHECK (`command_type` IN ('MANUAL_BID', 'UPSERT_PROXY', 'DISABLE_PROXY')),
    CONSTRAINT `chk_auction_bid_command_payload_hash`
        CHECK (REGEXP_LIKE(`payload_hash`, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT `chk_auction_bid_command_status`
        CHECK (`status` IN ('PROCESSING', 'SUCCEEDED')),
    CONSTRAINT `chk_auction_bid_command_result`
        CHECK ((`status` = 'PROCESSING'
                    AND `result_bid_count` IS NULL
                    AND `result_price` IS NULL
                    AND `result_leading` IS NULL
                    AND `first_sequence_no` IS NULL
                    AND `last_sequence_no` IS NULL
                    AND `completed_at` IS NULL)
            OR (`status` = 'SUCCEEDED'
                    AND `result_bid_count` BETWEEN 0 AND 2
                    AND `result_price` > 0.00
                    AND `result_leading` IS NOT NULL
                    AND ((`result_bid_count` = 0
                            AND `first_sequence_no` IS NULL
                            AND `last_sequence_no` IS NULL)
                        OR (`result_bid_count` > 0
                            AND `first_sequence_no` > 0
                            AND `last_sequence_no` = `first_sequence_no` + `result_bid_count` - 1))
                    AND `completed_at` >= `created_at`))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Idempotent manual and proxy bidding command';

ALTER TABLE `bid_record`
    DROP INDEX `uk_bid_record_bidder_request`,
    ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL or PROXY' AFTER `request_id`,
    ADD COLUMN `command_id` BIGINT NULL COMMENT 'Triggering auction_bid_command; null only for historical rows' AFTER `source`,
    ADD KEY `idx_bid_record_bidder_request` (`bidder_id`, `request_id`),
    ADD KEY `idx_bid_record_command_sequence` (`command_id`, `sequence_no`),
    ADD CONSTRAINT `fk_bid_record_command`
        FOREIGN KEY (`command_id`) REFERENCES `auction_bid_command` (`id`) ON DELETE RESTRICT,
    ADD CONSTRAINT `chk_bid_record_source`
        CHECK (`source` IN ('MANUAL', 'PROXY'));
