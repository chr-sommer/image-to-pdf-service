package com.example.pdfconverter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String bucketName,
        String region,
        String endpoint,
        String publicEndpoint,
        boolean pathStyleAccess,
        long presignedUrlExpirationMinutes
) {}