package com.termux.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.os.Build;
import android.os.Process;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import com.termux.BuildConfig;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import java.io.File;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.media.AudioAttributes;
import android.content.res.AssetFileDescriptor;
import android.view.inputmethod.InputMethodManager;
import com.termux.shared.view.KeyboardUtils;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.VideoView;
import android.net.Uri;
import android.widget.Toast;
import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.packages.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView;
import com.termux.app.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.app.utils.CrashUtils;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends Activity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionClient mTermuxTerminalSessionClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    OpenIpcServer mOpenIpcServer;

    View mLoadingSplashOverlay;
    boolean mSplashDismissed = false;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean isOnResumeAfterOnCreate = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private AlertDialog mCurrentSetupDialog = null;
    private boolean mBridgeSetupDismissedByChoice = false;
    private boolean mPayloadReady = false;

    private boolean isBridgeSetupCompleted() {
        if (mBridgeSetupDismissedByChoice) return true;
        try {
            SharedPreferences prefs = getSharedPreferences("open_installer_prefs", Context.MODE_PRIVATE);
            return prefs.getBoolean("bridge_setup_configured", false);
        } catch (Exception e) {
            return false;
        }
    }

    private void markBridgeSetupCompleted() {
        mBridgeSetupDismissedByChoice = true;
        try {
            getSharedPreferences("open_installer_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("bridge_setup_configured", true)
                .apply();
        } catch (Exception ignored) {}
    }

    private int mNavBarHeight;

    private int mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {

        Logger.logDebug(LOG_TAG, "onCreate");
        isOnResumeAfterOnCreate = true;

        // Check if a crash happened on last run of the app and show a
        // notification with the crash details if it did
        CrashUtils.notifyAppCrashOnLastRun(this, LOG_TAG);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load termux shared properties
        mProperties = new TermuxAppSharedProperties(this);

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);
        mLoadingSplashOverlay = findViewById(R.id.loading_splash_overlay);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mOpenIpcServer = new OpenIpcServer(this);
        mOpenIpcServer.start();

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setDrawerTheme();

        try {
            OpenAlarmReceiver.scheduleDailyDream(this);
        } catch (Exception ignored) {}

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);

        // Start the {@link TermuxService} and make it run regardless of who is bound to it
        Intent serviceIntent = new Intent(this, TermuxService.class);
        startService(serviceIntent);

        // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
        // callback if it succeeds.
        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        isOnResumeAfterOnCreate = false;

        boolean allPermissionsGranted = OpenAccessService.isOn() && hasStoragePermission();

        if (allPermissionsGranted) {
            markBridgeSetupCompleted();
            if (mCurrentSetupDialog != null && mCurrentSetupDialog.isShowing()) {
                try {
                    mCurrentSetupDialog.dismiss();
                } catch (Exception ignored) {}
                mCurrentSetupDialog = null;
            }
            TermuxInstaller.setupStorageSymlinks(this);
            startInitialSessionIfReady();
        } else if (isBridgeSetupCompleted()) {
            startInitialSessionIfReady();
        } else {
            checkBridgeAndPromptIfNeeded();
        }
    }

    private synchronized void startInitialSessionIfReady() {
        if (mTermuxService == null || !mIsVisible || mIsInvalidState) return;

        if (!mPayloadReady) {
            File marker = new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".open-setup-done");
            File zshBin = new File(com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "zsh");
            if (marker.exists() && zshBin.exists()) {
                mPayloadReady = true;
            } else {
                return; // Wait for payload extraction
            }
        }

        if (!isBridgeSetupCompleted() && (!OpenAccessService.isOn() || !hasStoragePermission())) {
            checkBridgeAndPromptIfNeeded();
            return;
        }

        // Synchronously guarantee open-debian and bridge binaries are present
        TermuxInstaller.ensureOpenDebianBinary(this);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mTermuxService == null || !mIsVisible || mIsInvalidState) return;
                if (!isBridgeSetupCompleted() && mCurrentSetupDialog != null && mCurrentSetupDialog.isShowing()) return;
                if (!mTermuxService.isTermuxSessionsEmpty()) return;

                // Launch terminal session directly
                try {
                    Bundle bundle = getIntent().getExtras();
                    boolean launchFailsafe = false;
                    if (bundle != null) {
                        launchFailsafe = bundle.getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                    }
                    mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);
                    if (mTerminalView != null) {
                        mTerminalView.postDelayed(TermuxActivity.this::dismissSplash, 15000);
                    }
                } catch (WindowManager.BadTokenException ignored) {}
            });
        }
    }

    public boolean isSplashVisible() {
        return mLoadingSplashOverlay != null && mLoadingSplashOverlay.getVisibility() == View.VISIBLE && !mSplashDismissed;
    }

    public void checkDismissSplash(TerminalSession session) {
        if (mSplashDismissed || mLoadingSplashOverlay == null) return;
        if (session == null || session.getEmulator() == null) return;

        boolean isAlt = session.getEmulator().isAlternateBufferActive();
        String transcript = "";
        try {
            transcript = session.getEmulator().getScreen().getTranscriptText();
        } catch (Exception ignored) {}

        if (isAlt || transcript.contains("opencode") || transcript.contains("OpenCode") || transcript.contains("ask anything") || transcript.contains("Build")) {
            dismissSplash();
        }
    }

    public void dismissSplash() {
        if (mSplashDismissed) return;
        mSplashDismissed = true;
        runOnUiThread(() -> {
            if (mLoadingSplashOverlay != null && mLoadingSplashOverlay.getVisibility() == View.VISIBLE) {
                mLoadingSplashOverlay.animate()
                    .alpha(0f)
                    .setDuration(350)
                    .withEndAction(() -> {
                        mLoadingSplashOverlay.setVisibility(View.GONE);
                    })
                    .start();
            }
        });
    }

    private boolean isRestrictedSettingsAllowed() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps != null) {
                int mode = appOps.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void openAppDetailsSettings() {
        Intent[] candidates = new Intent[]{
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
            new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            new Intent(Settings.ACTION_APPLICATION_SETTINGS),
            new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent it : candidates) {
            try {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return;
            } catch (Exception ignored) {}
        }
        Toast.makeText(this, "Open Settings > Apps > OpenCode", Toast.LENGTH_LONG).show();
    }

    private void showBridgeSetupDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;
        if (mCurrentSetupDialog != null && mCurrentSetupDialog.isShowing()) return;

        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (22 * density);
        int padV = (int) (14 * density);
        int btnPadV = (int) (10 * density);
        int marginV = (int) (5 * density);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(padH, padV, padH, padV);

        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setTextSize(14);
        msg.setTextColor(Color.WHITE);
        msg.setLineSpacing(0, 1.25f);
        msg.setText("To grant OpenCode full device control (tap, keys, screen capture, and app installation), follow these steps:\n\n" +
                    "1️⃣ Tap '1. Go to Accessibility' and select OpenCode.\n" +
                    "   (If Android displays the 'Restricted setting' warning, return here and tap step 2).\n\n" +
                    "2️⃣ If you see 'Restricted setting': Tap '2. Unlock (3 dots)', tap the 3 dots (⋮) in the top right corner, and select 'Allow restricted settings'.\n\n" +
                    "3️⃣ Tap '3. Files Access' to grant permission to manage all files.\n\n" +
                    "4️⃣ Return here and tap 'Continue to OpenCode' if it does not launch automatically.");
        layout.addView(msg);

        View sep = new View(this);
        android.widget.LinearLayout.LayoutParams sepLp = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (12 * density));
        layout.addView(sep, sepLp);

        // 1. Go to Accessibility
        androidx.appcompat.widget.AppCompatButton btn1 = new androidx.appcompat.widget.AppCompatButton(this);
        btn1.setText("1. Go to Accessibility");
        btn1.setTextSize(14);
        btn1.setAllCaps(false);
        btn1.setTextColor(Color.parseColor("#448AFF"));
        btn1.setPadding(padH, btnPadV, padH, btnPadV);
        android.widget.LinearLayout.LayoutParams lp1 = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.setMargins(0, marginV, 0, marginV);
        btn1.setLayoutParams(lp1);
        btn1.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Open Settings > Accessibility > OpenCode", Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(btn1);

        // 2. Unlock (3 dots)
        androidx.appcompat.widget.AppCompatButton btn2 = new androidx.appcompat.widget.AppCompatButton(this);
        btn2.setText("2. Unlock (3 dots)");
        btn2.setTextSize(14);
        btn2.setAllCaps(false);
        btn2.setTextColor(Color.parseColor("#FFA000"));
        btn2.setPadding(padH, btnPadV, padH, btnPadV);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.setMargins(0, marginV, 0, marginV);
        btn2.setLayoutParams(lp2);
        btn2.setOnClickListener(v -> openAppDetailsSettings());
        layout.addView(btn2);

        // 3. Files Access
        androidx.appcompat.widget.AppCompatButton btn3 = new androidx.appcompat.widget.AppCompatButton(this);
        btn3.setText("3. Files Access");
        btn3.setTextSize(14);
        btn3.setAllCaps(false);
        btn3.setTextColor(Color.parseColor("#00E676"));
        btn3.setPadding(padH, btnPadV, padH, btnPadV);
        android.widget.LinearLayout.LayoutParams lp3 = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.setMargins(0, marginV, 0, marginV);
        btn3.setLayoutParams(lp3);
        btn3.setOnClickListener(v -> requestStoragePermission());
        layout.addView(btn3);

        // 4. Continue to OpenCode
        androidx.appcompat.widget.AppCompatButton btn4 = new androidx.appcompat.widget.AppCompatButton(this);
        btn4.setText("Continue to OpenCode");
        btn4.setTextSize(14);
        btn4.setAllCaps(false);
        btn4.setTextColor(Color.parseColor("#B0BEC5"));
        btn4.setPadding(padH, btnPadV, padH, btnPadV);
        android.widget.LinearLayout.LayoutParams lp4 = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp4.setMargins(0, marginV, 0, marginV);
        btn4.setLayoutParams(lp4);
        btn4.setOnClickListener(v -> {
            markBridgeSetupCompleted();
            if (mCurrentSetupDialog != null) {
                try { mCurrentSetupDialog.dismiss(); } catch (Exception ignored) {}
                mCurrentSetupDialog = null;
            }
            startInitialSessionIfReady();
        });
        layout.addView(btn4);

        scrollView.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("⚡ Total Control Setup")
            .setView(scrollView)
            .setCancelable(false);

        mCurrentSetupDialog = builder.create();
        mCurrentSetupDialog.setCanceledOnTouchOutside(false);
        mCurrentSetupDialog.setOnDismissListener(d -> {
            markBridgeSetupCompleted();
        });
        mCurrentSetupDialog.show();
    }

    private void checkBridgeAndPromptIfNeeded() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;

        if (isBridgeSetupCompleted()) {
            startInitialSessionIfReady();
            return;
        }

        try {
            OpenAccessService.syncBridgeScripts(this);
        } catch (Exception ignored) {}

        // Auto-activation attempt if WRITE_SECURE_SETTINGS permission exists
        try {
            String serviceName = getPackageName() + "/" + OpenAccessService.class.getName();
            String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || !enabledServices.contains(serviceName)) {
                String newServices = (enabledServices == null || enabledServices.isEmpty()) ? serviceName : enabledServices + ":" + serviceName;
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices);
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Exception ignored) {}

        boolean a11yOn = OpenAccessService.isOn();
        boolean storageOn = hasStoragePermission();

        if (storageOn) {
            try {
                TermuxInstaller.setupStorageSymlinks(this);
            } catch (Exception ignored) {}
        }

        if (a11yOn && storageOn) {
            markBridgeSetupCompleted();
            if (mCurrentSetupDialog != null && mCurrentSetupDialog.isShowing()) {
                try {
                    mCurrentSetupDialog.dismiss();
                } catch (Exception ignored) {}
                mCurrentSetupDialog = null;
            }
            startInitialSessionIfReady();
            return;
        }

        showBridgeSetupDialog();
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiever();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }

        if (mOpenIpcServer != null) {
            mOpenIpcServer.stop();
            mOpenIpcServer = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {

        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        SharedPreferences prefs = getSharedPreferences("open_installer_prefs", Context.MODE_PRIVATE);
        int lastVer = prefs.getInt("last_installed_version", 0);
        boolean isNewVersion = lastVer < BuildConfig.VERSION_CODE;

        if (isNewVersion || mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                if (!mTermuxService.isTermuxSessionsEmpty() && isNewVersion) {
                    for (int s = mTermuxService.getTermuxSessionsSize() - 1; s >= 0; s--) {
                        com.termux.shared.shell.TermuxSession stale = mTermuxService.getTermuxSession(s);
                        if (stale != null && stale.getTerminalSession() != null) {
                            stale.getTerminalSession().finishIfRunning();
                        }
                    }
                }
                TermuxInstaller.setupOpenIfNeeded(TermuxActivity.this, () -> {
                    mPayloadReady = true;
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    startInitialSessionIfReady();
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            Intent i = getIntent();
            if (i != null && Intent.ACTION_RUN.equals(i.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = i.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionClient.setCurrentSession(mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }





    private void setActivityTheme() {
        if (mProperties.isUsingBlackUI()) {
            this.setTheme(R.style.Theme_Termux_Black);
        } else {
            this.setTheme(R.style.Theme_Termux);
        }
    }

    private void setDrawerTheme() {
        if (mProperties.isUsingBlackUI()) {
            findViewById(R.id.left_drawer).setBackgroundColor(ContextCompat.getColor(this,
                android.R.color.background_dark));
            ((ImageButton) findViewById(R.id.settings_button)).setColorFilter(Color.WHITE);
        }
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionClient = new TermuxTerminalSessionClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = (int) Math.round(mTerminalToolbarDefaultHeight *
            (mProperties.getExtraKeysInfo() == null ? 0 : mProperties.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView =  findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        // Filter unwanted toast banners like [1], (1), session numbers or automatic titles
        if (text.trim().matches(".*[\\[\\(]?\\d+[\\]\\)]?.*") || text.contains("exited") || text.contains("session")) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionClient != null)
                mTermuxTerminalSessionClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install, (dialog, which) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)))).setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    public boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    public void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } else {
            PermissionUtils.requestPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION);
        }
    }

    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     */
    public boolean ensureStoragePermissionGranted() {
        if (hasStoragePermission()) {
            TermuxInstaller.setupStorageSymlinks(this);
            return true;
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission not granted, requesting permission.");
            requestStoragePermission();
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Logger.logInfo(LOG_TAG, "Storage permission granted by user on request.");
            TermuxInstaller.setupStorageSymlinks(this);
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission denied by user on request.");
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return isOnResumeAfterOnCreate;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
        }
    }

    private void unregisterTermuxActivityBroadcastReceiever() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceieverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            if (mIsVisible) {
                fixTermuxActivityBroadcastReceieverIntent(intent);

                switch (action) {
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        if (ensureStoragePermissionGranted())
                            TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling();
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling() {
        if (mProperties!= null) {
            mProperties.loadTermuxPropertiesFromDisk();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mProperties.getExtraKeysInfo());
            }
        }

        setMargins();
        setTerminalToolbarHeight();

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onReload();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReload();

        if (mTermuxService != null)
            mTermuxService.setTerminalTranscriptRows();

        // To change the activity and drawer theme, activity needs to be recreated.
        // But this will destroy the activity, and will call the onCreate() again.
        // We need to investigate if enabling this is wise, since all stored variables and
        // views will be destroyed and bindService() will be called again. Extra keys input
        // text will we restored since that has already been implemented. Terminal sessions
        // and transcripts are also already preserved. Theme does change properly too.
        // TermuxActivity.this.recreate();
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        context.startActivity(newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
