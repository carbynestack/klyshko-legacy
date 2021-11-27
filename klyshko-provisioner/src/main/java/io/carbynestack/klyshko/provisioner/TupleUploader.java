package io.carbynestack.klyshko.provisioner;

import io.carbynestack.castor.client.upload.CastorUploadClient;
import io.carbynestack.castor.client.upload.DefaultCastorUploadClient;
import io.carbynestack.castor.common.entities.TupleChunk;
import io.carbynestack.castor.common.entities.TupleType;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
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

    // TODO Handle failure cases
    @PreDestroy
    public void destroy() throws InterruptedException {
        Log.info("Shutdown sequence initiated");
        jobId.ifPresentOrElse(jid -> {
            tupleType.ifPresentOrElse(tt -> {
                TupleType tupleType =
                        Arrays.stream(TupleType.values()).filter(t -> t.toString().equals(tt)).findFirst().get(); //
                // Improve handling
                getCastorUploadClient().ifPresentOrElse(c -> {
                    UUID chunkId = UUID.fromString(jid);
                    try {
                        uploadTuples(c, chunkId, tupleType);
                        Log.infof("Tuples with chunk identifier '%s' successfully uploaded to Castor", chunkId);
                    } catch (IOException ioe) {
                        Log.errorf(ioe, "Upload failed for tuple chunk with identifier '%s'", chunkId);
                    }
                }, () -> Log.warnf("Skipping tuple chunk upload for job with id '%s' due to unavailable Castor service",
                        jobId));
            }, () -> Log.error("Tuple type must be supplied using environment variable 'KII_TUPLE_TYPE"));
        }, () -> Log.error("Job identifier must be supplied using environment variable 'KII_JOB_ID"));
    }

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

    long headerLength(FileInputStream fis) throws IOException {
        byte[] buffer = fis.readNBytes(Long.BYTES);
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    void uploadTuples(CastorUploadClient castorUploadClient, UUID chunkId, TupleType tupleType) throws IOException {
        File tupleFile = new File("/kii/tuples");
        try (FileInputStream fileInputStream = new FileInputStream(tupleFile)) {
            long headerLength = headerLength(fileInputStream);
            Log.infof("Tuple file contains %d bytes with %d byte header", tupleFile.length(), headerLength);
            IOUtils.skip(fileInputStream, headerLength); // Skip header
            var tupleChunk = TupleChunk.of(tupleType.getTupleCls(),
                    tupleType.getField(),
                    chunkId,
                    toByteArray(fileInputStream));
            castorUploadClient.connectWebSocket(5000);
            castorUploadClient.uploadTupleChunk(tupleChunk);
        }
    }

}
