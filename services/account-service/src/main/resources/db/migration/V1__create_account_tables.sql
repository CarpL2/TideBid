CREATE TABLE `user_account`
(
    `id`            BIGINT       NOT NULL COMMENT 'Application-generated user ID',
    `username`      VARCHAR(32)  NOT NULL COMMENT 'Normalized login name',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt password hash',
    `nickname`      VARCHAR(64)  NOT NULL COMMENT 'Display name',
    `status`        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE or DISABLED',
    `version`       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `created_at`    DATETIME(6)  NOT NULL COMMENT 'UTC creation time',
    `updated_at`    DATETIME(6)  NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_user_account_username` UNIQUE (`username`),
    CONSTRAINT `chk_user_account_username`
        CHECK (REGEXP_LIKE(`username`, '^[A-Za-z0-9_]{4,32}$', 'c')),
    CONSTRAINT `chk_user_account_status`
        CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `chk_user_account_version`
        CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'User credentials and profile';

CREATE TABLE `user_role`
(
    `user_id`    BIGINT      NOT NULL COMMENT 'User ID',
    `role_code`  VARCHAR(16) NOT NULL COMMENT 'USER or ADMIN',
    `created_at` DATETIME(6) NOT NULL COMMENT 'UTC creation time',
    PRIMARY KEY (`user_id`, `role_code`),
    KEY `idx_user_role_role_code` (`role_code`),
    CONSTRAINT `fk_user_role_user`
        FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_user_role_role_code`
        CHECK (`role_code` IN ('USER', 'ADMIN'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Roles assigned to a user';

CREATE TABLE `wallet_account`
(
    `id`                BIGINT        NOT NULL COMMENT 'Application-generated wallet ID',
    `user_id`           BIGINT        NOT NULL COMMENT 'Wallet owner',
    `available_balance` DECIMAL(19,2) NOT NULL DEFAULT 0.00 COMMENT 'Spendable virtual balance',
    `frozen_balance`    DECIMAL(19,2) NOT NULL DEFAULT 0.00 COMMENT 'Reserved virtual balance',
    `version`           BIGINT        NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    `created_at`        DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    `updated_at`        DATETIME(6)   NOT NULL COMMENT 'UTC update time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_wallet_account_user_id` UNIQUE (`user_id`),
    CONSTRAINT `fk_wallet_account_user`
        FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_wallet_account_available_balance`
        CHECK (`available_balance` >= 0.00),
    CONSTRAINT `chk_wallet_account_frozen_balance`
        CHECK (`frozen_balance` >= 0.00),
    CONSTRAINT `chk_wallet_account_version`
        CHECK (`version` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Virtual wallet balance';

CREATE TABLE `wallet_ledger`
(
    `id`                      BIGINT        NOT NULL COMMENT 'Application-generated ledger ID',
    `wallet_id`               BIGINT        NOT NULL COMMENT 'Wallet ID',
    `business_no`             VARCHAR(64)   NOT NULL COMMENT 'Idempotent business reference',
    `ledger_type`             VARCHAR(32)   NOT NULL COMMENT 'Balance change type',
    `available_delta`         DECIMAL(19,2) NOT NULL COMMENT 'Signed available balance change',
    `frozen_delta`            DECIMAL(19,2) NOT NULL COMMENT 'Signed frozen balance change',
    `available_balance_after` DECIMAL(19,2) NOT NULL COMMENT 'Available balance after change',
    `frozen_balance_after`    DECIMAL(19,2) NOT NULL COMMENT 'Frozen balance after change',
    `created_at`              DATETIME(6)   NOT NULL COMMENT 'UTC creation time',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_wallet_ledger_business_no` UNIQUE (`business_no`),
    KEY `idx_wallet_ledger_wallet_created` (`wallet_id`, `created_at`, `id`),
    CONSTRAINT `fk_wallet_ledger_wallet`
        FOREIGN KEY (`wallet_id`) REFERENCES `wallet_account` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_wallet_ledger_available_balance_after`
        CHECK (`available_balance_after` >= 0.00),
    CONSTRAINT `chk_wallet_ledger_frozen_balance_after`
        CHECK (`frozen_balance_after` >= 0.00)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Immutable virtual wallet ledger';
