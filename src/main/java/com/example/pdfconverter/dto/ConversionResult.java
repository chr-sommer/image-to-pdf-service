package com.example.pdfconverter.dto;

public record ConversionResult(
        byte[] pdfBytes,
        int pageCount,
        String pdfStandard
) {}