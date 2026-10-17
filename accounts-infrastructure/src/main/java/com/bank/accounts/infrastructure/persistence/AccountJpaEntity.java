package com.bank.accounts.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** Row of sc_acc_accounts_ledger.account (V1). */
@Entity
@Table(name = "account")
public class AccountJpaEntity {

    @Id
    @Column(name = "account_id", length = 64)
    private String accountId;

    @Column(name = "category", nullable = false, length = 16, updatable = false)
    private String category;

    @Column(name = "holder_id", length = 64, updatable = false)
    private String holderId;

    @Column(name = "account_type", nullable = false, length = 16, updatable = false)
    private String accountType;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "islamic_compliant", nullable = false, updatable = false)
    private boolean islamicCompliant;

    @Column(name = "freeze_reason", length = 256)
    private String freezeReason;

    @Column(name = "booked_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal bookedBalance;

    @Column(name = "open_request_key", nullable = false, length = 128, updatable = false)
    private String openRequestKey;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AccountJpaEntity() {
    }

    AccountJpaEntity(String accountId, String category, String holderId, String accountType, String currency,
                     boolean islamicCompliant, String openRequestKey, Instant openedAt) {
        this.accountId = accountId;
        this.category = category;
        this.holderId = holderId;
        this.accountType = accountType;
        this.currency = currency;
        this.islamicCompliant = islamicCompliant;
        this.openRequestKey = openRequestKey;
        this.openedAt = openedAt;
    }

    void updateState(String status, String freezeReason, BigDecimal bookedBalance, Instant updatedAt) {
        this.status = status;
        this.freezeReason = freezeReason;
        this.bookedBalance = bookedBalance;
        this.updatedAt = updatedAt;
    }

    public String getAccountId() { return accountId; }
    public String getCategory() { return category; }
    public String getHolderId() { return holderId; }
    public String getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public boolean isIslamicCompliant() { return islamicCompliant; }
    public String getFreezeReason() { return freezeReason; }
    public BigDecimal getBookedBalance() { return bookedBalance; }
    public String getOpenRequestKey() { return openRequestKey; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
