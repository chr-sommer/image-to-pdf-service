package com.example.pdfconverter.controller;

import com.example.pdfconverter.dto.ConversionResponse;
import com.example.pdfconverter.dto.ConversionResult;
import com.example.pdfconverter.service.ImageToPdfService;
import com.example.pdfconverter.service.S3StorageService;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/convert")
public class ConversionController {

    private final ImageToPdfService pdfService;
    private final S3StorageService s3StorageService;
    private final Tika tika = new Tika();

    public ConversionController(ImageToPdfService pdfService, S3StorageService s3StorageService) {
        this.pdfService = pdfService;
        this.s3StorageService = s3StorageService;
    }

    @PostMapping(
            value = "/image-to-pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ConversionResponse> convertImage(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean autoOrientation,
            @RequestParam(defaultValue = "false") boolean lossless) throws Exception {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Die hochgeladene Datei ist leer.");
        }

        byte[] bytes = file.getBytes();
        String detectedMimeType = tika.detect(bytes);

        if (detectedMimeType == null || !detectedMimeType.startsWith("image/") || "image/svg+xml".equals(detectedMimeType)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        ConversionResult result = pdfService.convertImageToPdf(
                bytes,
                file.getOriginalFilename(),
                autoOrientation,
                lossless
        );

        ConversionResponse response = s3StorageService.uploadAndCreateResponse(
                result,
                file.getOriginalFilename(),
                detectedMimeType
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}