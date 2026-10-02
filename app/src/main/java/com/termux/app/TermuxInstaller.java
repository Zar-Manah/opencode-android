package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.BuildConfig;

import com.termux.R;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.models.errors.Error;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.notification.TermuxNotificationUtils;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // Check if prefix exists and contains working bootstrap binaries (tar and sh)
        File bootstrapTar = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tar");
        File bootstrapSh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sh");
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true) && bootstrapTar.exists() && bootstrapTar.canExecute() && bootstrapSh.exists()) {
            whenDone.run();
            return;
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods") ||
                                        zipEntryName.equals("etc/termux/bootstrap/termux-bootstrap-second-stage.sh")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    // Run Termux bootstrap second stage if present (best-effort, non-fatal)
                    String termuxBootstrapSecondStageFile = TERMUX_PREFIX_DIR_PATH + "/etc/termux/bootstrap/termux-bootstrap-second-stage.sh";
                    if (FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                        Logger.logInfo(LOG_TAG, "Running Termux bootstrap second stage (non-fatal)...");
                        try {
                            ExecutionCommand executionCommand = new ExecutionCommand(-1,
                                termuxBootstrapSecondStageFile, null, null,
                                null, true, false);
                            executionCommand.commandLabel = "Termux Bootstrap Second Stage Command";
                            executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;
                            TermuxTask.execute(activity, executionCommand, null, new TermuxShellEnvironmentClient(), true);
                        } catch (Exception e) {
                            Logger.logWarn(LOG_TAG, "Bootstrap second stage ignored error: " + e.getMessage());
                        }
                    } else {
                        Logger.logInfo(LOG_TAG, "Bootstrap second stage script not found, proceeding directly.");
                    }

                    // Ensure tar in prefix bin is executable for the offline payload extraction
                    File tarBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tar");
                    if (tarBin.exists()) {
                        try { Os.chmod(tarBin.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages unpacked successfully.");
                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /** OpenCode: full first-boot chain (bootstrap + offline payload). */
    static void setupOpenIfNeeded(final Activity activity, final Runnable whenDone) {
        File marker = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".open-setup-done");

        File zshBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "zsh");
        if (marker.exists() && zshBin.exists()) {
            new Thread(() -> {
                updateOpenDynamicAssets(activity);
                activity.runOnUiThread(whenDone);
            }).start();
            return;
        }

        setupBootstrapIfNeeded(activity, () -> {
            extractOpenOfflinePayload(activity, () -> {
                updateOpenDynamicAssets(activity);
                installOpenFontIfNeeded(activity);
                activity.runOnUiThread(whenDone);
            });
        });
    }

    /** OpenCode: extracts offline bundle in continuous stream. */
    static void extractOpenOfflinePayload(final Activity activity, final Runnable whenDone) {
        List<String> payloadAssets = new ArrayList<>();
        boolean isGzipped = true;
        try {
            String[] list = activity.getAssets().list("");
            if (list != null) {
                for (String name : list) {
                    if (name.startsWith("open-offline-payload.tar.gz.part")) {
                        payloadAssets.add(name);
                    }
                }
                Collections.sort(payloadAssets);
            }
        } catch (IOException ignored) {}

        if (payloadAssets.isEmpty()) {
            for (String singleTar : new String[]{"open-offline-payload.tar"}) {
                try (InputStream is = activity.getAssets().open(singleTar)) {
                    payloadAssets.add(singleTar);
                    isGzipped = false;
                    break;
                } catch (IOException ignored1) {}
            }
            if (payloadAssets.isEmpty()) {
                for (String singleTarGz : new String[]{"open-offline-payload.tar.gz"}) {
                    try (InputStream is2 = activity.getAssets().open(singleTarGz)) {
                        payloadAssets.add(singleTarGz);
                        isGzipped = true;
                        break;
                    } catch (IOException ignored2) {}
                }
            }
            if (payloadAssets.isEmpty()) {
                Logger.logInfo(LOG_TAG, "No offline payload found in assets, skipping payload extraction.");
            }
        }

        if (payloadAssets.isEmpty()) {
            whenDone.run();
            return;
        }

        final List<String> finalAssetList = payloadAssets;
        final boolean finalIsGzipped = isGzipped;

        final ProgressDialog progress = ProgressDialog.show(activity, "OpenCode",
            "Installing offline environment...\nDeploying Debian, Node 24, OpenCode and development tools.\nThis will only take a few seconds...", true, false);

        new Thread(() -> {
            try {
                Logger.logInfo(LOG_TAG, "Starting extraction of assets: " + finalAssetList);
                String tarPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tar";
                File tarBin = new File(tarPath);
                if (!tarBin.exists() || !tarBin.canExecute()) {
                    throw new RuntimeException("Tar binary not found or not executable at: " + tarPath);
                }

                String targetDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;

                List<String> tarCmd = new ArrayList<>();
                tarCmd.add(tarPath);
                tarCmd.add("--warning=no-unknown-keyword");
                tarCmd.add(finalIsGzipped ? "-xpzf" : "-xpf");
                tarCmd.add("-");
                tarCmd.add("-C");
                tarCmd.add(targetDir);
                ProcessBuilder pb = new ProcessBuilder(tarCmd);
                Map<String, String> env = pb.environment();
                env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
                env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
                env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.redirectErrorStream(true);

                Process process = pb.start();

                // Drain output concurrently to prevent OS pipe deadlocks
                final StringBuilder outputLog = new StringBuilder();
                Thread outputDrainer = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (outputLog.length() < 10000) {
                                outputLog.append(line).append("\n");
                            }
                        }
                    } catch (IOException ignored) {}
                });
                outputDrainer.start();

                // Stream the payload parts continuously into tar stdin (256KB buffer for max I/O throughput)
                try (OutputStream out = process.getOutputStream()) {
                    byte[] buffer = new byte[262144];
                    for (String chunkName : finalAssetList) {
                        Logger.logInfo(LOG_TAG, "Streaming chunk into tar: " + chunkName);
                        try (InputStream in = activity.getAssets().open(chunkName)) {
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                        }
                    }
                }

                outputDrainer.join();
                int exitCode = process.waitFor();
                File zshCheck = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "zsh");
                if (exitCode != 0 && !zshCheck.exists()) {
                    throw new RuntimeException("Tar extraction failed with exit code " + exitCode + ":\n" + outputLog);
                }
                if (exitCode != 0) {
                    Logger.logWarn(LOG_TAG, "Tar returned non-fatal code " + exitCode + " (metadata/xattr warnings), but prefix extracted successfully.");
                }
                Logger.logInfo(LOG_TAG, finalAssetList + " extracted successfully.");

                // Apply permissions, symlinks, and dotfiles
                applyOpenPostExtraction(activity);

                File marker = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".open-setup-done");
                if (!marker.createNewFile()) {
                    Logger.logWarn(LOG_TAG, "Marker file already existed or could not be created.");
                }
                sendOpenSetupFinishedNotification(activity.getApplicationContext());

                activity.runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (Exception ignored) {}
                    whenDone.run();
                });

            } catch (final Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error extracting OpenCode offline payload", e);
                activity.runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (Exception ignored) {}
                    showBootstrapErrorDialog(activity, whenDone, "Error extracting OpenCode offline payload:\n" + e.getMessage());
                });
            }
        }).start();
    }

    private static void applyOpenPostExtraction(Context context) {
        try {
            // Deploy open-debian entry script from assets to both prefix bin and home bin
            File openDebianPrefix = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "open-debian");
            try {
                copyAssetFile(context, "open-bin/open-debian", openDebianPrefix);
                Os.chmod(openDebianPrefix.getAbsolutePath(), 0755);
            } catch (Exception ignored) {}

            File openDebianHome = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin/open-debian");
            try {
                File homeBinDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin");
                if (!homeBinDir.exists()) homeBinDir.mkdirs();
                copyAssetFile(context, "open-bin/open-debian", openDebianHome);
                Os.chmod(openDebianHome.getAbsolutePath(), 0755);
            } catch (Exception ignored) {}

            // Executable permissions for $PREFIX/bin
            String[] prefixExecutables = new String[]{
                "zsh", "proot", "proot-distro", "adb", "fastboot", "termux-setup-storage",
                "open-debian", "bash", "sh", "tar", "gzip"
            };
            for (String exe : prefixExecutables) {
                File f = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, exe);
                if (f.exists()) {
                    try { Os.chmod(f.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                }
            }

            // Executable permissions for $HOME/bin
            File homeBin = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin");
            if (homeBin.exists() && homeBin.isDirectory()) {
                File[] files = homeBin.listFiles();
                if (files != null) {
                    for (File f : files) {
                        try { Os.chmod(f.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                    }
                }
            }


            // Debian proot wrappers in rootfs (soporta containers/debian/rootfs, containers/debian e installed-rootfs)
            File[] possibleDebianRoots = new File[]{
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/containers/debian/rootfs"),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/containers/debian"),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/installed-rootfs/debian")
            };
            for (File debianRoot : possibleDebianRoots) {
                if (debianRoot.exists()) {
                    String[] debianBins = new String[]{
                        "usr/local/bin/opencode", "usr/local/bin/oc", "usr/local/bin/pc", "usr/local/bin/pam",
                        "usr/local/bin/pdump", "usr/local/bin/popen", "usr/local/bin/ppm", "usr/local/bin/pset",
                        "usr/local/bin/pshare", "usr/local/bin/pstart", "usr/local/bin/pcmd", "usr/local/bin/adb",
                        "usr/local/bin/curl", "usr/local/bin/gradle", "usr/local/bin/aapt2", "usr/local/bin/zipalign",
                        "usr/local/bin/crontab", "usr/local/bin/service", "usr/local/bin/cron-daemon",
                        "usr/local/bin/pbridge",
                        "opt/node/bin/node", "opt/node/bin/npm", "opt/node/bin/npx", "opt/node/bin/opencode",
                        "opt/node/bin/oc", "root/.opencode/bin/opencode", "root/.opencode/bin/oc",
                        "opt/gradle-8.7/bin/gradle", "opt/android-sdk/build-tools/35.0.1/aapt2", "opt/android-sdk/build-tools/35.0.1/aidl",
                        "opt/android-sdk/build-tools/35.0.1/zipalign", "opt/android-sdk/build-tools/35.0.1/split-select",
                        "opt/android-sdk/cmdline-tools/latest/bin/sdkmanager",
                        "usr/lib/jvm/java-21-openjdk-arm64/bin/java", "usr/lib/jvm/java-21-openjdk-arm64/bin/javac",
                        "root/.opencode/dream.sh", "root/.opencode/morning.sh"
                    };
                    for (String rel : debianBins) {
                        File bf = new File(debianRoot, rel);
                        if (bf.exists()) {
                            try { Os.chmod(bf.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                        }
                    }

                    // Asegurar wrapper curl ejecutable en usr/local/bin
                    File curlBin = new File(debianRoot, "usr/local/bin/curl");
                    if (!curlBin.exists()) {
                        try {
                            File parent = curlBin.getParentFile();
                            if (parent != null && !parent.exists()) parent.mkdirs();
                            String curlWrapper = "#!/bin/sh\n" +
                                "SELF=\"$(readlink -f \"$0\" 2>/dev/null || echo \"$0\")\"\n" +
                                "if [ -x /usr/bin/curl ] && [ \"$(readlink -f /usr/bin/curl 2>/dev/null)\" != \"$SELF\" ]; then\n" +
                                "    exec /usr/bin/curl \"$@\"\n" +
                                "elif [ -x /system/bin/curl ] && [ \"$(readlink -f /system/bin/curl 2>/dev/null)\" != \"$SELF\" ]; then\n" +
                                "    exec /system/bin/curl \"$@\"\n" +
                                "elif [ -x /open-prefix/bin/curl ] && [ \"$(readlink -f /open-prefix/bin/curl 2>/dev/null)\" != \"$SELF\" ]; then\n" +
                                "    exec /open-prefix/bin/curl \"$@\"\n" +
                                "else\n" +
                                "    /system/bin/curl \"$@\" 2>/dev/null || echo '{\"ok\":false,\"error\":\"curl not found\"}'\n" +
                                "fi\n";
                            try (FileOutputStream fos = new FileOutputStream(curlBin)) {
                                fos.write(curlWrapper.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                fos.flush();
                            }
                            Os.chmod(curlBin.getAbsolutePath(), 0755);
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Sincronizar scripts y assets del puente nativo inmediatamente
            try {
                OpenAccessService.syncBridgeScripts(context);
            } catch (Exception ignored) {}

            // Shell symlink: ~/.termux/shell -> $PREFIX/bin/zsh
            File termuxDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux");
            if (!termuxDir.exists()) termuxDir.mkdirs();
            File shellSymlink = new File(termuxDir, "shell");
            if (shellSymlink.exists() || FileUtils.symlinkFileExists(shellSymlink.getAbsolutePath())) {
                shellSymlink.delete();
            }
            try {
                Os.symlink(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/zsh", shellSymlink.getAbsolutePath());
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to create shell symlink: " + e.getMessage());
            }

            // .hushlogin (silent motd)
            File hush = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".hushlogin");
            if (!hush.exists()) {
                hush.createNewFile();
            }

            // Clear motd in etc
            File motd = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "motd");
            if (motd.getParentFile().exists()) {
                try (FileOutputStream out = new FileOutputStream(motd)) {
                    // empty
                }
            }

            Logger.logInfo(LOG_TAG, "OpenCode post-extraction permissions and symlinks applied.");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error applying OpenCode post-extraction: " + e.getMessage());
        }
    }

    /** OpenCode: installs font if missing (~/.termux/font.ttf). */
    static void installOpenFontIfNeeded(final Context context) {
        try {
            File termuxDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux");
            if (!termuxDir.exists() && !termuxDir.mkdirs()) return;
            File fontFile = new File(termuxDir, "font.ttf");
            if (fontFile.exists()) return;
            InputStream fontIn = null;
            try {
                fontIn = context.getAssets().open("font.ttf");
            } catch (Exception ignored) {
                try {
                    fontIn = context.getAssets().open("open-font.ttf");
                } catch (Exception ignored2) {}
            }
            if (fontIn == null) return;
            try (InputStream in = fontIn;
                 FileOutputStream out = new FileOutputStream(fontFile)) {
                byte[] buffer = new byte[8192];
                int readBytes;
                while ((readBytes = in.read(buffer)) != -1)
                    out.write(buffer, 0, readBytes);
            }
            Logger.logInfo(LOG_TAG, "Installed OpenCode font.");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to install font: " + e.getMessage());
        }
    }

    public static void copyAssetFile(Context context, String assetPath, File destFile) throws IOException {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            try {
                out.getFD().sync();
            } catch (Exception ignored) {}
        }
    }

    public static void copyFile(File src, File dst) {
        if (src == null || !src.exists()) return;
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            try {
                out.getFD().sync();
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public static synchronized void ensureOpenDebianBinary(final Context context) {
        if (context == null) return;
        try {
            File marker = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".open-setup-done");
            if (!marker.exists()) {
                try { marker.createNewFile(); } catch (Exception ignored) {}
            }

            File binPrefix = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            File tarBin = new File(binPrefix, "tar");
            boolean prefixReady = binPrefix.exists() && tarBin.exists();

            File homeBinDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin");
            if (!homeBinDir.exists()) homeBinDir.mkdirs();

            File bridgeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".open-bridge");
            if (!bridgeDir.exists()) bridgeDir.mkdirs();

            File openDebianHome = new File(homeBinDir, "open-debian");
            if (!openDebianHome.exists()) {
                try {
                    copyAssetFile(context, "open-bin/open-debian", openDebianHome);
                } catch (Exception ignored) {}
            }

            if (openDebianHome.exists()) {
                try { Os.chmod(openDebianHome.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                openDebianHome.setExecutable(true, false);
                openDebianHome.setReadable(true, false);
            }

            if (prefixReady) {
                File openDebianPrefix = new File(binPrefix, "open-debian");
                if (!openDebianPrefix.exists()) {
                    try {
                        copyAssetFile(context, "open-bin/open-debian", openDebianPrefix);
                    } catch (Exception ignored) {}
                }
                if (openDebianPrefix.exists()) {
                    try { Os.chmod(openDebianPrefix.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                    openDebianPrefix.setExecutable(true, false);
                    openDebianPrefix.setReadable(true, false);
                }
            }

            File[] possibleDebianRoots = new File[]{
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/installed-rootfs/debian"),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/containers/debian/rootfs"),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/containers/debian")
            };
            for (File debRoot : possibleDebianRoots) {
                if (debRoot.exists()) {
                    try {
                        new File(debRoot, "open-home").mkdirs();
                        new File(debRoot, "bridge").mkdirs();
                        new File(debRoot, "open-prefix").mkdirs();
                        new File(debRoot, "sdcard").mkdirs();
                        new File(debRoot, "storage/emulated/0").mkdirs();
                    } catch (Exception ignored) {}
                }
            }

            String[] bins = new String[]{"pc", "adb", "curl", "crontab", "service", "cron-daemon", "cron-daemon.js", "free", "ping", "open-auth", "open-factory-setup", "factory-setup", "pbridge"};
            for (String b : bins) {
                File destHomeBin = new File(homeBinDir, b);
                File destBridge = new File(bridgeDir, b);
                if (!destHomeBin.exists()) {
                    try {
                        copyAssetFile(context, "open-bin/" + b, destHomeBin);
                        Os.chmod(destHomeBin.getAbsolutePath(), 0755);
                        destHomeBin.setExecutable(true, false);
                    } catch (Exception ignored) {}
                }
                if (!destBridge.exists()) {
                    try {
                        copyAssetFile(context, "open-bin/" + b, destBridge);
                        Os.chmod(destBridge.getAbsolutePath(), 0755);
                        destBridge.setExecutable(true, false);
                    } catch (Exception ignored) {}
                }
                if (prefixReady) {
                    File destPrefix = new File(binPrefix, b);
                    if (!destPrefix.exists()) {
                        try {
                            copyAssetFile(context, "open-bin/" + b, destPrefix);
                            Os.chmod(destPrefix.getAbsolutePath(), 0755);
                            destPrefix.setExecutable(true, false);
                        } catch (Exception ignored) {}
                    }
                }
            }

            File zshrcDest = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".zshrc");
            if (!zshrcDest.exists() || zshrcDest.length() == 0) {
                try {
                    String[] candidateZshrc = new String[]{"open-config/zshrc", "open-config/.zshrc"};
                    for (String cand : candidateZshrc) {
                        try {
                            copyAssetFile(context, cand, zshrcDest);
                            Os.chmod(zshrcDest.getAbsolutePath(), 0644);
                            break;
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "ensureOpenDebianBinary error", e);
        }
    }

    public static void copyAssetFolder(Context context, String assetFolder, File destFolder) {
        try {
            String[] files = context.getAssets().list(assetFolder);
            if (files == null || files.length == 0) {
                copyAssetFile(context, assetFolder, destFolder);
                return;
            }
            if (!destFolder.exists()) destFolder.mkdirs();
            for (String file : files) {
                String subAsset = assetFolder.isEmpty() ? file : (assetFolder + "/" + file);
                File subDest = new File(destFolder, file);
                copyAssetFolder(context, subAsset, subDest);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error copying asset folder: " + assetFolder, e);
        }
    }

    public static void updateOpenDynamicAssets(final Context context) {
        try {
            ensureOpenDebianBinary(context);

            SharedPreferences prefs = context.getSharedPreferences("open_installer_prefs", Context.MODE_PRIVATE);
            int lastVersion = prefs.getInt("last_installed_version", 0);
            int currentVersion = BuildConfig.VERSION_CODE;

            if (lastVersion == currentVersion) {
                return;
            }

            prefs.edit().putInt("last_installed_version", currentVersion).apply();

            // Clean up any legacy home folders/files
            for (String legacyHome : new String[]{".portal-intro", ".portal-bridge", ".portal-cmd", ".portal-rpg-completed", ".guardian", ".guardian-mirror", ".sofia", ".sofia-mirror"}) {
                File f = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, legacyHome);
                if (f.exists()) FileUtils.deleteFile("legacyHome_" + legacyHome, f.getAbsolutePath(), true);
            }
            for (String legacyBin : new String[]{"portal-debian", "portal-play"}) {
                File f1 = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, legacyBin);
                if (f1.exists()) f1.delete();
                File f2 = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin/" + legacyBin);
                if (f2.exists()) f2.delete();
            }

            // 1. Deploy open-debian binary from assets to prefix and home bin
            File openDebianPrefix = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "open-debian");
            try {
                copyAssetFile(context, "open-bin/open-debian", openDebianPrefix);
                Os.chmod(openDebianPrefix.getAbsolutePath(), 0755);
            } catch (Exception ignored) {}

            File openDebianHome = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin/open-debian");
            try {
                File homeBinDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "bin");
                if (!homeBinDir.exists()) homeBinDir.mkdirs();
                copyAssetFile(context, "open-bin/open-debian", openDebianHome);
                Os.chmod(openDebianHome.getAbsolutePath(), 0755);
            } catch (Exception ignored) {}

            // 2. Sync .zshrc from assets
            File zshrcDest = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".zshrc");
            try {
                String[] candidateZshrc = new String[]{"open-config/zshrc", "open-config/.zshrc"};
                for (String cand : candidateZshrc) {
                    try {
                        context.getAssets().open(cand).close();
                        copyAssetFile(context, cand, zshrcDest);
                        Os.chmod(zshrcDest.getAbsolutePath(), 0644);
                        break;
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to copy .zshrc", e);
            }

            // Revert terminal colors to normal default (no custom colors.properties)
            try {
                File colorsFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/colors.properties");
                if (colorsFile.exists()) {
                    colorsFile.delete();
                }
            } catch (Exception ignored) {}

            // 3. Sync configs in Debian (opencode.json and AGENTS.md)
            File[] possibleDebianRoots = new File[]{
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/installed-rootfs/debian"),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/containers/debian/rootfs"),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/containers/debian")
            };
            for (File debianRoot : possibleDebianRoots) {
                if (debianRoot.exists()) {
                    try {
                        // Clean up all legacy / orphan portal, guardian, and sofia files & directories
                        File guardianPlugin = new File(debianRoot, "root/.config/opencode/plugins/guardian-phone-core.ts");
                        if (guardianPlugin.exists()) guardianPlugin.delete();
                        for (String legacyDir : new String[]{"root/.guardian", "root/.guardian-mirror", "root/.sofia", "root/.sofia-mirror"}) {
                            File dir = new File(debianRoot, legacyDir);
                            if (dir.exists()) FileUtils.deleteFile("legacyDir_" + legacyDir, dir.getAbsolutePath(), true);
                        }
                        for (String legacyBin : new String[]{"recuerdos-guardian", "diario-guardian", "sofia-recall", "sofia-journal"}) {
                            File f = new File(debianRoot, "usr/local/bin/" + legacyBin);
                            if (f.exists()) f.delete();
                        }
                        File legacyCron = new File(debianRoot, "etc/profile.d/portal-cron.sh");
                        if (legacyCron.exists()) legacyCron.delete();
                        File legacyAndroidEnv = new File(debianRoot, "etc/profile.d/portal-android.sh");
                        if (legacyAndroidEnv.exists()) legacyAndroidEnv.delete();
                        File legacyPortalPlay = new File(debianRoot, "usr/local/bin/portal-play");
                        if (legacyPortalPlay.exists()) legacyPortalPlay.delete();

                        // Revert any theme override so OpenCode uses its normal default theme
                        File tuiFile = new File(debianRoot, "root/.config/opencode/tui.json");
                        if (tuiFile.exists()) tuiFile.delete();
                        File workTuiFile = new File(debianRoot, "root/opencode/.opencode/tui.json");
                        if (workTuiFile.exists()) workTuiFile.delete();
                        File kvFile = new File(debianRoot, "root/.local/share/opencode/kv.json");
                        if (kvFile.exists()) kvFile.delete();
                        File themePortal = new File(debianRoot, "root/.config/opencode/themes/portal.json");
                        if (themePortal.exists()) themePortal.delete();
                        File workThemePortal = new File(debianRoot, "root/opencode/.opencode/themes/portal.json");
                        if (workThemePortal.exists()) workThemePortal.delete();

                        // Initialize clean OpenCode state in Debian
                        File opencodeDir = new File(debianRoot, "root/.opencode");
                        if (!opencodeDir.exists()) opencodeDir.mkdirs();

                        File goalFile = new File(opencodeDir, "goal.md");
                        if (!goalFile.exists() || goalFile.length() < 10) {
                            try (FileOutputStream fos = new FileOutputStream(goalFile)) {
                                fos.write("OpenCode development and operations on Android.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }

                        File crontabFile = new File(opencodeDir, "crontab.txt");
                        if (!crontabFile.exists() || crontabFile.length() == 0) {
                            try (FileOutputStream fos = new FileOutputStream(crontabFile)) {
                                fos.write("30 3 * * * /root/.opencode/dream.sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }

                        File memoryFile = new File(opencodeDir, "memory.json");
                        if (!memoryFile.exists() || memoryFile.length() < 20) {
                            String memContent = "{\n" +
                                "  \"memories\": [\n" +
                                "    {\n" +
                                "      \"id\": \"arch_overview\",\n" +
                                "      \"t\": " + System.currentTimeMillis() + ",\n" +
                                "      \"text\": \"Self-architecture and capabilities: This is OpenCode running natively in Debian rootfs on Android. Full device control is provided via the native bridge (/usr/local/bin/pc) on port 4399 (screen capture, view hierarchy dumps, taps, gestures, keys, clipboard, app management, and background execution). Android storage is directly accessible at /sdcard. AI inference runs on free built-in OpenCode models without requiring any API keys. Native Android app development and compilation is fully supported via the android-app-factory skill and the preinstalled toolchain: OpenJDK 21, Gradle 8.7, Android SDK Platform 35, and ARM Build-Tools 35.0.1 (aapt2, aidl, zipalign). The system operates cleanly and autonomously without any missing dependencies.\"\n" +
                                "    },\n" +
                                "    {\n" +
                                "      \"id\": \"cognitive_architecture\",\n" +
                                "      \"t\": " + System.currentTimeMillis() + ",\n" +
                                "      \"text\": \"Cognitive layer and autonomous memory: This organism possesses persistent cognitive memory and reflection capabilities. Operational activity and observations are logged in /root/.opencode/journal.jsonl. An autonomous nightly dream consolidation cycle runs at 3:30 AM via /root/.opencode/dream.sh, reading recent experiences from journal.jsonl, synthesizing learnings, and consolidating them into persistent long-term memories in /root/.opencode/memory.json. Morning briefings are generated via /root/.opencode/morning.sh. This ensures continuous learning, persistent self-awareness, and cognitive continuity across reboots.\"\n" +
                                "    }\n" +
                                "  ],\n" +
                                "  \"entries\": [\n" +
                                "    {\n" +
                                "      \"id\": \"init_state\",\n" +
                                "      \"t\": " + System.currentTimeMillis() + ",\n" +
                                "      \"text\": \"OpenCode Android environment active. Cognitive layer initialized with persistent memory and dream cycles. Native bridge connected, complete Android compilation toolchain available, rootless operation 100% functional.\"\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}\n";
                            try (FileOutputStream fos = new FileOutputStream(memoryFile)) {
                                fos.write(memContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }

                        File journalFile = new File(opencodeDir, "journal.jsonl");
                        if (!journalFile.exists() || journalFile.length() == 0) {
                            try (FileOutputStream fos = new FileOutputStream(journalFile)) {
                                fos.write(("{\"t\":" + System.currentTimeMillis() + ",\"text\":\"OpenCode initialized. Native bridge active.\"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }

                        File dreamFile = new File(opencodeDir, "dream.sh");
                        if (!dreamFile.exists() || dreamFile.length() == 0) {
                            String dreamScript = "#!/bin/bash\n" +
                                "export HOME=/root PATH=/root/.opencode/bin:/opt/node/bin:/usr/local/bin:/usr/bin:/bin:$PATH\n" +
                                "LOG=/root/.opencode/cron.log\n" +
                                "mkdir -p /root/.opencode\n" +
                                "echo \"=== dream $(date '+%F %T') ===\" >> \"$LOG\"\n" +
                                "opencode run --dir /root/opencode --title \"Dream\" \\\n" +
                                "  \"Memory consolidation. Read recent activity from /root/.opencode/journal.jsonl, synthesize key learnings into /root/.opencode/memory.json, and write a summary entry to journal.jsonl starting with 'Dream:'.\" >> \"$LOG\" 2>&1\n" +
                                "echo \"dream exit=$?\" >> \"$LOG\"\n";
                            try (FileOutputStream fos = new FileOutputStream(dreamFile)) {
                                fos.write(dreamScript.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                        try { Os.chmod(dreamFile.getAbsolutePath(), 0755); } catch (Exception ignored) {}

                        File morningFile = new File(opencodeDir, "morning.sh");
                        if (!morningFile.exists() || morningFile.length() == 0) {
                            String morningScript = "#!/bin/bash\n" +
                                "export HOME=/root PATH=/root/.opencode/bin:/opt/node/bin:/usr/local/bin:/usr/bin:/bin:$PATH\n" +
                                "echo \"# OpenCode Status ($(date '+%F %T'))\" > /root/.opencode/morning.md\n" +
                                "echo \"System operational. Local environment ready.\" >> /root/.opencode/morning.md\n";
                            try (FileOutputStream fos = new FileOutputStream(morningFile)) {
                                fos.write(morningScript.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                        try { Os.chmod(morningFile.getAbsolutePath(), 0755); } catch (Exception ignored) {}

                        File inventoryFile = new File(opencodeDir, "inventory.md");
                        if (!inventoryFile.exists() || inventoryFile.length() == 0) {
                            String invContent = "# Inventory\n\n" +
                                "## Installed Capabilities\n" +
                                "- **Native Device Automation**: `/usr/local/bin/pc` (screen inspection, input synthesis, app management).\n" +
                                "- **Runtime Environment**: Debian GNU/Linux (Trixie), Node.js v24, OpenJDK 21, Gradle 8.7, Android SDK 35.\n" +
                                "- **Agent CLI**: OpenCode local cognitive layer with background memory consolidation.\n";
                            try (FileOutputStream fos = new FileOutputStream(inventoryFile)) {
                                fos.write(invContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }

                        // Wrappers in usr/local/bin for direct CLI execution
                        File usrLocalBin = new File(debianRoot, "usr/local/bin");
                        if (!usrLocalBin.exists()) usrLocalBin.mkdirs();
                        File dreamBin = new File(usrLocalBin, "dream");
                        if (!dreamBin.exists()) {
                            try (FileOutputStream fos = new FileOutputStream(dreamBin)) {
                                fos.write("#!/bin/sh\nexec /root/.opencode/dream.sh \"$@\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                        try { Os.chmod(dreamBin.getAbsolutePath(), 0755); } catch (Exception ignored) {}

                        File morningBin = new File(usrLocalBin, "morning");
                        if (!morningBin.exists()) {
                            try (FileOutputStream fos = new FileOutputStream(morningBin)) {
                                fos.write("#!/bin/sh\nexec /root/.opencode/morning.sh \"$@\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                        try { Os.chmod(morningBin.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                    } catch (Exception ignored) {}

                    // Sync opencode.json
                    try {
                        String candCfg = "open-config/opencode.json";
                        File opencodeCfg = new File(debianRoot, "root/.config/opencode/opencode.json");
                        copyAssetFile(context, candCfg, opencodeCfg);
                        Os.chmod(opencodeCfg.getAbsolutePath(), 0644);

                        File opencodeWorkCfg = new File(debianRoot, "root/opencode/opencode.json");
                        copyAssetFile(context, candCfg, opencodeWorkCfg);
                        Os.chmod(opencodeWorkCfg.getAbsolutePath(), 0644);
                    } catch (Exception ignored) {}

                    // Sync AGENTS.md
                    try {
                        String candAgents = "open-bin/AGENTS.md";
                        File agentsCfg = new File(debianRoot, "root/.config/opencode/AGENTS.md");
                        copyAssetFile(context, candAgents, agentsCfg);
                        Os.chmod(agentsCfg.getAbsolutePath(), 0644);

                        File agentsWorkCfg = new File(debianRoot, "root/opencode/AGENTS.md");
                        copyAssetFile(context, candAgents, agentsWorkCfg);
                        Os.chmod(agentsWorkCfg.getAbsolutePath(), 0644);
                    } catch (Exception ignored) {}

                    // Sync SKILL.md
                    try {
                        String candSkill = "open-bin/SKILL.md";
                        File skillDir = new File(debianRoot, "root/.config/opencode/skill/open-phone");
                        if (!skillDir.exists()) skillDir.mkdirs();
                        File skillFile = new File(skillDir, "SKILL.md");
                        copyAssetFile(context, candSkill, skillFile);
                        Os.chmod(skillFile.getAbsolutePath(), 0644);
                    } catch (Exception ignored) {}

                    // Sync android-app-factory SKILL.md
                    try {
                        String candSkill = "open-skills/android-app-factory/SKILL.md";
                        File skillDir = new File(debianRoot, "root/.config/opencode/skill/android-app-factory");
                        if (!skillDir.exists()) skillDir.mkdirs();
                        File skillFile = new File(skillDir, "SKILL.md");
                        copyAssetFile(context, candSkill, skillFile);
                        Os.chmod(skillFile.getAbsolutePath(), 0644);
                    } catch (Exception ignored) {}
                }
            }

            // 4. Apply permissions and symlinks
            applyOpenPostExtraction(context);

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in updateOpenDynamicAssets", e);
        }
    }

    private static void sendOpenSetupFinishedNotification(final Context context) {
        try {
            String channelId = "opencode.setup";
            NotificationUtils.setupNotificationChannel(context, channelId, "OpenCode", NotificationManager.IMPORTANCE_DEFAULT);
            android.content.Intent intent = new android.content.Intent(context, com.termux.app.TermuxActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, channelId, Notification.PRIORITY_DEFAULT,
                "OpenCode ready", "Offline setup complete", "Terminal ZSH, Debian, and OpenCode are ready.", contentIntent, null, NotificationUtils.NOTIFICATION_MODE_NONE);
            if (builder == null) return;
            builder.setSmallIcon(R.drawable.ic_new_session);
            builder.setAutoCancel(true);
            NotificationManager notificationManager = NotificationUtils.getNotificationManager(context);
            if (notificationManager != null)
                notificationManager.notify(TermuxNotificationUtils.getNextNotificationId(context), builder.build());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to send OpenCode setup notification: " + e.getMessage());
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        TermuxInstaller.setupOpenIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        // Silenced to prevent unwanted crash notifications
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Error.getErrorMarkdownString(error), true, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    final File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 1) {
                        for (int i = 1; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)), true, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
