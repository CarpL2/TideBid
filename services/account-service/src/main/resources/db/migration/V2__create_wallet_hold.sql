CREATE TABLE `wallet_hold`
(
    `id`            BIGINT        NOT NULL COMMENT 'Application-generated hold ID',
    `hold_no`       VARCHAR(64)   NOT NULL COMMENT 'Idempotent business reference supplied by Auction',
    `user_id`       BIGINT        NOT NULL COMMENT 'Wallet owner',
    `business_type` VARCHAR(32)   NOT NULL COMMENT 'Reason for reserving wallet funds',
    `amount`        DECIMAL(19,2) NOT NULL COMMENT 'Positive reserved amount',
    `status`        VARCHAR(16)   NOT NULL COMMENT 'HELD, RELEASED or CAPTURED',
    `version`       BIGINT        NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `created_at`    DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`    DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_wallet_hold_hold_no` UNIQUE (`hold_no`),
    KEY `idx_wallet_hold_user_status_created` (`user_id`, `status`, `created_at`, `id`),
    CONSTRAINT `fk_wallet_hold_user`
        FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_wallet_hold_business_type`
        CHECK (`business_type` IN ('AUCTION_DEPOSIT')),
    CONSTRAINT `chk_wallet_hold_amount`
        CHECK (`amount` > 0.00),
    CONSTRAINT `chk_wallet_hold_status`
        CHECK (`status` IN ('HELD', 'RELEASED', 'CAPTURED')),
    CONSTRAINT `chk_wallet_hold_version`
        CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Idempotent virtual wallet fund holds';
