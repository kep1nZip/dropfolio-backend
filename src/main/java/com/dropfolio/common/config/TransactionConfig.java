package com.dropfolio.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Milestone 7 addition. {@code AdminSyncService} needs "create sync_jobs RUNNING row + record
 * ADMIN_TRIGGER_SYNC audit row" to commit or roll back together (PM Decision M7 §9 — never
 * leave an audit row implying a sync started when it didn't, and symmetrically never leave a
 * RUNNING job row with no corresponding audit trail). Exposed as the {@link TransactionOperations}
 * interface (not the concrete {@link TransactionTemplate}) so it can be swapped for a simple
 * synchronous stub in unit tests without a real {@link PlatformTransactionManager}/datasource.
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
