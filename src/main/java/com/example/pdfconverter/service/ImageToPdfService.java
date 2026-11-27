package com.example.pdfconverter.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.example.pdfconverter.dto.ConversionResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;

@Service
public class ImageToPdfService {

    private static final float MARGIN = 28.35f; // 10 mm Rand in PDF-Punkten (72 DPI)
    private static final String PDF_STANDARD = "PDF/A-2b";

    public ConversionResult convertImageToPdf(
            byte[] imageBytes,
            String title,
            boolean autoRotateOrientation,
            boolean lossless) throws Exception {

        int exifOrientation = readExifOrientation(imageBytes);

        try (PDDocument document = new PDDocument();
             InputStream is = new ByteArrayInputStream(imageBytes);
             ImageInputStream iis = ImageIO.createImageInputStream(is)) {

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Nicht unterstütztes oder defektes Bildformat.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int numPages = reader.getNumImages(true);

                for (int i = 0; i < numPages; i++) {
                    BufferedImage rawImage = reader.read(i);
                    BufferedImage orientedImage = (i == 0) ? applyExifRotation(rawImage, exifOrientation) : rawImage;
                    addImageAsPage(document, orientedImage, autoRotateOrientation, lossless);
                }
            } finally {
                reader.dispose();
            }

            applyPdfA2bCompliance(document, title);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);

            return new ConversionResult(
                    baos.toByteArray(),
                    document.getNumberOfPages(),
                    PDF_STANDARD
            );
        }
    }

    private void addImageAsPage(PDDocument doc, BufferedImage image, boolean autoOrientation, boolean lossless) throws Exception {
        boolean isLandscape = image.getWidth() > image.getHeight();
        PDRectangle pageSize = (autoOrientation && isLandscape)
                ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                : PDRectangle.A4;

        PDPage page = new PDPage(pageSize);
        doc.addPage(page);

        PDImageXObject pdImage = lossless
                ? LosslessFactory.createFromImage(doc, image)
                : JPEGFactory.createFromImage(doc, image, 0.85f);

        float availWidth = pageSize.getWidth() - (2 * MARGIN);
        float availHeight = pageSize.getHeight() - (2 * MARGIN);
        float scale = Math.min(availWidth / pdImage.getWidth(), availHeight / pdImage.getHeight());

        float finalWidth = pdImage.getWidth() * scale;
        float finalHeight = pdImage.getHeight() * scale;
        float x = (pageSize.getWidth() - finalWidth) / 2;
        float y = (pageSize.getHeight() - finalHeight) / 2;

        try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
            contentStream.drawImage(pdImage, x, y, finalWidth, finalHeight);
        }
    }

    private void applyPdfA2bCompliance(PDDocument document, String title) throws Exception {
        // 1. XMP-Metadaten für PDF/A-2b setzen
        XMPMetadata xmp = XMPMetadata.createXMPMetadata();
        DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
        dc.setTitle(title != null ? title : "Converted Image Document");

        PDFAIdentificationSchema id = xmp.createAndAddPDFAIdentificationSchema();
        id.setPart(2);
        id.setConformance("B");

        ByteArrayOutputStream xmpOut = new ByteArrayOutputStream();
        new XmpSerializer().serialize(xmp, xmpOut, true);

        PDMetadata metadata = new PDMetadata(document);
        metadata.importXMPMetadata(xmpOut.toByteArray());
        document.getDocumentCatalog().setMetadata(metadata);

        // 2. sRGB ICC Farbprofil als OutputIntent einbetten
        ICC_Profile srgbProfile = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
        try (InputStream iccStream = new ByteArrayInputStream(srgbProfile.getData())) {
            PDOutputIntent intent = new PDOutputIntent(document, iccStream);
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            document.getDocumentCatalog().addOutputIntent(intent);
        }
    }

    private int readExifOrientation(byte[] imageBytes) {
        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            Metadata metadata = ImageMetadataReader.readMetadata(is);
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private BufferedImage applyExifRotation(BufferedImage img, int orientation) {
        int width = img.getWidth();
        int height = img.getHeight();
        AffineTransform transform = new AffineTransform();

        switch (orientation) {
            case 6 -> {
                transform.translate(height, 0);
                transform.rotate(Math.toRadians(90));
            }
            case 3 -> {
                transform.translate(width, height);
                transform.rotate(Math.toRadians(180));
            }
            case 8 -> {
                transform.translate(0, width);
                transform.rotate(Math.toRadians(270));
            }
            default -> {
                return img;
            }
        }

        boolean swapDimensions = (orientation == 6 || orientation == 8);
        BufferedImage rotated = new BufferedImage(
                swapDimensions ? height : width,
                swapDimensions ? width : height,
                img.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : img.getType()
        );
        AffineTransformOp op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);
        return op.filter(img, rotated);
    }
}