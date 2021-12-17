/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.provisioner;

import io.carbynestack.castor.client.upload.CastorUploadClient;
import io.carbynestack.castor.client.upload.DefaultCastorUploadClient;
import io.carbynestack.castor.common.entities.TupleChunk;
import io.carbynestack.castor.common.entities.TupleType;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.apache.commons.io.IOUtils.toByteArray;

@Singleton
public class TupleUploader {

    @Inject
    KubernetesClient k8sClient;

    @ConfigProperty(name = "kii.job.id")
    Optional<String> jobId;

    @ConfigProperty(name = "kii.tuple-type")
    Optional<String> tupleType;

    @ConfigProperty(name = "kii.tuple-file")
    Optional<String> tupleFilePath;

    private Option<Tuple2<UUID, TupleType>> getUploadArguments() {
        var jobId = Option.ofOptional(this.jobId)
                .onEmpty(() -> Log.error("Job identifier must be supplied using environment variable 'KII_JOB_ID"))
                .flatMap(jid ->
                        Try.of(() -> UUID.fromString(jid))
                                .onFailure((t) ->
                                        Log.errorf(t, "Supplied job identifier is invalid: %s", jid))
                                .toOption());
        var tupleType = Option.ofOptional(this.tupleType)
                .onEmpty(() -> Log.error("Tuple type must be supplied using environment variable 'KII_TUPLE_TYPE"))
                .flatMap(tt -> Option.ofOptional(Arrays.stream(TupleType.values())
                                .filter(t -> t.toString().equals(tt))
                                .findFirst())
                        .onEmpty(() -> Log.errorf("Unknown tuple type (%s). Must be one of %s", tt,
                                TupleType.values())));
        return jobId.flatMap(jid -> tupleType.map(tt -> new Tuple2<>(jid, tt)));
    }

    // TODO: Signal failure to main
    @PreDestroy
    public void destroy() {
        Log.info("Shutdown sequence initiated");
        getUploadArguments()
                .onEmpty(() ->
                        Log.warn("Skipping tuple chunk upload for job due to missing arguments"))
                .peek(args -> {
                    var chunkId = args._1;
                    var tupleType = args._2;
                    getCastorUploadClient()
                            .onEmpty(() -> Log.warnf("Skipping tuple chunk upload for job with id '%s' due to " +
                                    "unavailable Castor service", jobId))
                            .peek(client -> Try.run(() -> uploadTuples(client, chunkId, tupleType))
                                    .onSuccess(c -> Log.infof("Tuples with chunk identifier '%s' successfully " +
                                                    "uploaded to Castor",
                                            chunkId))
                                    .onFailure(t -> Log.errorf(t, "Upload failed for tuple chunk with identifier '%s'",
                                            chunkId)));
                });
    }

    private Option<String> getUrl(Service service) {
        var spec = service.getSpec();
        return Option.ofOptional(spec.getPorts().stream().findFirst())
                .onEmpty(() -> Log.warnf("No port available for castor service '%s'.",
                        service.getMetadata().getName()))
                .map(ServicePort::getPort)
                .map(p -> String.format("http://%s:%d", spec.getClusterIP(), p));
    }

    private Option<CastorUploadClient> getCastorUploadClient() {
        var castorServiceName = "cs-castor"; // TODO Make service name configurable
        var castorService = k8sClient.services().withName(castorServiceName);
        if (!castorService.isReady()) {
            Log.warnf("Castor service with name '%s' not available", castorServiceName);
            return Option.none();
        }
        return getUrl(castorService.get())
                .peek(url -> Log.infof("Castor service discovered at URL %s", url))
                .map(url -> DefaultCastorUploadClient.builder(url).withoutSslCertificateValidation().build());
    }

    private void uploadTuples(CastorUploadClient castorUploadClient, UUID chunkId, TupleType tupleType) throws IOException {
        File tupleFile = new File(tupleFilePath.orElse("/kii/tuples")); // TODO Extract constant
        try (FileInputStream fileInputStream = new FileInputStream(tupleFile)) {
            skipHeader(tupleFile, fileInputStream);
            var tupleChunk = TupleChunk.of(tupleType.getTupleCls(),
                    tupleType.getField(),
                    chunkId,
                    toByteArray(fileInputStream));
            castorUploadClient.connectWebSocket(5000);
            castorUploadClient.uploadTupleChunk(tupleChunk);
        }
    }

    private long readHeaderLength(FileInputStream fis) throws IOException {
        byte[] buffer = fis.readNBytes(Long.BYTES);
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private void skipHeader(File tupleFile, FileInputStream fileInputStream) throws IOException {
        long headerLength = readHeaderLength(fileInputStream);
        Log.debugf("Tuple file contains %d bytes with %d byte header", tupleFile.length(), headerLength);
        IOUtils.skip(fileInputStream, headerLength);
    }

}
