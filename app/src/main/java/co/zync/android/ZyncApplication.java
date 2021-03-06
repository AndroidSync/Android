package co.zync.android;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceActivity;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import co.zync.android.activities.SettingsActivity;
import co.zync.android.activities.SignInActivity;
import co.zync.android.api.*;
import co.zync.android.api.callback.ZyncCallback;
import co.zync.android.services.ZyncClipboardService;
import co.zync.android.services.ZyncInstanceIdService;
import co.zync.android.services.ZyncMessagingService;
import co.zync.android.listeners.ZyncPreferenceChangeListener;
import co.zync.android.listeners.NullDialogClickListener;
import co.zync.android.listeners.RequestStatusListener;
import co.zync.android.utils.Consumer;
import co.zync.android.utils.ZyncExceptionInfo;
import com.crashlytics.android.Crashlytics;
import okhttp3.OkHttpClient;

import javax.crypto.AEADBadTagException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZyncApplication extends Application {
    public static final List<String> SENSITIVE_PREFERENCE_FIELDS = Arrays.asList("zync_history", "zync_api_token", "encryption_pass", "encryption_key");
    public static final List<ZyncExceptionInfo> LOGGED_EXCEPTIONS = Collections.synchronizedList(new ArrayList<ZyncExceptionInfo>() {
        @Override
        public boolean add(ZyncExceptionInfo zyncExceptionInfo) {
            if (zyncExceptionInfo.ex() instanceof ZyncAPIException) {
                ZyncError error = ((ZyncAPIException) zyncExceptionInfo.ex()).error();

                if (error.code() != 300 && !("Clipboard Empty".equals(error.message()))) {
                    Crashlytics.logException(zyncExceptionInfo.ex());
                }
            }

            return super.add(zyncExceptionInfo);
        }
    });
    /* START NOTIFICATION IDS */
    public static int CLIPBOARD_UPDATED_ID = 281902;
    public static int CLIPBOARD_POSTED_ID = 213812;
    public static int CLIPBOARD_INCORRECT_PASS_ID = 2312435;
    public static int CLIPBOARD_ERROR_ID = 9308312;
    public static int PERSISTENT_NOTIFICATION_ID = 329321;
    public static int CLIPBOARD_PROGRESS_ID = 3901831;
    /* END NOTIFICATION IDS */
    // whether the last request was successful or not
    private AtomicBoolean lastRequestStatus = new AtomicBoolean(true);
    private RequestStatusListener requestStatusListener;
    private OkHttpClient httpClient;
    private ZyncConfiguration config;
    private ZyncDataManager dataManager;
    private ZyncAPI api;
    private ZyncWifiReceiver receiver; // do not remove, we have to retain the reference
    private SignInActivity.AuthenticateCallback authenticateCallback;
    private final ZyncPreferenceChangeListener preferenceChangeListener = new ZyncPreferenceChangeListener(this);

    @Override
    public void onCreate() {
        super.onCreate();

        this.config = new ZyncConfiguration(this);

        // if we are connected to wifi or allowed to use data, setup Zync network services
        if (isWifiConnected() || config.useOnData()) {
            setupNetwork();
        }

        dataManager = new ZyncDataManager(this);

        // setup wifi receiver to get updates on network changes
        receiver = new ZyncWifiReceiver();
        registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // set a preference listener to make changes to activity when user changes setting

        ZyncClipData.listenForDecryptionError(new Consumer<AEADBadTagException>() {
            @Override
            public void consume(AEADBadTagException value) {
                sendNotification(
                        CLIPBOARD_INCORRECT_PASS_ID,
                        getString(R.string.decryption_error_notification),
                        getString(R.string.decryption_error_notification_desc)
                );
            }
        });
    }

    public ZyncConfiguration getConfig() {
        return config;
    }

    public ZyncDataManager getDataManager() {
        return dataManager;
    }

    // utility method to effectively -> filter(timestamp).findFirst()
    public ZyncClipData clipFromTimestamp(long timestamp, List<ZyncClipData> history) {
        for (ZyncClipData data : history) {
            if (data.timestamp() == timestamp) {
                return data;
            }
        }

        return null;
    }

    public void setupNetwork() {
        if (api != null) {
            enableClipboardService();
            api.setClient(httpClient);
        }

        // Zync services
        startService(ZyncInstanceIdService.class);
        startService(ZyncMessagingService.class);

        httpClient = new OkHttpClient();
    }

    // remove network services
    public void removeNetworkUsages() {
        stopService(new Intent(this, ZyncInstanceIdService.class));
        stopService(new Intent(this, ZyncMessagingService.class));

        httpClient = null;

        if (api != null) {
            disableClipboardService();
            api.setClient(null);
        }
    }

    public boolean isWifiConnected() {
        return isWifiConnected(this);
    }

    // go across all networks and test if the device is connected to the internet
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;

        for (Network network : connManager.getAllNetworks()) {
            NetworkInfo info = connManager.getNetworkInfo(network);

            if (info.getType() != ConnectivityManager.TYPE_WIFI) {
                continue;
            }

            if (info.isConnected()) {
                connected = true;
            }
        }

        return connected;
    }

    public void handleErrorGeneric(Activity activity, ZyncError error, int action) {
        handleErrorGeneric(activity, error, action, null);
    }

    public void handleErrorGeneric(Activity activity, ZyncError error, int action, ProgressDialog dialog) {
        if (dialog != null) {
            dialog.dismiss();
        }

        setLastRequestStatus(false);
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(activity);

        alertDialog.setTitle(getString(R.string.unable, getResources().getString(action)));
        alertDialog.setMessage(getString(R.string.unable_msg, error.code(), error.message()));
        alertDialog.setPositiveButton(R.string.ok, new NullDialogClickListener());

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                alertDialog.show();
            }
        });
    }

    public void enableClipboardService() {
        if (ZyncClipboardService.getInstance() == null) {
            startService(ZyncClipboardService.class);
        }
    }

    public void disableClipboardService() {
        if (ZyncClipboardService.getInstance() != null) {
            stopService(new Intent(this, ZyncClipboardService.class));
            ZyncClipboardService.nullify();
        }
    }

    public void setRequestStatusListener(RequestStatusListener requestStatusListener) {
        this.requestStatusListener = requestStatusListener;
    }

    public void setLastRequestStatus(boolean val) {
        lastRequestStatus.set(val);

        if (requestStatusListener != null) {
            requestStatusListener.onStatusChange(val);
        }
    }

    public boolean lastRequestStatus() {
        return lastRequestStatus.get();
    }

    // utility method to start a service
    private void startService(Class<? extends Service> cls) {
        Intent intent = new Intent(this, cls);
        startService(intent);
    }

    // utility method to get a color which uses the best method
    // to get a color from resources based on version of device
    public int getColorSafe(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(colorRes);
        } else {
            return getResources().getColor(colorRes);
        }
    }

    /*       NOTIFICATION METHODS START        */

    public void sendClipErrorNotification() {
        sendNotification(
                CLIPBOARD_ERROR_ID,
                getString(R.string.clipboard_post_error_notification),
                getString(R.string.clipboard_post_error_notification_desc)
        );
    }

    public void sendClipPostedNotification() {
        if (config.sendNotificationOnClipChange()) {
            sendNotification(
                    CLIPBOARD_POSTED_ID,
                    getString(R.string.clipboard_posted_notification),
                    getString(R.string.clipboard_posted_notification_desc)
            );
        }
    }

    // utility method to create a pending intent for a notification
    public PendingIntent createPendingIntent(Intent intent, Class<? extends Activity> activityClass) {
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(activityClass);
        stackBuilder.addNextIntent(intent);

        return stackBuilder.getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    public void sendNotification(int id, String title, String text) {
        sendNotification(id, title, text, null);
    }

    // sends a notification to the system with a general template
    public void sendNotification(int id, String title, String text, PendingIntent intent) {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification_icon);
        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setShowWhen(true);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setVibrate(new long[] {0, 125, 125, 125});

        if (intent != null) {
            builder.setContentIntent(intent);
        }

        notificationManager.notify(id, builder.build());
    }

    /*       NOTIFICATION METHODS END        */

    /*
     * Sync cloud clipboard to local
     */
    public void syncDown() {
        if (config.syncDown()) {
            api.getClipboard(config.getEncryptionPass(), new ZyncCallback<ZyncClipData>() {
                @Override
                public void success(ZyncClipData value) {
                    byte[] data;

                    if (value != null && (data = value.data()) != null
                            && isTypeSupported(value.type())) {
                        if (value.type() == ZyncClipType.TEXT) {
                            ZyncClipboardService.getInstance().writeToClip(new String(data), false);
                            if (getConfig().sendNotificationOnClipChange()) {
                                sendNotification(
                                        ZyncApplication.CLIPBOARD_UPDATED_ID,
                                        getString(R.string.clipboard_changed_notification),
                                        getString(R.string.clipboard_changed_notification_desc)
                                );
                            }
                        } else if (value.type() == ZyncClipType.IMAGE) {
                            // download image to file for later
                            dataManager.load(value, true, new ZyncCallback<Void>() {
                                @Override
                                public void success(Void value) {
                                    if (getConfig().sendNotificationOnClipChange()) {
                                        sendNotification(
                                                ZyncApplication.CLIPBOARD_UPDATED_ID,
                                                getString(R.string.clipboard_changed_notification),
                                                getString(R.string.clipboard_changed_notification_desc)
                                        );
                                    }
                                }

                                @Override
                                public void handleError(ZyncError error) {
                                }
                            });
                        }
                    }
                }

                @Override
                public void handleError(ZyncError error) {
                    // ignored
                }
            });
        }
    }

    // check if the clip type is supported in the app
    // modification will cause odd behaviour and errors
    // in the app
    public boolean isTypeSupported(ZyncClipType type) {
        return type == ZyncClipType.TEXT;
    }

    // utility method to open settings directly
    public void openSettings() {
        Intent settingsIntent = new Intent(getApplicationContext(), SettingsActivity.class);

        settingsIntent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.GeneralPreferenceFragment.class.getName());
        settingsIntent.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true);
        startActivity(settingsIntent);
    }

    public SignInActivity.AuthenticateCallback authenticateCallback() {
        return authenticateCallback;
    }

    public void setAuthenticateCallback(SignInActivity.AuthenticateCallback authenticateCallback) {
        this.authenticateCallback = authenticateCallback;
    }

    public OkHttpClient httpClient() {
        return httpClient;
    }

    public void setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public ZyncAPI getApi() {
        return api;
    }

    public void setApi(ZyncAPI api) {
        this.api = api;
    }

    // creates the info file (zync_debug_info.txt) and fills it with a debug report
    public File createInfoFile() throws IOException {
        File file = new File(new File(getFilesDir(), "attachments/"), "zync_debug_info.txt");
        if (file.exists()) {
            file.delete();
        }

        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        file.createNewFile();
        FileOutputStream fos = new FileOutputStream(file);
        String fileContents = debugInfo();

        fos.write(fileContents.getBytes(Charset.forName("UTF-8")));
        fos.flush();
        fos.close();

        return file;
    }

    // creates debug report to figure out issues with bugs
    public String debugInfo() {
        StringBuilder builder = new StringBuilder();

        builder.append("-----------------------------------------\n");
        builder.append("Zync for Android Info report\n");
        builder.append("Unix time report generated: ").append(System.currentTimeMillis()).append('\n');
        builder.append("App Version: ").append(BuildConfig.VERSION_NAME).append('\n');
        builder.append("API Version: ").append(ZyncAPI.VERSION).append('\n');
        builder.append("API Base URL: ").append(ZyncAPI.BASE).append('\n');
        builder.append("Debug mode: ").append(BuildConfig.DEBUG).append("\n\n");

        builder.append("Device info:\n");
        builder.append("- Android Version: ").append(Build.VERSION.SDK_INT).append('\n');
        builder.append("- Device: ").append(Build.DEVICE).append('\n');
        builder.append("- Model: ").append(Build.MODEL).append('\n');
        builder.append("- Product: ").append(Build.PRODUCT).append('\n');
        builder.append("- User-Agent: ").append(System.getProperty("http.agent")).append("\n\n");

        builder.append("ZyncAPI initialized: ").append(api != null).append('\n');

        if (api != null) {
            builder.append("Zync Token: ").append(api.getToken()).append("\n");
        }

        builder.append('\n');
        builder.append("Zync Settings:\n");
        Map<String, ?> prefs = config.getPreferences().getAll();

        for (String key : prefs.keySet()) {
            // do NOT include sensitive information such as their history or encryption password
            if (SENSITIVE_PREFERENCE_FIELDS.contains(key.toLowerCase())) {
                continue;
            }

            builder.append("- ").append(key).append("=").append(prefs.get(key).toString()).append('\n');
        }

        builder.append('\n');
        builder.append("-----------------------------------------\n\n");

        if (LOGGED_EXCEPTIONS.isEmpty()) {
            builder.append("No exceptions to be found!");
        } else {
            builder.append("Listing exceptions...\n\n");

            for (ZyncExceptionInfo exceptionInfo : LOGGED_EXCEPTIONS) {
                builder.append("---------------------------------\n");
                builder.append("Exception Type: ").append(exceptionInfo.ex().getClass().getName()).append('\n');

                if (exceptionInfo.ex().getMessage() != null) {
                    builder.append("Message: ").append(exceptionInfo.ex().getMessage()).append('\n');
                }

                builder.append("Exception at ").append(exceptionInfo.timestamp()).append('\n');

                if ("Unknown".equals(exceptionInfo.action())) {
                    builder.append("Attempted action is unknown\n");
                } else {
                    builder.append("Application was attempting to ").append(exceptionInfo.action()).append('\n');
                }

                builder.append("\nFull Stacktrace:\n");
                builder.append(Log.getStackTraceString(exceptionInfo.ex())).append('\n');
            }

            builder.append("---------------------------------\n");
        }

        return builder.toString();
    }

    // open the prompt to direct the user to a URL
    // in their preferred web application
    // errorMessage=res id
    public void directToLink(String url, int errorMessage) {
        Uri webpage = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, webpage);

        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            AlertDialog.Builder dialog = new AlertDialog.Builder(getApplicationContext());

            dialog.setTitle(R.string.error);
            dialog.setMessage(errorMessage);
            dialog.setPositiveButton(R.string.ok, new NullDialogClickListener());

            dialog.show();
        }
    }
}
