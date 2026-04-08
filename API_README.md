# Panako HTTP API

Embedded REST API for the Panako acoustic fingerprinting system. No external frameworks — uses JDK built-in `com.sun.net.httpserver`.

Filename is used as stable identifier key — files named `ISRC.mp3`, `ISRC.aac`, `ISRC.m4a` all map to the same identifier via murmurhash3 of the ISRC.

## Build

```bash
./gradlew shadowJar
```

## Run

### HTTP API Server

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp build/libs/panako-2.1-all.jar \
  be.panako.http.PanakoHttpServer
```

With custom parameters:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp build/libs/panako-2.1-all.jar \
  be.panako.http.PanakoHttpServer \
  SERVER_PORT=9090 \
  STRATEGY=OLAF \
  SERVER_THREAD_POOL_SIZE=10
```

### CLI (unchanged)

All existing CLI commands work as before:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar store audio.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar query fragment.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar stats
```

### Docker

```bash
./gradlew shadowJar
docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .
```

Or run with docker compose:

```bash
docker compose up
```

The API will be available at `http://localhost:8344` (mapped to container port 8080).

Database files are persisted in `./data/panako-db`.

## API Endpoints

### `GET /api/v1/health`

Health check.

```bash
curl http://localhost:8080/api/v1/health
```

Response:

```json
{"status": "ok", "version": "2.1-api"}
```

### `GET /api/v1/stats`

Database statistics.

```bash
curl http://localhost:8080/api/v1/stats
```

Response:

```json
{
  "status": "ok",
  "fingerprint_count": 125000,
  "audio_items_count": 50,
  "strategy": "OLAF"
}
```

### `POST /api/v1/store`

Store audio fingerprints. Accepts `multipart/form-data` with field name `audio`.

Filename should be `ISRC.ext` (e.g. `USRC17607839.mp3`). The ISRC is extracted and returned in the response.

```bash
curl -X POST http://localhost:8080/api/v1/store -F "audio=@USRC17607839.mp3"
```

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250,
  "processing_time_ms": 2430
}
```

If the same ISRC is already stored (duplicate):

```json
{
  "status": "already_exists",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3"
}
```

### `POST /api/v1/store/url`

Store audio fingerprints by downloading from a URL. Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/store/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/USRC17607839.mp3", "filename": "USRC17607839.mp3"}'
```

- `audio_url` — required, URL to download the audio from
- `filename` — optional, if omitted it is derived from the URL path

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "audio_url": "https://example.com/USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250,
  "processing_time_ms": 2430
}
```

Duplicate check works the same as `POST /api/v1/store`.

### `POST /api/v1/query`

Query for matches. Accepts `multipart/form-data` with field name `audio`.

```bash
curl -X POST http://localhost:8080/api/v1/query -F "audio=@fragment.mp3"
```

Response:

```json
{
  "status": "ok",
  "query_duration_seconds": 10.5,
  "processing_time_ms": 320,
  "matches": [
    {
      "identifier": 1612789453,
      "isrc": "USRC17607839",
      "filename": "USRC17607839.mp3",
      "match_start_seconds": 45.2,
      "match_end_seconds": 55.7,
      "query_start_seconds": 0.0,
      "query_end_seconds": 10.5,
      "score": 193,
      "time_factor": 1.001,
      "frequency_factor": 1.000,
      "match_percentage": 1.0
    }
  ]
}
```

No match returns `"matches": []`.

### `POST /api/v1/delete`

Delete fingerprints. Accepts `multipart/form-data` with the original audio file.

```bash
curl -X POST http://localhost:8080/api/v1/delete -F "audio=@USRC17607839.mp3"
```

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "deleted": true
}
```

## Configuration

| Parameter | Default | Description |
|---|---|---|
| `SERVER_PORT` | 8080 | HTTP server port |
| `SERVER_MAX_UPLOAD_SIZE_MB` | 100 | Maximum upload file size in MB |
| `SERVER_THREAD_POOL_SIZE` | 10 | HTTP handler thread pool size |
| `STRATEGY` | OLAF | Fingerprinting algorithm (`OLAF` or `PANAKO`) |

Parameters can be passed as CLI arguments (`KEY=VALUE`), system properties (`-DSERVER_PORT=8080`), or environment variables.

## Identifier Logic

The `identifier` is a stable numeric key derived from the filename (without extension):

- `USRC17607839.mp3` → murmurhash3(`"USRC17607839"`) → same ID regardless of format
- `USRC17607839.aac` → same ID
- `1855.mp3` → `1855` (numeric filenames used directly)

This ensures the same ISRC always maps to the same identifier, enabling duplicate detection and consistent query results.

## Concurrency

LMDB allows concurrent reads but only a single writer. Store and delete operations are serialized with a lock. Query and stats requests run concurrently without blocking.

## JVM Flag

`--add-opens=java.base/java.nio=ALL-UNNAMED` is required for LMDB to function. It is already included in the Dockerfile `ENTRYPOINT`.
