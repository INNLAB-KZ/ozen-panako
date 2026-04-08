# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Panako is a Java acoustic fingerprinting system for identifying audio in large archives. It is robust to time-stretching, pitch-shifting, and speed changes. Developed at IPEM, Ghent University.

## Build & Test Commands

```bash
# Build
./gradlew build

# Create fat JAR (output: build/libs/panako-2.1-all.jar)
./gradlew shadowJar

# Install to ~/.panako
./gradlew install

# Run tests (JUnit 5)
./gradlew test

# Generate javadoc
./gradlew javadoc
```

Tests require `--add-opens=java.base/java.nio=ALL-UNNAMED` JVM arg (already configured in build.gradle). Tests also require `ffmpeg` to be available on PATH.

Main class: `be.panako.cli.Panako`

## Architecture

**Strategy pattern** is the core abstraction. Three fingerprinting algorithms implement `Strategy`:
- `PanakoStrategy` — primary algorithm, robust to pitch/speed changes
- `OlafStrategy` — Shazam-like baseline
- `PitchClassHistogramStrategy` — histogram-based

Each strategy has its own fingerprint, event point, event point processor, and storage classes under `src/main/java/be/panako/strategy/{panako,olaf,pch}/`.

**CLI applications** extend `Application` and live in `be.panako.cli`. They are auto-discovered at runtime via reflection (Reflections library). The `Panako` main class dispatches to the appropriate `Application` subclass using Trie-based prefix matching.

**Storage** is abstracted per strategy with LMDB (persistent) and Memory implementations. Caching wrappers (`PanakoCachingStorage`, `OlafCachingStorage`) sit on top.

**Audio decoding** uses ffmpeg via subprocess pipe — no Java audio decoding. The ffmpeg command is configurable via `Key.DECODER_PIPE_COMMAND`.

**Configuration** is managed by `Config` singleton + `Key` enum. Defaults in `resources/defaults/config.properties`, overrideable by CLI args or `~/.panako/config.properties`.

**HTTP API** lives in `be.panako.http`. The `Server` CLI application starts an embedded `com.sun.net.httpserver.HttpServer`. A `ReentrantLock` serializes write operations (store/delete) since LMDB allows only a single writer. Endpoints: `/api/v1/{health,stats,store,query,delete}`.

## Key Dependencies

- **TarsosDSP** — DSP primitives (FFT, audio processing)
- **JGaborator** — Gabor transform via JNI (C++ native library)
- **LMDB Java** — embedded key-value store for fingerprint persistence
- **Reflections** — runtime classpath scanning for CLI plugin discovery
