/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.carbynestack.castor.client.download.CastorIntraVcpClient;
import io.carbynestack.castor.client.download.DefaultCastorIntraVcpClient;
import io.carbynestack.castor.common.entities.TupleType;
import io.carbynestack.klyshko.operator.scheduler.Scheduler;
import io.etcd.jetcd.*;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.carbynestack.klyshko.operator.KlyshkoOperator.ETCD_PREFIX;
import static io.carbynestack.klyshko.operator.scheduler.hysteresis.VcpJobManager.JobState;
import static org.zalando.fauxpas.FauxPas.throwingConsumer;
import static org.zalando.fauxpas.FauxPas.throwingFunction;

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
 * of {@link VcpJobManager}. Job rosters on which the job on all VCPs have been completed are removed by removing the
 * respective K/V entry in etcd.
 * <p>
 * The number of (per type) tuple generation jobs is limited by configurable upper limit.
 */
public class HysteresisScheduler implements Closeable, io.etcd.jetcd.Watch.Listener {

    static final int NUMBER_OF_PARTIES = 2; // TODO Read from VC configuration as soon as available

    private enum State {
        IDLE, GENERATING
    }

    private final ObjectMapper objectMapper;
    private final KubernetesClient k8sClient;
    private final ConcurrentMap<TupleType, State> states;
    private final Scheduler schedulerResource;
    private final Client etcdClient;
    private final VcpJobManager vcpJobManager;
    private final Optional<Watch.Watcher> jobWatcher;
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    public HysteresisScheduler(KubernetesClient k8sClient, Scheduler schedulerResource, ObjectMapper objectMapper) {
        this.k8sClient = k8sClient;
        this.schedulerResource = schedulerResource;
        this.objectMapper = objectMapper;
        states = new ConcurrentHashMap<>();
        Arrays.stream(TupleType.values()).forEach(t -> states.put(t, State.IDLE));
        String etcdEndpoint = String.format("http://%s:%d", schedulerResource.getSpec().etcdEndpoint(), 2379);
        Log.infof("Using etcd at endpoint %s", etcdEndpoint);
        etcdClient = Client.builder().endpoints(etcdEndpoint).namespace(ByteSequence.from(ETCD_PREFIX,
                StandardCharsets.UTF_8)).build();
        vcpJobManager = new VcpJobManager(k8sClient, etcdClient, schedulerResource, objectMapper);
        boolean isMaster = schedulerResource.getSpec().master();
        if (isMaster) {
            jobWatcher = Optional.of(etcdClient.getWatchClient().watch(new JobRosterDirectoryKey().toEtcdKey(),
                    WatchOption.newBuilder().isPrefix(true).withNoDelete(true).build(), this));
            ses.scheduleWithFixedDelay(this::controlLoop, 0, 5, TimeUnit.SECONDS); // TODO: Make configurable
            ses.scheduleWithFixedDelay(this::schedule, 0, 10, TimeUnit.SECONDS); // TODO: Make configurable
        } else {
            jobWatcher = Optional.empty();
        }
        Log.infof("Scheduler instance %s created, acting as %s", schedulerResource.getMetadata().getName(), isMaster ?
                "master" : "follower");
    }

    @Override
    public void close() {
        ses.shutdown();
        jobWatcher.ifPresent(Watch.Watcher::close);
        vcpJobManager.close();
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
            Log.debugf("Castor service discovered at endpoint %s", castorEndpoint);
            CastorIntraVcpClient castorIntraVcpClient =
                    DefaultCastorIntraVcpClient.builder(castorEndpoint).withoutSslCertificateValidation().build();
            var telemetryData = castorIntraVcpClient.getTelemetryData();
            if (Log.isDebugEnabled()) {
                Log.debugf("Telemetry data received: %s", telemetryData);
            }
            for (var m : telemetryData.getMetrics()) {
                var currentState = states.getOrDefault(m.getType(), State.IDLE);
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
                var generating =
                        states.entrySet().stream().filter(e -> State.GENERATING.equals(e.getValue())).map(Map.Entry::getKey).toList();
                if (!generating.isEmpty()) {
                    generating.stream()
                            .skip(ThreadLocalRandom.current().nextLong(generating.size()))
                            .findFirst()
                            .ifPresent(throwingConsumer(t -> {
                                var rosterKey = new JobRosterKey(UUID.randomUUID());
                                var jobData = new JobData(new JobParameters(t), JobState.CREATED);
                                var rosterKeyEntryOps = IntStream.range(0, NUMBER_OF_PARTIES)
                                        .mapToObj(p -> Op.put(
                                                rosterKey.getJobRosterEntryKey(p).toEtcdKey(),
                                                ByteSequence.from(JobState.CREATED.toString(), StandardCharsets.UTF_8),
                                                PutOption.DEFAULT))
                                        .toArray(Op[]::new);
                                kv.txn()
                                        .If(new Cmp(rosterKey.toEtcdKey(), Cmp.Op.GREATER, CmpTarget.version(0)))
                                        .Else(Op.put(rosterKey.toEtcdKey(),
                                                ByteSequence.from(objectMapper.writeValueAsBytes(jobData)),
                                                PutOption.DEFAULT))
                                        .Else(rosterKeyEntryOps)
                                        .commit()
                                        .thenAccept(r -> {
                                            var level = r.isSucceeded() ? Logger.Level.INFO : Logger.Level.ERROR;
                                            Log.logf(level,
                                                    "Roster creation for job with tuple type %s and ID %s %s",
                                                    t, rosterKey.jobId(), r.isSucceeded() ? "successful" : "failed");
                                        });
                            }));
                }
            }).join();
        } catch (Exception e) {
            Log.error("Error occurred", e);
        }
    }

    private JobState computeVcJobState(List<JobState> vcpJobState) {
        var vcJobState = JobState.RUNNING;
        if (vcpJobState.stream().anyMatch(s -> s == JobState.FAILED)) {
            vcJobState = JobState.FAILED;
        } else if (vcpJobState.stream().allMatch(s -> s == JobState.SUCCEEDED)) {
            vcJobState = JobState.SUCCEEDED;
        }
        return vcJobState;
    }

    @Override
    public void onNext(WatchResponse watchResponse) {
        if (Log.isDebugEnabled()) {
            Log.debugf("Etcd watch triggered for keys: %s",
                    watchResponse.getEvents().stream().map(e -> e.getKeyValue().getKey()).collect(Collectors.toList()));
        }
        for (var event : watchResponse.getEvents()) {
            ByteSequence key = event.getKeyValue().getKey();
            Log.debugf("Processing %s event for key %s", event.getEventType(), key);
            Key.fromEtcdKeyOptional(key, JobRosterEntryKey.class).ifPresentOrElse(k -> {
                UUID jobId = k.jobId();
                Log.debugf("Processing roster update event on job with ID %s", jobId);
                var rosterKey = k.toEtcdParentKey().get();
                var rosterPrefixKey = k.toEtcdPrefixKey().get();
                var kvClient = etcdClient.getKVClient();
                kvClient.get(rosterPrefixKey, GetOption.newBuilder().isPrefix(true).build())
                        .thenApply(r -> {
                            // Derive VC job state from VCP job states
                            var vcpJobStates =
                                    r.getKvs().stream().map(kv -> JobState.valueOf(kv.getValue().toString(StandardCharsets.UTF_8))).collect(Collectors.toList());
                            var newVcJobState = computeVcJobState(vcpJobStates);
                            if (Log.isDebugEnabled()) {
                                Log.debugf("New VC job state '%s' computed from VCP job states '%s'", newVcJobState,
                                        vcpJobStates);
                            }
                            return newVcJobState;
                        })
                        .thenCombine(
                                // Update the VC job state stored in roster to new state
                                kvClient.get(rosterKey)
                                        .thenApply(r -> r.getKvs().stream().findFirst()
                                                .map(throwingFunction(v -> objectMapper.readValue(v.getValue().getBytes(), JobData.class)))),
                                (newVcJobState, jobData) -> {
                                    jobData.ifPresent(throwingConsumer(jd -> {
                                        if (jd.jobState() != newVcJobState) {
                                            JobData njd = new JobData(jd.jobParameters(), newVcJobState);
                                            kvClient.put(rosterKey,
                                                    ByteSequence.from(objectMapper.writeValueAsBytes(njd))).join();
                                            Log.infof("Roster for job with ID %s updated to new VC job state %s", jobId,
                                                    newVcJobState);
                                        }
                                    }));
                                    return newVcJobState;
                                })
                        .thenCompose(jobState -> {
                            // Delete roster in case VC job was either successful or failed
                            if (EnumSet.of(JobState.SUCCEEDED, JobState.FAILED).contains(jobState)) {
                                Log.infof("Jobs on all parties completed for ID %s with state %s", jobId, jobState);
                                return kvClient.delete(rosterKey,
                                        DeleteOption.newBuilder().isPrefix(true).build()).thenRun(() ->
                                        Log.infof("Job roster for ID %s deleted", jobId));
                            } else {
                                return CompletableFuture.completedFuture(null);
                            }
                        })
                        .exceptionally(t -> {
                            Log.errorf(t, "Roster update for job with ID %s failed", jobId);
                            return null;
                        }).join();
            }, () -> Log.debugf("Skipping as key '%s' is not a job roster entry key", key));
        }

    }

    @Override
    public void onError(Throwable throwable) {
        Log.errorf(throwable, "Error for watch on scheduler %s", schedulerResource.getMetadata().getName());
    }

    @Override
    public void onCompleted() {
        Log.debugf("Watch result processing completed on scheduler %s", schedulerResource.getMetadata().getName());
    }

}
