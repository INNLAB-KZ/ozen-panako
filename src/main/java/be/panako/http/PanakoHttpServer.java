package be.panako.http;

import be.panako.strategy.Strategy;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import be.tarsos.dsp.io.PipeDecoder;
import be.tarsos.dsp.io.PipedAudioStream;

/**
 * Embedded HTTP server for the Panako acoustic fingerprinting API.
 * Uses com.sun.net.httpserver.HttpServer (built into the JDK).
 *
 * <p>A ReentrantLock serializes store/delete operations because the underlying
 * LMDB storage layer uses plain HashMap for per-thread queues (not thread-safe)
 * and LMDB only allows a single write transaction at a time.
 * Query and stats requests run concurrently without the lock.</p>
 *
 * <p>This class has its own main() so the HTTP server can be launched independently
 * without modifying any upstream Panako code.</p>
 */
public class PanakoHttpServer {

	private static final Logger LOG = Logger.getLogger(PanakoHttpServer.class.getName());

	private static final int DEFAULT_PORT = 8080;
	private static final int DEFAULT_THREAD_POOL_SIZE = 10;
	private static final int DEFAULT_MAX_UPLOAD_SIZE_MB = 100;

	private final int port;
	private final int maxUploadSizeMB;
	private final Strategy strategy;
	private final HttpServer server;
	private final ExecutorService executor;
	private final ReentrantLock writeLock = new ReentrantLock();

	/**
	 * Create a new HTTP server.
	 * @param port the port to listen on
	 * @param threadPoolSize the number of handler threads
	 * @param maxUploadSizeMB max upload size in MB
	 * @throws IOException if the server socket cannot be opened
	 */
	public PanakoHttpServer(int port, int threadPoolSize, int maxUploadSizeMB) throws IOException {
		this.port = port;
		this.maxUploadSizeMB = maxUploadSizeMB;

		this.strategy = Strategy.getInstance();
		this.executor = Executors.newFixedThreadPool(threadPoolSize);
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		this.server.setExecutor(executor);

		// Register endpoints
		server.createContext("/api/v1/health", new HealthHandler());
		server.createContext("/api/v1/stats", new StatsHandler(strategy));
		server.createContext("/api/v1/store/url", new StoreUrlHandler(strategy, writeLock, maxUploadSizeMB));
		server.createContext("/api/v1/store", new StoreHandler(strategy, writeLock, maxUploadSizeMB));
		server.createContext("/api/v1/query", new QueryHandler(strategy, maxUploadSizeMB));
		server.createContext("/api/v1/delete", new DeleteHandler(strategy, writeLock, maxUploadSizeMB));

		LOG.info(String.format("Panako HTTP server configured on port %d with %d threads", port, threadPoolSize));
	}

	/**
	 * Start the server. This method blocks until the JVM shuts down.
	 */
	public void start() {
		server.start();
		String strategyName = Config.get(Key.STRATEGY);
		System.out.printf("Panako HTTP API server started on port %d (strategy: %s)%n", port, strategyName);
		System.out.printf("  POST /api/v1/store       — store audio fingerprints (multipart)%n");
		System.out.printf("  POST /api/v1/store/url   — store audio from URL (JSON)%n");
		System.out.printf("  POST /api/v1/query       — query for matches%n");
		System.out.printf("  POST /api/v1/delete      — delete fingerprints%n");
		System.out.printf("  GET  /api/v1/stats       — database statistics%n");
		System.out.printf("  GET  /api/v1/health      — health check%n");

		// Add shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("Shutting down Panako HTTP server...");
			server.stop(2);
			executor.shutdown();
		}));

		// Block the main thread
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Stop the server.
	 */
	public void stop() {
		server.stop(2);
		executor.shutdown();
	}

	/**
	 * Get an integer from system property, environment variable, or default.
	 * Checks: -Dkey=value, then env KEY, then default.
	 */
	private static int getIntConfig(String name, int defaultValue) {
		String val = System.getProperty(name);
		if (val == null) val = System.getenv(name.toUpperCase());
		if (val == null) val = System.getenv(name);
		if (val != null) {
			try { return Integer.parseInt(val.trim()); } catch (NumberFormatException ignored) {}
		}
		return defaultValue;
	}

	/**
	 * Main entry point for running the Panako HTTP API server standalone.
	 *
	 * <p>Usage: java -cp panako.jar be.panako.http.PanakoHttpServer [KEY=VALUE ...]</p>
	 *
	 * <p>Supported config (via args, system properties, or env vars):</p>
	 * <ul>
	 *   <li>SERVER_PORT (default: 8080)</li>
	 *   <li>SERVER_THREAD_POOL_SIZE (default: 10)</li>
	 *   <li>SERVER_MAX_UPLOAD_SIZE_MB (default: 100)</li>
	 *   <li>STRATEGY (default: OLAF) — passed to Panako's Config</li>
	 * </ul>
	 */
	public static void main(String[] args) {
		Locale.setDefault(Locale.US);

		// Parse KEY=VALUE arguments and set as Panako config overrides
		for (String arg : args) {
			if (arg.contains("=")) {
				String[] parts = arg.split("=", 2);
				try {
					Key key = Key.valueOf(parts[0]);
					Config.set(key, parts[1]);
				} catch (IllegalArgumentException e) {
					// Not a Panako Key — store as system property for our own config
					System.setProperty(parts[0], parts[1]);
				}
			}
		}

		// Initialize Panako config and decoder
		Config.getInstance();
		String pipeEnvironment = Config.get(Key.DECODER_PIPE_ENVIRONMENT);
		String pipeArgument = Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG);
		String pipeCommand = Config.get(Key.DECODER_PIPE_COMMAND);
		String pipeLogFile = Config.get(Key.DECODER_PIPE_LOG_FILE);
		int pipeBuffer = Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE);
		PipeDecoder decoder = new PipeDecoder(pipeEnvironment, pipeArgument, pipeCommand, pipeLogFile, pipeBuffer);
		PipedAudioStream.setDecoder(decoder);

		// Read server config
		int port = getIntConfig("SERVER_PORT", DEFAULT_PORT);
		int threadPoolSize = getIntConfig("SERVER_THREAD_POOL_SIZE", DEFAULT_THREAD_POOL_SIZE);
		int maxUploadSizeMB = getIntConfig("SERVER_MAX_UPLOAD_SIZE_MB", DEFAULT_MAX_UPLOAD_SIZE_MB);

		// LMDB maxReaders is set from AVAILABLE_PROCESSORS — must be >= thread pool size
		int configuredProcessors = Config.getInt(Key.AVAILABLE_PROCESSORS);
		if (configuredProcessors < threadPoolSize) {
			Config.set(Key.AVAILABLE_PROCESSORS, String.valueOf(threadPoolSize));
		}

		try {
			PanakoHttpServer server = new PanakoHttpServer(port, threadPoolSize, maxUploadSizeMB);
			server.start();
		} catch (IOException e) {
			LOG.severe("Failed to start HTTP server: " + e.getMessage());
			System.err.println("Failed to start HTTP server: " + e.getMessage());
			System.exit(1);
		}
	}
}
