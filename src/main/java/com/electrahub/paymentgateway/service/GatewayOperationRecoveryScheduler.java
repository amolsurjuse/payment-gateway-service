package com.electrahub.paymentgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GatewayOperationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(GatewayOperationRecoveryScheduler.class);
    private final GatewayOperationService operationService;

    public GatewayOperationRecoveryScheduler(GatewayOperationService operationService) {
        this.operationService = operationService;
    }

    @Scheduled(fixedDelayString = "${app.gateway.operation-recovery-fixed-delay:60000}")
    public void reconcilePendingOperations() {
        int finalized = operationService.reconcilePendingOperations(100);
        if (finalized > 0) {
            log.info("Reconciled {} pending payment gateway operation(s)", finalized);
        }
    }
}
