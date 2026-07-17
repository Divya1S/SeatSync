package com.seatsync.booking.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs best-effort side effects (Redis, WS broadcasts) after the surrounding
 * transaction commits; if no transaction is active (plain unit tests), runs
 * them immediately. Registration order is preserved after commit.
 */
final class TxSideEffects {

    private TxSideEffects() {
    }

    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
