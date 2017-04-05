package co.zync.zync.activities;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.support.design.widget.Snackbar;
import android.support.v4.app.NavUtils;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.CardView;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.ViewGroup.LayoutParams;
import android.widget.*;
import co.zync.zync.R;
import co.zync.zync.ZyncApplication;
import co.zync.zync.ZyncClipboardService;
import co.zync.zync.api.ZyncAPI;
import co.zync.zync.api.ZyncClipData;
import co.zync.zync.api.ZyncClipType;
import co.zync.zync.api.ZyncError;
import co.zync.zync.utils.NullDialogClickListener;
import co.zync.zync.utils.ZyncExceptionInfo;
import org.json.JSONObject;

import javax.crypto.AEADBadTagException;
import java.util.*;

public class HistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setContentView(R.layout.activity_history);
        setupActionBar();

        final ProgressDialog dialog = new ProgressDialog(this);

        dialog.setIndeterminate(true);
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setTitle(R.string.loading_history);
        dialog.setMessage(getString(R.string.please_wait));

        dialog.show();

        final ZyncApplication app = getZyncApp();

        app.getApi().getHistory(app.getEncryptionPass(), new ZyncAPI.ZyncCallback<List<ZyncClipData>>() {
            @Override
            public void success(final List<ZyncClipData> history) {
                app.setLastRequestStatus(history != null);

                if (history != null) {
                    /*
                     * Load history from file and compare with server.
                     *
                     * If the client has the data for the clipboard entry locally, load from there.
                     * Otherwise, add the timestamp to a list that we will request the payload for.
                     */
                    List<ZyncClipData> localHistory = historyFromFile();
                    List<Long> missingTimestamps = new ArrayList<>();

                    for (ZyncClipData historyEntry : history) {
                        ZyncClipData local = app.clipFromTimestamp(historyEntry.timestamp(), localHistory);

                        if (local != null && local.data() != null) {
                            historyEntry.setData(local.data());
                        } else {
                            missingTimestamps.add(historyEntry.timestamp());
                        }
                    }

                    /*
                     * If we are missing data (if we have been offline for some time or some other reason):
                     *
                     * Request the clip data from the server
                     * add the data to our list
                     * Update history in local storage
                     * Display history on Activity
                     *
                     * Otherwise, display local history on activity
                     */
                    if(!missingTimestamps.isEmpty()) {
                        app.getApi().getClipboard(getZyncApp().getEncryptionPass(), missingTimestamps, new ZyncAPI.ZyncCallback<List<ZyncClipData>>() {
                            @Override
                            public void success(List<ZyncClipData> value) {
                                for (ZyncClipData clip : value) {
                                    app.clipFromTimestamp(clip.timestamp(), history).setData(clip.data());
                                }

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        setHistory(history);
                                        app.setHistory(history);
                                        dialog.dismiss();
                                    }
                                });
                            }

                            @Override
                            public void handleError(ZyncError error) {
                                handleHistoryError(dialog, error);
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setHistory(history);
                                app.setHistory(history);
                                dialog.dismiss();
                            }
                        });
                    }
                } else {
                    // if there was an error processing server history, load from file
                    loadHistoryFromFile();
                }
            }

            @Override
            public void handleError(ZyncError error) {
                handleHistoryError(dialog, error);
            }
        });
    }

    private void handleHistoryError(ProgressDialog dialog, ZyncError error) {
        dialog.dismiss();
        getZyncApp().setLastRequestStatus(false);
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(HistoryActivity.this);

        alertDialog.setTitle(R.string.unable_fetch_history);
        alertDialog.setMessage(getString(R.string.unable_fetch_history_msg, error.code(), error.message()));
        alertDialog.setPositiveButton(R.string.ok, new NullDialogClickListener());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                alertDialog.show();
            }
        });

        loadHistoryFromFile();
    }

    /*       ACTION BAR START         */

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.history);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*       ACTION BAR END         */

    private ZyncApplication getZyncApp() {
        return (ZyncApplication) getApplication();
    }

    private void loadHistoryFromFile() {
        final List<ZyncClipData> history = historyFromFile();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setHistory(history);
            }
        });
    }

    private List<ZyncClipData> historyFromFile() {
        Set<String> historyStr = getZyncApp().getPreferences()
                .getStringSet("zync_history", new HashSet<String>());
        final List<ZyncClipData> history = new ArrayList<>(historyStr.size());

        for (String json : historyStr) {
            try {
                history.add(new ZyncClipData(getZyncApp().getEncryptionPass(), new JSONObject(json)));
            } catch (Exception e) {
                e.printStackTrace();

                if (!(e instanceof AEADBadTagException)) {
                    ZyncApplication.LOGGED_EXCEPTIONS.add(new ZyncExceptionInfo(e, "decoding history from file"));
                }
            }
        }

        Collections.sort(history, new ZyncClipData.TimeComparator());
        return history;
    }

    private boolean canJoin(ZyncClipData data) {
        return data.type() == ZyncClipType.TEXT && new String(data.data()).length() < 350;
    }

    // set history that is being displayed on the activity
    // this will not override any pre-existing entries
    private void setHistory(List<ZyncClipData> history) {
        LinearLayout prevLayout = null; // ha
        int historySize = history.size() - 1;
        // define variables to be used
        int cardElevation = convertDpToPixel(3);
        int cardCorner = convertDpToPixel(4);
        int cardHeight = convertDpToPixel(200);
        int cardLayoutHeight = convertDpToPixel(215);
        int cardTopMargin = convertDpToPixel(6);
        int cardBottomMargin = convertDpToPixel(8);
        int cardStartMargin = convertDpToPixel(10);
        int textHeight = convertDpToPixel(150);
        int textMargin = cardStartMargin;
        int imageHeight = convertDpToPixel(160);
        int separatorHeight = convertDpToPixel(0.5f);
        int separatorMargin = imageHeight;
        int stampTopMargin = convertDpToPixel(168);
        int stampStartMargin = convertDpToPixel(12);
        int buttonDimension = convertDpToPixel(30);
        int copyEndMargin = convertDpToPixel(50);
        int shareEndMargin = convertDpToPixel(10);
        int buttonTopMargin = convertDpToPixel(165);
        int overallPadding = convertDpToPixel(16);

        for (int i = 0; i < history.size(); i++) {
            final ZyncClipData data = history.get(i);

            if (data.data() == null) {
                System.out.println(data.timestamp() + " is ignored due to null data");
                continue;
            }

            LinearLayout mainLayout = (LinearLayout) findViewById(R.id.history_layout);
            boolean even = (i % 2) == 0;
            boolean joining = false;

            if (even && prevLayout != null) {
                mainLayout = prevLayout;
                prevLayout = null;
                joining = true;
            } else if (!even && canJoin(data) && i != historySize && canJoin(history.get(i + 1))) {
                LinearLayout layout = new LinearLayout(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cardLayoutHeight);
                layout.setLayoutParams(params);
                layout.setPadding(overallPadding, cardTopMargin, overallPadding, cardBottomMargin);
                layout.setOutlineProvider(ViewOutlineProvider.BOUNDS);

                layout.setOrientation(LinearLayout.HORIZONTAL);
                layout.setClipToPadding(false);
                layout.setWeightSum(2);
                layout.setBackgroundColor(getZyncApp().getColorSafe(android.R.color.background_light));

                prevLayout = layout;
                mainLayout.addView(layout);
                mainLayout = layout;
                joining = true;
            }

            CardView card = new CardView(this);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, cardHeight);
            layoutParams.weight = 1;

            if (!joining) {
                layoutParams.width = LayoutParams.MATCH_PARENT;
                layoutParams.setMarginStart(overallPadding);
                layoutParams.setMarginEnd(overallPadding);
                layoutParams.topMargin = cardTopMargin;
            } else {
                layoutParams.bottomMargin = cardBottomMargin;

                if (even) {
                    layoutParams.setMarginStart(cardStartMargin);
                }
            }

            card.setLayoutParams(layoutParams);
            card.setCardElevation(cardElevation);
            card.setRadius(cardCorner); // card corner radius for rounded card

            // setup data preview
            switch (data.type()) {
                case TEXT:
                    TextView textPreview = new TextView(this);
                    setLayout(textPreview, LayoutParams.WRAP_CONTENT, textHeight, textMargin);
                    LinearLayout.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) textPreview.getLayoutParams();
                    params.setMarginStart(textMargin);

                    setTextAppearance(textPreview, android.R.style.TextAppearance_Material_Body1);
                    textPreview.setTextSize(12);

                    byte[] rawData = data.data();
                    String text = rawData == null ?
                            getString(R.string.history_encryption_error) :
                            new String(data.data());

                    if (text.length() > 450) {
                        text = text.substring(0, 447) + "\u2026";
                    }

                    textPreview.setText(text);
                    card.addView(textPreview);
                    break;
            }

            if (data.type() == ZyncClipType.TEXT) {
                // prepare separator
                ImageView separator = new ImageView(this);

                setLayout(separator, LayoutParams.MATCH_PARENT, separatorHeight, separatorMargin);
                separator.setContentDescription(getString(R.string.separator));
                separator.setImageDrawable(new ColorDrawable(Color.rgb(211, 211, 211)));
                card.addView(separator);
            }

            // timestamp
            TextView timestamp = new TextView(this);

            // margins
            setLayout(timestamp, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, stampTopMargin);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) timestamp.getLayoutParams();
            params.setMarginStart(stampStartMargin);
            timestamp.setLayoutParams(params);

            // appearance
            timestamp.setTextSize(12);
            setTextAppearance(timestamp, android.R.style.TextAppearance_Material_Small);

            // content
            timestamp.setText(DateUtils.getRelativeTimeSpanString(
                    data.timestamp(),
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS,
                    DateUtils.FORMAT_NUMERIC_DATE
            ));

            card.addView(timestamp);

            card.addView(createShareButton(data, buttonDimension, buttonTopMargin, shareEndMargin));

            if (data.type() == ZyncClipType.TEXT) {
                card.addView(createCopyButton(data, buttonDimension, buttonTopMargin, copyEndMargin));
            }

            // add layout for this entry to the main linear layout
            mainLayout.addView(card);
        }
    }

    private ImageView createCopyButton(final ZyncClipData data,
                                       int buttonDimension,
                                       int buttonTopMargin,
                                       int copyEndMargin) {
        final ImageView view = new ImageView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonDimension, buttonDimension);
        params.topMargin = buttonTopMargin;
        params.setMarginEnd(copyEndMargin);
        params.gravity = Gravity.END;
        view.setLayoutParams(params);

        view.setImageDrawable(getDrawable(R.drawable.ic_content_copy));
        view.setContentDescription(getString(R.string.history_copy_button));
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTextToClip(data.data(), v);
            }
        });

        return view;
    }

    private void copyTextToClip(byte[] data, View v) {
        // update clipboard and display snackbar
        ZyncClipboardService.getInstance().writeToClip(new String(data), false);
        Snackbar.make(
                v,
                R.string.history_clip_updated_msg,
                Snackbar.LENGTH_LONG
        ).show();
    }

    private ImageView createShareButton(final ZyncClipData data,
                                        int buttonDimension,
                                        int buttonTopMargin,
                                        int shareEndMargin) {
        final ImageView view = new ImageView(this);

        setLayout(view, buttonDimension, buttonDimension, buttonTopMargin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonDimension, buttonDimension);
        params.topMargin = buttonTopMargin;
        params.setMarginEnd(shareEndMargin);
        params.gravity = Gravity.END;
        view.setLayoutParams(params);
        view.setImageDrawable(getDrawable(R.drawable.ic_share));
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (data.type()) {
                    case TEXT:
                        shareText(data.data());
                        break;
                }
            }
        });

        return view;
    }

    private void shareText(byte[] data) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, new String(data));
        sendIntent.setType("text/plain");
        startActivity(sendIntent);
    }

    private void setTextAppearance(TextView view, int resId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            view.setTextAppearance(this, resId);
        } else {
            view.setTextAppearance(resId);
        }
    }

    private void setLayout(View view, int width, int height, int marginTop) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();

        if (params == null) {
            params = new ViewGroup.MarginLayoutParams(width, height);
        }

        params.height = height;
        params.width = width;

        if (marginTop != -1) {
            params.topMargin = marginTop;
        }

        view.setLayoutParams(params);
    }

    private int convertDpToPixel(float dp) {
        Resources resources = getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        return (int) Math.floor(dp * (metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT));
    }
}
