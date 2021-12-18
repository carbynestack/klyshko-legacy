/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler;

public record SchedulerSpec(
    boolean master,
    int parallelism,
    int lowerThreshold,
    int upperThreshold,
    int tuplesPerJob,
    String etcdEndpoint) {}
