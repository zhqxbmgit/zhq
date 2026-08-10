package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private enum RecoveryState {
        IDLE,
        WAITING_FOR_HOST,
        WAITING_FOR_FRESH_SERVER_INFO,
        WAITING_FOR_FRESH_APP_LIST,
        READY_TO_LAUNCH,
        LAUNCH_IN_FLIGHT,
        BLOCKED_FATAL,
        CANCELLED
    }

    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private boolean autoStartDesktopRequested = false;
    private boolean receivedServerInfo = false;
    private boolean requireFreshServerInfo = true;
    private boolean invalidatedStateForFreshServerInfo = false;
    private boolean autoDesktopLaunchPending = false;
    private boolean autoDesktopLaunchDispatched = false;
    private boolean autoDesktopConfirmationPending = false;
    private boolean autoDesktopConfirmationCancelled = false;
    private boolean fatalAutoDesktopLaunchBlocked = false;

    private RecoveryState recoveryState = RecoveryState.IDLE;
    private StreamRecoveryStore.RecoveryRecord recoveryRecord;
    private long recoverySessionId = StreamRecoveryStore.NO_SESSION_ID;
    private long requestedRecoverySessionId = StreamRecoveryStore.NO_SESSION_ID;
    private long activeUpdateGeneration;
    private long requiredAppListSuccessGeneration;
    private boolean firstResumePending = true;
    private boolean updateGenerationPrepared;
    private boolean computerUpdatesStarted;
    private boolean receivedFreshAppList;
    private ComputerManagerService.AppListSnapshot freshAppListSnapshot;
    private int freshRunningAppId;
    private String freshRunningAppUuid;
    private int recoveryTerminalMessageRes;

    private View recoveryOverlay;
    private View appFragmentContainer;
    private TextView appListLabel;
    private TextView recoveryStatus;
    private ProgressBar recoveryProgress;
    private Button recoveryCancel;
    private ExtendedFloatingActionButton profilesButton;

    private PreferenceConfiguration prefConfig;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";
    public final static String AUTO_START_DESKTOP_STREAM_EXTRA = "auto_start_desktop_stream";
    private final static String AUTO_DESKTOP_CONFIRMATION_CANCELLED_STATE =
            "autoDesktopConfirmationCancelled";
    private final static String FATAL_AUTO_DESKTOP_LAUNCH_BLOCKED_STATE =
            "fatalAutoDesktopLaunchBlocked";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finishWithRecoveryLog("service_computer_missing");
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finishWithRecoveryLog("app_grid_initialization_failed");
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    @MainThread
    private void startComputerUpdates() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::startComputerUpdates);
            return;
        }

        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground || computerUpdatesStarted) {
            return;
        }

        if (!updateGenerationPrepared) {
            prepareForegroundUpdateGeneration(requireFreshServerInfo);
        }

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }

        requiredAppListSuccessGeneration = poller.getSuccessGeneration();
        receivedFreshAppList = false;
        freshAppListSnapshot = null;

        if (requireFreshServerInfo && !invalidatedStateForFreshServerInfo) {
            receivedServerInfo = false;
            managerBinder.invalidateStateForComputer(uuidString);
            invalidatedStateForFreshServerInfo = true;
        }

        final long callbackGeneration = activeUpdateGeneration;
        final long callbackSessionId = hasActiveRecoverySession() ?
                recoverySessionId : StreamRecoveryStore.NO_SESSION_ID;
        computerUpdatesStarted = true;

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                // ComputerDetails is shared and mutated by both pollers. Snapshot it before
                // posting to the main thread so a later poll cannot change this callback.
                final ComputerDetails detailsSnapshot = new ComputerDetails(details);

                List<NvApp> parsedAppList = null;
                try {
                    if (detailsSnapshot.rawAppList != null) {
                        parsedAppList = NvHTTP.getAppListByReader(
                                new StringReader(detailsSnapshot.rawAppList));
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }

                final List<NvApp> finalParsedAppList = parsedAppList;
                final ComputerManagerService.AppListSnapshot appListSnapshot =
                        poller.getLatestSuccessfulSnapshot();
                AppView.this.runOnUiThread(() -> {
                    if (!isCurrentUpdateCallback(callbackGeneration, callbackSessionId)) {
                        return;
                    }

                    handleComputerUpdateOnMainThread(
                            detailsSnapshot,
                            finalParsedAppList,
                            appListSnapshot);
                });
            }
        });

        poller.start();
    }

    @MainThread
    private boolean isCurrentUpdateCallback(long callbackGeneration, long callbackSessionId) {
        if (!inForeground || !computerUpdatesStarted ||
                callbackGeneration != activeUpdateGeneration ||
                recoveryState == RecoveryState.CANCELLED) {
            return false;
        }

        if (callbackSessionId == StreamRecoveryStore.NO_SESSION_ID) {
            return !hasActiveRecoverySession();
        }

        return hasActiveRecoverySession() && callbackSessionId == recoverySessionId;
    }

    private void stopComputerUpdates() {
        computerUpdatesStarted = false;

        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        autoStartDesktopRequested = getIntent().getBooleanExtra(
                AUTO_START_DESKTOP_STREAM_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        requestedRecoverySessionId = getIntent().getLongExtra(
                Game.EXTRA_RECOVERY_SESSION_ID,
                StreamRecoveryStore.NO_SESSION_ID);
        StreamRecoveryStore.clearExpired(this);
        StreamRecoveryStore.RecoveryRecord pendingRecovery =
                loadPendingRecoveryForThisView();

        // A newly entered AppView represents a new user launch flow. Activity recreation
        // retains the explicit-termination suppression for the existing flow.
        if (savedInstanceState == null) {
            Game.terminatedByUser = false;
            fatalAutoDesktopLaunchBlocked = false;
            if (pendingRecovery == null &&
                    requestedRecoverySessionId == StreamRecoveryStore.NO_SESSION_ID) {
                Game.clearPendingFatalTermination();
            }
        } else {
            autoDesktopConfirmationCancelled = savedInstanceState.getBoolean(
                    AUTO_DESKTOP_CONFIRMATION_CANCELLED_STATE, false);
            fatalAutoDesktopLaunchBlocked = savedInstanceState.getBoolean(
                    FATAL_AUTO_DESKTOP_LAUNCH_BLOCKED_STATE, false);
        }

        if (fatalAutoDesktopLaunchBlocked) {
            if (pendingRecovery != null) {
                StreamRecoveryStore.clearIfSessionMatches(
                        this,
                        pendingRecovery.getSessionId(),
                        "appview_saved_fatal_block");
            }
            logRecoveryEvent(
                    "saved_b02_block",
                    pendingRecovery != null ?
                            pendingRecovery.getSessionId() :
                            requestedRecoverySessionId);
            pendingRecovery = null;
            recoveryState = RecoveryState.BLOCKED_FATAL;
        } else if (pendingRecovery != null) {
            activateRecovery(pendingRecovery);
        }

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        appFragmentContainer = findViewById(R.id.appFragmentContainer);
        appListLabel = findViewById(R.id.appListText);
        profilesButton = findViewById(R.id.profilesButton);
        recoveryOverlay = findViewById(R.id.streamRecoveryOverlay);
        recoveryStatus = findViewById(R.id.streamRecoveryStatus);
        recoveryProgress = findViewById(R.id.streamRecoveryProgress);
        recoveryCancel = findViewById(R.id.streamRecoveryCancel);
        recoveryCancel.setOnClickListener(v -> cancelRecovery("cancel_button"));

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        profilesButton.setOnClickListener(
                v -> startActivity(new Intent(this, ProfilesActivity.class)));

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        setTitle(computerName);
        appListLabel.setText(computerName);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);
        updateRecoveryUi();
        prepareForegroundUpdateGeneration(true);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(AUTO_DESKTOP_CONFIRMATION_CANCELLED_STATE,
                autoDesktopConfirmationCancelled || autoDesktopConfirmationPending);
        outState.putBoolean(FATAL_AUTO_DESKTOP_LAUNCH_BLOCKED_STATE,
                fatalAutoDesktopLaunchBlocked);
        super.onSaveInstanceState(outState);
    }

    private StreamRecoveryStore.RecoveryRecord loadPendingRecoveryForThisView() {
        StreamRecoveryStore.RecoveryRecord pending =
                StreamRecoveryStore.loadPendingRecovery(this, uuidString);
        if (pending != null &&
                requestedRecoverySessionId != StreamRecoveryStore.NO_SESSION_ID &&
                pending.getSessionId() != requestedRecoverySessionId) {
            LimeLog.warning("Stream recovery AppView: sessionId=" +
                    pending.getSessionId() +
                    " requestedSessionId=" + requestedRecoverySessionId +
                    " computerUuid=" + uuidString +
                    " state=" + recoveryState +
                    " reason=requested_session_mismatch_ignored");
        }

        // The durable pending session for this computer is authoritative. The
        // session carried by the Intent is diagnostic only because an existing
        // AppView can retain an older Intent across Activity/task reconstruction.
        return pending;
    }

    private void logRecoveryEvent(String reason, long sessionId) {
        LimeLog.info("Stream recovery AppView: sessionId=" + sessionId +
                " computerUuid=" + uuidString +
                " state=" + recoveryState +
                " reason=" + reason);
    }

    private void finishWithRecoveryLog(String reason) {
        logRecoveryEvent(reason, recoverySessionId);
        finish();
    }

    @MainThread
    private boolean hasActiveRecoverySession() {
        return recoveryRecord != null &&
                recoverySessionId != StreamRecoveryStore.NO_SESSION_ID &&
                recoveryState != RecoveryState.BLOCKED_FATAL &&
                recoveryState != RecoveryState.CANCELLED;
    }

    @MainThread
    private void activateRecovery(StreamRecoveryStore.RecoveryRecord record) {
        // Invalidate callbacks captured before this durable recovery session was
        // adopted. Callers that activate from a live callback must restart polling
        // so the replacement listener captures this session and generation.
        activeUpdateGeneration++;
        updateGenerationPrepared = false;
        recoveryRecord = record;
        recoverySessionId = record.getSessionId();
        recoveryState = RecoveryState.WAITING_FOR_HOST;
        recoveryTerminalMessageRes = 0;
        autoDesktopLaunchPending = false;
        autoDesktopLaunchDispatched = false;
        autoDesktopConfirmationPending = false;

        if (blockingLoadSpinner != null) {
            blockingLoadSpinner.dismiss();
            blockingLoadSpinner = null;
        }

        logRecoveryEvent("recovery_activated", recoverySessionId);
        updateRecoveryUi();
    }

    @MainThread
    private boolean reactivatePendingRecoveryFromHostCallback(String reason) {
        StreamRecoveryStore.RecoveryRecord pendingRecovery =
                loadPendingRecoveryForThisView();
        if (pendingRecovery == null) {
            return false;
        }

        activateRecovery(pendingRecovery);
        logRecoveryEvent(reason, pendingRecovery.getSessionId());

        // The current listener captured NO_SESSION_ID (otherwise this callback
        // could not have reached the ordinary host-state path). Replace it so
        // future callbacks remain valid for the newly adopted recovery session.
        if (computerUpdatesStarted) {
            stopComputerUpdates();
        }
        prepareForegroundUpdateGeneration(true);
        recoveryState = RecoveryState.WAITING_FOR_HOST;
        updateRecoveryUi();
        startComputerUpdates();
        return true;
    }

    @MainThread
    private void waitForRecoveryHost() {
        receivedServerInfo = false;
        requireFreshServerInfo = true;
        receivedFreshAppList = false;
        freshAppListSnapshot = null;
        freshRunningAppId = 0;
        freshRunningAppUuid = null;
        if (poller != null) {
            requiredAppListSuccessGeneration = poller.getSuccessGeneration();
        }
        recoveryState = RecoveryState.WAITING_FOR_HOST;
        updateRecoveryUi();
    }

    @MainThread
    private void deactivateRecovery() {
        activeUpdateGeneration++;
        updateGenerationPrepared = false;
        recoveryRecord = null;
        recoverySessionId = StreamRecoveryStore.NO_SESSION_ID;
        recoveryState = RecoveryState.IDLE;
        recoveryTerminalMessageRes = 0;
        updateRecoveryUi();
    }

    @MainThread
    private void prepareForegroundUpdateGeneration(boolean requireFreshState) {
        activeUpdateGeneration++;
        updateGenerationPrepared = true;
        receivedFreshAppList = false;
        freshAppListSnapshot = null;
        freshRunningAppId = 0;
        freshRunningAppUuid = null;

        if (requireFreshState) {
            beginFreshServerInfoWait();
        }

        if (hasActiveRecoverySession()) {
            recoveryState = RecoveryState.WAITING_FOR_FRESH_SERVER_INFO;
            updateRecoveryUi();
        }
    }

    @MainThread
    private void updateRecoveryUi() {
        if (recoveryOverlay == null) {
            return;
        }

        boolean visible = recoveryState != RecoveryState.IDLE &&
                recoveryState != RecoveryState.BLOCKED_FATAL;
        recoveryOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        appFragmentContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
        appListLabel.setVisibility(visible ? View.GONE : View.VISIBLE);
        profilesButton.setVisibility(visible ? View.GONE : View.VISIBLE);

        if (!visible) {
            return;
        }

        recoveryOverlay.bringToFront();
        recoveryCancel.setEnabled(true);
        recoveryCancel.setVisibility(View.VISIBLE);
        recoveryProgress.setVisibility(
                recoveryState == RecoveryState.CANCELLED ? View.GONE : View.VISIBLE);

        int messageRes;
        switch (recoveryState) {
            case WAITING_FOR_FRESH_SERVER_INFO:
                messageRes = R.string.stream_recovery_waiting_for_fresh_server_info;
                break;
            case WAITING_FOR_FRESH_APP_LIST:
                messageRes = R.string.stream_recovery_waiting_for_fresh_app_list;
                break;
            case READY_TO_LAUNCH:
                messageRes = R.string.stream_recovery_ready_to_launch;
                break;
            case LAUNCH_IN_FLIGHT:
                messageRes = R.string.stream_recovery_launch_in_flight;
                break;
            case CANCELLED:
                messageRes = recoveryTerminalMessageRes != 0 ?
                        recoveryTerminalMessageRes :
                        R.string.stream_recovery_waiting_for_host;
                break;
            case WAITING_FOR_HOST:
            default:
                messageRes = R.string.stream_recovery_waiting_for_host;
                break;
        }
        recoveryStatus.setText(messageRes);
    }

    @MainThread
    private void cancelRecovery(String reason) {
        long sessionId = recoverySessionId;
        activeUpdateGeneration++;
        updateGenerationPrepared = false;
        recoveryState = RecoveryState.CANCELLED;
        recoveryRecord = null;

        if (sessionId != StreamRecoveryStore.NO_SESSION_ID) {
            StreamRecoveryStore.cancel(
                    this,
                    sessionId,
                    "appview_user_cancelled");
        }

        stopComputerUpdates();
        if (isTaskRoot()) {
            Intent pcViewIntent = new Intent(this, PcView.class);
            pcViewIntent.setAction(Intent.ACTION_MAIN);
            pcViewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(pcViewIntent);
        }
        finishWithRecoveryLog(reason);
    }

    @MainThread
    private void stopRecoveryWithMessage(int messageRes, String reason) {
        long sessionId = recoverySessionId;
        activeUpdateGeneration++;
        updateGenerationPrepared = false;

        if (sessionId != StreamRecoveryStore.NO_SESSION_ID) {
            StreamRecoveryStore.clearIfSessionMatches(
                    this,
                    sessionId,
                    reason);
        }

        logRecoveryEvent(reason, sessionId);
        recoveryRecord = null;
        recoveryState = RecoveryState.CANCELLED;
        recoveryTerminalMessageRes = messageRes;
        stopComputerUpdates();
        updateRecoveryUi();
    }

    @MainThread
    private void blockRecoveryForFatalTermination(long fatalRecoverySessionId,
                                                   long pendingRecoverySessionId) {
        if (fatalRecoverySessionId != StreamRecoveryStore.NO_SESSION_ID) {
            StreamRecoveryStore.clearIfSessionMatches(
                    this,
                    fatalRecoverySessionId,
                    "appview_b02_fatal");
        } else if (recoverySessionId != StreamRecoveryStore.NO_SESSION_ID) {
            StreamRecoveryStore.clearIfSessionMatches(
                    this,
                    recoverySessionId,
                    "appview_b02_fatal");
        } else if (pendingRecoverySessionId != StreamRecoveryStore.NO_SESSION_ID) {
            StreamRecoveryStore.clearIfSessionMatches(
                    this,
                    pendingRecoverySessionId,
                    "appview_b02_fatal");
        }

        long blockedSessionId =
                fatalRecoverySessionId != StreamRecoveryStore.NO_SESSION_ID ?
                        fatalRecoverySessionId :
                        (recoverySessionId != StreamRecoveryStore.NO_SESSION_ID ?
                                recoverySessionId :
                                pendingRecoverySessionId);
        logRecoveryEvent("b02_fatal_blocked", blockedSessionId);
        activeUpdateGeneration++;
        updateGenerationPrepared = false;
        recoveryRecord = null;
        recoverySessionId = StreamRecoveryStore.NO_SESSION_ID;
        recoveryState = RecoveryState.BLOCKED_FATAL;
        recoveryTerminalMessageRes = 0;
        autoDesktopLaunchPending = false;
        autoDesktopLaunchDispatched = false;
        updateRecoveryUi();
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // Recovery has its own non-modal, opaque waiting UI. Never place the
            // legacy blocking app-list dialog over it.
            if (recoverySessionId == StreamRecoveryStore.NO_SESSION_ID) {
                loadAppsBlocking();
            }
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        if (hasActiveRecoverySession() && !isFinishing()) {
            logRecoveryEvent(
                    isChangingConfigurations() ?
                            "activity_destroyed_for_configuration_change" :
                            "activity_destroyed_without_finish",
                    recoverySessionId);
        }

        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        String incomingComputerUuid = intent.getStringExtra(UUID_EXTRA);
        if (incomingComputerUuid != null && uuidString != null &&
                !incomingComputerUuid.equalsIgnoreCase(uuidString)) {
            // This instance owns an adapter, poller, and service state for uuidString.
            // Start a clean instance rather than carrying this host's saved state into
            // the incoming host through recreate().
            StreamRecoveryStore.RecoveryRecord pendingRecovery =
                    loadPendingRecoveryForThisView();
            StreamRecoveryStore.cancel(
                    this,
                    pendingRecovery != null ?
                            pendingRecovery.getSessionId() :
                            recoverySessionId,
                    "appview_different_computer_intent");
            activeUpdateGeneration++;
            updateGenerationPrepared = false;
            stopComputerUpdates();
            Intent replacementIntent = new Intent(intent);
            replacementIntent.setFlags(intent.getFlags() &
                    ~(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finishWithRecoveryLog("different_computer_intent");
            startActivity(replacementIntent);
            return;
        }

        setIntent(intent);
        requestedRecoverySessionId = intent.getLongExtra(
                Game.EXTRA_RECOVERY_SESSION_ID,
                StreamRecoveryStore.NO_SESSION_ID);

        // CLEAR_TOP | SINGLE_TOP delivers recovery to the existing AppView. Hide
        // its grid and invalidate old callbacks before onResume can draw a frame.
        activeUpdateGeneration++;
        updateGenerationPrepared = false;
        if (computerUpdatesStarted) {
            stopComputerUpdates();
        }
        StreamRecoveryStore.RecoveryRecord pendingRecovery =
                loadPendingRecoveryForThisView();
        if (pendingRecovery != null) {
            activateRecovery(pendingRecovery);
            updateRecoveryUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean requireFreshStateAfterLaunch = autoDesktopLaunchDispatched;
        Game.FatalTerminationEvent fatalTermination =
                Game.consumeFatalTerminationForComputer(uuidString);
        StreamRecoveryStore.RecoveryRecord unfilteredPendingRecovery =
                loadPendingRecoveryForThisView();
        boolean staleFatalTermination = fatalTermination != null &&
                fatalTermination.getRecoverySessionId() !=
                        StreamRecoveryStore.NO_SESSION_ID &&
                unfilteredPendingRecovery != null &&
                fatalTermination.getRecoverySessionId() !=
                        unfilteredPendingRecovery.getSessionId();
        boolean recoveryChanged = false;

        // B02 takes priority over every recovery path. Consume and clear the matching
        // token before looking for a pending recovery session. A fatal callback from
        // an older recovery session must not clear or block a newer session.
        if (fatalTermination != null && !staleFatalTermination) {
            fatalAutoDesktopLaunchBlocked = true;
            blockRecoveryForFatalTermination(
                    fatalTermination.getRecoverySessionId(),
                    unfilteredPendingRecovery != null ?
                            unfilteredPendingRecovery.getSessionId() :
                            StreamRecoveryStore.NO_SESSION_ID);
        } else {
            if (staleFatalTermination) {
                LimeLog.warning("Stream recovery AppView: sessionId=" +
                        fatalTermination.getRecoverySessionId() +
                        " computerUuid=" + uuidString +
                        " state=" + recoveryState +
                        " reason=stale_b02_ignored");
            }
            StreamRecoveryStore.RecoveryRecord pendingRecovery =
                    unfilteredPendingRecovery;
            if (pendingRecovery != null) {
                if (recoverySessionId != pendingRecovery.getSessionId() ||
                        !hasActiveRecoverySession()) {
                    activateRecovery(pendingRecovery);
                    recoveryChanged = true;
                } else {
                    recoveryRecord = pendingRecovery;
                }
            } else if (hasActiveRecoverySession() ||
                    recoveryState == RecoveryState.LAUNCH_IN_FLIGHT) {
                deactivateRecovery();
                recoveryChanged = true;
            }
        }

        // A dispatched launch pauses AppView. If this instance becomes visible again,
        // allow the coordinator to handle a legitimate lifecycle resume.
        if (requireFreshStateAfterLaunch) {
            autoDesktopLaunchPending = false;
            autoDesktopLaunchDispatched = false;
        }

        inForeground = true;
        if (recoveryState == RecoveryState.CANCELLED) {
            updateRecoveryUi();
            return;
        }

        if (firstResumePending) {
            firstResumePending = false;
            if (recoveryChanged || !updateGenerationPrepared) {
                prepareForegroundUpdateGeneration(
                        hasActiveRecoverySession() || requireFreshStateAfterLaunch);
            }
        } else {
            prepareForegroundUpdateGeneration(
                    hasActiveRecoverySession() || requireFreshStateAfterLaunch);
        }
        updateRecoveryUi();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        startComputerUpdates();

        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
    }

    @MainThread
    private void beginFreshServerInfoWait() {
        receivedServerInfo = false;
        requireFreshServerInfo = true;
        invalidatedStateForFreshServerInfo = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // Handles dialog dismissal through Back/outside-touch in addition to the Cancel button.
        if (hasFocus && autoDesktopConfirmationPending && !autoDesktopLaunchDispatched) {
            autoDesktopConfirmationPending = false;
            autoDesktopLaunchPending = false;
            autoDesktopConfirmationCancelled = true;
        }
    }

    @Override
    public void onBackPressed() {
        if (recoveryState != RecoveryState.IDLE &&
                recoveryState != RecoveryState.BLOCKED_FATAL) {
            cancelRecovery("back_pressed_active_recovery");
            return;
        }

        logRecoveryEvent("back_pressed_normal", recoverySessionId);
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        activeUpdateGeneration++;
        updateGenerationPrepared = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        int itemId = item.getItemId();
        switch (itemId) {
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                startAppManually(app.app, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            startAppManually(app.app, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> startAppManually(app.app, true),
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    startAppManually(app.app, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    @MainThread
    private void handleComputerUpdateOnMainThread(
            ComputerDetails details,
            List<NvApp> parsedAppList,
            ComputerManagerService.AppListSnapshot appListSnapshot) {
        if (details.state == ComputerDetails.State.OFFLINE) {
            if (!hasActiveRecoverySession() &&
                    reactivatePendingRecoveryFromHostCallback(
                            "offline_callback_pending_reactivated")) {
                return;
            }

            if (hasActiveRecoverySession()) {
                waitForRecoveryHost();
                return;
            }

            Toast.makeText(
                    AppView.this,
                    R.string.lost_connection,
                    Toast.LENGTH_SHORT).show();
            finishWithRecoveryLog("ordinary_offline_callback");
            return;
        }

        if (details.state == ComputerDetails.State.UNKNOWN) {
            if (!hasActiveRecoverySession() &&
                    reactivatePendingRecoveryFromHostCallback(
                            "unknown_callback_pending_reactivated")) {
                return;
            }

            if (hasActiveRecoverySession()) {
                waitForRecoveryHost();
            } else {
                // Preserve the ordinary UNKNOWN running-marker behavior.
                updateUiWithServerinfo(details);
            }
            return;
        }

        if (details.state == ComputerDetails.State.ONLINE &&
                details.pairState != PairingManager.PairState.PAIRED) {
            shortcutHelper.disableComputerShortcut(
                    details,
                    getResources().getString(R.string.scut_not_paired));

            if (!hasActiveRecoverySession()) {
                StreamRecoveryStore.RecoveryRecord pendingRecovery =
                        loadPendingRecoveryForThisView();
                if (pendingRecovery != null) {
                    activateRecovery(pendingRecovery);
                }
            }

            if (hasActiveRecoverySession()) {
                stopRecoveryWithMessage(
                        R.string.stream_recovery_host_unpaired,
                        "appview_host_unpaired");
            } else {
                Toast.makeText(
                        AppView.this,
                        R.string.scut_not_paired,
                        Toast.LENGTH_SHORT).show();
                finishWithRecoveryLog("ordinary_host_unpaired");
            }
            return;
        }

        if (details.state != ComputerDetails.State.ONLINE ||
                details.pairState != PairingManager.PairState.PAIRED) {
            // Any future non-ONLINE state is not fresh server information and
            // must never release the auto-launch gate.
            if (!hasActiveRecoverySession()) {
                updateUiWithServerinfo(details);
            }
            return;
        }

        boolean firstFreshServerInfoForGeneration =
                !receivedServerInfo || requireFreshServerInfo;
        receivedServerInfo = true;
        requireFreshServerInfo = false;
        lastRunningAppId = details.runningGameId;
        freshRunningAppId = details.runningGameId;
        freshRunningAppUuid = details.runningGameUUID;

        if (hasActiveRecoverySession() &&
                firstFreshServerInfoForGeneration && poller != null) {
            // A previous foreground generation may have completed an app-list fetch,
            // which normally moves the poller to its long interval. Request a new one
            // immediately now that this generation has fresh server information.
            poller.pollNow();
        }

        boolean appListChanged = details.rawAppList != null &&
                !details.rawAppList.equals(lastRawApplist);
        boolean appListReady = true;

        if (appListChanged) {
            if (parsedAppList == null) {
                // Keep the previous raw list so a later callback can retry parsing.
                appListReady = false;
            } else {
                lastRawApplist = details.rawAppList;
                updateUiWithAppList(parsedAppList);

                if (blockingLoadSpinner != null) {
                    blockingLoadSpinner.dismiss();
                    blockingLoadSpinner = null;
                }
            }
        }

        if (!hasActiveRecoverySession()) {
            updateUiWithServerinfo(details);
        }

        if (appListSnapshot != null &&
                appListSnapshot.getGeneration() > requiredAppListSuccessGeneration) {
            receivedFreshAppList = true;
            freshAppListSnapshot = appListSnapshot;
        }

        if (hasActiveRecoverySession()) {
            if (!receivedFreshAppList || freshAppListSnapshot == null) {
                recoveryState = RecoveryState.WAITING_FOR_FRESH_APP_LIST;
                updateRecoveryUi();
                return;
            }

            coordinateRecoveryLaunch(freshAppListSnapshot.getApps());
        } else if (appListReady) {
            // Preserve the ordinary, non-recovery coordinator behavior. Only recovery
            // requires the new app-list success-generation latch.
            coordinateAutoDesktopLaunch();
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private boolean isDesktopApp(NvApp app) {
        return app != null && isDesktopName(app.getAppName());
    }

    private boolean isDesktopName(String name) {
        if (name == null) return false;

        name = name.trim();
        return name.equalsIgnoreCase("Desktop") || name.equals("桌面");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean namesMatch(String first, String second) {
        return hasText(first) && hasText(second) &&
                first.trim().equalsIgnoreCase(second.trim());
    }

    private boolean uuidsMatch(String first, String second) {
        return hasText(first) && hasText(second) &&
                first.trim().equalsIgnoreCase(second.trim());
    }

    private NvApp resolveRecoveryTarget(List<NvApp> freshApps) {
        if (recoveryRecord == null || freshApps == null) {
            return null;
        }

        String targetUuid = recoveryRecord.getAppUuid();
        if (hasText(targetUuid)) {
            NvApp uuidMatch = null;
            for (NvApp app : freshApps) {
                if (uuidsMatch(targetUuid, app.getAppUUID())) {
                    if (uuidMatch != null) {
                        return null;
                    }
                    uuidMatch = app;
                }
            }
            if (uuidMatch != null) {
                return uuidMatch;
            }
        }

        int targetAppId = recoveryRecord.getAppId();
        String targetName = recoveryRecord.getAppName();
        if (targetAppId > 0 && hasText(targetName)) {
            NvApp idAndNameMatch = null;
            for (NvApp app : freshApps) {
                if (app.getAppId() == targetAppId &&
                        namesMatch(targetName, app.getAppName())) {
                    if (idAndNameMatch != null) {
                        return null;
                    }
                    idAndNameMatch = app;
                }
            }
            if (idAndNameMatch != null) {
                return idAndNameMatch;
            }
        }

        if (hasText(targetName)) {
            NvApp uniqueNameMatch = null;
            for (NvApp app : freshApps) {
                if (namesMatch(targetName, app.getAppName())) {
                    if (uniqueNameMatch != null) {
                        return null;
                    }
                    uniqueNameMatch = app;
                }
            }
            if (uniqueNameMatch != null) {
                return uniqueNameMatch;
            }
        }

        // Only a token that originally targeted Desktop may use the localized
        // Desktop-name fallback after a host configuration change.
        if (isDesktopName(targetName)) {
            NvApp uniqueDesktopMatch = null;
            for (NvApp app : freshApps) {
                if (isDesktopApp(app)) {
                    if (uniqueDesktopMatch != null) {
                        return null;
                    }
                    uniqueDesktopMatch = app;
                }
            }
            return uniqueDesktopMatch;
        }

        return null;
    }

    @MainThread
    private void coordinateRecoveryLaunch(List<NvApp> freshApps) {
        if (!hasActiveRecoverySession() ||
                recoveryState == RecoveryState.LAUNCH_IN_FLIGHT ||
                !receivedServerInfo || requireFreshServerInfo ||
                !receivedFreshAppList || freshAppListSnapshot == null ||
                fatalAutoDesktopLaunchBlocked || Game.terminatedByUser) {
            return;
        }

        NvApp targetApp = resolveRecoveryTarget(freshApps);
        if (targetApp == null) {
            stopRecoveryWithMessage(
                    R.string.stream_recovery_target_missing,
                    "appview_target_missing");
            return;
        }

        boolean hasRunningApp = freshRunningAppId != 0 ||
                hasText(freshRunningAppUuid);
        boolean runningTarget;
        if (hasText(freshRunningAppUuid) && hasText(targetApp.getAppUUID())) {
            runningTarget = uuidsMatch(
                    freshRunningAppUuid,
                    targetApp.getAppUUID());
        } else {
            runningTarget = freshRunningAppId != 0 &&
                    freshRunningAppId == targetApp.getAppId();
        }

        if (hasRunningApp && !runningTarget) {
            stopRecoveryWithMessage(
                    R.string.stream_recovery_other_app_running,
                    "appview_other_app_running");
            return;
        }

        recoveryState = RecoveryState.READY_TO_LAUNCH;
        updateRecoveryUi();
        dispatchRecoveryLaunch(targetApp);
    }

    @MainThread
    private void dispatchRecoveryLaunch(NvApp targetApp) {
        if (!hasActiveRecoverySession() ||
                recoveryState == RecoveryState.LAUNCH_IN_FLIGHT ||
                !inForeground || managerBinder == null || computer == null ||
                computer.state != ComputerDetails.State.ONLINE ||
                computer.pairState != PairingManager.PairState.PAIRED ||
                computer.activeAddress == null || !receivedServerInfo ||
                requireFreshServerInfo || !receivedFreshAppList ||
                fatalAutoDesktopLaunchBlocked) {
            return;
        }

        long sessionId = recoverySessionId;
        if (!StreamRecoveryStore.markLaunchInFlight(this, sessionId)) {
            stopRecoveryWithMessage(
                    R.string.stream_recovery_already_attempted,
                    "appview_launch_already_attempted");
            return;
        }

        boolean withVirtualDisplay = recoveryRecord.isWithVirtualDisplay();
        recoveryState = RecoveryState.LAUNCH_IN_FLIGHT;
        autoDesktopLaunchPending = true;
        autoDesktopLaunchDispatched = true;
        updateRecoveryUi();
        boolean started = ServerHelper.doStart(
                AppView.this,
                targetApp,
                computer,
                managerBinder,
                withVirtualDisplay,
                sessionId);
        if (!started) {
            autoDesktopLaunchPending = false;
            autoDesktopLaunchDispatched = false;
            stopRecoveryWithMessage(
                    R.string.stream_recovery_host_unavailable,
                    "appview_host_unavailable_before_launch");
        }
    }

    @MainThread
    private void startAppManually(NvApp app, boolean withVirtualDisplay) {
        // Reaching this method means the user completed any required confirmation and
        // is starting a new attempt. Automatic launches never pass through this method.
        fatalAutoDesktopLaunchBlocked = false;
        if (recoveryState == RecoveryState.BLOCKED_FATAL) {
            recoveryState = RecoveryState.IDLE;
            updateRecoveryUi();
        }
        ServerHelper.doStart(AppView.this, app, computer, managerBinder, withVirtualDisplay);
    }

    @MainThread
    private void coordinateAutoDesktopLaunch() {
        if (computer == null || computer.state != ComputerDetails.State.ONLINE ||
                computer.pairState != PairingManager.PairState.PAIRED ||
                !receivedServerInfo || requireFreshServerInfo ||
                autoDesktopLaunchPending || hasActiveRecoverySession() ||
                autoDesktopConfirmationCancelled || fatalAutoDesktopLaunchBlocked ||
                Game.terminatedByUser) {
            return;
        }

        NvApp desktopApp = null;
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject obj = (AppObject) appGridAdapter.getItem(i);
            if (isDesktopApp(obj.app)) {
                desktopApp = obj.app;
                break;
            }
        }

        if (desktopApp == null) {
            return;
        }

        boolean shouldAutoResume = lastRunningAppId == desktopApp.getAppId();
        boolean shouldAutoStart = autoStartDesktopRequested && lastRunningAppId == 0;
        if (!shouldAutoResume && !shouldAutoStart) {
            // Another app is running, or cold-start auto Desktop is disabled.
            return;
        }

        if (prefConfig.useVirtualDisplay &&
                !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
            final NvApp finalDesktopApp = desktopApp;
            final long confirmationGeneration = activeUpdateGeneration;
            autoDesktopLaunchPending = true;
            autoDesktopConfirmationPending = true;
            UiHelper.displayVdisplayConfirmationDialog(
                    AppView.this,
                    computer,
                    () -> {
                        if (!inForeground ||
                                confirmationGeneration != activeUpdateGeneration ||
                                hasActiveRecoverySession()) {
                            return;
                        }
                        autoDesktopConfirmationPending = false;
                        dispatchAutoDesktopLaunch(finalDesktopApp, true);
                    },
                    () -> {
                        if (confirmationGeneration != activeUpdateGeneration) {
                            return;
                        }
                        autoDesktopConfirmationPending = false;
                        autoDesktopLaunchPending = false;
                        autoDesktopConfirmationCancelled = true;
                    }
            );
        } else {
            dispatchAutoDesktopLaunch(desktopApp, prefConfig.useVirtualDisplay);
        }
    }

    @MainThread
    private void dispatchAutoDesktopLaunch(NvApp desktopApp, boolean withVirtualDisplay) {
        if (!inForeground || fatalAutoDesktopLaunchBlocked ||
                hasActiveRecoverySession() || managerBinder == null || computer == null ||
                computer.state != ComputerDetails.State.ONLINE ||
                computer.pairState != PairingManager.PairState.PAIRED ||
                !receivedServerInfo || requireFreshServerInfo ||
                computer.activeAddress == null) {
            autoDesktopLaunchPending = false;
            autoDesktopLaunchDispatched = false;
            return;
        }

        autoDesktopLaunchPending = true;
        autoDesktopLaunchDispatched = true;
        ServerHelper.doStart(AppView.this, desktopApp, computer, managerBinder, withVirtualDisplay);
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }

            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(pos);

                // Only open the context menu if something is running, otherwise start it
                if (lastRunningAppId != 0) {
                    if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                        startAppManually(app.app, prefConfig.useVirtualDisplay);
                    } else {
                        openContextMenu(arg1);
                    }
                } else {
                    if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                        UiHelper.displayVdisplayConfirmationDialog(
                                AppView.this,
                                computer,
                                () -> startAppManually(app.app, true),
                                null
                        );
                    } else {
                        startAppManually(app.app, prefConfig.useVirtualDisplay);
                    }
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
        listView.requestFocus();
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
