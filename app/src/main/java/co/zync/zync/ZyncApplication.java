package co.zync.zync;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.preference.PreferenceActivity;
import co.zync.zync.activities.SettingsActivity;
import co.zync.zync.api.ZyncAPI;
import co.zync.zync.api.ZyncClipData;
import co.zync.zync.api.ZyncClipType;
import co.zync.zync.api.ZyncError;
import co.zync.zync.firebase.ZyncInstanceIdService;
import co.zync.zync.firebase.ZyncMessagingService;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

import java.util.concurrent.atomic.AtomicBoolean;

public class ZyncApplication extends Application {
    /* START NOTIFICATION IDS */
    public static int CLIPBOARD_UPDATED_ID = 281902;
    public static int CLIPBOARD_POSTED_ID = 213812;
    public static int CLIPBOARD_ERROR_ID = 9308312;
    public static int PERSISTENT_NOTIFICATION_ID = 329321;
    /* END NOTIFICATION IDS */
    private AtomicBoolean lastRequestStatus = new AtomicBoolean(true);
    private ZyncPostClipTask.RequestStatusListener requestStatusListener;
    private RequestQueue httpRequestQueue;
    private ZyncAPI api;
    private ZyncWifiReceiver receiver; // do not remove, we have to retain the reference
    private final ZyncPreferenceChangeListener preferenceChangeListener = new ZyncPreferenceChangeListener(this);

    @Override
    public void onCreate() {
        super.onCreate();

        if (isWifiConnected() || getPreferences().getBoolean("use_on_data", true)) {
            setupNetwork();
        }

        receiver = new ZyncWifiReceiver();
        registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        getPreferences().registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    public void setupNetwork() {
        // Zync services
        startService(ZyncInstanceIdService.class);
        startService(ZyncMessagingService.class);

        httpRequestQueue = Volley.newRequestQueue(getApplicationContext());

        if (api != null) {
            enableClipboardService();
            api.setQueue(httpRequestQueue);
        }
    }

    public void removeNetworkUsages() {
        stopService(new Intent(this, ZyncInstanceIdService.class));
        stopService(new Intent(this, ZyncMessagingService.class));

        httpRequestQueue = null;

        if (api != null) {
            disableClipboardService();
            api.setQueue(null);
        }
    }

    public boolean isWifiConnected() {
        ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
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

    public void setRequestStatusListener(ZyncPostClipTask.RequestStatusListener requestStatusListener) {
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

    private void startService(Class<? extends Service> cls) {
        Intent intent = new Intent(this, cls);
        startService(intent);
    }

    public void sendNotification(int id, String title, String text) {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.cancel(id);

        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification_icon);
        builder.setContentTitle(title);
        builder.setContentText(text);

        notificationManager.notify(id, builder.build());
    }

    /*
     * Sync cloud clipboard to local
     */
    public void syncDown() {
        if (getPreferences().getBoolean("sync_down", true)) {
            api.getClipboard(getEncryptionPass(), new ZyncAPI.ZyncCallback<ZyncClipData>() {
                @Override
                public void success(ZyncClipData value) {
                    String data;

                    if (value != null && (data = value.data()) != null
                            && isTypeSupported(value.type())) {
                        ZyncClipboardService.getInstance().writeToClip(data, false);
                    }
                }

                @Override
                public void handleError(ZyncError error) {
                    // ignored
                }
            });
        }
    }

    public boolean isTypeSupported(ZyncClipType type) {
        return type == ZyncClipType.TEXT;
    }

    public void openSettings(Context context) {
        Intent settingsIntent = new Intent(context, SettingsActivity.class);

        settingsIntent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.GeneralPreferenceFragment.class.getName());
        settingsIntent.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true);
        startActivity(settingsIntent);
    }

    public String getEncryptionPass() {
        return getPreferences().getString("encryption_pass", "default");
    }

    public RequestQueue httpRequestQueue() {
        return httpRequestQueue;
    }

    public ZyncAPI getApi() {
        return api;
    }

    public SharedPreferences getPreferences() {
        return getSharedPreferences(SettingsActivity.PREFERENCES_NAME, 0);
    }

    public void setApi(ZyncAPI api) {
        this.api = api;
    }
}
