# image-to-pdf-service

REST-Service, der Bilder (JPEG, PNG, TIFF, WebP, BMP u. a.) in **PDF/A-2b**-Dokumente konvertiert, in einem S3-kompatiblen Speicher (z. B. MinIO) ablegt und eine zeitlich begrenzte **Presigned Download-URL** zurückliefert.

## Features

- Konvertierung von Bild zu PDF/A-2b (XMP-Metadaten, eingebettetes sRGB-ICC-Profil als OutputIntent)
- Mehrseitige Bilder (z. B. Multi-Page-TIFF) werden zu mehrseitigen PDFs
- Format-Erkennung über Magic Bytes (Apache Tika) statt über Dateiendung/Content-Type; SVG wird abgelehnt
- Automatische Korrektur der EXIF-Ausrichtung (Orientation 3, 6, 8)
- Automatische Seitenausrichtung (Querformat-Bilder → A4 quer)
- Verlustbehaftet (JPEG, Qualität 0,85) oder verlustfrei
- Upload nach S3 inkl. SHA-256-Prüfsumme, Rückgabe einer Presigned URL
- Spring Boot Actuator (Health-Endpoint)

## Technologie-Stack

| Bereich | Technologie |
|---|---|
| Laufzeit | Java 21, Spring Boot 3.4.0 |
| PDF | Apache PDFBox 3.0.3, XMPBox |
| Bildformate | TwelveMonkeys ImageIO 3.12.0 (TIFF, WebP, JPEG, BMP) |
| EXIF | metadata-extractor 2.19.0 |
| MIME-Erkennung | Apache Tika 3.0.0 |
| Speicher | AWS SDK v2 (S3), lokal MinIO |

## Schnellstart

### Mit Docker Compose (empfohlen)

```bash
docker compose up --build
```

Startet drei Container:

| Dienst | Zweck | Port |
|---|---|---|
| `minio` | lokaler S3-Speicher | 9000 (API), 9001 (Web-Konsole) |
| `minio-init` | legt den Bucket `pdf-conversion-artifacts` an | – |
| `pdf-converter` | dieser Service | 8080 |

MinIO-Konsole: <http://localhost:9001> (Login `minioadmin` / `minioadmin`).

### Lokal ohne Docker (Service)

Voraussetzungen: JDK 21, Maven 3.9+ und ein erreichbarer S3-Speicher.

```bash
# nur MinIO + Bucket-Initialisierung starten
docker compose up minio minio-init

# Service starten (Zugangsdaten für S3 über Standard-AWS-Variablen)
export AWS_ACCESS_KEY_ID=minioadmin
export AWS_SECRET_ACCESS_KEY=minioadmin
mvn spring-boot:run
```

PowerShell: `$env:AWS_ACCESS_KEY_ID="minioadmin"; $env:AWS_SECRET_ACCESS_KEY="minioadmin"`

## API

### `POST /api/v1/convert/image-to-pdf`

`multipart/form-data`

| Parameter | Typ | Default | Beschreibung |
|---|---|---|---|
| `file` | Datei (Pflicht) | – | Das zu konvertierende Bild |
| `autoOrientation` | boolean (Query) | `true` | Querformat-Bilder auf A4 quer platzieren |
| `lossless` | boolean (Query) | `false` | Verlustfreie Einbettung statt JPEG (Qualität 0,85) |

Beispiel:

```bash
curl -X POST "http://localhost:8080/api/v1/convert/image-to-pdf?autoOrientation=true&lossless=false" \
  -F "file=@foto.jpg"
```

Antwort `201 Created`:

```json
{
  "documentId": "…",
  "s3Bucket": "pdf-conversion-artifacts",
  "s3Key": "…",
  "downloadUrl": "http://localhost:9000/…?X-Amz-Signature=…",
  "urlExpiresAt": "2026-01-01T12:15:00Z",
  "metadata": {
    "originalFilename": "foto.jpg",
    "sourceMimeType": "image/jpeg",
    "fileSizeBytes": 123456,
    "pageCount": 1,
    "pdfStandard": "PDF/A-2b",
    "sha256Checksum": "…"
  }
}
```

### Fehlercodes

| Status | Ursache |
|---|---|
| `201` | Konvertierung erfolgreich |
| `413` | Datei größer als 25 MB |
| `415` | Datei ist kein Bild (oder SVG) |
| `422` | Leere Datei bzw. nicht unterstütztes/defektes Bildformat |
| `500` | Sonstiger Konvertierungs- oder Speicherfehler |

Fehler (außer `415`) werden als RFC-7807 `ProblemDetail` zurückgegeben.

### Health

`GET /actuator/health`

## Schnelltest

`test-convert.sh` erzeugt ein Demo-Bild (Python 3 erforderlich), sendet es an den Service und lädt das PDF über die Presigned URL herunter:

```bash
./test-convert.sh   # Ergebnis: ergebnis.pdf
```

## Konfiguration

Datei: [src/main/resources/application.yml](src/main/resources/application.yml). Werte lassen sich per Umgebungsvariable überschreiben.

| Variable | Default | Beschreibung |
|---|---|---|
| `APP_S3_BUCKET_NAME` | `pdf-conversion-artifacts` | Ziel-Bucket |
| `APP_S3_REGION` | `eu-central-1` | S3-Region |
| `APP_S3_ENDPOINT` | `http://localhost:9000` | S3-Endpunkt, den der Service selbst nutzt |
| `APP_S3_PUBLIC_ENDPOINT` | `http://localhost:9000` | Endpunkt in der Presigned URL (muss für den Client erreichbar sein) |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | – | S3-Zugangsdaten |

Fest in `application.yml`: Path-Style-Access aktiv, Presigned URL 15 Minuten gültig, Upload-Limit 25 MB pro Datei (30 MB pro Request).

> **Hinweis:** Die `minioadmin`-Zugangsdaten in `docker-compose.yml` sind nur für die lokale Entwicklung gedacht.

## Projektstruktur

```
src/main/java/com/example/pdfconverter/
├── PdfConverterApplication.java
├── config/        S3Config, S3Properties
├── controller/    ConversionController, GlobalExceptionHandler
├── dto/           ConversionResponse, ConversionResult
└── service/       ImageToPdfService, S3StorageService
```

## Build

```bash
mvn clean package          # JAR unter target/
docker build -t image-to-pdf-service .
```

Das Docker-Image nutzt einen Multi-Stage-Build und läuft als Non-Root-User.
