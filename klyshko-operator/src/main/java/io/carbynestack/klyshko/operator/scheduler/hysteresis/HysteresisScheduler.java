/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.carbynestack.castor.client.download.CastorIntraVcpClient;
import io.carbynestack.castor.client.download.DefaultCastorIntraVcpClient;
import io.carbynestack.castor.common.entities.TupleType;
import io.carbynestack.klyshko.operator.scheduler.Scheduler;
import io.etcd.jetcd.*;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static io.carbynestack.klyshko.operator.KlyshkoOperator.ETCD_PREFIX;

/**
 * Schedules tuple generation jobs using a hysteresis control strategy.
 * <p>
 * The scheduler operates in a master/follower model using a shared etcd server to coordinate actions.
 * <p>
 * The master discovers the castor service (by name, currently hard-coded as "cs-castor") and periodically fetches
 * telemetry data. Tuple generation is started when the number of available tuples drops below a given threshold and
 * stopped when it exceeds an upper threshold (see {@link #controlLoop()}).
 * <p>
 * The scheduler periodically checks (see {@link #schedule()}), whether tuples of certain type should be generated
 * and instructs all VCPs in a VC to create a respective job by instantiating an etcd-backed job roster used to track
 * the states of all correlated jobs across the VCPs in the VC. VCP-local job management is delegated to an instance
 * of {@link JobManager}. Job rosters on which the job on all VCPs have been completed are removed by removing the
 * respective K/V entry in etcd.
 * <p>
 * The number of (per type) tuple generation jobs is limited by configurable upper limit.
 */
public class HysteresisScheduler implements Closeable, io.etcd.jetcd.Watch.Listener {

    private static final int NUMBER_OF_PARTIES = 2; // TODO Read from VC configuration as soon as available

    private enum State {
        IDLE, GENERATING
    }

    private final KubernetesClient k8sClient;
    private final ConcurrentMap<TupleType, State> states;
    private final Scheduler schedulerResource;
    private final Client etcdClient;
    private final JobManager jobManager;
    private final Optional<Watch.Watcher> jobWatcher;
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    public HysteresisScheduler(KubernetesClient k8sClient, Scheduler schedulerResource) {
        this.k8sClient = k8sClient;
        this.schedulerResource = schedulerResource;
        states = new ConcurrentHashMap<>();
        Arrays.stream(TupleType.values()).forEach(t -> states.put(t, State.IDLE));
        String etcdEndpoint = String.format("http://%s:%d", schedulerResource.getSpec().etcdEndpoint(), 2379);
        Log.infof("Using etcd at endpoint %s", etcdEndpoint);
        etcdClient = Client.builder().endpoints(etcdEndpoint).namespace(ByteSequence.from(ETCD_PREFIX,
                StandardCharsets.UTF_8)).build();
        jobManager = new JobManager(k8sClient, etcdClient, schedulerResource);
        boolean isMaster = schedulerResource.getSpec().master();
        if (isMaster) {
            jobWatcher = Optional.of(etcdClient.getWatchClient().watch(new JobRosterDirectoryKey().toEtcdKey(),
                    WatchOption.newBuilder().isPrefix(true).withNoDelete(true).build(), this));
            ses.scheduleWithFixedDelay(this::controlLoop, 0, 1, TimeUnit.MINUTES); // TODO: Make configurable
            ses.scheduleWithFixedDelay(this::schedule, 0, 10, TimeUnit.SECONDS); // TODO: Make configurable
        } else {
            jobWatcher = Optional.empty();
        }
        Log.infof("Scheduler instance %s created, acting as %s", schedulerResource.getFullResourceName(), isMaster ?
                "master" : "follower");
    }

    @Override
    public void close() {
        ses.shutdown();
        jobWatcher.ifPresent(Watch.Watcher::close);
        jobManager.close();
    }

    public void controlLoop() {
        Log.debug("Job creation control loop execution begins");
        try {
            var spec = schedulerResource.getSpec();
            var castorService = k8sClient.services().withName("cs-castor"); // TODO Make service name configurable
            if (castorService == null) {
                Log.warn("Castor service not available - aborting control loop execution");
                return;
            }
            var castorSpec = castorService.get().getSpec();
            String castorEndpoint = String.format("http://%s:%d",
                    castorSpec.getClusterIP(),
                    castorSpec.getPorts().get(0).getPort()); // TODO Handle case when port is not given (possible?)
            Log.infof("Castor service discovered at endpoint %s", castorEndpoint);
            CastorIntraVcpClient castorIntraVcpClient =
                    DefaultCastorIntraVcpClient.builder(castorEndpoint).withoutSslCertificateValidation().build();
            var telemetryData = castorIntraVcpClient.getTelemetryData();
            for (var m : telemetryData.getMetrics()) {
                var currentState = states.get(m.getType());
                states.put(m.getType(), switch (currentState) {
                    case State s && s == State.IDLE && m.getAvailable() < spec.lowerThreshold() -> {
                        Log.infof("Available tuples (%d) for type %s below lower threshold (%d) - start generating",
                                m.getAvailable(), m.getType(), spec.lowerThreshold());
                        yield State.GENERATING;
                    }
                    case State s && s == State.GENERATING && m.getAvailable() > spec.upperThreshold() -> {
                        Log.infof("Available tuples (%d) for type %s above upper threshold (%d) - stop generating",
                                m.getAvailable(), m.getType(), spec.upperThreshold());
                        yield State.IDLE;
                    }
                    default -> currentState;
                });
            }
        } catch (Exception e) {
            Log.error("Error occurred in control loop execution", e);
        }
    }

    private CompletableFuture<Long> getNumberOfActiveJobs(KV kv) {
        return kv.get(JobRosterDirectoryKey.INSTANCE.toEtcdKey(),
                GetOption.newBuilder().isPrefix(true).build()).thenApply(r ->
                r.getKvs().stream().map(KeyValue::getKey).filter(k -> Key.fromEtcdKeyOptional(k,
                        JobRosterKey.class).isPresent()).count());
    }

    void schedule() {
        try {
            var spec = schedulerResource.getSpec();
            KV kv = etcdClient.getKVClient();
            getNumberOfActiveJobs(kv).thenAccept(c -> {
                if (c >= spec.parallelism()) {
                    Log.infof("At maximum allowed parallelism (%d of %d jobs) - no jobs to be scheduled", c,
                            spec.parallelism());
                    return;
                }
                Arrays.stream(TupleType.values())
                        .skip(ThreadLocalRandom.current().nextLong(TupleType.values().length))
                        .findFirst()
                        .ifPresent(t -> {
                                    var jobId = UUID.randomUUID();
                                    var rosterKey = new JobRosterKey(jobId);
                                    var jobParams = new JobParameters(t);
                                    kv.put(rosterKey.toEtcdKey(), jobParams.toByteSequence())
                                            .thenRun(() -> Log.infof(
                                                    "Roster created for job with tuple type %s and ID %s", t, jobId));
                                }
                        );
            });
        } catch (Exception e) {
            Log.error("Error occurred", e);
        }
    }

    @Override
    public void onNext(WatchResponse watchResponse) {
        if (Log.isDebugEnabled()) {
            Log.debugf("Etcd watch triggered for keys: ",
                    watchResponse.getEvents().stream().map(e -> e.getKeyValue().getKey()).collect(Collectors.toList()));
        }
        for (var event : watchResponse.getEvents()) {
            ByteSequence key = event.getKeyValue().getKey();
            Log.infof("Processing %s event for key %s", event.getEventType(), key);
            Key.fromEtcdKeyOptional(key, JobRosterEntryKey.class).ifPresent(k -> {
                UUID jobId = k.jobId();
                Log.infof("Processing roster update event on job with ID %s", jobId);
                var rosterKey = k.toEtcdParentKey().get();
                etcdClient.getKVClient().get(rosterKey, GetOption.newBuilder().isPrefix(true).build()).thenCompose(r -> {
                    long completed = r.getKvs().stream().filter(kv -> kv.getValue().toString().equals(
                            JobManager.JobState.COMPLETED.toString())).count();
                    Log.debugf("Roster for job with ID %s updated (%d/%d jobs completed)", jobId, r.getCount(),
                            NUMBER_OF_PARTIES);
                    if (completed == NUMBER_OF_PARTIES) {
                        Log.infof("Jobs on all parties completed for ID %s", jobId);
                        return etcdClient.getKVClient().delete(rosterKey,
                                DeleteOption.newBuilder().isPrefix(true).build()).thenRun(() ->
                                Log.infof("Job roster for ID %s deleted", jobId));
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                }).join();
            });
        }
    }

    @Override
    public void onError(Throwable throwable) {
        Log.errorf(throwable, "Error for watch on scheduler %s", schedulerResource.getFullResourceName());
    }

    @Override
    public void onCompleted() {
        Log.debugf("Watch result processing completed on scheduler %s", schedulerResource.getFullResourceName());
    }

}
