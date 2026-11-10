package com.serviceflow.operations;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkflowProperties.class)
public class OperationsConfiguration {
  @Bean @ConditionalOnMissingBean(Clock.class)
  Clock workflowClock() { return Clock.systemUTC(); }
}
