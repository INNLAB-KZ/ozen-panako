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
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250
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

Duplicate check works the same as `POST /api/v1/store` (returns `already_exists` with `duration_seconds` and `fingerprints_count`).

### `POST /api/v1/store/fingerprints`

Store pre-computed fingerprints directly without sending audio. Accepts `application/json`.

This is useful when fingerprints are extracted on the client side (e.g. via `panako print`) and only the compact fingerprint data needs to be sent to the server.

```bash
curl -X POST http://localhost:8080/api/v1/store/fingerprints \
  -H "Content-Type: application/json" \
  -d '{
    "filename": "USRC17607839.mp3",
    "duration": 195.4,
    "fingerprints": [
      {"hash": 123456789, "t1": 10, "f1": 200},
      {"hash": 987654321, "t1": 20, "f1": 150}
    ]
  }'
```

- `filename` — required, used to derive identifier and ISRC
- `duration` — required, audio duration in seconds
- `identifier` — optional, if omitted it is derived from the filename
- `fingerprints` — required, array of `{hash, t1, f1}` objects

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 2,
  "processing_time_ms": 5
}
```

Duplicate check works the same as other store endpoints.

### `POST /api/v1/query`

Query for matches. Accepts `multipart/form-data` with field name `audio`.

### `POST /api/v1/query/fingerprints`

Query for matches using pre-computed fingerprints (no audio needed). Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/query/fingerprints \
  -H "Content-Type: application/json" \
  -d '{
    "fingerprints": [
      {"hash": 123456789, "t1": 10, "f1": 200},
      {"hash": 987654321, "t1": 20, "f1": 150}
    ]
  }'
```

Response format is identical to `POST /api/v1/query`.

Results are validated to filter false positives:
- **Match density**: short clips (< 15s) need >= 8 matches; longer clips need >= max(duration x 0.15, 20)
- **Duration check**: query must not be longer than matched reference + 5 seconds

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

### `POST /api/v1/monitor`

Monitor a long audio file (e.g. radio recording) and find all matching tracks. The audio is split into overlapping windows (default 25s with 5s overlap) and each window is queried separately. Results are **deduplicated by track** — multiple window hits for the same track are merged into a single entry with the overall time range. Accepts `multipart/form-data` with field name `audio`.

```bash
curl -X POST http://localhost:8080/api/v1/monitor -F "audio=@radio_recording.mp3"
```

Response:

```json
{
  "status": "ok",
  "processing_time_ms": 15230,
  "unique_tracks_count": 2,
  "matches": [
    {
      "identifier": 1612789453,
      "isrc": "USRC17607839",
      "filename": "USRC17607839.mp3",
      "query_start_seconds": 60.0,
      "query_end_seconds": 255.4,
      "match_start_seconds": 0.0,
      "match_end_seconds": 195.4,
      "score": 450,
      "time_factor": 1.001,
      "frequency_factor": 1.000,
      "match_percentage": 85.0,
      "window_hits": 3
    },
    {
      "identifier": 987654321,
      "isrc": "GBAYE0601498",
      "filename": "GBAYE0601498.mp3",
      "query_start_seconds": 300.0,
      "query_end_seconds": 520.0,
      "match_start_seconds": 10.2,
      "match_end_seconds": 230.0,
      "score": 600,
      "time_factor": 1.000,
      "frequency_factor": 1.000,
      "match_percentage": 90.5,
      "window_hits": 5
    }
  ]
}
```

- `unique_tracks_count` — number of distinct tracks identified
- `query_start_seconds` / `query_end_seconds` — when the track starts/ends in your recording
- `match_start_seconds` / `match_end_seconds` — matched region in the reference track
- `score` — total score across all windows
- `window_hits` — how many monitoring windows matched this track

### `POST /api/v1/monitor/url`

Monitor audio downloaded from a URL. Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/monitor/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/radio_recording.mp3"}'
```

- `audio_url` — required, URL to download the audio from
- `filename` — optional, if omitted it is derived from the URL path

Response format is identical to `POST /api/v1/monitor`.

### `POST /api/v1/monitor/fingerprints`

Monitor with pre-computed fingerprints. Splits fingerprints into overlapping time windows and queries each window. Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/monitor/fingerprints \
  -H "Content-Type: application/json" \
  -d '{
    "fingerprints": [
      {"hash": 123456789, "t1": 10, "f1": 200},
      {"hash": 987654321, "t1": 20, "f1": 150},
      {"hash": 555555555, "t1": 5000, "f1": 180}
    ]
  }'
```

Response format is identical to `POST /api/v1/monitor`.

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
