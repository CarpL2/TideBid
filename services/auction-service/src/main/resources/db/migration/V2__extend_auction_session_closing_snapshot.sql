ALTER TABLE `auction_session`
    DROP CHECK `chk_auction_session_status`,
    ADD COLUMN `winner_id` BIGINT NULL COMMENT 'Winning bidder account ID for a sold auction' AFTER `current_bidder_id`,
    ADD COLUMN `winning_bid_id` BIGINT NULL COMMENT 'Accepted bid that won the auction' AFTER `winner_id`,
    ADD COLUMN `final_price` DECIMAL(19,2) NULL COMMENT 'Immutable sold price' AFTER `winning_bid_id`,
    ADD COLUMN `closed_at` DATETIME(6) NULL COMMENT 'UTC terminal transition time' AFTER `end_at`,
    ADD CONSTRAINT `uk_auction_session_winning_bid` UNIQUE (`winning_bid_id`),
    ADD CONSTRAINT `fk_auction_session_winning_bid`
        FOREIGN KEY (`winning_bid_id`) REFERENCES `bid_record` (`id`) ON DELETE RESTRICT,
    ADD CONSTRAINT `chk_auction_session_winner_id`
        CHECK (`winner_id` IS NULL OR `winner_id` > 0),
    ADD CONSTRAINT `chk_auction_session_winning_bid_id`
        CHECK (`winning_bid_id` IS NULL OR `winning_bid_id` > 0),
    ADD CONSTRAINT `chk_auction_session_final_price`
        CHECK (`final_price` IS NULL OR `final_price` > 0.00),
    ADD CONSTRAINT `chk_auction_session_closed_at`
        CHECK (`closed_at` IS NULL OR `closed_at` >= `end_at`),
    ADD CONSTRAINT `chk_auction_session_status`
        CHECK (`status` IN (
            'DRAFT', 'SCHEDULED', 'OPEN', 'AWAITING_CLOSE', 'CLOSED_SOLD', 'CLOSED_UNSOLD'
        )),
    ADD CONSTRAINT `chk_auction_session_terminal_snapshot`
        CHECK (
            (`status` IN ('DRAFT', 'SCHEDULED', 'OPEN', 'AWAITING_CLOSE')
                AND `winner_id` IS NULL
                AND `winning_bid_id` IS NULL
                AND `final_price` IS NULL
                AND `closed_at` IS NULL)
            OR
            (`status` = 'CLOSED_SOLD'
                AND `bid_count` > 0
                AND `winner_id` = `current_bidder_id`
                AND `winning_bid_id` IS NOT NULL
                AND `final_price` = `current_price`
                AND `closed_at` IS NOT NULL)
            OR
            (`status` = 'CLOSED_UNSOLD'
                AND `bid_count` = 0
                AND `current_price` IS NULL
                AND `current_bidder_id` IS NULL
                AND `winner_id` IS NULL
                AND `winning_bid_id` IS NULL
                AND `final_price` IS NULL
                AND `closed_at` IS NOT NULL)
        );
