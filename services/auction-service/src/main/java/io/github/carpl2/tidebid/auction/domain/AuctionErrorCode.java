package io.github.carpl2.tidebid.auction.domain;

import io.github.carpl2.tidebid.core.ErrorCode;

public enum AuctionErrorCode implements ErrorCode {

    ASSET_NOT_FOUND("AUCTION_ASSET_NOT_FOUND", "Auction asset was not found", 404),
    ASSET_INVALID("AUCTION_ASSET_INVALID", "Auction asset input is invalid", 400),
    ASSET_ACCESS_DENIED("AUCTION_ASSET_ACCESS_DENIED", "Access to the auction asset is forbidden", 403),
    ASSET_STATE_CONFLICT("AUCTION_ASSET_STATE_CONFLICT", "Auction asset is not in the required state", 409),
    SUBMISSION_VERSION_CONFLICT("AUCTION_SUBMISSION_VERSION_CONFLICT", "Auction submission version is stale", 409),
    IMAGE_INVALID("AUCTION_IMAGE_INVALID", "Auction image is invalid", 400),
    STORAGE_UNAVAILABLE("AUCTION_STORAGE_UNAVAILABLE", "Object storage is temporarily unavailable", 503),
    AUCTION_NOT_FOUND("AUCTION_NOT_FOUND", "Auction was not found", 404),
    AUCTION_INVALID("AUCTION_INVALID", "Auction input is invalid", 400),
    AUCTION_STATE_CONFLICT("AUCTION_STATE_CONFLICT", "Auction is not in the required state", 409),
    AUCTION_TIME_INVALID("AUCTION_TIME_INVALID", "Auction time range is invalid", 400),
    AUCTION_AMOUNT_INVALID("AUCTION_AMOUNT_INVALID", "Auction amount is invalid", 400),
    AUCTION_NOT_STARTED("AUCTION_NOT_STARTED", "Auction has not started", 409),
    AUCTION_ENDED("AUCTION_ENDED", "Auction has ended", 409),
    SELLER_CANNOT_PARTICIPATE("AUCTION_SELLER_CANNOT_PARTICIPATE", "Seller cannot participate in their own auction", 409),
    REGISTRATION_NOT_FOUND("AUCTION_REGISTRATION_NOT_FOUND", "Auction registration was not found", 404),
    REGISTRATION_REQUIRED("AUCTION_REGISTRATION_REQUIRED", "A completed registration is required", 409),
    REGISTRATION_PENDING("AUCTION_REGISTRATION_PENDING", "Auction registration is still processing", 409),
    DEPOSIT_INSUFFICIENT("AUCTION_DEPOSIT_INSUFFICIENT", "Available wallet balance is insufficient for the deposit", 409),
    BID_AMOUNT_INVALID("AUCTION_BID_AMOUNT_INVALID", "Bid amount must be a positive value with at most two decimal places", 400),
    BID_TOO_LOW("AUCTION_BID_TOO_LOW", "Bid amount is below the minimum next bid", 409),
    BID_CONFLICT("AUCTION_BID_CONFLICT", "Auction price changed before the bid was accepted", 409),
    IDEMPOTENCY_CONFLICT("AUCTION_IDEMPOTENCY_CONFLICT", "Request id was already used with different data", 409),
    ACCOUNT_SERVICE_UNAVAILABLE("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE", "Account service is temporarily unavailable", 503);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    AuctionErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
