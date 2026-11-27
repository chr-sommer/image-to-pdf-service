package com.example.pdfconverter.dto;

import java.time.Instant;

public record ConversionResponse(
        String documentId,
        String s3Bucket,
        String s3Key,
        String downloadUrl,
        Instant urlExpiresAt,
        DocumentMetadata metadata
) {
    public record DocumentMetadata(
            String originalFilename,
            String sourceMimeType,
            long fileSizeBytes,
            int pageCount,
            String pdfStandard,
            String sha256Checksum
    ) {}
}