# Panako HTTP API

Embedded REST API for the Panako acoustic fingerprinting system. No external frameworks — uses JDK built-in `com.sun.net.httpserver`.

Filename is used as stable identifier key — files named `ISRC.mp3`, `ISRC.aac`, `ISRC.m4a` all map to the same identifier via murmurhash3 of the ISRC.

## Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
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
  STRATEGY=PANAKO \
  PANAKO_STORAGE=CLICKHOUSE \
  PANAKO_CLICKHOUSE_URL=jdbc:ch://localhost:8123/default
```

### Docker

```bash
docker compose up
```

Stack includes: Panako + ClickHouse + Kafka. API available at `http://localhost:8344`.

Build and push image:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew shadowJar
docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .
```

### CLI (unchanged)

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar store audio.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar query fragment.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar monitor radio.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar stats
```

## API Endpoints

### `GET /api/v1/health`

Health check.

```bash
curl http://localhost:8344/api/v1/health
```

```json
{"status": "ok", "version": "2.1-api"}
```

### `GET /api/v1/stats`

Database statistics.

```bash
curl http://localhost:8344/api/v1/stats
```

```json
{
  "status": "ok",
  "fingerprint_count": 125000,
  "audio_items_count": 50,
  "strategy": "PANAKO"
}
```

### `POST /api/v1/store`

Store audio fingerprints. Accepts `multipart/form-data` with field name `audio`.

Filename should be `ISRC.ext` (e.g. `USRC17607839.mp3`). The ISRC is extracted and returned in the response.

```bash
curl -X POST http://localhost:8344/api/v1/store -F "audio=@USRC17607839.mp3"
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

If the same ISRC is already stored:

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

If a previous store was interrupted (incomplete data), the track is automatically deleted and re-stored.

### `POST /api/v1/store/url`

Store audio by downloading from a URL. Accepts `application/json`.

```bash
curl -X POST http://localhost:8344/api/v1/store/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/USRC17607839.mp3", "filename": "USRC17607839.mp3"}'
```

- `audio_url` — required
- `filename` — optional, derived from URL if omitted

### `POST /api/v1/store/fingerprints`

Store pre-computed fingerprints directly (no audio needed). Accepts `application/json`.

```bash
curl -X POST http://localhost:8344/api/v1/store/fingerprints \
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

### `POST /api/v1/query`

Query for a single match. Accepts `multipart/form-data` with field name `audio`.

Results are validated:
- **Quality**: score >= `MATCH_MIN_SCORE` and match_percentage >= `MATCH_MIN_PERCENTAGE`
- **Density**: short clips (< 15s) need >= 8 matches; longer clips need >= max(duration x 0.15, 20)
- **Duration**: query must not be longer than matched reference + 5 seconds

```bash
curl -X POST http://localhost:8344/api/v1/query -F "audio=@fragment.mp3"
```

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

### `POST /api/v1/query/fingerprints`

Query with pre-computed fingerprints. Accepts `application/json`.

```bash
curl -X POST http://localhost:8344/api/v1/query/fingerprints \
  -H "Content-Type: application/json" \
  -d '{"fingerprints": [{"hash": 123456789, "t1": 10, "f1": 200}]}'
```

### `POST /api/v1/monitor`

Monitor a long audio file and find all matching tracks. Accepts `multipart/form-data` with field name `audio`.

**How it works:**
1. Audio is split into overlapping windows (configurable via `MONITOR_STEP_SIZE` / `MONITOR_OVERLAP`)
2. Each window is queried against the fingerprint database
3. Results are merged by track identifier with absolute timestamps
4. **Boundary refinement**: additional queries to find precise start/end times
5. **Waveform**: peak amplitude per second for the full recording
6. False positives filtered by `MONITOR_MIN_SCORE` and `MONITOR_MIN_PERCENTAGE`

```bash
curl -X POST http://localhost:8344/api/v1/monitor -F "audio=@radio_recording.mp3"
```

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
      "query_start_time": "00:01:00",
      "query_end_seconds": 255.4,
      "query_end_time": "00:04:15",
      "match_start_seconds": 0.0,
      "match_end_seconds": 195.4,
      "score": 4500,
      "time_factor": 1.001,
      "frequency_factor": 1.000,
      "match_percentage": 1.0,
      "window_hits": 9
    }
  ],
  "waveform": [0.125, 0.340, 0.892, 0.654, ...]
}
```

| Field | Description |
|---|---|
| `unique_tracks_count` | Number of distinct tracks identified |
| `query_start_seconds` | When the track starts in your recording (seconds) |
| `query_start_time` | Same in `HH:mm:ss` format |
| `query_end_seconds` | When the track ends in your recording (seconds) |
| `query_end_time` | Same in `HH:mm:ss` format |
| `match_start_seconds` | Where the match starts in the reference track |
| `match_end_seconds` | Where the match ends in the reference track |
| `score` | Total fingerprint hit count across all windows |
| `time_factor` | Time stretching factor (1.0 = no change) |
| `frequency_factor` | Pitch shifting factor (1.0 = no change) |
| `match_percentage` | Fraction of seconds with matching fingerprints (0.0-1.0) |
| `window_hits` | How many monitoring windows matched this track |
| `waveform` | Peak amplitude per second (0.0-1.0) for the full recording |

### `POST /api/v1/monitor/url`

Monitor audio downloaded from a URL. Accepts `application/json`.

```bash
curl -X POST http://localhost:8344/api/v1/monitor/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/radio_recording.mp3"}'
```

### `POST /api/v1/delete`

Delete fingerprints. Accepts `multipart/form-data` with the original audio file.

```bash
curl -X POST http://localhost:8344/api/v1/delete -F "audio=@USRC17607839.mp3"
```

```json
{"status": "ok", "identifier": 1612789453, "deleted": true}
```

## Kafka Integration

When `KAFKA_ENABLED=TRUE`, the server starts a Kafka consumer/producer for async store and monitor operations.

### Topics

| Topic | Direction | Description |
|---|---|---|
| `panako-store-requests` | consume | Store audio by URL |
| `panako-store-results` | produce | Store results |
| `panako-monitor-requests` | consume | Monitor audio by URL |
| `panako-monitor-results` | produce | Monitor results |

### Request format

```json
{
  "audio_url": "https://example.com/track.mp3",
  "filename": "ISRC.mp3",
  "request_id": "optional-correlation-id"
}
```

### Response format

Same as HTTP API responses, with an additional `request_id` field for correlation.

```json
{
  "request_id": "req-001",
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  ...
}
```

### Example

```bash
# Send store request
echo '{"audio_url": "https://example.com/ISRC.mp3", "filename": "ISRC.mp3", "request_id": "req-001"}' | \
  docker exec -i ozen-kafka kafka-console-producer.sh --bootstrap-server localhost:9092 --topic panako-store-requests

# Read results
docker exec ozen-kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic panako-store-results --from-beginning

# Send monitor request
echo '{"audio_url": "https://example.com/radio.mp3", "request_id": "mon-001"}' | \
  docker exec -i ozen-kafka kafka-console-producer.sh --bootstrap-server localhost:9092 --topic panako-monitor-requests

# Read monitor results
docker exec ozen-kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic panako-monitor-results --from-beginning
```

## Configuration

All parameters can be set via environment variables (Docker), CLI arguments (`KEY=VALUE`), or `~/.panako/config.properties`. Priority: CLI args > env vars > config file > defaults.

### Server

| Parameter | Default | Description |
|---|---|---|
| `SERVER_PORT` | 8080 | HTTP server port |
| `SERVER_MAX_UPLOAD_SIZE_MB` | 100 | Maximum upload file size in MB |
| `SERVER_THREAD_POOL_SIZE` | 10 | HTTP handler thread pool size |
| `STRATEGY` | OLAF | Fingerprinting algorithm (`OLAF` or `PANAKO`) |

### Storage

| Parameter | Default | Description |
|---|---|---|
| `OLAF_STORAGE` | LMDB | OLAF storage backend (`LMDB`, `CLICKHOUSE`, `MEM`) |
| `OLAF_CLICKHOUSE_URL` | `jdbc:ch://localhost:8123/default` | ClickHouse JDBC URL for OLAF |
| `PANAKO_STORAGE` | LMDB | PANAKO storage backend (`LMDB`, `CLICKHOUSE`, `MEM`) |
| `PANAKO_CLICKHOUSE_URL` | `jdbc:ch://localhost:8123/default` | ClickHouse JDBC URL for PANAKO |

### Query filtering

| Parameter | Default | Description |
|---|---|---|
| `MATCH_MIN_SCORE` | 20 | Minimum score to accept a query match |
| `MATCH_MIN_PERCENTAGE` | 0.5 | Minimum match percentage (0.0-1.0) for query |

### Monitor

| Parameter | Default | Description |
|---|---|---|
| `MONITOR_STEP_SIZE` | 30 | Window size in seconds |
| `MONITOR_OVERLAP` | 10 | Window overlap in seconds |
| `MONITOR_MIN_SCORE` | 20 | Minimum total score for monitor match |
| `MONITOR_MIN_PERCENTAGE` | 0.5 | Minimum match percentage for monitor |
| `MONITOR_REFINE_THRESHOLD` | 2.0 | Gap (seconds) to trigger boundary refinement |
| `MONITOR_REFINE_CHUNK_SIZE` | 30 | Chunk size for refinement queries |

### Kafka

| Parameter | Default | Description |
|---|---|---|
| `KAFKA_ENABLED` | FALSE | Enable Kafka consumer/producer |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka bootstrap servers |
| `KAFKA_GROUP_ID` | panako | Consumer group ID |
| `KAFKA_STORE_REQUEST_TOPIC` | panako-store-requests | Topic for store requests |
| `KAFKA_STORE_RESULT_TOPIC` | panako-store-results | Topic for store results |
| `KAFKA_MONITOR_REQUEST_TOPIC` | panako-monitor-requests | Topic for monitor requests |
| `KAFKA_MONITOR_RESULT_TOPIC` | panako-monitor-results | Topic for monitor results |

## Architecture

### Strategy comparison

| | OLAF | PANAKO |
|---|---|---|
| Algorithm | Spectral peaks (Shazam-like) | Gabor transform + pitch-invariant |
| BPM/speed change tolerance | No | Up to +/-20% |
| Pitch shift tolerance | No | Up to +/-20% |
| Store speed | Fast (~2-3s/track) | Slower (~8-15s/track) |
| Query speed | <1s | 2-5s |
| Recommended for | Exact match, speed-critical | Radio monitoring, BPM variations |

### Storage comparison

| | LMDB | ClickHouse |
|---|---|---|
| Max tracks (single server) | ~40K-650K (RAM dependent) | ~5-10M |
| 30M tracks | Not possible | Yes (sharding) |
| Disk size (30M tracks) | 8-12 TB | 2-3 TB (compressed) |
| RAM requirement | = DB size | 10-20% of DB |
| Concurrent writes | Single writer (lock) | Multiple writers |
| Setup | Zero (embedded) | Separate server |

## Identifier Logic

The `identifier` is a stable numeric key derived from the filename (without extension):

- `USRC17607839.mp3` -> murmurhash3(`"USRC17607839"`) -> same ID regardless of format
- `USRC17607839.aac` -> same ID
- `1855.mp3` -> `1855` (numeric filenames used directly)

## JVM Flag

`--add-opens=java.base/java.nio=ALL-UNNAMED` is required for LMDB. Included in the Dockerfile `ENTRYPOINT`.
