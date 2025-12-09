package com.example.keys.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;


@Service
public class S3Service {
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

    private S3Client buildClient() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }

    public boolean isEnabled() { return enabled && !bucket.isBlank() && !region.isBlank(); }

    public String putObject(String key, InputStream in, long size, String contentType) throws Exception {
        if (!isEnabled()) return null;
        S3Client s3 = buildClient();
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + key)
                .contentType(contentType)
                .build();
        s3.putObject(req, RequestBody.fromInputStream(in, size));
        // 返回可推断的 URL（若开启静态托管或CDN可替换）
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + prefix + key;
    }
}







