package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamRecoveryStoreTest {
    private static final String PREFS_NAME = "StreamRecovery";
    private static final String HOST_A =
            "11111111-1111-1111-1111-111111111111";
    private static final String HOST_B =
            "22222222-2222-2222-2222-222222222222";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void noLossNaturalExpiryAtomicallyCreatesMatchingSuppression() {
        StreamRecoveryStore.GracefulHostLossCandidate candidate =
                createCandidate(HOST_A);
        long afterTtl = afterTtl(candidate);

        assertTrue(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_A, afterTtl));
        assertNull(StreamRecoveryStore.getGracefulHostLossCandidate(
                context, afterTtl));
        assertTrue(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
    }

    @Test
    public void lossObservedButUnpromotedExpiryStillCreatesSuppression() {
        StreamRecoveryStore.GracefulHostLossCandidate candidate =
                createCandidate(HOST_A);
        assertTrue(StreamRecoveryStore
                .markGracefulHostLossCandidateServiceUnavailable(
                        context,
                        HOST_A,
                        candidate.getCreatedAt() + 1));

        long afterTtl = afterTtl(candidate);
        assertTrue(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_A, afterTtl));
        assertNull(StreamRecoveryStore.getGracefulHostLossCandidate(
                context, afterTtl));
        assertTrue(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
    }

    @Test
    public void suppressionMatchesOnlyItsHost() {
        createExpiredSuppression(HOST_A);

        assertTrue(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_A));
        assertFalse(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_B));
    }

    @Test
    public void differentHostCandidateDoesNotClearExistingSuppression() {
        createExpiredSuppression(HOST_A);

        StreamRecoveryStore.GracefulHostLossCandidate hostBCandidate =
                createCandidate(HOST_B);

        assertNotNull(hostBCandidate);
        assertTrue(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
        assertFalse(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_B));
        assertTrue(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_B));
    }

    @Test
    public void explicitClearRestoresOrdinaryAdmission() {
        createExpiredSuppression(HOST_A);

        assertTrue(StreamRecoveryStore
                .clearOrdinaryAutoDesktopLaunchSuppression(
                        context,
                        HOST_A,
                        "test_explicit_launch"));
        assertFalse(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_A));
    }

    @Test
    public void newGracefulCandidateReplacesOldSuppression() {
        createExpiredSuppression(HOST_A);

        StreamRecoveryStore.GracefulHostLossCandidate newCandidate =
                createCandidate(HOST_A);

        assertNotNull(newCandidate);
        assertFalse(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
        assertTrue(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context, HOST_A));
    }

    @Test
    public void directRecoveryClearsSuppressionAndCreatesPendingRecord() {
        createExpiredSuppression(HOST_A);

        StreamRecoveryStore.RecoveryRecord recovery =
                StreamRecoveryStore.createRecovery(
                        context,
                        HOST_A,
                        "app-uuid",
                        1,
                        "Desktop",
                        false);

        assertNotNull(recovery);
        assertTrue(recovery.isRecoveryPending());
        assertFalse(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
        assertNotNull(StreamRecoveryStore.loadPendingRecovery(context, HOST_A));
    }

    @Test
    public void confirmedOfflinePromotionConsumesCandidateWithoutSuppression() {
        createCandidate(HOST_A);

        StreamRecoveryStore.RecoveryRecord recovery =
                StreamRecoveryStore.promoteGracefulHostLossCandidate(
                        context,
                        HOST_A);

        assertNotNull(recovery);
        assertTrue(recovery.isRecoveryPending());
        assertNull(StreamRecoveryStore.getGracefulHostLossCandidate(context));
        assertFalse(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
    }

    @Test
    public void transientPromotionConsumesCandidateWithoutSuppression() {
        StreamRecoveryStore.GracefulHostLossCandidate candidate =
                createCandidate(HOST_A);
        long lossAt = candidate.getCreatedAt() + 1;
        assertTrue(StreamRecoveryStore
                .markGracefulHostLossCandidateServiceUnavailable(
                        context,
                        HOST_A,
                        lossAt));

        StreamRecoveryStore.RecoveryRecord recovery =
                StreamRecoveryStore
                        .promoteGracefulHostLossCandidateAfterServiceRecovery(
                                context,
                                HOST_A,
                                lossAt + 1);

        assertNotNull(recovery);
        assertTrue(recovery.isRecoveryPending());
        assertNull(StreamRecoveryStore.getGracefulHostLossCandidate(context));
        assertFalse(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(context, HOST_A));
    }

    private StreamRecoveryStore.GracefulHostLossCandidate createCandidate(
            String computerUuid) {
        StreamRecoveryStore.GracefulHostLossCandidate candidate =
                StreamRecoveryStore.createGracefulHostLossCandidate(
                        context,
                        computerUuid,
                        "app-uuid",
                        1,
                        "Desktop",
                        false);
        assertNotNull(candidate);
        return candidate;
    }

    private void createExpiredSuppression(String computerUuid) {
        StreamRecoveryStore.GracefulHostLossCandidate candidate =
                createCandidate(computerUuid);
        assertTrue(StreamRecoveryStore.isOrdinaryAutoDesktopLaunchSuppressed(
                context,
                computerUuid,
                afterTtl(candidate)));
        assertTrue(StreamRecoveryStore
                .hasOrdinaryAutoDesktopLaunchSuppression(
                        context,
                        computerUuid));
    }

    private long afterTtl(
            StreamRecoveryStore.GracefulHostLossCandidate candidate) {
        return candidate.getCreatedAt() +
                StreamRecoveryStore
                        .GRACEFUL_HOST_LOSS_CANDIDATE_EXPIRATION_MS +
                1;
    }
}
