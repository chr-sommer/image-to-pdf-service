package com.example.pdfconverter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        return S3Client.builder()
                .region(Region.of(props.region()))
                .endpointOverride(URI.create(props.endpoint()))
                .forcePathStyle(props.pathStyleAccess())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(S3Properties props) {
        String presignEndpoint = (props.publicEndpoint() != null && !props.publicEndpoint().isBlank())
                ? props.publicEndpoint()
                : props.endpoint();

        return S3Presigner.builder()
                .region(Region.of(props.region()))
                .endpointOverride(URI.create(presignEndpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.pathStyleAccess())
                        .build())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}