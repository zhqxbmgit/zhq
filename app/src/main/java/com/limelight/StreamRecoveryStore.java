package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Persistent, single-session handoff between a terminated Game and AppView.
 *
 * All mutations are committed synchronously because callers transition between
 * activities immediately after writing and the record must survive process loss.
 */
public final class StreamRecoveryStore {
    public static final long NO_SESSION_ID = 0;

    public static final long UNSTABLE_RECOVERY_WINDOW_MS = 30_000;
    public static final long GRACEFUL_HOST_LOSS_CANDIDATE_EXPIRATION_MS = 60_000;
    public static final long RECOVERY_EXPIRATION_MS = 60 * 60 * 1000L;

    private static final String PREFS_NAME = "StreamRecovery";
    private static final String KEY_LAST_GENERATED_SESSION_ID = "lastGeneratedSessionId";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_RECOVERY_PENDING = "recoveryPending";
    private static final String KEY_COMPUTER_UUID = "computerUuid";
    private static final String KEY_APP_UUID = "appUuid";
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_APP_NAME = "appName";
    private static final String KEY_RECOVERY_STARTED_AT = "recoveryStartedAt";
    private static final String KEY_WITH_VIRTUAL_DISPLAY = "withVirtualDisplay";
    private static final String KEY_ATTEMPT_COUNT = "attemptCount";
    private static final String KEY_LAST_ATTEMPT_AT = "lastAttemptAt";
    private static final String KEY_CONNECTED_AT = "connectedAt";
    private static final String KEY_STABLE_UNTIL = "stableUntil";
    private static final String KEY_CANDIDATE_COMPUTER_UUID = "candidateComputerUuid";
    private static final String KEY_CANDIDATE_APP_UUID = "candidateAppUuid";
    private static final String KEY_CANDIDATE_APP_ID = "candidateAppId";
    private static final String KEY_CANDIDATE_APP_NAME = "candidateAppName";
    private static final String KEY_CANDIDATE_WITH_VIRTUAL_DISPLAY =
            "candidateWithVirtualDisplay";
    private static final String KEY_CANDIDATE_CREATED_AT = "candidateCreatedAt";
    private static final String KEY_CANDIDATE_SERVICE_LOSS_OBSERVED_AT =
            "candidateServiceLossObservedAt";
    private static final String KEY_ORDINARY_AUTO_DESKTOP_SUPPRESSION_PREFIX =
            "ordinaryAutoDesktopSuppression.";

    private static final Object STORE_LOCK = new Object();

    private StreamRecoveryStore() {
    }

    public static final class RecoveryRecord {
        public final long sessionId;
        public final boolean recoveryPending;
        public final String computerUuid;
        public final String appUuid;
        public final int appId;
        public final String appName;
        public final long recoveryStartedAt;
        public final boolean withVirtualDisplay;
        public final int attemptCount;
        public final long lastAttemptAt;
        public final long connectedAt;
        public final long stableUntil;

        private RecoveryRecord(long sessionId,
                               boolean recoveryPending,
                               String computerUuid,
                               String appUuid,
                               int appId,
                               String appName,
                               long recoveryStartedAt,
                               boolean withVirtualDisplay,
                               int attemptCount,
                               long lastAttemptAt,
                               long connectedAt,
                               long stableUntil) {
            this.sessionId = sessionId;
            this.recoveryPending = recoveryPending;
            this.computerUuid = computerUuid;
            this.appUuid = appUuid;
            this.appId = appId;
            this.appName = appName;
            this.recoveryStartedAt = recoveryStartedAt;
            this.withVirtualDisplay = withVirtualDisplay;
            this.attemptCount = attemptCount;
            this.lastAttemptAt = lastAttemptAt;
            this.connectedAt = connectedAt;
            this.stableUntil = stableUntil;
        }

        public long getSessionId() {
            return sessionId;
        }

        public boolean isRecoveryPending() {
            return recoveryPending;
        }

        public String getComputerUuid() {
            return computerUuid;
        }

        public String getAppUuid() {
            return appUuid;
        }

        public int getAppId() {
            return appId;
        }

        public String getAppName() {
            return appName;
        }

        public long getRecoveryStartedAt() {
            return recoveryStartedAt;
        }

        public boolean isWithVirtualDisplay() {
            return withVirtualDisplay;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public long getLastAttemptAt() {
            return lastAttemptAt;
        }

        public long getConnectedAt() {
            return connectedAt;
        }

        public long getStableUntil() {
            return stableUntil;
        }
    }

    public static final class GracefulHostLossCandidate {
        private final String computerUuid;
        private final String appUuid;
        private final int appId;
        private final String appName;
        private final boolean withVirtualDisplay;
        private final long createdAt;
        private final long serviceLossObservedAt;

        private GracefulHostLossCandidate(String computerUuid,
                                          String appUuid,
                                          int appId,
                                          String appName,
                                          boolean withVirtualDisplay,
                                          long createdAt,
                                          long serviceLossObservedAt) {
            this.computerUuid = computerUuid;
            this.appUuid = appUuid;
            this.appId = appId;
            this.appName = appName;
            this.withVirtualDisplay = withVirtualDisplay;
            this.createdAt = createdAt;
            this.serviceLossObservedAt = serviceLossObservedAt;
        }

        public String getComputerUuid() {
            return computerUuid;
        }

        public String getAppUuid() {
            return appUuid;
        }

        public int getAppId() {
            return appId;
        }

        public String getAppName() {
            return appName;
        }

        public boolean isWithVirtualDisplay() {
            return withVirtualDisplay;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getServiceLossObservedAt() {
            return serviceLossObservedAt;
        }
    }

    public static RecoveryRecord createRecovery(Context context,
                                                String computerUuid,
                                                String appUuid,
                                                int appId,
                                                String appName,
                                                boolean withVirtualDisplay) {
        if (context == null || computerUuid == null || computerUuid.trim().isEmpty()) {
            return null;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            clearExpiredCandidateLocked(prefs, now);
            removeCandidateLocked(prefs, null, "direct_recovery_created");
            long sessionId = nextSessionIdLocked(
                    now,
                    prefs.getLong(KEY_SESSION_ID, NO_SESSION_ID),
                    prefs.getLong(KEY_LAST_GENERATED_SESSION_ID, NO_SESSION_ID));

            boolean committed = prefs.edit()
                    .putLong(KEY_LAST_GENERATED_SESSION_ID, sessionId)
                    .putLong(KEY_SESSION_ID, sessionId)
                    .putBoolean(KEY_RECOVERY_PENDING, true)
                    .putString(KEY_COMPUTER_UUID, computerUuid)
                    .putString(KEY_APP_UUID, appUuid)
                    .putInt(KEY_APP_ID, appId)
                    .putString(KEY_APP_NAME, appName)
                    .putLong(KEY_RECOVERY_STARTED_AT, now)
                    .putBoolean(KEY_WITH_VIRTUAL_DISPLAY, withVirtualDisplay)
                    .putInt(KEY_ATTEMPT_COUNT, 0)
                    .putLong(KEY_LAST_ATTEMPT_AT, 0)
                    .putLong(KEY_CONNECTED_AT, 0)
                    .putLong(KEY_STABLE_UNTIL, 0)
                    .remove(getOrdinaryAutoDesktopSuppressionKey(computerUuid))
                    .commit();
            RecoveryRecord record = committed ? readRecordLocked(prefs) : null;
            if (record != null) {
                logLifecycle(
                        "created",
                        record.sessionId,
                        record.computerUuid,
                        getRecordStateForLog(record),
                        "connection_interrupted");
            }
            return record;
        }
    }

    public static GracefulHostLossCandidate createGracefulHostLossCandidate(
            Context context,
            String computerUuid,
            String appUuid,
            int appId,
            String appName,
            boolean withVirtualDisplay) {
        if (context == null || computerUuid == null || computerUuid.trim().isEmpty()) {
            return null;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            clearExpiredCandidateLocked(prefs, now);
            clearExpiredLocked(prefs, now);

            // A pending recovery or connected stability guard always has priority
            // over the weaker graceful host-loss evidence.
            if (readRecordLocked(prefs) != null) {
                return null;
            }

            GracefulHostLossCandidate existingCandidate =
                    readCandidateLocked(prefs);
            if (existingCandidate != null &&
                    computerUuid.equalsIgnoreCase(existingCandidate.computerUuid)) {
                return existingCandidate;
            }

            if (existingCandidate != null) {
                removeCandidateLocked(
                        prefs,
                        existingCandidate.computerUuid,
                        "replaced_by_new_candidate");
            }

            boolean committed = prefs.edit()
                    .putString(KEY_CANDIDATE_COMPUTER_UUID, computerUuid)
                    .putString(KEY_CANDIDATE_APP_UUID, appUuid)
                    .putInt(KEY_CANDIDATE_APP_ID, appId)
                    .putString(KEY_CANDIDATE_APP_NAME, appName)
                    .putBoolean(KEY_CANDIDATE_WITH_VIRTUAL_DISPLAY,
                            withVirtualDisplay)
                    .putLong(KEY_CANDIDATE_CREATED_AT, now)
                    .remove(KEY_CANDIDATE_SERVICE_LOSS_OBSERVED_AT)
                    .remove(getOrdinaryAutoDesktopSuppressionKey(computerUuid))
                    .commit();
            GracefulHostLossCandidate candidate =
                    committed ? readCandidateLocked(prefs) : null;
            if (candidate != null) {
                LimeLog.info("Stream recovery candidate_created:" +
                        " computerUuid=" + candidate.computerUuid +
                        " appId=" + candidate.appId +
                        " appName=" +
                        (candidate.appName != null ? candidate.appName : "unknown") +
                        " reason=graceful_termination");
            }
            return candidate;
        }
    }

    public static GracefulHostLossCandidate getGracefulHostLossCandidate(
            Context context) {
        return getGracefulHostLossCandidate(
                context,
                System.currentTimeMillis());
    }

    static GracefulHostLossCandidate getGracefulHostLossCandidate(
            Context context,
            long now) {
        if (context == null) {
            return null;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            clearExpiredCandidateLocked(prefs, now);
            return readCandidateLocked(prefs);
        }
    }

    public static GracefulHostLossCandidate getGracefulHostLossCandidate(
            Context context,
            String computerUuid) {
        if (context == null || computerUuid == null) {
            return null;
        }

        GracefulHostLossCandidate candidate =
                getGracefulHostLossCandidate(context);
        if (candidate == null ||
                !computerUuid.equalsIgnoreCase(candidate.computerUuid)) {
            return null;
        }
        return candidate;
    }

    public static boolean hasValidGracefulHostLossCandidate(
            Context context,
            String computerUuid) {
        return getGracefulHostLossCandidate(context, computerUuid) != null;
    }

    public static boolean isOrdinaryAutoDesktopLaunchSuppressed(
            Context context,
            String computerUuid) {
        return isOrdinaryAutoDesktopLaunchSuppressed(
                context,
                computerUuid,
                System.currentTimeMillis());
    }

    static boolean isOrdinaryAutoDesktopLaunchSuppressed(
            Context context,
            String computerUuid,
            long now) {
        if (context == null || !hasText(computerUuid)) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            clearExpiredCandidateLocked(prefs, now);

            GracefulHostLossCandidate candidate = readCandidateLocked(prefs);
            boolean matchingCandidate = candidate != null &&
                    computerUuid.equalsIgnoreCase(candidate.computerUuid);
            return matchingCandidate ||
                    hasOrdinaryAutoDesktopSuppressionLocked(
                            prefs,
                            computerUuid);
        }
    }

    public static boolean clearOrdinaryAutoDesktopLaunchSuppression(
            Context context,
            String computerUuid,
            String reason) {
        if (context == null || !hasText(computerUuid)) {
            return false;
        }

        synchronized (STORE_LOCK) {
            return removeOrdinaryAutoDesktopSuppressionLocked(
                    getPreferences(context),
                    computerUuid,
                    reason);
        }
    }

    static boolean hasOrdinaryAutoDesktopLaunchSuppression(
            Context context,
            String computerUuid) {
        if (context == null || !hasText(computerUuid)) {
            return false;
        }

        synchronized (STORE_LOCK) {
            return hasOrdinaryAutoDesktopSuppressionLocked(
                    getPreferences(context),
                    computerUuid);
        }
    }

    public static boolean markGracefulHostLossCandidateServiceUnavailable(
            Context context,
            String computerUuid,
            long serviceLossObservedAt) {
        if (context == null || computerUuid == null ||
                serviceLossObservedAt <= 0) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            clearExpiredCandidateLocked(prefs, now);
            GracefulHostLossCandidate candidate = readCandidateLocked(prefs);
            if (candidate == null ||
                    !computerUuid.equalsIgnoreCase(candidate.computerUuid) ||
                    now < candidate.createdAt ||
                    serviceLossObservedAt < candidate.createdAt) {
                return false;
            }

            if (candidate.serviceLossObservedAt > 0) {
                return true;
            }

            boolean committed = prefs.edit()
                    .putLong(KEY_CANDIDATE_SERVICE_LOSS_OBSERVED_AT,
                            serviceLossObservedAt)
                    .commit();
            if (committed) {
                LimeLog.info("Stream recovery candidate_service_loss_observed:" +
                        " computerUuid=" + candidate.computerUuid +
                        " reason=fresh_serverinfo_unreachable");
            }
            return committed;
        }
    }

    public static RecoveryRecord promoteGracefulHostLossCandidate(
            Context context,
            String computerUuid) {
        return promoteGracefulHostLossCandidate(
                context,
                computerUuid,
                false,
                0,
                "host_confirmed_offline");
    }

    public static RecoveryRecord
            promoteGracefulHostLossCandidateAfterServiceRecovery(
                    Context context,
                    String computerUuid,
                    long serviceRecoveredAt) {
        return promoteGracefulHostLossCandidate(
                context,
                computerUuid,
                true,
                serviceRecoveredAt,
                "service_recovered_after_transient_loss");
    }

    private static RecoveryRecord promoteGracefulHostLossCandidate(
            Context context,
            String computerUuid,
            boolean requireObservedServiceLoss,
            long serviceRecoveredAt,
            String reason) {
        if (context == null || computerUuid == null) {
            return null;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            clearExpiredCandidateLocked(prefs, now);
            GracefulHostLossCandidate candidate = readCandidateLocked(prefs);
            if (candidate == null ||
                    !computerUuid.equalsIgnoreCase(candidate.computerUuid)) {
                return null;
            }
            if (requireObservedServiceLoss &&
                    (candidate.serviceLossObservedAt <= 0 ||
                            serviceRecoveredAt <
                                    candidate.serviceLossObservedAt ||
                            serviceRecoveredAt < candidate.createdAt)) {
                return null;
            }

            clearExpiredLocked(prefs, now);
            if (readRecordLocked(prefs) != null) {
                removeCandidateLocked(
                        prefs,
                        computerUuid,
                        "recovery_session_present");
                return null;
            }

            long sessionId = nextSessionIdLocked(
                    now,
                    prefs.getLong(KEY_SESSION_ID, NO_SESSION_ID),
                    prefs.getLong(KEY_LAST_GENERATED_SESSION_ID, NO_SESSION_ID));
            SharedPreferences.Editor editor = prefs.edit()
                    .putLong(KEY_LAST_GENERATED_SESSION_ID, sessionId)
                    .putLong(KEY_SESSION_ID, sessionId)
                    .putBoolean(KEY_RECOVERY_PENDING, true)
                    .putString(KEY_COMPUTER_UUID, candidate.computerUuid)
                    .putString(KEY_APP_UUID, candidate.appUuid)
                    .putInt(KEY_APP_ID, candidate.appId)
                    .putString(KEY_APP_NAME, candidate.appName)
                    .putLong(KEY_RECOVERY_STARTED_AT, now)
                    .putBoolean(KEY_WITH_VIRTUAL_DISPLAY,
                            candidate.withVirtualDisplay)
                    .putInt(KEY_ATTEMPT_COUNT, 0)
                    .putLong(KEY_LAST_ATTEMPT_AT, 0)
                    .putLong(KEY_CONNECTED_AT, 0)
                    .putLong(KEY_STABLE_UNTIL, 0)
                    .remove(getOrdinaryAutoDesktopSuppressionKey(
                            candidate.computerUuid));
            removeCandidateKeys(editor);

            if (!editor.commit()) {
                return null;
            }

            RecoveryRecord promoted = readRecordLocked(prefs);
            if (promoted != null && promoted.sessionId == sessionId &&
                    promoted.recoveryPending) {
                if (requireObservedServiceLoss) {
                    LimeLog.info("Stream recovery candidate_service_recovered:" +
                            " computerUuid=" + promoted.computerUuid +
                            " reason=fresh_serverinfo_success_after_loss");
                }
                LimeLog.info("Stream recovery candidate_promoted:" +
                        " computerUuid=" + promoted.computerUuid +
                        " reason=" + reason);
                return promoted;
            }
            return null;
        }
    }

    public static boolean clearGracefulHostLossCandidate(
            Context context,
            String computerUuid,
            String reason) {
        if (context == null) {
            return false;
        }

        synchronized (STORE_LOCK) {
            return removeCandidateLocked(
                    getPreferences(context),
                    computerUuid,
                    reason);
        }
    }

    public static RecoveryRecord loadPendingRecovery(Context context) {
        if (context == null) {
            return null;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            clearExpiredLocked(prefs, System.currentTimeMillis());

            RecoveryRecord record = readRecordLocked(prefs);
            if (record == null || !record.recoveryPending) {
                return null;
            }
            return record;
        }
    }

    public static RecoveryRecord loadPendingRecovery(Context context, String computerUuid) {
        if (computerUuid == null) {
            return null;
        }

        RecoveryRecord record = loadPendingRecovery(context);
        if (record == null || !computerUuid.equalsIgnoreCase(record.computerUuid)) {
            return null;
        }
        return record;
    }

    public static boolean markLaunchInFlight(Context context, long sessionId) {
        if (context == null || sessionId == NO_SESSION_ID) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            clearExpiredLocked(prefs, System.currentTimeMillis());
            RecoveryRecord record = readRecordLocked(prefs);
            if (record == null || record.sessionId != sessionId ||
                    !record.recoveryPending || record.attemptCount != 0) {
                return false;
            }

            return prefs.edit()
                    .putInt(KEY_ATTEMPT_COUNT, 1)
                    .putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis())
                    .commit();
        }
    }

    public static boolean markConnected(Context context, long sessionId) {
        if (context == null || sessionId == NO_SESSION_ID) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            clearExpiredLocked(prefs, now);
            RecoveryRecord record = readRecordLocked(prefs);
            if (record == null || record.sessionId != sessionId) {
                return false;
            }

            if (record.connectedAt != 0) {
                // Duplicate connectionStarted callbacks are idempotent only for a
                // record that completed the one permitted launch transition.
                return !record.recoveryPending && record.attemptCount == 1;
            }

            if (!record.recoveryPending || record.attemptCount != 1) {
                return false;
            }

            return prefs.edit()
                    .putBoolean(KEY_RECOVERY_PENDING, false)
                    .putLong(KEY_CONNECTED_AT, now)
                    .putLong(KEY_STABLE_UNTIL, now + UNSTABLE_RECOVERY_WINDOW_MS)
                    .commit();
        }
    }

    public static boolean isWithinUnstableRecoveryWindow(Context context, long sessionId) {
        if (context == null || sessionId == NO_SESSION_ID) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            RecoveryRecord record = readRecordLocked(prefs);
            if (record == null || record.sessionId != sessionId ||
                    record.connectedAt == 0 || record.stableUntil == 0) {
                return false;
            }

            if (now >= record.stableUntil) {
                removeTokenLocked(prefs, "stable_window_completed");
                return false;
            }
            return true;
        }
    }

    public static boolean clearIfSessionMatches(Context context, long sessionId) {
        return clearIfSessionMatches(context, sessionId, "session_cleared");
    }

    public static boolean clearIfSessionMatches(Context context,
                                                long sessionId,
                                                String reason) {
        if (context == null || sessionId == NO_SESSION_ID) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            if (prefs.getLong(KEY_SESSION_ID, NO_SESSION_ID) != sessionId) {
                return false;
            }
            return removeTokenLocked(prefs, reason);
        }
    }

    public static boolean cancel(Context context, long sessionId) {
        return cancel(context, sessionId, "user_cancelled");
    }

    public static boolean cancel(Context context, long sessionId, String reason) {
        return clearIfSessionMatches(context, sessionId, reason);
    }

    public static boolean clearExpired(Context context) {
        if (context == null) {
            return false;
        }

        synchronized (STORE_LOCK) {
            SharedPreferences prefs = getPreferences(context);
            long now = System.currentTimeMillis();
            boolean recoveryCleared = clearExpiredLocked(prefs, now);
            boolean candidateCleared = clearExpiredCandidateLocked(prefs, now);
            return recoveryCleared || candidateCleared;
        }
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static RecoveryRecord readRecordLocked(SharedPreferences prefs) {
        long sessionId = prefs.getLong(KEY_SESSION_ID, NO_SESSION_ID);
        if (sessionId == NO_SESSION_ID) {
            return null;
        }

        String computerUuid = prefs.getString(KEY_COMPUTER_UUID, null);
        long recoveryStartedAt = prefs.getLong(KEY_RECOVERY_STARTED_AT, 0);
        if (computerUuid == null || computerUuid.trim().isEmpty()) {
            removeTokenLocked(prefs, "invalid_missing_computer_uuid");
            return null;
        }
        if (recoveryStartedAt <= 0) {
            removeTokenLocked(prefs, "invalid_missing_start_time");
            return null;
        }

        return new RecoveryRecord(
                sessionId,
                prefs.getBoolean(KEY_RECOVERY_PENDING, false),
                computerUuid,
                prefs.getString(KEY_APP_UUID, null),
                prefs.getInt(KEY_APP_ID, 0),
                prefs.getString(KEY_APP_NAME, null),
                recoveryStartedAt,
                prefs.getBoolean(KEY_WITH_VIRTUAL_DISPLAY, false),
                prefs.getInt(KEY_ATTEMPT_COUNT, 0),
                prefs.getLong(KEY_LAST_ATTEMPT_AT, 0),
                prefs.getLong(KEY_CONNECTED_AT, 0),
                prefs.getLong(KEY_STABLE_UNTIL, 0));
    }

    private static GracefulHostLossCandidate readCandidateLocked(
            SharedPreferences prefs) {
        long createdAt = prefs.getLong(KEY_CANDIDATE_CREATED_AT, 0);
        if (createdAt == 0) {
            return null;
        }

        String computerUuid = prefs.getString(
                KEY_CANDIDATE_COMPUTER_UUID, null);
        if (computerUuid == null || computerUuid.trim().isEmpty() ||
                createdAt < 0) {
            removeCandidateLocked(
                    prefs,
                    null,
                    "invalid_candidate");
            return null;
        }

        return new GracefulHostLossCandidate(
                computerUuid,
                prefs.getString(KEY_CANDIDATE_APP_UUID, null),
                prefs.getInt(KEY_CANDIDATE_APP_ID, 0),
                prefs.getString(KEY_CANDIDATE_APP_NAME, null),
                prefs.getBoolean(
                        KEY_CANDIDATE_WITH_VIRTUAL_DISPLAY, false),
                createdAt,
                prefs.getLong(
                        KEY_CANDIDATE_SERVICE_LOSS_OBSERVED_AT, 0));
    }

    private static boolean clearExpiredLocked(SharedPreferences prefs, long now) {
        RecoveryRecord record = readRecordLocked(prefs);
        if (record == null) {
            return false;
        }

        boolean hasConnectedGuard =
                record.connectedAt > 0 && record.stableUntil > 0;
        boolean recoveryExpired = !hasConnectedGuard &&
                now >= record.recoveryStartedAt &&
                now - record.recoveryStartedAt >= RECOVERY_EXPIRATION_MS;
        boolean stableWindowCompleted =
                hasConnectedGuard && now >= record.stableUntil;
        if (!recoveryExpired && !stableWindowCompleted) {
            return false;
        }

        return removeTokenLocked(
                prefs,
                recoveryExpired ? "recovery_expired" : "stable_window_completed");
    }

    private static boolean clearExpiredCandidateLocked(
            SharedPreferences prefs,
            long now) {
        GracefulHostLossCandidate candidate = readCandidateLocked(prefs);
        if (candidate == null || now < candidate.createdAt ||
                now - candidate.createdAt <
                        GRACEFUL_HOST_LOSS_CANDIDATE_EXPIRATION_MS) {
            return false;
        }

        SharedPreferences.Editor editor = prefs.edit();
        removeCandidateKeys(editor);
        editor.putLong(
                getOrdinaryAutoDesktopSuppressionKey(candidate.computerUuid),
                candidate.createdAt);

        boolean committed = editor.commit();
        if (committed) {
            LimeLog.info("Stream recovery candidate_cleared:" +
                    " computerUuid=" + candidate.computerUuid +
                    " reason=expired_to_ordinary_auto_suppression");
            LimeLog.info("Stream recovery ordinary_auto_suppression_created:" +
                    " computerUuid=" + candidate.computerUuid +
                    " interruptionAt=" + candidate.createdAt +
                    " reason=graceful_candidate_expired");
        }
        return committed;
    }

    private static boolean removeTokenLocked(SharedPreferences prefs, String reason) {
        long sessionId = prefs.getLong(KEY_SESSION_ID, NO_SESSION_ID);
        String computerUuid = prefs.getString(KEY_COMPUTER_UUID, null);
        String state = getStoredStateForLog(prefs);

        boolean committed = prefs.edit()
                .remove(KEY_SESSION_ID)
                .remove(KEY_RECOVERY_PENDING)
                .remove(KEY_COMPUTER_UUID)
                .remove(KEY_APP_UUID)
                .remove(KEY_APP_ID)
                .remove(KEY_APP_NAME)
                .remove(KEY_RECOVERY_STARTED_AT)
                .remove(KEY_WITH_VIRTUAL_DISPLAY)
                .remove(KEY_ATTEMPT_COUNT)
                .remove(KEY_LAST_ATTEMPT_AT)
                .remove(KEY_CONNECTED_AT)
                .remove(KEY_STABLE_UNTIL)
                .commit();
        if (committed && sessionId != NO_SESSION_ID) {
            logLifecycle(
                    "removed",
                    sessionId,
                    computerUuid,
                    state,
                    normalizeReason(reason));
        }
        return committed;
    }

    private static boolean removeCandidateLocked(SharedPreferences prefs,
                                                 String computerUuid,
                                                 String reason) {
        GracefulHostLossCandidate candidate = readCandidateForRemovalLocked(prefs);
        if (candidate == null ||
                (computerUuid != null &&
                        !computerUuid.equalsIgnoreCase(candidate.computerUuid))) {
            return false;
        }

        SharedPreferences.Editor editor = prefs.edit();
        removeCandidateKeys(editor);
        boolean committed = editor.commit();
        if (committed) {
            LimeLog.info("Stream recovery candidate_cleared:" +
                    " computerUuid=" + candidate.computerUuid +
                    " reason=" + normalizeReason(reason));
        }
        return committed;
    }

    private static GracefulHostLossCandidate readCandidateForRemovalLocked(
            SharedPreferences prefs) {
        long createdAt = prefs.getLong(KEY_CANDIDATE_CREATED_AT, 0);
        String computerUuid = prefs.getString(
                KEY_CANDIDATE_COMPUTER_UUID, null);
        if (createdAt == 0 && computerUuid == null) {
            return null;
        }

        return new GracefulHostLossCandidate(
                computerUuid != null ? computerUuid : "unknown",
                prefs.getString(KEY_CANDIDATE_APP_UUID, null),
                prefs.getInt(KEY_CANDIDATE_APP_ID, 0),
                prefs.getString(KEY_CANDIDATE_APP_NAME, null),
                prefs.getBoolean(
                        KEY_CANDIDATE_WITH_VIRTUAL_DISPLAY, false),
                createdAt,
                prefs.getLong(
                        KEY_CANDIDATE_SERVICE_LOSS_OBSERVED_AT, 0));
    }

    private static void removeCandidateKeys(SharedPreferences.Editor editor) {
        editor.remove(KEY_CANDIDATE_COMPUTER_UUID)
                .remove(KEY_CANDIDATE_APP_UUID)
                .remove(KEY_CANDIDATE_APP_ID)
                .remove(KEY_CANDIDATE_APP_NAME)
                .remove(KEY_CANDIDATE_WITH_VIRTUAL_DISPLAY)
                .remove(KEY_CANDIDATE_CREATED_AT)
                .remove(KEY_CANDIDATE_SERVICE_LOSS_OBSERVED_AT);
    }

    private static boolean hasOrdinaryAutoDesktopSuppressionLocked(
            SharedPreferences prefs,
            String computerUuid) {
        return prefs.contains(
                getOrdinaryAutoDesktopSuppressionKey(computerUuid));
    }

    private static boolean removeOrdinaryAutoDesktopSuppressionLocked(
            SharedPreferences prefs,
            String computerUuid,
            String reason) {
        String key = getOrdinaryAutoDesktopSuppressionKey(computerUuid);
        if (!prefs.contains(key)) {
            return false;
        }

        long interruptionAt = prefs.getLong(key, 0);
        boolean committed = prefs.edit().remove(key).commit();
        if (committed) {
            LimeLog.info("Stream recovery ordinary_auto_suppression_cleared:" +
                    " computerUuid=" + computerUuid +
                    " interruptionAt=" + interruptionAt +
                    " reason=" + normalizeReason(reason));
        }
        return committed;
    }

    private static String getOrdinaryAutoDesktopSuppressionKey(
            String computerUuid) {
        return KEY_ORDINARY_AUTO_DESKTOP_SUPPRESSION_PREFIX +
                computerUuid.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String getStoredStateForLog(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_RECOVERY_PENDING, false)) {
            return prefs.getInt(KEY_ATTEMPT_COUNT, 0) == 0 ?
                    "PENDING" : "LAUNCH_IN_FLIGHT";
        }
        if (prefs.getLong(KEY_CONNECTED_AT, 0) > 0 &&
                prefs.getLong(KEY_STABLE_UNTIL, 0) > 0) {
            return "CONNECTED_GUARD";
        }
        return "NOT_PENDING";
    }

    private static String getRecordStateForLog(RecoveryRecord record) {
        if (record.recoveryPending) {
            return record.attemptCount == 0 ? "PENDING" : "LAUNCH_IN_FLIGHT";
        }
        if (record.connectedAt > 0 && record.stableUntil > 0) {
            return "CONNECTED_GUARD";
        }
        return "NOT_PENDING";
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.trim().isEmpty() ? "unspecified" : reason;
    }

    private static void logLifecycle(String action,
                                     long sessionId,
                                     String computerUuid,
                                     String state,
                                     String reason) {
        LimeLog.info("Stream recovery " + action +
                ": sessionId=" + sessionId +
                " computerUuid=" + (computerUuid != null ? computerUuid : "unknown") +
                " state=" + state +
                " reason=" + reason);
    }

    private static long nextSessionIdLocked(long now,
                                            long currentSessionId,
                                            long lastGeneratedSessionId) {
        long candidate = Math.max(now, Math.max(lastGeneratedSessionId + 1, currentSessionId + 1));
        if (candidate <= NO_SESSION_ID) {
            candidate = 1;
        }
        return candidate;
    }
}
