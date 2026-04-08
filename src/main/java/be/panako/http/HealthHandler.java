package be.panako.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /api/v1/health — simple health check endpoint.
 */
public class HealthHandler implements HttpHandler {

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Method not allowed\"}");
			return;
		}
		String json = "{\"status\":\"ok\",\"version\":\"2.1-api\"}";
		HttpUtil.sendJson(exchange, 200, json);
	}
}
