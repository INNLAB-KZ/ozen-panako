package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.Strategy;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/query — queries the fingerprint database with an uploaded audio file.
 */
public class QueryHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(QueryHandler.class.getName());

	private final Strategy strategy;
	private final long maxBytes;

	public QueryHandler(Strategy strategy, int maxUploadSizeMB) {
		this.strategy = strategy;
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
		try {
			upload = MultipartParser.parse(exchange.getRequestBody(), contentType, maxBytes);
			if (upload == null) {
				HttpUtil.sendError(exchange, 400, "No audio file found in request (field name: audio)");
				return;
			}

			String filePath = upload.tempFile.toAbsolutePath().toString();
			int maxResults = Config.getInt(Key.NUMBER_OF_QUERY_RESULTS);

			long startTime = System.currentTimeMillis();

			CollectingResultHandler handler = new CollectingResultHandler();
			strategy.query(filePath, maxResults, new HashSet<>(), handler);

			long processingTimeMs = System.currentTimeMillis() - startTime;

			// Estimate query duration from the file
			double queryDuration = 0;
			if (!handler.results.isEmpty()) {
				QueryResult first = handler.results.get(0);
				queryDuration = first.queryStop > 0 ? first.queryStop : 0;
			}

			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"status\":\"ok\",");
			json.append("\"query_duration_seconds\":").append(String.format("%.1f", queryDuration)).append(",");
			json.append("\"processing_time_ms\":").append(processingTimeMs).append(",");
			json.append("\"matches\":[");

			for (int i = 0; i < handler.results.size(); i++) {
				QueryResult r = handler.results.get(i);
				if (i > 0) json.append(",");
				json.append("{");
				String isrc = HttpUtil.extractIsrc(r.refPath);
				json.append("\"identifier\":").append(r.refIdentifier).append(",");
				json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
				json.append("\"filename\":\"").append(HttpUtil.escapeJson(r.refPath)).append("\",");
				json.append("\"match_start_seconds\":").append(String.format("%.1f", r.refStart)).append(",");
				json.append("\"match_end_seconds\":").append(String.format("%.1f", r.refStop)).append(",");
				json.append("\"query_start_seconds\":").append(String.format("%.1f", r.queryStart)).append(",");
				json.append("\"query_end_seconds\":").append(String.format("%.1f", r.queryStop)).append(",");
				json.append("\"score\":").append((int) r.score).append(",");
				json.append("\"time_factor\":").append(String.format("%.3f", r.timeFactor)).append(",");
				json.append("\"frequency_factor\":").append(String.format("%.3f", r.frequencyFactor)).append(",");
				json.append("\"match_percentage\":").append(String.format("%.1f", r.percentOfSecondsWithMatches));
				json.append("}");
			}

			json.append("]");
			json.append("}");

			HttpUtil.sendJson(exchange, 200, json.toString());

		} catch (IOException e) {
			LOG.log(Level.WARNING, "Query request failed", e);
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Query request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		} finally {
			if (upload != null) {
				try { Files.deleteIfExists(upload.tempFile); } catch (IOException ignored) {}
			}
		}
	}

	private static class CollectingResultHandler implements QueryResultHandler {
		final List<QueryResult> results = new ArrayList<>();

		@Override
		public void handleQueryResult(QueryResult result) {
			results.add(result);
		}

		@Override
		public void handleEmptyResult(QueryResult result) {
			// No match — leave results empty
		}
	}
}
