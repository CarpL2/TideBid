CREATE TABLE `auction_outbox`
(
    `id`              BIGINT       NOT NULL COMMENT 'Application-generated local row ID',
    `event_id`        CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Globally unique event UUID',
    `aggregate_type`  VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable aggregate type',
    `aggregate_id`    VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable aggregate business ID',
    `event_type`      VARCHAR(96)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable versioned contract type',
    `schema_version`  INT          NOT NULL COMMENT 'Payload schema version',
    `topic`           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RocketMQ destination topic',
    `tag`             VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RocketMQ message tag',
    `message_key`     VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RocketMQ message key',
    `payload`         JSON         NOT NULL COMMENT 'Complete versioned event envelope',
    `payload_hash`    CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Lowercase SHA-256 of encoded payload',
    `deliver_at`      DATETIME(6)  NOT NULL COMMENT 'UTC requested delivery time',
    `status`          VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PUBLISHING, PUBLISHED or DEAD',
    `attempt_count`   INT          NOT NULL DEFAULT 0 COMMENT 'Completed publish attempts',
    `next_attempt_at` DATETIME(6)  NOT NULL COMMENT 'UTC earliest next claim time',
    `lease_owner`     VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Current publisher instance',
    `lease_token`     CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Unique token for the current claim',
    `lease_until`     DATETIME(6)  NULL COMMENT 'UTC current claim expiration',
    `published_at`    DATETIME(6)  NULL COMMENT 'UTC successful Broker acknowledgement time',
    `last_error_code` VARCHAR(96)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Stable safe publish failure code',
    `created_at`      DATETIME(6)  NOT NULL COMMENT 'UTC creation time',
    `updated_at`      DATETIME(6)  NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_outbox_event_id` UNIQUE (`event_id`),
    KEY `idx_auction_outbox_pending_scan` (`status`, `next_attempt_at`, `deliver_at`, `id`),
    KEY `idx_auction_outbox_lease_scan` (`status`, `lease_until`, `deliver_at`, `id`),
    KEY `idx_auction_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `created_at`, `id`),
    CONSTRAINT `chk_auction_outbox_id`
        CHECK (`id` > 0),
    CONSTRAINT `chk_auction_outbox_event_id`
        CHECK (REGEXP_LIKE(`event_id`,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')
            AND `event_id` <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT `chk_auction_outbox_aggregate`
        CHECK (CHAR_LENGTH(`aggregate_type`) BETWEEN 1 AND 64
            AND CHAR_LENGTH(`aggregate_id`) BETWEEN 1 AND 64),
    CONSTRAINT `chk_auction_outbox_event_type`
        CHECK (CHAR_LENGTH(`event_type`) BETWEEN 1 AND 96),
    CONSTRAINT `chk_auction_outbox_schema_version`
        CHECK (`schema_version` > 0),
    CONSTRAINT `chk_auction_outbox_destination`
        CHECK (CHAR_LENGTH(`topic`) BETWEEN 1 AND 128
            AND CHAR_LENGTH(`tag`) BETWEEN 1 AND 64
            AND `tag` = `event_type`
            AND `message_key` = `event_id`),
    CONSTRAINT `chk_auction_outbox_payload_hash`
        CHECK (REGEXP_LIKE(`payload_hash`, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT `chk_auction_outbox_status`
        CHECK (`status` IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT `chk_auction_outbox_attempt_count`
        CHECK (`attempt_count` >= 0),
    CONSTRAINT `chk_auction_outbox_lease_tuple`
        CHECK ((`lease_owner` IS NULL AND `lease_token` IS NULL AND `lease_until` IS NULL)
            OR (`lease_owner` IS NOT NULL AND `lease_token` IS NOT NULL AND `lease_until` IS NOT NULL)),
    CONSTRAINT `chk_auction_outbox_lease_token`
        CHECK (`lease_token` IS NULL OR REGEXP_LIKE(`lease_token`,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')
            AND `lease_token` <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT `chk_auction_outbox_state_shape`
        CHECK ((`status` = 'PENDING' AND `published_at` IS NULL
                    AND `lease_owner` IS NULL AND `lease_token` IS NULL AND `lease_until` IS NULL)
            OR (`status` = 'PUBLISHING' AND `published_at` IS NULL
                    AND `lease_owner` IS NOT NULL AND `lease_token` IS NOT NULL AND `lease_until` IS NOT NULL)
            OR (`status` = 'PUBLISHED' AND `published_at` IS NOT NULL
                    AND `lease_owner` IS NULL AND `lease_token` IS NULL AND `lease_until` IS NULL)
            OR (`status` = 'DEAD' AND `published_at` IS NULL
                    AND `lease_owner` IS NULL AND `lease_token` IS NULL AND `lease_until` IS NULL)),
    CONSTRAINT `chk_auction_outbox_last_error`
        CHECK (`last_error_code` IS NULL OR CHAR_LENGTH(`last_error_code`) BETWEEN 1 AND 96)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Auction service transactional outbox';

CREATE TABLE `auction_inbox`
(
    `id`            BIGINT      NOT NULL COMMENT 'Application-generated local row ID',
    `consumer_name` VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable RocketMQ consumer group',
    `event_id`      CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Consumed global event UUID',
    `event_type`    VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Consumed stable contract type',
    `schema_version` INT        NOT NULL COMMENT 'Consumed payload schema version',
    `payload_hash`  CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Lowercase SHA-256 used for replay conflict detection',
    `processed_at`  DATETIME(6) NOT NULL COMMENT 'UTC local transaction completion time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_inbox_consumer_event` UNIQUE (`consumer_name`, `event_id`),
    KEY `idx_auction_inbox_processed` (`processed_at`, `id`),
    CONSTRAINT `chk_auction_inbox_id`
        CHECK (`id` > 0),
    CONSTRAINT `chk_auction_inbox_consumer_name`
        CHECK (CHAR_LENGTH(`consumer_name`) BETWEEN 1 AND 96),
    CONSTRAINT `chk_auction_inbox_event_id`
        CHECK (REGEXP_LIKE(`event_id`,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')
            AND `event_id` <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT `chk_auction_inbox_event_type`
        CHECK (CHAR_LENGTH(`event_type`) BETWEEN 1 AND 96),
    CONSTRAINT `chk_auction_inbox_schema_version`
        CHECK (`schema_version` > 0),
    CONSTRAINT `chk_auction_inbox_payload_hash`
        CHECK (REGEXP_LIKE(`payload_hash`, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Auction service idempotent consumer inbox';
