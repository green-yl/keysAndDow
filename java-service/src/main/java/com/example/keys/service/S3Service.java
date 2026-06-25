package com.example.keys.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Service
public class S3Service {
    private static final Logger log = LoggerFactory.getLogger(S3Service.class);

    @Value("${app.s3.enabled:false}")
    private boolean enabled;
    @Value("${app.s3.bucket:}")
    private String bucket;
    @Value("${app.s3.region:}")
    private String region;
    @Value("${app.s3.prefix:artifacts/}")
    private String prefix;
    @Value("${app.s3.access-key-id:}")
    private String accessKeyId;
    @Value("${app.s3.secret-access-key:}")
    private String secretAccessKey;

    private volatile S3Client s3Client;

    @PostConstruct
    public void init() {
        if (isEnabled()) {
            s3Client = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                    .build();
            log.info("S3 client initialized, bucket={}, region={}", bucket, region);
        } else {
            log.info("S3 is disabled, using local storage only");
        }
    }

    @PreDestroy
    public void destroy() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    public boolean isEnabled() { return enabled && !bucket.isBlank() && !region.isBlank(); }

    public String putObject(String key, InputStream in, long size, String contentType) throws Exception {
        if (!isEnabled() || s3Client == null) return null;
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + key)
                .contentType(contentType)
                .build();
        s3Client.putObject(req, RequestBody.fromInputStream(in, size));
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + prefix + key;
    }

    public void deleteObject(String key) {
        if (!isEnabled() || s3Client == null || key == null || key.isBlank()) return;
        DeleteObjectRequest req = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + key)
                .build();
        s3Client.deleteObject(req);
    }
}






