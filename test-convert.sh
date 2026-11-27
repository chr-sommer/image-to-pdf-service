#!/usr/bin/env bash
set -euo pipefail

DEMO_IMAGE="demo-landscape.png"
OUTPUT_PDF="ergebnis.pdf"
API_URL="http://localhost:8080/api/v1/convert/image-to-pdf"

echo "1. Erzeuge Demo-Bild (${DEMO_IMAGE}, 800x400 Querformat)..."
python3 -c "
import zlib, struct
width, height = 800, 400
# Farbverlauf erzeugen (RGBA)
raw_data = b''.join(
    b'\x00' + b''.join(struct.pack('BBBB', x % 256, y % 256, 180, 255) for x in range(width))
    for y in range(height)
)
def chunk(tag, data):
    return struct.pack('!I', len(data)) + tag + data + struct.pack('!I', zlib.crc32(tag + data) & 0xffffffff)

png = b'\x89PNG\r\n\x1a\n' + \
      chunk(b'IHDR', struct.pack('!IIBBBBB', width, height, 8, 6, 0, 0, 0)) + \
      chunk(b'IDAT', zlib.compress(raw_data)) + \
      chunk(b'IEND', b'')

with open('${DEMO_IMAGE}', 'wb') as f:
    f.write(png)
"

echo "2. Sende Bild an REST-Service..."
RESPONSE=$(curl -s -X POST "${API_URL}?autoOrientation=true&lossless=true" \
  -H "Accept: application/json" \
  -F "file=@${DEMO_IMAGE};type=image/png")

echo "3. JSON-Response vom Server:"
echo "${RESPONSE}" | python3 -m json.tool

# Presigned URL extrahieren
DOWNLOAD_URL=$(echo "${RESPONSE}" | python3 -c "import sys, json; print(json.load(sys.stdin)['downloadUrl'])")

echo "4. Lade fertiges PDF über Presigned S3 URL herunter..."
curl -s -o "${OUTPUT_PDF}" "${DOWNLOAD_URL}"

echo "Fertig! Gespeichert als '${OUTPUT_PDF}' ($(wc -c < "${OUTPUT_PDF}") Bytes)."
echo "MinIO Web-Konsole erreichbar unter: http://localhost:9001 (Login: minioadmin / minioadmin)"