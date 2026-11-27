package com.example.pdfconverter.controller;

import com.example.pdfconverter.dto.ConversionResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrationstest für den {@link ConversionController}.
 * Startet S3Mock als S3-Ersatz per Testcontainers (Docker erforderlich).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversionControllerIT {

    private static final String BUCKET = "pdf-conversion-artifacts";
    private static final String REGION = "eu-central-1";

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:latest")
            .withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
            .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));

    @BeforeAll
    static void prepareCredentials() {
        // Der Service nutzt DefaultCredentialsProvider -> liest u. a. diese System Properties
        // (S3Mock validiert die Credentials nicht)
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    private static String s3Url() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.s3.endpoint", ConversionControllerIT::s3Url);
        registry.add("app.s3.public-endpoint", ConversionControllerIT::s3Url);
        registry.add("app.s3.bucket-name", () -> BUCKET);
        registry.add("app.s3.region", () -> REGION);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void convertPng_returnsDownloadLinkToValidSinglePagePdf() throws Exception {
        byte[] png = createPng(400, 200);

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "test.png";
            }
        }, fileHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<ConversionResponse> response = restTemplate.postForEntity(
                "/api/v1/convert/image-to-pdf",
                new HttpEntity<>(body, headers),
                ConversionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ConversionResponse result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.downloadUrl()).isNotBlank();
        assertThat(result.s3Bucket()).isEqualTo(BUCKET);
        assertThat(result.metadata().originalFilename()).isEqualTo("test.png");
        assertThat(result.metadata().sourceMimeType()).isEqualTo("image/png");
        assertThat(result.metadata().pageCount()).isEqualTo(1);
        assertThat(result.metadata().pdfStandard()).isEqualTo("PDF/A-2b");

        // Presigned URL abrufen
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(result.downloadUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(download.statusCode()).isEqualTo(200);
        byte[] pdfBytes = download.body();
        assertThat(pdfBytes).hasSize((int) result.metadata().fileSizeBytes());
        assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void nonImageFile_returnsUnsupportedMediaType() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("kein Bild".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "text.txt";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/convert/image-to-pdf",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private static byte[] createPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | 180);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
