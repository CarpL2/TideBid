ALTER TABLE `trade_order`
    ADD COLUMN `winner_hold_amount` DECIMAL(19,2) NULL
        COMMENT 'Immutable winning deposit amount snapshot' AFTER `winner_hold_no`;

UPDATE `trade_order` AS `orders`
JOIN `trade_outbox` AS `outbox`
  ON `outbox`.`aggregate_type` = 'TRADE_ORDER'
 AND `outbox`.`aggregate_id` = CAST(`orders`.`id` AS CHAR)
 AND `outbox`.`event_type` = 'deposit.settlement-requested'
SET `orders`.`winner_hold_amount` = CAST(
        JSON_UNQUOTE(JSON_EXTRACT(`outbox`.`payload`, '$.payload.holdAmount')) AS DECIMAL(19,2)
    )
WHERE `orders`.`winner_hold_amount` IS NULL;

ALTER TABLE `trade_order`
    MODIFY COLUMN `winner_hold_amount` DECIMAL(19,2) NOT NULL
        COMMENT 'Immutable winning deposit amount snapshot',
    ADD CONSTRAINT `chk_trade_order_winner_hold_amount`
        CHECK (`winner_hold_amount` > 0.00);
