/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.carbynestack.castor.client.upload.CastorUploadClient;
import io.carbynestack.castor.client.upload.DefaultCastorUploadClient;
import io.carbynestack.klyshko.operator.scheduler.Scheduler;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchResponse;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.logging.Log;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages K8s jobs according to job roster state.
 * <p>
 * Watches etcd for creation of job roster entries and creates the corresponding local tuple generation job as a
 * K8s job. When a job roster is deleted from etcd the associated local K8s job gets deleted. In case tuples have been
 * successfully created by all VCPs, the respective tuple chunk is activated.
 * <p>
 * The etcd job roster state is updated based on the VCP-local K8s job state.
 */
class VcpJobManager implements io.etcd.jetcd.Watch.Listener, Watcher<Pod>, Closeable {

    enum JobState {
        CREATED, PENDING, RUNNING, FAILED, SUCCEEDED
    }

    private final KubernetesClient k8sClient;
    private final Client etcdClient;
    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;
    private final Watch jobsWatch;
    private final io.etcd.jetcd.Watch.Watcher jobWatcher;

    VcpJobManager(KubernetesClient k8sClient, Client etcdClient, Scheduler scheduler, ObjectMapper objectMapper) {
        this.k8sClient = k8sClient;
        this.etcdClient = etcdClient;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
        jobsWatch = k8sClient.pods().watch(this);
        jobWatcher = etcdClient.getWatchClient().watch(JobRosterDirectoryKey.INSTANCE.toEtcdKey(),
                WatchOption.newBuilder().isPrefix(true).withPrevKV(true).build(), this);
        Log.infof("Job manager created for scheduler %s", scheduler.getMetadata().getName());
    }

    @Override
    public void close() {
        jobWatcher.close();
        jobsWatch.close();
        Log.infof("Job manager disposed for scheduler %s", scheduler.getMetadata().getName());
    }

    @Override
    public void onNext(WatchResponse watchResponse) {
        if (Log.isDebugEnabled()) {
            Log.debugf("Watch triggered for keys: %s",
                    watchResponse.getEvents().stream().map(e -> e.getKeyValue().getKey()).collect(Collectors.toList()));
        }
        for (var event : watchResponse.getEvents()) {
            var kv = event.getKeyValue();
            var key = kv.getKey();
            Key.fromEtcdKeyOptional(key, JobRosterKey.class).ifPresentOrElse(k -> {
                Log.debugf("Processing %s event for key %s", event.getEventType(), key);
                try {
                    switch (event.getEventType()) {
                        case PUT -> {
                            // Roster for VC job created, launch local VCP job
                            JobData jobData = objectMapper.readValue(kv.getValue().getBytes(), JobData.class);
                            if (jobData.jobState() == JobState.CREATED) {
                                createJob(k, jobData.jobParameters());
                            }
                        }
                        case DELETE -> {
                            /*
                             * Roster for VC job has been deleted:
                             * (1) activate tuple chunk, iff job has been successful
                             * (2) delete local VCP job
                             */
                            JobData jobData = objectMapper.readValue(event.getPrevKV().getValue().getBytes(),
                                    JobData.class);
                            if (jobData.jobState() == JobState.SUCCEEDED) {
                                activateTuples(k.jobId());
                            }
                            //deleteJob(k);
                        }
                        case UNRECOGNIZED -> Log.warn("Unrecognized watch event type encountered");
                    }
                } catch (IOException ioe) {
                    Log.errorf(ioe, "Failed to handle roster event for job with ID %s", k.jobId());
                }
            }, () -> Log.debugf("Skipping key '%s' - not a job roster key", key));
        }
    }

    @Override
    public void onError(Throwable throwable) {
        Log.errorf(throwable, "Error for watch on job manager for scheduler %s", scheduler.getMetadata().getName());
    }

    @Override
    public void onCompleted() {
        Log.debugf("Watch result processing completed on job manager for scheduler %s",
                scheduler.getMetadata().getName());
    }

    void createJob(JobRosterKey key, JobParameters params) {
        Log.debugf("Creating VCP-local job for VC job with ID '%s' using parameters: %s", key.jobId(), params);
        var kiiSharedFolderPath = "/kii";
        var job = k8sClient.pods().create(new PodBuilder()
                .withNewMetadata()
                .withName("klyshko-crg-" + key.jobId())
                .withLabels(Map.of(
                        // TODO Add other recommended labels (see https://kubernetes.io/docs/concepts/overview/working-with-objects/common-labels/)
                        "app.kubernetes.io/part-of", "klyshko.carbynestack.io",
                        "klyshko.carbynestack.io/jobId", key.jobId().toString(),
                        "klyshko.carbynestack.io/type", params.tupleType().toString()))
                .endMetadata()
                .withNewSpec()
                .withShareProcessNamespace(true)
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("generator")
                .withImage("carbynestack/klyshko-mp-spdz:1.0.0-SNAPSHOT") // TODO: Get from CRD
                .withEnv(
                        new EnvVarBuilder()
                                .withName("KII_JOB_ID")
                                .withValue(key.jobId().toString())
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_PLAYER_COUNT")
                                .withValue(Integer.toString(HysteresisScheduler.NUMBER_OF_PARTIES))
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_TUPLE_TYPE")
                                .withValue(params.tupleType().toString())
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_TUPLES_PER_JOB")
                                .withValue(Integer.toString(scheduler.getSpec().tuplesPerJob()))
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_PLAYER_NUMBER")
                                .withValue(scheduler.getSpec().master() ? "0" : "1")
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_SHARED_FOLDER")
                                .withValue(kiiSharedFolderPath)
                                .build())
                .withVolumeMounts(
                        new VolumeMountBuilder()
                                .withName("kii")
                                .withMountPath(kiiSharedFolderPath)
                                .build(),
                        new VolumeMountBuilder()
                                .withName("params")
                                .withMountPath("/etc/kii/params")
                                .withReadOnly(true)
                                .build(),
                        new VolumeMountBuilder()
                                .withName("secret-params")
                                .withMountPath("/etc/kii/secret-params")
                                .withReadOnly(true)
                                .build(),
                        new VolumeMountBuilder()
                                .withName("extra-params")
                                .withMountPath("/etc/kii/extra-params")
                                .withReadOnly(true)
                                .build())

                .endContainer()
                .addNewContainer()
                .withName("provisioner")
                .withImage("carbynestack/klyshko-provisioner:1.0.0-SNAPSHOT")
                .withEnv(
                        new EnvVarBuilder()
                                .withName("KII_JOB_ID")
                                .withValue(key.jobId().toString())
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_TUPLE_TYPE")
                                .withValue(params.tupleType().toString())
                                .build(),
                        new EnvVarBuilder()
                                .withName("KII_SHARED_FOLDER")
                                .withValue("/kii")
                                .build())
                .withVolumeMounts(
                        new VolumeMountBuilder()
                                .withName("kii")
                                .withMountPath("/kii")
                                .build())
                .endContainer()
                .withVolumes(
                        new VolumeBuilder()
                                .withName("kii")
                                .withNewEmptyDir()
                                .and()
                                .build(),
                        new VolumeBuilder()
                                .withName("params")
                                .withConfigMap(
                                        new ConfigMapVolumeSourceBuilder()
                                                .withName("io.carbynestack.engine.params")
                                                .build())
                                .build(),
                        new VolumeBuilder()
                                .withName("secret-params")
                                .withSecret(
                                        new SecretVolumeSourceBuilder()
                                                .withSecretName("io.carbynestack.engine.params.secret")
                                                .build())
                                .build(),
                        new VolumeBuilder()
                                .withName("extra-params")
                                .withConfigMap(
                                        new ConfigMapVolumeSourceBuilder()
                                                .withName("io.carbynestack.engine.params.extra")
                                                .build())
                                .build())
                .endSpec()
                .build()
        );
        Log.infof("Job with name %s created for key %s", job.getMetadata().getName(), key);
    }

    void deleteJob(JobRosterKey key) {
        boolean deleted =
                k8sClient.pods().withLabel("klyshko.carbynestack.io/jobId",
                        key.jobId().toString()).delete();
        Log.infof("Deletion of job with key %s %s", key.toString(), deleted ? "successful" : "failed");
    }

    Optional<JobState> toVcpJobState(PodStatus status) {
        return switch (status.getPhase()) {
            case "Pending":
                yield Optional.of(JobState.PENDING);
            case "Running":
                yield Optional.of(JobState.RUNNING);
            case "Succeeded":
                yield Optional.of(JobState.SUCCEEDED);
            case "Failed":
                yield Optional.of(JobState.FAILED);
            default:
                yield Optional.empty();
        };
    }

    @Override
    public void eventReceived(Action action, Pod pod) {
        String jobId = pod.getMetadata().getLabels().get("klyshko.carbynestack.io/jobId");
        if (jobId == null) {
            Log.debugf("Non Klyshko-managed pod %s - skipping", pod.getMetadata().getName());
            return;
        }
        JobRosterKey key = new JobRosterKey(UUID.fromString(jobId));
        Log.debugf("processing Klyshko-managed VCP job %s with status %s and key %s", pod.getMetadata().getName(),
                pod.getStatus(), key);
        // TODO Use configured player number
        JobRosterEntryKey jreKey = new JobRosterEntryKey(key.jobId(), scheduler.getSpec().master() ? 0 : 1);
        toVcpJobState(pod.getStatus()).ifPresent(s -> {
            Log.debugf("Pod state '%s' mapped to job state '%s'", pod.getStatus().getPhase(), s);
            var kbs = jreKey.toEtcdKey();
            var sbs = ByteSequence.from(s.toString(), StandardCharsets.UTF_8);
            // Update roster entry value iff state has changed
            etcdClient.getKVClient()
                    .txn()
                    .If(new Cmp(kbs, Cmp.Op.NOT_EQUAL, CmpTarget.value(sbs)))
                    .Then(Op.put(kbs, sbs, PutOption.DEFAULT))
                    .commit()
                    .thenAccept(r -> {
                        Log.infof("Updated roster entry for key %s to %s", jreKey, s);
                    });
        });
    }

    @Override
    public void onClose(WatcherException e) {
    }

    void activateTuples(UUID jobId) {
        getCastorUploadClient().ifPresentOrElse(c -> {
            try {
                c.activateTupleChunk(jobId);
                Log.infof("Tuples with chunk identifier '%s' successfully activated", jobId);
            } catch (Exception e) {
                Log.errorf(e, "Activation failed for tuple chunk with identifier '%s'", jobId);
            }
        }, () -> Log.warnf("Skipping tuple chunk activation for job with id '%s' due to unavailable Castor service",
                jobId));
    }

    // TODO: Dedup (see provisioner)
    Optional<CastorUploadClient> getCastorUploadClient() {
        var castorService = k8sClient.services().withName("cs-castor"); // TODO Make service name configurable
        if (!castorService.isReady()) {
            Log.warn("Castor service not available");
            return Optional.empty();
        }
        var castorSpec = castorService.get().getSpec();
        String castorEndpoint = String.format("http://%s:%d",
                castorSpec.getClusterIP(),
                castorSpec.getPorts().get(0).getPort()); // TODO Handle case when port is not given (possible?)
        Log.infof("Castor service discovered at endpoint %s", castorEndpoint);
        return Optional.of(DefaultCastorUploadClient.builder(castorEndpoint).withoutSslCertificateValidation().build());
    }

}
