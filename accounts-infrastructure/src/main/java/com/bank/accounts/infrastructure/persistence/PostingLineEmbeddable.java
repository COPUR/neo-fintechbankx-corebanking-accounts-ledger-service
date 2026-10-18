package com.bank.accounts.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/** Row of sc_acc_accounts_ledger.posting_line (V1); line_no is the list order. */
@Embeddable
public class PostingLineEmbeddable {

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "side", nullable = false, length = 6)
    private String side;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected PostingLineEmbeddable() {
    }

    PostingLineEmbeddable(String accountId, String side, BigDecimal amount) {
        this.accountId = accountId;
        this.side = side;
        this.amount = amount;
    }

    public String getAccountId() { return accountId; }
    public String getSide() { return side; }
    public BigDecimal getAmount() { return amount; }
}
