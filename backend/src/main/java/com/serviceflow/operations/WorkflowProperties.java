package com.serviceflow.operations;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.workflows")
@Validated
public record WorkflowProperties(
    boolean autoAssign,
    @Min(1) @Max(10000) int agentCapacity,
    @Min(1) long firstResponseMinutes,
    @Min(1) long resolutionMinutes,
    @Min(1) @Max(1000) int scanBatchSize) {}
