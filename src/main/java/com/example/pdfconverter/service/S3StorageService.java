package com.example.pdfconverter.service;

import com.example.pdfconverter.config.S3Properties;
import com.example.pdfconverter.dto.ConversionResponse;
import com.example.pdfconverter.dto.ConversionResult;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class S3StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties props;

    public S3StorageService(S3Client s3Client, S3Presigner s3Presigner, S3Properties props) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.props = props;
    }

    public ConversionResponse uploadAndCreateResponse(
            ConversionResult conversionResult,
            String originalFilename,
            String sourceMimeType) throws Exception {

        String documentId = UUID.randomUUID().toString();
        String datePrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String s3Key = String.format("converted/%s/%s.pdf", datePrefix, documentId);

        byte[] pdfBytes = conversionResult.pdfBytes();
        String sha256 = calculateSha256(pdfBytes);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(props.bucketName())
                .key(s3Key)
                .contentType("application/pdf")
                .contentLength((long) pdfBytes.length)
                .metadata(Map.of(
                        "original-filename", sanitizeFilename(originalFilename),
                        "page-count", String.valueOf(conversionResult.pageCount()),
                        "pdf-standard", conversionResult.pdfStandard(),
                        "sha256", sha256
                ))
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(pdfBytes));

        Duration expiration = Duration.ofMinutes(props.presignedUrlExpirationMinutes());
        Instant expiresAt = Instant.now().plus(expiration);

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(props.bucketName())
                .key(s3Key)
                .responseContentDisposition("inline; filename=\"" + replaceExtensionWithPdf(originalFilename) + "\"")
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .getObjectRequest(getRequest)
                .build();

        String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString();

        ConversionResponse.DocumentMetadata metadata = new ConversionResponse.DocumentMetadata(
                originalFilename,
                sourceMimeType,
                pdfBytes.length,
                conversionResult.pageCount(),
                conversionResult.pdfStandard(),
                sha256
        );

        return new ConversionResponse(
                documentId,
                props.bucketName(),
                s3Key,
                presignedUrl,
                expiresAt,
                metadata
        );
    }

    private String calculateSha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }

    private String sanitizeFilename(String name) {
        return name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String replaceExtensionWithPdf(String filename) {
        if (filename == null || filename.isBlank()) return "converted.pdf";
        int dotIndex = filename.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? filename.substring(0, dotIndex) : filename;
        return sanitizeFilename(baseName) + ".pdf";
    }
}