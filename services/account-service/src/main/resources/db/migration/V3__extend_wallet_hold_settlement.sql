ALTER TABLE `wallet_hold`
    ADD COLUMN `captured_amount` DECIMAL(19,2) NOT NULL DEFAULT 0.00
        COMMENT 'Amount permanently captured from this hold' AFTER `amount`,
    ADD COLUMN `released_amount` DECIMAL(19,2) NOT NULL DEFAULT 0.00
        COMMENT 'Amount returned to available balance' AFTER `captured_amount`,
    ADD COLUMN `settlement_event_id` CHAR(36) NULL
        COMMENT 'Deposit settlement request event that finalized this hold' AFTER `status`,
    ADD COLUMN `settled_at` DATETIME(6) NULL
        COMMENT 'UTC time when this hold reached its terminal state' AFTER `settlement_event_id`,
    ADD CONSTRAINT `uk_wallet_hold_settlement_event_id` UNIQUE (`settlement_event_id`),
    ADD CONSTRAINT `chk_wallet_hold_captured_amount`
        CHECK (`captured_amount` >= 0.00),
    ADD CONSTRAINT `chk_wallet_hold_released_amount`
        CHECK (`released_amount` >= 0.00),
    ADD CONSTRAINT `chk_wallet_hold_settlement_event_id`
        CHECK (
            `settlement_event_id` IS NULL
            OR (
                REGEXP_LIKE(
                    `settlement_event_id`,
                    '^[0-9a-f]{8}-[0-9a-f]{4}-[1-9a-f][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
                    'c'
                )
                AND `settlement_event_id` <> '00000000-0000-0000-0000-000000000000'
            )
        ),
    ADD CONSTRAINT `chk_wallet_hold_settled_at`
        CHECK (`settled_at` IS NULL OR `settled_at` >= `created_at`),
    ADD CONSTRAINT `chk_wallet_hold_settlement_snapshot`
        CHECK (
            (
                `status` = 'HELD'
                AND `captured_amount` = 0.00
                AND `released_amount` = 0.00
                AND `settlement_event_id` IS NULL
                AND `settled_at` IS NULL
            )
            OR (
                `status` = 'RELEASED'
                AND `captured_amount` = 0.00
                AND `released_amount` = `amount`
                AND `settlement_event_id` IS NOT NULL
                AND `settled_at` IS NOT NULL
            )
            OR (
                `status` = 'CAPTURED'
                AND `captured_amount` > 0.00
                AND `captured_amount` + `released_amount` = `amount`
                AND `settlement_event_id` IS NOT NULL
                AND `settled_at` IS NOT NULL
            )
        );
