package com.tickethub.event.service;

import com.tickethub.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Stores posters in a PRIVATE S3 bucket and hands out short-lived presigned URLs. Credentials come from the default AWS chain. */
@Service
public class PosterStorage {

    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final String bucket;
    private final Region region;
    private S3Client client;
    private S3Presigner presigner;

    public PosterStorage(@Value("${tickethub.s3.bucket:}") String bucket,
                         @Value("${tickethub.s3.region:ap-south-1}") String region) {
        this.bucket = bucket;
        this.region = Region.of(region);
    }

    public boolean configured() {
        return bucket != null && !bucket.isBlank();
    }

    public String upload(Long eventId, MultipartFile file) {
        if (!configured()) {
            throw BusinessException.unavailable("STORAGE_NOT_CONFIGURED", "Poster storage is not configured");
        }
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("FILE_REQUIRED", "A non-empty image file is required");
        }
        String ext = ALLOWED.get(file.getContentType());
        if (ext == null) {
            throw BusinessException.badRequest("UNSUPPORTED_FILE_TYPE", "Only JPEG, PNG or WebP images are allowed");
        }
        String key = "posters/" + eventId + "/" + UUID.randomUUID() + "." + ext;
        try (InputStream in = file.getInputStream()) {
            s3().putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType(file.getContentType()).build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "STORAGE_ERROR",
                    "Could not store the poster");
        }
        return key;
    }

    public String presignedUrl(String key) {
        if (key == null || !configured()) {
            return null;
        }
        try {
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build();
            return presigner().presignGetObject(req).url().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private synchronized S3Client s3() {
        if (client == null) {
            client = S3Client.builder().region(region).build();
        }
        return client;
    }

    private synchronized S3Presigner presigner() {
        if (presigner == null) {
            presigner = S3Presigner.builder().region(region).build();
        }
        return presigner;
    }
}
