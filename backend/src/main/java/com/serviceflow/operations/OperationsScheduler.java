package com.serviceflow.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="app.workflows",name="scan-enabled",havingValue="true",matchIfMissing=true)
public class OperationsScheduler {
  private final OperationsService operations;
  public OperationsScheduler(OperationsService operations) { this.operations=operations; }
  @Scheduled(fixedDelayString="${app.workflows.scan-delay-ms:30000}",initialDelayString="${app.workflows.scan-delay-ms:30000}")
  public void scan() { operations.scan(null); }
}
