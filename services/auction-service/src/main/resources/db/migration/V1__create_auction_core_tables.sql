CREATE TABLE `auction_item`
(
    `id`                 BIGINT        NOT NULL COMMENT 'Application-generated item ID',
    `seller_id`          BIGINT        NOT NULL COMMENT 'Seller account ID from Account service',
    `title`              VARCHAR(80)   NOT NULL COMMENT 'Auction item title',
    `description`        VARCHAR(2000) NOT NULL COMMENT 'Auction item description',
    `category`           VARCHAR(32)   NOT NULL COMMENT 'Controlled category code',
    `item_condition`     VARCHAR(24)   NOT NULL COMMENT 'NEW, LIKE_NEW, GOOD or FAIR',
    `review_status`      VARCHAR(24)   NOT NULL COMMENT 'DRAFT, PENDING_REVIEW, APPROVED or REJECTED',
    `submission_version` INT           NOT NULL DEFAULT 0 COMMENT 'Submitted snapshot version',
    `version`            BIGINT        NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `submitted_at`       DATETIME(6)   NULL COMMENT 'UTC latest submission time',
    `approved_at`        DATETIME(6)   NULL COMMENT 'UTC approval time',
    `created_at`         DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`         DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    KEY `idx_auction_item_seller_created` (`seller_id`, `created_at` DESC, `id` DESC),
    KEY `idx_auction_item_review_submitted` (`review_status`, `submitted_at`, `id`),
    CONSTRAINT `chk_auction_item_seller_id`
        CHECK (`seller_id` > 0),
    CONSTRAINT `chk_auction_item_title`
        CHECK (CHAR_LENGTH(`title`) BETWEEN 2 AND 80),
    CONSTRAINT `chk_auction_item_description`
        CHECK (CHAR_LENGTH(`description`) BETWEEN 10 AND 2000),
    CONSTRAINT `chk_auction_item_category`
        CHECK (CHAR_LENGTH(`category`) BETWEEN 2 AND 32),
    CONSTRAINT `chk_auction_item_condition`
        CHECK (`item_condition` IN ('NEW', 'LIKE_NEW', 'GOOD', 'FAIR')),
    CONSTRAINT `chk_auction_item_review_status`
        CHECK (`review_status` IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT `chk_auction_item_submission_version`
        CHECK (`submission_version` >= 0),
    CONSTRAINT `chk_auction_item_version`
        CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Seller-owned auction item and review state';

CREATE TABLE `auction_item_image`
(
    `id`                BIGINT       NOT NULL COMMENT 'Application-generated upload intent ID',
    `item_id`           BIGINT       NULL COMMENT 'Bound auction item; null while upload is pending',
    `owner_id`          BIGINT       NOT NULL COMMENT 'Account that requested the upload intent',
    `object_key`        VARCHAR(512) NOT NULL COMMENT 'Server-generated private OSS object key',
    `original_filename` VARCHAR(255) NOT NULL COMMENT 'Original client filename for display only',
    `content_type`      VARCHAR(64)  NOT NULL COMMENT 'Validated image MIME type',
    `content_length`    BIGINT       NOT NULL COMMENT 'Expected object size in bytes',
    `content_sha256`    CHAR(64)     NULL COMMENT 'Optional lowercase hexadecimal SHA-256',
    `sort_order`        INT          NULL COMMENT 'Zero-based display position after binding',
    `storage_status`    VARCHAR(24)  NOT NULL COMMENT 'PENDING, BOUND or EXPIRED',
    `upload_expires_at` DATETIME(6)  NOT NULL COMMENT 'UTC signed upload expiration time',
    `created_at`        DATETIME(6)  NOT NULL COMMENT 'UTC creation time',
    `updated_at`        DATETIME(6)  NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_item_image_object_key` UNIQUE (`object_key`),
    CONSTRAINT `uk_auction_item_image_item_sort` UNIQUE (`item_id`, `sort_order`),
    KEY `idx_auction_item_image_owner_status` (`owner_id`, `storage_status`, `created_at`, `id`),
    KEY `idx_auction_item_image_expiry` (`storage_status`, `upload_expires_at`, `id`),
    CONSTRAINT `fk_auction_item_image_item`
        FOREIGN KEY (`item_id`) REFERENCES `auction_item` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_auction_item_image_owner_id`
        CHECK (`owner_id` > 0),
    CONSTRAINT `chk_auction_item_image_content_length`
        CHECK (`content_length` > 0),
    CONSTRAINT `chk_auction_item_image_sha256`
        CHECK (`content_sha256` IS NULL OR REGEXP_LIKE(`content_sha256`, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT `chk_auction_item_image_sort_order`
        CHECK (`sort_order` IS NULL OR `sort_order` BETWEEN 0 AND 8),
    CONSTRAINT `chk_auction_item_image_status`
        CHECK (`storage_status` IN ('PENDING', 'BOUND', 'EXPIRED')),
    CONSTRAINT `chk_auction_item_image_binding`
        CHECK ((`storage_status` = 'BOUND' AND `item_id` IS NOT NULL AND `sort_order` IS NOT NULL)
            OR (`storage_status` IN ('PENDING', 'EXPIRED') AND `item_id` IS NULL AND `sort_order` IS NULL))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Private OSS upload intent and item image binding';

CREATE TABLE `auction_review`
(
    `id`                 BIGINT       NOT NULL COMMENT 'Application-generated review ID',
    `item_id`            BIGINT       NOT NULL COMMENT 'Reviewed auction item',
    `submission_version` INT          NOT NULL COMMENT 'Immutable submitted version',
    `reviewer_id`        BIGINT       NOT NULL COMMENT 'Administrator account ID',
    `decision`           VARCHAR(16)  NOT NULL COMMENT 'APPROVED or REJECTED',
    `comment`            VARCHAR(500) NULL COMMENT 'Review explanation',
    `reviewed_at`        DATETIME(6)  NOT NULL COMMENT 'UTC decision time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_review_item_submission` UNIQUE (`item_id`, `submission_version`),
    KEY `idx_auction_review_reviewer_time` (`reviewer_id`, `reviewed_at` DESC, `id` DESC),
    CONSTRAINT `fk_auction_review_item`
        FOREIGN KEY (`item_id`) REFERENCES `auction_item` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_auction_review_submission_version`
        CHECK (`submission_version` > 0),
    CONSTRAINT `chk_auction_review_reviewer_id`
        CHECK (`reviewer_id` > 0),
    CONSTRAINT `chk_auction_review_decision`
        CHECK (`decision` IN ('APPROVED', 'REJECTED')),
    CONSTRAINT `chk_auction_review_comment`
        CHECK (`comment` IS NULL OR CHAR_LENGTH(`comment`) BETWEEN 1 AND 500)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Immutable auction item review decision';

CREATE TABLE `auction_session`
(
    `id`                BIGINT        NOT NULL COMMENT 'Application-generated auction ID',
    `item_id`           BIGINT        NOT NULL COMMENT 'One-to-one auction item',
    `seller_id`         BIGINT        NOT NULL COMMENT 'Denormalized seller account ID',
    `start_price`       DECIMAL(19,2) NOT NULL COMMENT 'Minimum first bid',
    `bid_increment`     DECIMAL(19,2) NOT NULL COMMENT 'Minimum subsequent increment',
    `deposit_amount`    DECIMAL(19,2) NOT NULL COMMENT 'Required registration deposit',
    `current_price`     DECIMAL(19,2) NULL COMMENT 'Current accepted bid',
    `current_bidder_id` BIGINT        NULL COMMENT 'Current leading bidder account ID',
    `bid_count`         BIGINT        NOT NULL DEFAULT 0 COMMENT 'Accepted bid count',
    `start_at`          DATETIME(6)   NOT NULL COMMENT 'UTC scheduled opening time',
    `end_at`            DATETIME(6)   NOT NULL COMMENT 'UTC scheduled closing boundary',
    `status`            VARCHAR(24)   NOT NULL COMMENT 'DRAFT, SCHEDULED, OPEN or AWAITING_CLOSE',
    `version`           BIGINT        NOT NULL DEFAULT 0 COMMENT 'CAS version for lifecycle and bids',
    `created_at`        DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`        DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_session_item_id` UNIQUE (`item_id`),
    KEY `idx_auction_session_lobby` (`status`, `start_at`, `id`),
    KEY `idx_auction_session_end_scan` (`status`, `end_at`, `id`),
    CONSTRAINT `fk_auction_session_item`
        FOREIGN KEY (`item_id`) REFERENCES `auction_item` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_auction_session_seller_id`
        CHECK (`seller_id` > 0),
    CONSTRAINT `chk_auction_session_start_price`
        CHECK (`start_price` > 0.00),
    CONSTRAINT `chk_auction_session_bid_increment`
        CHECK (`bid_increment` > 0.00),
    CONSTRAINT `chk_auction_session_deposit_amount`
        CHECK (`deposit_amount` > 0.00),
    CONSTRAINT `chk_auction_session_current_price`
        CHECK (`current_price` IS NULL OR `current_price` >= `start_price`),
    CONSTRAINT `chk_auction_session_bid_state`
        CHECK ((`bid_count` = 0 AND `current_price` IS NULL AND `current_bidder_id` IS NULL)
            OR (`bid_count` > 0 AND `current_price` IS NOT NULL AND `current_bidder_id` > 0)),
    CONSTRAINT `chk_auction_session_time_range`
        CHECK (`end_at` > `start_at`),
    CONSTRAINT `chk_auction_session_status`
        CHECK (`status` IN ('DRAFT', 'SCHEDULED', 'OPEN', 'AWAITING_CLOSE')),
    CONSTRAINT `chk_auction_session_version`
        CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Auction schedule, lifecycle and current bid snapshot';

CREATE TABLE `auction_registration`
(
    `id`              BIGINT        NOT NULL COMMENT 'Application-generated registration ID',
    `registration_no` VARCHAR(64)   NOT NULL COMMENT 'Stable Account wallet hold business key',
    `auction_id`      BIGINT        NOT NULL COMMENT 'Auction session ID',
    `bidder_id`       BIGINT        NOT NULL COMMENT 'Buyer account ID',
    `deposit_amount`  DECIMAL(19,2) NOT NULL COMMENT 'Immutable requested deposit',
    `status`          VARCHAR(24)   NOT NULL COMMENT 'PENDING_HOLD, REGISTERED or FAILED',
    `failure_code`    VARCHAR(64)   NULL COMMENT 'Stable deterministic Account error code',
    `attempt_count`   INT           NOT NULL DEFAULT 0 COMMENT 'Remote hold attempts',
    `next_retry_at`   DATETIME(6)   NULL COMMENT 'UTC next recovery time',
    `last_attempt_at` DATETIME(6)   NULL COMMENT 'UTC latest remote attempt time',
    `lease_owner`     VARCHAR(64)   NULL COMMENT 'Short-lived recovery worker identity',
    `lease_until`     DATETIME(6)   NULL COMMENT 'UTC recovery lease expiration',
    `registered_at`   DATETIME(6)   NULL COMMENT 'UTC confirmed hold time',
    `version`         BIGINT        NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `created_at`      DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`      DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_auction_registration_no` UNIQUE (`registration_no`),
    CONSTRAINT `uk_auction_registration_auction_bidder` UNIQUE (`auction_id`, `bidder_id`),
    KEY `idx_auction_registration_bidder_created` (`bidder_id`, `created_at` DESC, `id` DESC),
    KEY `idx_auction_registration_recovery` (`status`, `next_retry_at`, `lease_until`, `id`),
    CONSTRAINT `fk_auction_registration_session`
        FOREIGN KEY (`auction_id`) REFERENCES `auction_session` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_auction_registration_bidder_id`
        CHECK (`bidder_id` > 0),
    CONSTRAINT `chk_auction_registration_deposit`
        CHECK (`deposit_amount` > 0.00),
    CONSTRAINT `chk_auction_registration_status`
        CHECK (`status` IN ('PENDING_HOLD', 'REGISTERED', 'FAILED')),
    CONSTRAINT `chk_auction_registration_attempt_count`
        CHECK (`attempt_count` >= 0),
    CONSTRAINT `chk_auction_registration_result`
        CHECK ((`status` = 'PENDING_HOLD' AND `failure_code` IS NULL AND `registered_at` IS NULL)
            OR (`status` = 'REGISTERED' AND `failure_code` IS NULL AND `registered_at` IS NOT NULL)
            OR (`status` = 'FAILED' AND `failure_code` IS NOT NULL AND `registered_at` IS NULL)),
    CONSTRAINT `chk_auction_registration_lease`
        CHECK ((`lease_owner` IS NULL AND `lease_until` IS NULL)
            OR (`lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL)),
    CONSTRAINT `chk_auction_registration_version`
        CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Buyer registration and recoverable deposit hold state';

CREATE TABLE `bid_record`
(
    `id`             BIGINT        NOT NULL COMMENT 'Application-generated bid ID',
    `auction_id`     BIGINT        NOT NULL COMMENT 'Auction session ID',
    `bidder_id`      BIGINT        NOT NULL COMMENT 'Bidder account ID',
    `request_id`     VARCHAR(48)   NOT NULL COMMENT 'Caller idempotency key',
    `amount`         DECIMAL(19,2) NOT NULL COMMENT 'Accepted bid amount',
    `previous_price` DECIMAL(19,2) NULL COMMENT 'Previous accepted price; null for first bid',
    `sequence_no`    BIGINT        NOT NULL COMMENT 'Monotonic sequence within one auction',
    `created_at`     DATETIME(6)   NOT NULL COMMENT 'UTC acceptance time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_bid_record_bidder_request` UNIQUE (`bidder_id`, `request_id`),
    CONSTRAINT `uk_bid_record_auction_sequence` UNIQUE (`auction_id`, `sequence_no`),
    KEY `idx_bid_record_auction_created` (`auction_id`, `created_at` DESC, `id` DESC),
    CONSTRAINT `fk_bid_record_session`
        FOREIGN KEY (`auction_id`) REFERENCES `auction_session` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_bid_record_bidder_id`
        CHECK (`bidder_id` > 0),
    CONSTRAINT `chk_bid_record_amount`
        CHECK (`amount` > 0.00),
    CONSTRAINT `chk_bid_record_previous_price`
        CHECK (`previous_price` IS NULL OR `previous_price` > 0.00),
    CONSTRAINT `chk_bid_record_sequence_no`
        CHECK (`sequence_no` > 0),
    CONSTRAINT `chk_bid_record_sequence_state`
        CHECK ((`sequence_no` = 1 AND `previous_price` IS NULL)
            OR (`sequence_no` > 1 AND `previous_price` IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Immutable accepted manual bid record';
