package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.util.Config;
import be.panako.util.Key;

import java.util.logging.Logger;

/**
 * Validates Olaf query results to filter out false positives.
 *
 * <p>Two independent checks — both must pass:</p>
 * <ol>
 *   <li>{@link #validate} — match density check (enough hash matches for the query duration)</li>
 *   <li>{@link #validateDuration} — query must not be longer than reference + tolerance</li>
 * </ol>
 */
public class MatchValidator {

	private static final Logger LOG = Logger.getLogger(MatchValidator.class.getName());

	/** For short clips (< 15s), require at least this many matches */
	private static final int SHORT_CLIP_MIN_MATCHES = 8;

	/** Threshold in seconds: below this, the clip is considered "short" */
	private static final double SHORT_CLIP_THRESHOLD_SEC = 15.0;

	/** For longer clips, require at least this many matches regardless of duration */
	private static final int ABSOLUTE_MIN_MATCHES = 20;

	/** For longer clips, require at least this many matches per second of query */
	private static final double MIN_MATCHES_PER_SECOND = 0.15;

	/** Query duration may exceed reference by at most this many seconds */
	private static final double DURATION_TOLERANCE_SEC = 5.0;

	/** Minimum score — read from config MATCH_MIN_SCORE */
	private final int minScore;

	/** Minimum match_percentage — read from config MATCH_MIN_PERCENTAGE */
	private final double minMatchPercentage;

	public MatchValidator() {
		this.minScore = Config.getInt(Key.MATCH_MIN_SCORE);
		this.minMatchPercentage = Config.getFloat(Key.MATCH_MIN_PERCENTAGE);
	}

	/**
	 * Check match density: enough hash matches for the given query duration?
	 *
	 * @param result        the query result from Olaf
	 * @param queryDuration the duration of the query audio in seconds
	 * @return true if the match has sufficient density, false if it should be rejected
	 */
	public boolean validate(QueryResult result, double queryDuration) {
		if (result == null || result.refIdentifier == null || result.score < 0) {
			return false;
		}

		int matchCount = (int) result.score;
		int requiredMatches;

		if (queryDuration < SHORT_CLIP_THRESHOLD_SEC) {
			requiredMatches = SHORT_CLIP_MIN_MATCHES;
		} else {
			requiredMatches = (int) Math.max(queryDuration * MIN_MATCHES_PER_SECOND, ABSOLUTE_MIN_MATCHES);
		}

		if (matchCount < requiredMatches) {
			LOG.info(String.format("Match rejected: insufficient_match_density (score=%d, required=%d, queryDuration=%.1fs, ref=%s)",
					matchCount, requiredMatches, queryDuration, result.refIdentifier));
			return false;
		}

		return true;
	}

	/**
	 * Check score and match_percentage thresholds.
	 *
	 * @param result the query result
	 * @return true if the match passes quality thresholds
	 */
	public boolean validateQuality(QueryResult result) {
		if (result == null) return false;

		int score = (int) result.score;
		if (score < minScore) {
			LOG.info(String.format("Match rejected: low_score (score=%d, min=%d, ref=%s)",
					score, minScore, result.refIdentifier));
			return false;
		}

		if (result.percentOfSecondsWithMatches < minMatchPercentage) {
			LOG.info(String.format("Match rejected: low_match_percentage (percentage=%.2f, min=%.2f, ref=%s)",
					result.percentOfSecondsWithMatches, minMatchPercentage, result.refIdentifier));
			return false;
		}

		return true;
	}

	/**
	 * Check that the query is not longer than the reference audio + tolerance.
	 *
	 * @param result          the query result from Olaf
	 * @param queryDuration   the duration of the query audio in seconds
	 * @param refDurationSec  the duration of the reference audio in seconds
	 * @return true if duration is acceptable, false if the match should be rejected
	 */
	public boolean validateDuration(QueryResult result, double queryDuration, double refDurationSec) {
		if (refDurationSec <= 0) {
			return true; // unknown reference duration, skip check
		}

		double maxAllowed = refDurationSec + DURATION_TOLERANCE_SEC;

		if (queryDuration > maxAllowed) {
			LOG.info(String.format("Match rejected: query_longer_than_reference (queryDuration=%.1fs, refDuration=%.1fs, maxAllowed=%.1fs, ref=%s)",
					queryDuration, refDurationSec, maxAllowed, result.refIdentifier));
			return false;
		}

		return true;
	}
}
