/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.carbynestack.klyshko.operator.scheduler.Scheduler;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchResponse;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.logging.Log;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages K8s jobs according to job roster state.
 * <p>
 * Watches etcd for creation of job roster entries and creates the corresponding local tuple generation job as a
 * K8s job. When a job roster is deleted from etcd the associated local K8s job gets deleted.
 * <p>
 * The etcd job roster state is updated based on the VCP-local K8s job state.
 */
class JobManager implements io.etcd.jetcd.Watch.Listener, Watcher<Job>, Closeable {

    enum JobState {
        RUNNING, COMPLETED
    }

    private final KubernetesClient k8sClient;
    private final Client etcdClient;
    private final Scheduler scheduler;
    private final Watch jobsWatch;
    private final io.etcd.jetcd.Watch.Watcher jobWatcher;

    JobManager(KubernetesClient k8sClient, Client etcdClient, Scheduler scheduler) {
        this.k8sClient = k8sClient;
        this.etcdClient = etcdClient;
        this.scheduler = scheduler;
        jobsWatch = k8sClient.batch().v1().jobs().watch(this);
        jobWatcher = etcdClient.getWatchClient().watch(JobRosterDirectoryKey.INSTANCE.toEtcdKey(),
                WatchOption.newBuilder().isPrefix(true).build(), this);
        Log.infof("Job manager created for scheduler %s", scheduler.getFullResourceName());
    }

    @Override
    public void close() {
        jobWatcher.close();
        jobsWatch.close();
        Log.infof("Job manager disposed for scheduler %s", scheduler.getFullResourceName());
    }

    @Override
    public void onNext(WatchResponse watchResponse) {
        if (Log.isDebugEnabled()) {
            Log.debugf("Watch triggered for keys: ",
                    watchResponse.getEvents().stream().map(e -> e.getKeyValue().getKey()).collect(Collectors.toList()));
        }
        for (var event : watchResponse.getEvents()) {
            var kv = event.getKeyValue();
            var key = kv.getKey();
            Log.infof("Processing %s event for key %s", event.getEventType(), key);
            Key.fromEtcdKeyOptional(kv.getKey(), JobRosterKey.class).ifPresent(k -> {
                switch (event.getEventType()) {
                    case PUT -> {
                        var jobParams = new JobParameters(kv.getValue());
                        createJob(k, jobParams);
                    }
                    case DELETE -> deleteJob(k);
                    case UNRECOGNIZED -> Log.warn("Unrecognized watch event type encountered");
                }
            });
        }
    }

    @Override
    public void onError(Throwable throwable) {
        Log.errorf(throwable, "Error for watch on job manager for scheduler %s", scheduler.getFullResourceName());
    }

    @Override
    public void onCompleted() {
        Log.debugf("Watch result processing completed on job manager for scheduler %s",
                scheduler.getFullResourceName());
    }

    void createJob(JobRosterKey key, JobParameters params) {
        var job = k8sClient.batch().v1().jobs().create(new JobBuilder()
                .withNewMetadata()
                .withName("test-" + key.jobId()) // TODO: derive name from job
                .withLabels(Map.of(
                        "klyshko.carbynestack.io/jobId", key.jobId().toString(),
                        "klyshko.carbynestack.io/type", params.tupleType().toString()))
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .addNewContainer() // TODO: add sidecar container to upload generated cr (see https://banzaicloud.com/blog/k8s-sidecars/#example)
                .withName("generator")
                .withImage("bash") // TODO: Get from CRD
                .withCommand("bash", "-c", "sleep 10") // TODO: Remove
                .endContainer()
                .withRestartPolicy("Never")
                .endSpec()
                .endTemplate()
                .withBackoffLimit(0)
                .endSpec()
                .build() // TODO: Add TTL (https://kubernetes.io/docs/concepts/workloads/controllers/job/#ttl-mechanism-for-finished-jobs)
        );
        Log.infof("Job with name %s created for key %s", key, job.getFullResourceName());
    }

    void deleteJob(JobRosterKey key) {
        boolean deleted =
                k8sClient.batch().v1().jobs().withLabel("klyshko.carbynestack.io/jobId",
                        key.jobId().toString()).delete();
        Log.infof("Deletion of job with key %s %s", key.toString(), deleted ? "successful" : "failed");
    }

    @Override
    public void eventReceived(Action action, Job job) {
        String jobId = job.getMetadata().getLabels().get("klyshko.carbynestack.io/jobId");
        if (jobId == null) {
            Log.infof("Non Klyshko-managed job %s - skipping", job.getFullResourceName());
            return;
        }
        JobRosterKey key = new JobRosterKey(UUID.fromString(jobId));
        Log.infof("processing Klyshko-managed job %s with status %s and key %s", job.getFullResourceName(),
                job.getStatus(), key);
        JobRosterEntryKey jreKey = new JobRosterEntryKey(key.jobId(), scheduler.getSpec().master() ? 0 : 1);
        var state = switch (action) {
            case ADDED -> Optional.of(JobState.RUNNING);
            case MODIFIED -> {
                var status = job.getStatus();
                var terminated =
                        (status.getSucceeded() != null && status.getSucceeded() > 0) || (status.getFailed() != null && status.getFailed() > 0);
                if (terminated) {
                    yield Optional.of(JobState.COMPLETED);
                } else {
                    yield Optional.empty();
                }
            }
            default -> Optional.empty();
        };
        state.map(s ->
                etcdClient.getKVClient()
                        .put(jreKey.toEtcdKey(), ByteSequence.from(s.toString(), StandardCharsets.UTF_8)).thenRun(() ->
                                Log.infof("Updating roster entry for key %s to %s", jreKey, s)
                        ));
    }

    @Override
    public void onClose(WatcherException e) {
    }

}
