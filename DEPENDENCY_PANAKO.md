# Using Panako as a Dependency for Fingerprint Extraction

How to use Panako in another Java project to extract audio fingerprints locally, then send them to the Panako server via `POST /api/v1/store/fingerprints` or query via `POST /api/v1/query/fingerprints`.

**IMPORTANT:** The client extraction strategy must match the server strategy. Default server strategy is **OLAF**.

## 1. Add Panako as Dependency

Build the fat JAR and add it to your project:

```bash
cd panako
./gradlew shadowJar
# Output: build/libs/panako-2.1-all.jar
```

**Gradle:**

```groovy
dependencies {
    implementation files('libs/panako-2.1-all.jar')
}
```

**Maven:**

```xml
<dependency>
    <groupId>be.panako</groupId>
    <artifactId>panako</artifactId>
    <version>2.1</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/libs/panako-2.1-all.jar</systemPath>
</dependency>
```

## 2. Requirements

- **Java 11+**
- **ffmpeg** must be on PATH (Panako uses ffmpeg subprocess for audio decoding)
- JVM arg: `--add-opens=java.base/java.nio=ALL-UNNAMED`

## 3. Extract Fingerprints — OLAF (default)

```java
import be.panako.strategy.olaf.OlafEventPointProcessor;
import be.panako.strategy.olaf.OlafFingerprint;
import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.io.PipeDecoder;
import be.tarsos.dsp.io.PipedAudioStream;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;

import java.util.List;

public class FingerprintExtractor {

    // Call once at application startup
    public static void init() {
        Config.getInstance();
        Config.set(Key.STRATEGY, "OLAF");

        String pipeEnvironment = Config.get(Key.DECODER_PIPE_ENVIRONMENT);
        String pipeArgument = Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG);
        String pipeCommand = Config.get(Key.DECODER_PIPE_COMMAND);
        String pipeLogFile = Config.get(Key.DECODER_PIPE_LOG_FILE);
        int pipeBuffer = Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE);
        PipeDecoder decoder = new PipeDecoder(pipeEnvironment, pipeArgument, pipeCommand, pipeLogFile, pipeBuffer);
        PipedAudioStream.setDecoder(decoder);
    }

    public static List<OlafFingerprint> extract(String audioFilePath) {
        int sampleRate = Config.getInt(Key.OLAF_SAMPLE_RATE);
        int size = Config.getInt(Key.OLAF_SIZE);
        int stepSize = Config.getInt(Key.OLAF_STEP_SIZE);
        int overlap = size - stepSize;

        AudioDispatcher dispatcher = AudioDispatcherFactory.fromPipe(
            audioFilePath, sampleRate, size, overlap
        );

        OlafEventPointProcessor processor = new OlafEventPointProcessor(size);
        dispatcher.addAudioProcessor(processor);
        dispatcher.run();

        return processor.getFingerprints();
    }
}
```

## 4. Convert Fingerprints to JSON

For **OLAF**, each fingerprint needs `hash` and `t1` (no `f1`):

```java
public static String toJson(String filename, double durationSeconds, List<OlafFingerprint> prints) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"filename\":\"").append(filename).append("\",");
    sb.append("\"duration\":").append(durationSeconds).append(",");
    sb.append("\"fingerprints\":[");

    for (int i = 0; i < prints.size(); i++) {
        OlafFingerprint fp = prints.get(i);
        if (i > 0) sb.append(",");
        sb.append("{\"hash\":").append(fp.hash());
        sb.append(",\"t1\":").append(fp.t1);
        sb.append("}");
    }

    sb.append("]}");
    return sb.toString();
}
```

## 5. Send to Panako Server

### Store fingerprints

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public static String storeFingerprintsRemotely(String serverUrl, String json) throws Exception {
    HttpClient client = HttpClient.newHttpClient();

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(serverUrl + "/api/v1/store/fingerprints"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return response.body();
}
```

### Query by fingerprints

```java
public static String queryFingerprintsRemotely(String serverUrl, List<OlafFingerprint> prints) throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"fingerprints\":[");
    for (int i = 0; i < prints.size(); i++) {
        OlafFingerprint fp = prints.get(i);
        if (i > 0) sb.append(",");
        sb.append("{\"hash\":").append(fp.hash());
        sb.append(",\"t1\":").append(fp.t1);
        sb.append("}");
    }
    sb.append("]}");

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(serverUrl + "/api/v1/query/fingerprints"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return response.body();
}
```

## 6. Full Example

```java
public class Main {
    public static void main(String[] args) throws Exception {
        FingerprintExtractor.init();

        String audioFile = "/path/to/USRC17607839.mp3";
        List<OlafFingerprint> prints = FingerprintExtractor.extract(audioFile);

        // Estimate duration from last fingerprint
        double duration = prints.isEmpty() ? 0 :
            prints.get(prints.size() - 1).t1 *
            (Config.getInt(Key.OLAF_STEP_SIZE) / (double) Config.getInt(Key.OLAF_SAMPLE_RATE));

        // Store
        String storeJson = toJson("USRC17607839.mp3", duration, prints);
        String storeResult = storeFingerprintsRemotely("http://panako-server:8080", storeJson);
        System.out.println("Store: " + storeResult);

        // Query
        String queryResult = queryFingerprintsRemotely("http://panako-server:8080", prints);
        System.out.println("Query: " + queryResult);
    }
}
```

## 7. Size Comparison

| Content | ~3 min audio |
|---|---|
| MP3 file | ~3 MB |
| WAV file | ~30 MB |
| Fingerprints JSON | ~10-50 KB |

## 8. Server Response

### Store response

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250,
  "processing_time_ms": 5
}
```

### Query response

```json
{
  "status": "ok",
  "query_duration_seconds": 10.5,
  "processing_time_ms": 120,
  "matches": [
    {
      "identifier": 1612789453,
      "isrc": "USRC17607839",
      "filename": "USRC17607839.mp3",
      "match_start_seconds": 0.0,
      "match_end_seconds": 195.4,
      "score": 850,
      "time_factor": 1.000,
      "frequency_factor": 1.000,
      "match_percentage": 1.0
    }
  ]
}
```

## 9. PANAKO Strategy (alternative)

If your server uses `STRATEGY=PANAKO`, replace extraction with:

```java
import be.panako.strategy.panako.PanakoEventPointProcessor;
import be.panako.strategy.panako.PanakoFingerprint;

public static List<PanakoFingerprint> extractPanako(String audioFilePath) {
    int sampleRate = Config.getInt(Key.PANAKO_SAMPLE_RATE);
    int blockSize = Config.getInt(Key.PANAKO_AUDIO_BLOCK_SIZE);
    int overlap = Config.getInt(Key.PANAKO_AUDIO_BLOCK_OVERLAP);

    AudioDispatcher dispatcher = AudioDispatcherFactory.fromPipe(
        audioFilePath, sampleRate, blockSize, overlap
    );

    PanakoEventPointProcessor processor = new PanakoEventPointProcessor(blockSize);
    dispatcher.addAudioProcessor(processor);
    dispatcher.run();

    return processor.getFingerprints();
}
```

PANAKO fingerprints include `f1`: `{"hash": 123, "t1": 10, "f1": 200}`.
