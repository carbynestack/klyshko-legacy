/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler;

import io.carbynestack.klyshko.operator.scheduler.hysteresis.HysteresisScheduler;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.*;
import io.quarkus.logging.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Controller
public class SchedulerController implements ResourceController<Scheduler> {

    public static final String KIND = "Scheduler";

    private final KubernetesClient kubernetesClient;

    private final ConcurrentMap<String, HysteresisScheduler> schedulers;

    public SchedulerController() {
        this(new DefaultKubernetesClient());
    }

    public SchedulerController(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
        schedulers = new ConcurrentHashMap<>();
    }

    @Override
    public UpdateControl<Scheduler> createOrUpdateResource(
            Scheduler resource, Context<Scheduler> context) {
        String name = resource.getFullResourceName();
        boolean exists = schedulers.containsKey(name);
        if (!exists) {
            Log.infof("HysteresisScheduler created: %s", resource.getFullResourceName());
            schedulers.put(resource.getFullResourceName(), new HysteresisScheduler(kubernetesClient, resource));
        } // TODO Implement update logic for existing scheduler
        return UpdateControl.noUpdate();
    }

    @Override
    public DeleteControl deleteResource(Scheduler resource, Context<Scheduler> context) {
        HysteresisScheduler scheduler = schedulers.remove(resource.getFullResourceName());
        if (scheduler != null) {
            scheduler.close();
        }
        Log.infof("HysteresisScheduler deleted: %s", resource.getFullResourceName());
        return DeleteControl.DEFAULT_DELETE;
    }

    void fetchTupleTelemetry() {
        String url = kubernetesClient.services().withName("test").getURL("");
    }

}