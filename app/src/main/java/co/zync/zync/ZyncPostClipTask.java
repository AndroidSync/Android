package co.zync.zync;

import android.content.SharedPreferences;
import android.os.AsyncTask;
import co.zync.zync.api.ZyncAPI;
import co.zync.zync.api.ZyncClipData;
import co.zync.zync.api.ZyncClipType;
import co.zync.zync.api.ZyncError;
import org.json.JSONException;

/**
 * Task which when given the appropriate variables
 * posts provided content to cloud clipboard
 *
 * @author Mazen Kotb
 */
public class ZyncPostClipTask extends AsyncTask<Void, Void, Void> {
    private final ZyncApplication app;
    private final byte[] data;
    private final ZyncClipType type;
    private ZyncAPI.ZyncCallback<Void> listener = new ZyncAPI.ZyncCallback<Void>() {
        @Override
        public void success(Void v) {
            System.out.println("Sent request!");
        }

        @Override
        public void handleError(ZyncError error) {
            System.out.println("error");
        }
    };

    public ZyncPostClipTask(ZyncApplication app, byte[] data, ZyncClipType type) {
        this.app = app;
        this.data = data;
        this.type = type;
    }

    public ZyncPostClipTask(ZyncApplication app, byte[] data, ZyncClipType type, ZyncAPI.ZyncCallback<Void> listener) {
        this.app = app;
        this.data = data;
        this.type = type;
        this.listener = listener;
    }

    @Override
    protected Void doInBackground(Void... params) {
        // act on data and push to servers async
        SharedPreferences preferences = app.getPreferences();

        if (app.getApi() == null) {
            return null; // app is not running yet
        }

        try {
            app.getApi().postClipboard(
                    new ZyncClipData(
                            preferences.getBoolean("encryption_enabled", false) ?
                                    preferences.getString("encryption_pass", "default") : null,
                            type,
                            data
                    ),
                    listener
            );
        } catch (JSONException ignored) {
        }
        return null;
    }
}
