package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.olaf.OlafFingerprint;
import be.panako.strategy.olaf.OlafMatch;
import be.panako.strategy.olaf.storage.OlafHit;
import be.panako.strategy.olaf.storage.OlafResourceMetadata;
import be.panako.strategy.olaf.storage.OlafStorage;
import be.panako.strategy.panako.PanakoEventPointProcessor;
import be.panako.strategy.panako.PanakoFingerprint;
import be.panako.strategy.panako.PanakoMatch;
import be.panako.strategy.panako.storage.PanakoHit;
import be.panako.strategy.panako.storage.PanakoResourceMetadata;
import be.panako.strategy.panako.storage.PanakoStorage;
import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.util.PitchConverter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/monitor/fingerprints — monitors with pre-computed fingerprints for multiple matches.
 *
 * <p>Splits fingerprints into overlapping time windows (like CLI monitor) and queries each window.
 * Returns all matches found across all windows.</p>
 *
 * <p>Accepts JSON:</p>
 * <pre>{@code
 * {
 *   "fingerprints": [
 *     {"hash": 123456789, "t1": 10, "f1": 200},
 *     ...
 *   ]
 * }
 * }</pre>
 */
public class MonitorFingerprintsHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(MonitorFingerprintsHandler.class.getName());

	private final boolean isOlaf;
	private final int panakoLatency;

	public MonitorFingerprintsHandler() {
		this.isOlaf = Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF");
		if (!isOlaf && !Config.getBoolean(Key.PANAKO_USE_GPU_EP_EXTRACTOR)) {
			int size = Config.getInt(Key.PANAKO_AUDIO_BLOCK_SIZE);
			PanakoEventPointProcessor ep = new PanakoEventPointProcessor(size);
			panakoLatency = ep.latency();
			ep.processingFinished();
		} else {
			panakoLatency = 0;
		}
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

			int arrayStart = body.indexOf("\"fingerprints\"");
			if (arrayStart == -1) {
				HttpUtil.sendError(exchange, 400, "Missing 'fingerprints' in JSON body");
				return;
			}
			int bracketStart = body.indexOf('[', arrayStart);
			int bracketEnd = StoreFingerprintsHandler.findMatchingBracket(body, bracketStart);
			if (bracketStart == -1 || bracketEnd == -1) {
				HttpUtil.sendError(exchange, 400, "Invalid 'fingerprints' array");
				return;
			}

			String arrayContent = body.substring(bracketStart + 1, bracketEnd);

			long startTime = System.currentTimeMillis();
			int maxResults = Config.getInt(Key.NUMBER_OF_QUERY_RESULTS);
			Set<Integer> avoid = new HashSet<>();

			List<QueryResult> allResults;
			if (isOlaf) {
				allResults = doMonitorOlaf(arrayContent, maxResults, avoid);
			} else {
				allResults = doMonitorPanako(arrayContent, maxResults, avoid);
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;

			String json = MonitorHandler.buildResponseJson(null, allResults, null, processingTimeMs);
			HttpUtil.sendJson(exchange, 200, json);

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Monitor fingerprints request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	// ===================== OLAF MONITOR =====================

	private List<QueryResult> doMonitorOlaf(String arrayContent, int maxResults, Set<Integer> avoid) {
		// Parse all fingerprints
		List<OlafFingerprint> allPrints = new ArrayList<>();
		int pos = 0;
		while (pos < arrayContent.length()) {
			int objStart = arrayContent.indexOf('{', pos);
			if (objStart == -1) break;
			int objEnd = arrayContent.indexOf('}', objStart);
			if (objEnd == -1) break;
			String obj = arrayContent.substring(objStart, objEnd + 1);
			long hash = StoreFingerprintsHandler.extractJsonLong(obj, "hash", -1);
			int t1 = StoreFingerprintsHandler.extractJsonInt(obj, "t1", -1);
			if (hash != -1 && t1 != -1) {
				allPrints.add(new OlafFingerprint(hash, t1));
			}
			pos = objEnd + 1;
		}

		if (allPrints.isEmpty()) return Collections.emptyList();

		// Sort by time
		allPrints.sort(Comparator.comparingInt(fp -> fp.t1));

		// Convert window config from seconds to blocks
		int stepSizeSeconds = Config.getInt(Key.MONITOR_STEP_SIZE);
		int overlapSeconds = Config.getInt(Key.MONITOR_OVERLAP);
		int actualStepSeconds = stepSizeSeconds - overlapSeconds;

		float blocksPerSecond = Config.getInt(Key.OLAF_SAMPLE_RATE) / (float) Config.getInt(Key.OLAF_STEP_SIZE);
		int windowBlocks = (int) (stepSizeSeconds * blocksPerSecond);
		int stepBlocks = (int) (actualStepSeconds * blocksPerSecond);

		int minT = allPrints.get(0).t1;
		int maxT = allPrints.get(allPrints.size() - 1).t1;

		List<QueryResult> allResults = new ArrayList<>();

		for (int windowStart = minT; windowStart <= maxT; windowStart += stepBlocks) {
			int windowEnd = windowStart + windowBlocks;

			// Collect fingerprints in this window
			List<OlafFingerprint> windowPrints = new ArrayList<>();
			for (OlafFingerprint fp : allPrints) {
				if (fp.t1 >= windowStart && fp.t1 < windowEnd) {
					windowPrints.add(fp);
				}
			}

			if (windowPrints.isEmpty()) continue;

			List<QueryResult> windowResults = queryOlafWindow(windowPrints, maxResults, avoid);
			allResults.addAll(windowResults);
		}

		return allResults;
	}

	private List<QueryResult> queryOlafWindow(List<OlafFingerprint> prints, int maxResults, Set<Integer> avoid) {
		OlafStorage db = StoreFingerprintsHandler.getOlafStorage();

		Map<Long, OlafFingerprint> printMap = new HashMap<>();
		for (OlafFingerprint print : prints) {
			long hash = print.hash();
			db.addToQueryQueue(hash);
			printMap.put(hash, print);
		}

		Map<Long, List<OlafHit>> matchAccumulator = new HashMap<>();
		int queryRange = Config.getInt(Key.OLAF_QUERY_RANGE);
		db.processQueryQueue(matchAccumulator, queryRange, avoid);

		HashMap<Integer, List<OlafMatch>> hitsPerIdentifier = new HashMap<>();
		final List<QueryResult> queryResults = new ArrayList<>();

		matchAccumulator.forEach((fingerprintHash, dbHits) -> {
			dbHits.forEach((dbHit) -> {
				int identifier = dbHit.resourceID;
				hitsPerIdentifier.computeIfAbsent(identifier, k -> new ArrayList<>());
				OlafMatch hit = new OlafMatch();
				hit.identifier = identifier;
				hit.matchTime = dbHit.t;
				hit.originalHash = dbHit.originalHash;
				hit.matchedNearHash = dbHit.matchedNearHash;
				hit.queryTime = printMap.get(fingerprintHash).t1;
				hitsPerIdentifier.get(identifier).add(hit);
			});
		});

		int minimumUnfilteredHits = Config.getInt(Key.OLAF_MIN_HITS_UNFILTERED);
		int minimumFilteredHits = Config.getInt(Key.OLAF_MIN_HITS_FILTERED);

		List<Integer> matchesToDelete = new ArrayList<>();
		hitsPerIdentifier.forEach((id, hitlist) -> {
			if (hitlist.size() < minimumUnfilteredHits) matchesToDelete.add(id);
		});
		matchesToDelete.forEach(hitsPerIdentifier::remove);

		hitsPerIdentifier.forEach((identifier, hitlist) -> {
			Collections.sort(hitlist, Comparator.comparingInt(a -> a.queryTime));

			int maxPartListSize = Config.getInt(Key.OLAF_HIT_PART_MAX_SIZE);
			int partDivider = Config.getInt(Key.OLAF_HIT_PART_DIVIDER);
			int partListLength = Math.min(maxPartListSize, Math.max(minimumUnfilteredHits, hitlist.size() / partDivider));
			List<OlafMatch> firstHits = hitlist.subList(0, partListLength);
			List<OlafMatch> lastHits = hitlist.subList(hitlist.size() - partListLength, hitlist.size());

			float y1 = mostCommonDeltaTOlaf(firstHits);
			float x1 = 0;
			for (OlafMatch hit : firstHits) {
				if (hit.deltaT() == y1) { x1 = hit.queryTime; break; }
			}

			float y2 = mostCommonDeltaTOlaf(lastHits);
			float x2 = 0;
			for (int i = lastHits.size() - 1; i >= 0; i--) {
				OlafMatch hit = lastHits.get(i);
				if (hit.deltaT() == y2) { x2 = hit.queryTime; break; }
			}

			float slope = (x2 != x1) ? (y2 - y1) / (x2 - x1) : 0;
			float offset = -x1 * slope + y1;
			float timeFactor = 1 - slope;

			double threshold = Config.getFloat(Key.OLAF_QUERY_RANGE);

			if (timeFactor > Config.getFloat(Key.OLAF_MIN_TIME_FACTOR) && timeFactor < Config.getFloat(Key.OLAF_MAX_TIME_FACTOR)) {
				List<OlafMatch> filteredHits = new ArrayList<>();
				for (OlafMatch hit : hitlist) {
					float yActual = hit.deltaT();
					float x = hit.queryTime;
					float yPredicted = slope * x + offset;
					if (Math.abs(yActual - yPredicted) <= threshold) filteredHits.add(hit);
				}

				if (filteredHits.size() > minimumFilteredHits) {
					float minDuration = Config.getFloat(Key.OLAF_MIN_MATCH_DURATION);
					float queryStart = olafBlocksToSeconds(filteredHits.get(0).queryTime);
					float queryStop = olafBlocksToSeconds(filteredHits.get(filteredHits.size() - 1).queryTime);
					float duration = queryStop - queryStart;

					if (duration >= minDuration) {
						int score = filteredHits.size();
						float refStart = olafBlocksToSeconds(filteredHits.get(0).matchTime);
						float refStop = olafBlocksToSeconds(filteredHits.get(filteredHits.size() - 1).matchTime);

						OlafResourceMetadata metadata = db.getMetadata((long) identifier);
						String refPath = "metadata unavailable!";
						if (metadata != null) refPath = metadata.path;

						TreeMap<Integer, Integer> matchesPerSecondHistogram = new TreeMap<>();
						for (OlafMatch hit : filteredHits) {
							float offsetInSec = olafBlocksToSeconds(hit.matchTime) - refStart;
							int secondBin = (int) offsetInSec;
							matchesPerSecondHistogram.merge(secondBin, 1, Integer::sum);
						}

						float numberOfMatchingSeconds = (float) Math.ceil(refStop - refStart);
						float emptySeconds = numberOfMatchingSeconds - matchesPerSecondHistogram.size();
						float percentOfSecondsWithMatches = 1 - (emptySeconds / numberOfMatchingSeconds);

						if (percentOfSecondsWithMatches >= Config.getFloat(Key.OLAF_MIN_SEC_WITH_MATCH)) {
							QueryResult r = new QueryResult("fingerprint_monitor", queryStart, queryStop, refPath,
									"" + identifier, refStart, refStop, score, timeFactor, 1.0f, percentOfSecondsWithMatches);
							queryResults.add(r);
						}
					}
				}
			}
		});

		queryResults.sort((a, b) -> Integer.compare((int) b.score, (int) a.score));
		if (queryResults.size() > maxResults) return queryResults.subList(0, maxResults);
		return queryResults;
	}

	// ===================== PANAKO MONITOR =====================

	private List<QueryResult> doMonitorPanako(String arrayContent, int maxResults, Set<Integer> avoid) {
		List<PanakoFingerprint> allPrints = new ArrayList<>();
		int pos = 0;
		while (pos < arrayContent.length()) {
			int objStart = arrayContent.indexOf('{', pos);
			if (objStart == -1) break;
			int objEnd = arrayContent.indexOf('}', objStart);
			if (objEnd == -1) break;
			String obj = arrayContent.substring(objStart, objEnd + 1);
			long hash = StoreFingerprintsHandler.extractJsonLong(obj, "hash", -1);
			int t1 = StoreFingerprintsHandler.extractJsonInt(obj, "t1", -1);
			int f1 = StoreFingerprintsHandler.extractJsonInt(obj, "f1", -1);
			if (hash != -1 && t1 != -1 && f1 != -1) {
				allPrints.add(new PanakoFingerprint(hash, t1, f1));
			}
			pos = objEnd + 1;
		}

		if (allPrints.isEmpty()) return Collections.emptyList();

		allPrints.sort(Comparator.comparingInt(fp -> fp.t1));

		int stepSizeSeconds = Config.getInt(Key.MONITOR_STEP_SIZE);
		int overlapSeconds = Config.getInt(Key.MONITOR_OVERLAP);
		int actualStepSeconds = stepSizeSeconds - overlapSeconds;

		float timeResolution = Config.getFloat(Key.PANAKO_TRANSF_TIME_RESOLUTION);
		float sampleRate = Config.getFloat(Key.PANAKO_SAMPLE_RATE);
		float blocksPerSecond = sampleRate / timeResolution;
		int windowBlocks = (int) (stepSizeSeconds * blocksPerSecond);
		int stepBlocks = (int) (actualStepSeconds * blocksPerSecond);

		int minT = allPrints.get(0).t1;
		int maxT = allPrints.get(allPrints.size() - 1).t1;

		List<QueryResult> allResults = new ArrayList<>();

		for (int windowStart = minT; windowStart <= maxT; windowStart += stepBlocks) {
			int windowEnd = windowStart + windowBlocks;

			List<PanakoFingerprint> windowPrints = new ArrayList<>();
			for (PanakoFingerprint fp : allPrints) {
				if (fp.t1 >= windowStart && fp.t1 < windowEnd) {
					windowPrints.add(fp);
				}
			}

			if (windowPrints.isEmpty()) continue;

			List<QueryResult> windowResults = queryPanakoWindow(windowPrints, maxResults, avoid);
			allResults.addAll(windowResults);
		}

		return allResults;
	}

	private List<QueryResult> queryPanakoWindow(List<PanakoFingerprint> prints, int maxResults, Set<Integer> avoid) {
		PanakoStorage db = StoreFingerprintsHandler.getPanakoStorage();

		Map<Long, PanakoFingerprint> printMap = new HashMap<>();
		for (PanakoFingerprint print : prints) {
			long hash = print.hash();
			db.addToQueryQueue(hash);
			printMap.put(hash, print);
		}

		Map<Long, List<PanakoHit>> matchAccumulator = new HashMap<>();
		int queryRange = Config.getInt(Key.PANAKO_QUERY_RANGE);
		db.processQueryQueue(matchAccumulator, queryRange, avoid);

		HashMap<Integer, List<PanakoMatch>> hitsPerIdentifier = new HashMap<>();
		final List<QueryResult> queryResults = new ArrayList<>();

		matchAccumulator.forEach((fingerprintHash, dbHits) -> {
			dbHits.forEach((dbHit) -> {
				int identifier = dbHit.resourceID;
				hitsPerIdentifier.computeIfAbsent(identifier, k -> new ArrayList<>());
				PanakoMatch hit = new PanakoMatch();
				hit.identifier = identifier;
				hit.matchTime = dbHit.t;
				hit.originalHash = dbHit.originalHash;
				hit.matchedNearHash = dbHit.matchedNearHash;
				hit.queryTime = printMap.get(fingerprintHash).t1;
				hit.queryF1 = printMap.get(fingerprintHash).f1;
				hit.matchF1 = dbHit.f;
				hitsPerIdentifier.get(identifier).add(hit);
			});
		});

		int minimumUnfilteredHits = Config.getInt(Key.PANAKO_MIN_HITS_UNFILTERED);
		int minimumFilteredHits = Config.getInt(Key.PANAKO_MIN_HITS_FILTERED);

		List<Integer> matchesToDelete = new ArrayList<>();
		hitsPerIdentifier.forEach((id, hitlist) -> {
			if (hitlist.size() < minimumUnfilteredHits) matchesToDelete.add(id);
		});
		matchesToDelete.forEach(hitsPerIdentifier::remove);

		hitsPerIdentifier.forEach((identifier, hitlist) -> {
			Collections.sort(hitlist, Comparator.comparingInt(a -> a.queryTime));

			int maxPartListSize = Config.getInt(Key.PANAKO_HIT_PART_MAX_SIZE);
			int partDivider = Config.getInt(Key.PANAKO_HIT_PART_DIVIDER);
			int partListLength = Math.min(maxPartListSize, Math.max(minimumUnfilteredHits, hitlist.size() / partDivider));
			List<PanakoMatch> firstHits = hitlist.subList(0, partListLength);
			List<PanakoMatch> lastHits = hitlist.subList(hitlist.size() - partListLength, hitlist.size());

			float y1 = mostCommonDeltaTPanako(firstHits);
			float x1 = 0;
			float frequencyFactor = 0;
			for (PanakoMatch hit : firstHits) {
				if (hit.deltaT() == y1) {
					x1 = hit.queryTime;
					frequencyFactor = panakoB2Hz(hit.matchF1) / panakoB2Hz(hit.queryF1);
					break;
				}
			}

			float y2 = mostCommonDeltaTPanako(lastHits);
			float x2 = 0;
			for (int i = lastHits.size() - 1; i >= 0; i--) {
				PanakoMatch hit = lastHits.get(i);
				if (hit.deltaT() == y2) { x2 = hit.queryTime; break; }
			}

			float slope = (x2 != x1) ? (y2 - y1) / (x2 - x1) : 0;
			float offset = -x1 * slope + y1;
			float timeFactor = 1.0f / (1 - slope);

			double threshold = Config.getFloat(Key.PANAKO_QUERY_RANGE);

			if (timeFactor > Config.getFloat(Key.PANAKO_MIN_TIME_FACTOR) && timeFactor < Config.getFloat(Key.PANAKO_MAX_TIME_FACTOR) &&
					frequencyFactor > Config.getFloat(Key.PANAKO_MIN_FREQ_FACTOR) && frequencyFactor < Config.getFloat(Key.PANAKO_MAX_FREQ_FACTOR)) {

				List<PanakoMatch> filteredHits = new ArrayList<>();
				for (PanakoMatch hit : hitlist) {
					float yActual = hit.deltaT();
					float x = hit.queryTime;
					float yPredicted = slope * x + offset;
					if (Math.abs(yActual - yPredicted) <= threshold) filteredHits.add(hit);
				}

				if (filteredHits.size() > minimumFilteredHits) {
					float minDuration = Config.getFloat(Key.PANAKO_MIN_MATCH_DURATION);
					float queryStart = panakoBlocksToSeconds(filteredHits.get(0).queryTime);
					float queryStop = panakoBlocksToSeconds(filteredHits.get(filteredHits.size() - 1).queryTime);
					float duration = queryStop - queryStart;

					if (duration >= minDuration) {
						int score = filteredHits.size();
						float refStart = panakoBlocksToSeconds(filteredHits.get(0).matchTime);
						float refStop = panakoBlocksToSeconds(filteredHits.get(filteredHits.size() - 1).matchTime);

						PanakoResourceMetadata metadata = db.getMetadata((long) identifier);
						String refPath = "metadata unavailable!";
						if (metadata != null) refPath = metadata.path;

						TreeMap<Integer, Integer> matchesPerSecondHistogram = new TreeMap<>();
						for (PanakoMatch hit : filteredHits) {
							float offsetInSec = panakoBlocksToSeconds(hit.matchTime) - refStart;
							int secondBin = (int) offsetInSec;
							matchesPerSecondHistogram.merge(secondBin, 1, Integer::sum);
						}

						float numberOfMatchingSeconds = (float) Math.ceil(refStop - refStart);
						float emptySeconds = numberOfMatchingSeconds - matchesPerSecondHistogram.size();
						float percentOfSecondsWithMatches = 1 - (emptySeconds / numberOfMatchingSeconds);

						if (percentOfSecondsWithMatches >= Config.getFloat(Key.PANAKO_MIN_SEC_WITH_MATCH)) {
							QueryResult r = new QueryResult("fingerprint_monitor", queryStart, queryStop, refPath,
									"" + identifier, refStart, refStop, score, timeFactor, frequencyFactor, percentOfSecondsWithMatches);
							queryResults.add(r);
						}
					}
				}
			}
		});

		queryResults.sort((a, b) -> Integer.compare((int) b.score, (int) a.score));
		if (queryResults.size() > maxResults) return queryResults.subList(0, maxResults);
		return queryResults;
	}

	// ===================== Helpers =====================

	private int mostCommonDeltaTOlaf(List<OlafMatch> hitList) {
		Map<Integer, Integer> countPerDiff = new HashMap<>();
		for (OlafMatch hit : hitList) countPerDiff.merge(hit.deltaT(), 1, Integer::sum);
		int maxCount = 0, mostCommon = 0;
		for (Map.Entry<Integer, Integer> entry : countPerDiff.entrySet()) {
			if (entry.getValue() > maxCount) { maxCount = entry.getValue(); mostCommon = entry.getKey(); }
		}
		return mostCommon;
	}

	private int mostCommonDeltaTPanako(List<PanakoMatch> hitList) {
		Map<Integer, Integer> countPerDiff = new HashMap<>();
		for (PanakoMatch hit : hitList) countPerDiff.merge(hit.deltaT(), 1, Integer::sum);
		int maxCount = 0, mostCommon = 0;
		for (Map.Entry<Integer, Integer> entry : countPerDiff.entrySet()) {
			if (entry.getValue() > maxCount) { maxCount = entry.getValue(); mostCommon = entry.getKey(); }
		}
		return mostCommon;
	}

	private float olafBlocksToSeconds(int t) {
		return t * (Config.getInt(Key.OLAF_STEP_SIZE) / (float) Config.getInt(Key.OLAF_SAMPLE_RATE));
	}

	private float panakoBlocksToSeconds(int t) {
		float timeResolution = Config.getFloat(Key.PANAKO_TRANSF_TIME_RESOLUTION);
		float sampleRate = Config.getFloat(Key.PANAKO_SAMPLE_RATE);
		return t * (timeResolution / sampleRate) + panakoLatency / sampleRate;
	}

	private float panakoB2Hz(int f) {
		float minFreq = Config.getFloat(Key.PANAKO_TRANSF_MIN_FREQ);
		float binsPerOctave = Config.getFloat(Key.PANAKO_TRANSF_BANDS_PER_OCTAVE);
		float centsPerBin = 1200.0f / binsPerOctave;
		float diffFromMinFreqInCents = f * centsPerBin;
		float minFreqInAbsCents = (float) PitchConverter.hertzToAbsoluteCent(minFreq);
		float binInAbsCents = minFreqInAbsCents + diffFromMinFreqInCents;
		return (float) PitchConverter.absoluteCentToHertz(binInAbsCents);
	}
}
