package be.panako.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared HTTP utilities for the Panako API handlers.
 */
public final class HttpUtil {

	private HttpUtil() {}

	/**
	 * Send a JSON response.
	 */
	public static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	/**
	 * Send an error JSON response.
	 */
	public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
		String json = "{\"status\":\"error\",\"message\":\"" + escapeJson(message) + "\"}";
		sendJson(exchange, statusCode, json);
	}

	/**
	 * Parse query parameters from the request URI.
	 */
	public static Map<String, String> parseQueryParams(HttpExchange exchange) {
		Map<String, String> params = new LinkedHashMap<>();
		URI uri = exchange.getRequestURI();
		String query = uri.getRawQuery();
		if (query == null || query.isEmpty()) return params;
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				params.put(pair.substring(0, eq), pair.substring(eq + 1));
			}
		}
		return params;
	}

	/**
	 * Extract ISRC from a filename like "USRC17607839.mp3" -> "USRC17607839".
	 * Returns the part before the last dot (the extension).
	 */
	public static String extractIsrc(String filename) {
		if (filename == null || filename.isEmpty()) return null;
		// Strip path if present
		int sep = filename.lastIndexOf('/');
		if (sep >= 0) filename = filename.substring(sep + 1);
		sep = filename.lastIndexOf('\\');
		if (sep >= 0) filename = filename.substring(sep + 1);
		// Remove extension
		int dot = filename.lastIndexOf('.');
		if (dot > 0) return filename.substring(0, dot);
		return filename;
	}

	/**
	 * Parse the metadata string from Strategy.metadata() and extract duration and fingerprint count.
	 * Format: "identifier ; path ; duration (s) ; numFingerprints (#) ; printsPerSecond (#/s)"
	 * Returns [duration, fingerprintCount] or null if parsing fails.
	 */
	public static double[] parseMetadata(String metadata) {
		if (metadata == null || metadata.isEmpty()) return null;
		try {
			String[] parts = metadata.split(";");
			if (parts.length >= 4) {
				double duration = Double.parseDouble(parts[2].replaceAll("[^0-9.]", "").trim());
				int fingerprints = Integer.parseInt(parts[3].replaceAll("[^0-9]", "").trim());
				return new double[]{duration, fingerprints};
			}
		} catch (NumberFormatException ignored) {}
		return null;
	}

	/**
	 * Minimal JSON string escaping.
	 */
	public static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
