package be.panako.http;

import be.panako.strategy.Strategy;
import be.panako.util.FileUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/store — stores audio fingerprints from an uploaded file.
 *
 * <p>A write lock serializes the entire store operation because the underlying
 * LMDB storage uses plain HashMap (not thread-safe) for internal queues.</p>
 */
public class StoreHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(StoreHandler.class.getName());

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;

	public StoreHandler(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB) {
		this.strategy = strategy;
		this.writeLock = writeLock;
		this.maxBytes = maxUploadSizeMB * 1024L * 1024L;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}

		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		if (contentType == null || !contentType.contains("multipart/form-data")) {
			HttpUtil.sendError(exchange, 400, "Content-Type must be multipart/form-data");
			return;
		}

		MultipartParser.UploadedFile upload = null;
		Path audioFile = null;
		try {
			upload = MultipartParser.parse(exchange.getRequestBody(), contentType, maxBytes);
			if (upload == null) {
				HttpUtil.sendError(exchange, 400, "No audio file found in request (field name: audio)");
				return;
			}

			// Rename temp file to original filename so that:
			// 1) FileUtils.getIdentifier() produces a stable ID based on the real name
			// 2) metadata.path stored in LMDB contains the real filename
			audioFile = upload.tempFile.resolveSibling(upload.fileName);
			Files.move(upload.tempFile, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			String filePath = audioFile.toAbsolutePath().toString();
			int identifier = FileUtils.getIdentifier(filePath);
			String isrc = HttpUtil.extractIsrc(upload.fileName);

			// Check for duplicates
			if (strategy.hasResource(filePath)) {
				StringBuilder json = new StringBuilder();
				json.append("{");
				json.append("\"status\":\"already_exists\",");
				json.append("\"identifier\":").append(identifier).append(",");
				json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
				json.append("\"filename\":\"").append(HttpUtil.escapeJson(upload.fileName)).append("\"");
				json.append("}");
				HttpUtil.sendJson(exchange, 200, json.toString());
				return;
			}

			long startTime = System.currentTimeMillis();

			writeLock.lock();
			double durationInSeconds;
			try {
				durationInSeconds = strategy.store(filePath, upload.fileName);
			} finally {
				writeLock.unlock();
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;
			int fingerprintCount = (int) Math.round(durationInSeconds * 7);

			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"status\":\"ok\",");
			json.append("\"identifier\":").append(identifier).append(",");
			json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
			json.append("\"filename\":\"").append(HttpUtil.escapeJson(upload.fileName)).append("\",");
			json.append("\"duration_seconds\":").append(String.format("%.1f", durationInSeconds)).append(",");
			json.append("\"fingerprints_count\":").append(fingerprintCount).append(",");
			json.append("\"processing_time_ms\":").append(processingTimeMs);
			json.append("}");

			HttpUtil.sendJson(exchange, 200, json.toString());

		} catch (IOException e) {
			LOG.log(Level.WARNING, "Store request failed", e);
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Store request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
			if (upload != null) {
				try { Files.deleteIfExists(upload.tempFile); } catch (IOException ignored) {}
			}
		}
	}
}
