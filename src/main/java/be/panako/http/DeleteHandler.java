package be.panako.http;

import be.panako.strategy.Strategy;
import be.panako.util.FileUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/delete — removes fingerprints from the index.
 * Accepts either application/json with {"identifier": 12345} or multipart/form-data with an audio file.
 */
public class DeleteHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(DeleteHandler.class.getName());

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;

	public DeleteHandler(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB) {
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

		if (contentType != null && contentType.contains("multipart/form-data")) {
			handleMultipartDelete(exchange, contentType);
		} else {
			handleJsonDelete(exchange);
		}
	}

	private void handleMultipartDelete(HttpExchange exchange, String contentType) throws IOException {
		MultipartParser.UploadedFile upload = null;
		Path audioFile = null;
		try {
			upload = MultipartParser.parse(exchange.getRequestBody(), contentType, maxBytes);
			if (upload == null) {
				HttpUtil.sendError(exchange, 400, "No audio file found in request");
				return;
			}

			// Rename to original filename so FileUtils.getIdentifier() matches the stored ID
			audioFile = upload.tempFile.resolveSibling(upload.fileName);
			Files.move(upload.tempFile, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			String filePath = audioFile.toAbsolutePath().toString();
			int identifier = FileUtils.getIdentifier(filePath);

			writeLock.lock();
			try {
				strategy.delete(filePath);
			} finally {
				writeLock.unlock();
			}

			String json = "{\"status\":\"ok\",\"identifier\":" + identifier + ",\"deleted\":true}";
			HttpUtil.sendJson(exchange, 200, json);

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Delete request failed", e);
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

	private void handleJsonDelete(HttpExchange exchange) throws IOException {
		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

			// Minimal JSON parsing: extract "identifier" value
			int identifier = extractIdentifier(body);
			if (identifier == -1) {
				HttpUtil.sendError(exchange, 400, "Missing or invalid 'identifier' in JSON body");
				return;
			}

			// Resolve the identifier back to a path if needed
			// For JSON-based delete, we need to find the resource by identifier.
			// The strategy.delete() requires a file path (to re-extract fingerprints).
			// This is a limitation — JSON delete works best with the file path approach.
			HttpUtil.sendError(exchange, 400,
					"JSON-based delete requires the original audio file. Use multipart/form-data with the audio file instead.");

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Delete request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	private int extractIdentifier(String json) {
		// Simple extraction of "identifier": <number>
		int idx = json.indexOf("\"identifier\"");
		if (idx == -1) return -1;
		int colon = json.indexOf(':', idx);
		if (colon == -1) return -1;
		StringBuilder num = new StringBuilder();
		for (int i = colon + 1; i < json.length(); i++) {
			char c = json.charAt(i);
			if (Character.isDigit(c) || c == '-') {
				num.append(c);
			} else if (num.length() > 0) {
				break;
			}
		}
		if (num.length() == 0) return -1;
		try {
			return Integer.parseInt(num.toString());
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
