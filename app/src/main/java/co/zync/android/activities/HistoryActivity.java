package co.zync.android.activities;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.support.design.widget.Snackbar;
import android.support.v4.app.NavUtils;
import android.support.v4.content.FileProvider;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.CardView;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.*;
import android.view.ViewGroup.LayoutParams;
import android.widget.*;
import co.zync.android.R;
import co.zync.android.ZyncApplication;
import co.zync.android.services.ZyncClipboardService;
import co.zync.android.api.ZyncClipData;
import co.zync.android.api.ZyncClipType;
import co.zync.android.api.ZyncError;
import co.zync.android.api.callback.ZyncCallback;
import co.zync.android.utils.GenericAsyncTask;
import com.google.firebase.perf.metrics.AddTrace;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;

public class HistoryActivity extends AppCompatActivity {
    private SparseArray<BitmapFactory.Options> imageOptions = new SparseArray<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setContentView(R.layout.activity_history);
        setupActionBar();
        getWindow().getDecorView().setBackgroundColor(getZyncApp().getColorSafe(android.R.color.background_light));

        final ProgressDialog dialog = new ProgressDialog(this);

        dialog.setIndeterminate(true);
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setTitle(R.string.loading_history);
        dialog.setMessage(getString(R.string.gathering_data));

        dialog.show();

        final ZyncApplication app = getZyncApp();

        app.getApi().getHistory(app.getConfig().getEncryptionPass(), new ZyncCallback<List<ZyncClipData>>() {
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
                    List<ZyncClipData> localHistory = app.getConfig().getHistory();
                    List<Long> missingTimestamps = new ArrayList<>();
                    List<Long> imageTimestamps = new ArrayList<>();

                    for (ZyncClipData historyEntry : history) {
                        ZyncClipData local = app.clipFromTimestamp(historyEntry.timestamp(), localHistory);

                        if (local != null && local.data() != null) {
                            historyEntry.setData(local.data());
                        } else {
                            if (historyEntry.type() == ZyncClipType.TEXT) {
                                missingTimestamps.add(historyEntry.timestamp());
                            } else {
                                imageTimestamps.add(historyEntry.timestamp());
                            }
                        }
                    }

                    for (int i = 0; i < imageTimestamps.size(); i++) {
                        setDialogMessage(dialog, getString(R.string.downloading_image_history, i + 1, imageTimestamps.size()));
                        // downloads the entry if need be, will block thread
                        getZyncApp().getDataManager().load(history.get(i), true);
                    }

                    setDialogMessage(dialog, getString(R.string.gathering_data));

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
                        app.getApi().getClipboard(app.getConfig().getEncryptionPass(), missingTimestamps, new ZyncCallback<List<ZyncClipData>>() {
                            @Override
                            public void success(List<ZyncClipData> value) {
                                for (ZyncClipData clip : value) {
                                    app.clipFromTimestamp(clip.timestamp(), history).setData(clip.data());
                                }

                                setDialogMessage(dialog, getString(R.string.processing_data));
                                setHistory(history);
                                app.getConfig().setHistory(history);
                                dialog.dismiss();
                            }

                            @Override
                            public void handleError(ZyncError error) {
                                handleHistoryError(dialog, error);
                            }
                        });
                    } else {
                        setDialogMessage(dialog, getString(R.string.processing_data));
                        setHistory(history);
                        app.getConfig().setHistory(history);
                        dialog.dismiss();
                    }
                } else {
                    // if there was an error processing server history, load from file
                    loadHistoryFromFile(null);
                }
            }

            @Override
            public void handleError(ZyncError error) {
                handleHistoryError(dialog, error);
            }
        });
    }

    public void setDialogMessage(final ProgressDialog dialog, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dialog.setMessage(message);
            }
        });
    }

    private void handleHistoryError(ProgressDialog dialog, ZyncError error) {
        getZyncApp().handleErrorGeneric(this, error, R.string.load_history_error, dialog);
        loadHistoryFromFile(null);
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

    private void loadHistoryFromFile(final Runnable callback) {
        if (callback != null) {
            new GenericAsyncTask(new Runnable() {
                @Override
                public void run() {
                    setHistory(getZyncApp().getConfig().getHistory());
                    callback.run();
                }
            }).execute();
        } else {
            setHistory(getZyncApp().getConfig().getHistory());
        }
    }

    private boolean canJoin(int index, ZyncClipData data) {
        if (data.type() == ZyncClipType.IMAGE) {
            BitmapFactory.Options bitMapOption = imageOptions.get(index);

            if (bitMapOption == null) {
                bitMapOption = new BitmapFactory.Options();
                bitMapOption.inJustDecodeBounds = true;

                try {
                    BitmapFactory.decodeStream(
                            new FileInputStream(getZyncApp().getDataManager().fileFor(data, false)),
                            null,
                            bitMapOption
                    );
                } catch (FileNotFoundException ignored) {
                }

                imageOptions.append(index, bitMapOption);
            }

            return bitMapOption.outWidth == 0 || (bitMapOption.outHeight / bitMapOption.outWidth) > 0.8 ||
                    (bitMapOption.outHeight < 700 && bitMapOption.outWidth < 700);
        }

        return data.type() == ZyncClipType.TEXT && new String(data.data()).length() < 350;
    }

    // set history that is being displayed on the activity
    // this will not override any pre-existing entries
    @AddTrace(name = "loadHistoryUi")
    protected void setHistory(List<ZyncClipData> systemHistory) {
        List<ZyncClipData> history = new ArrayList<>(systemHistory.size());
        LinearLayout prevLayout = null; // ha
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

        for (ZyncClipData data : systemHistory) {
            // check if encryption failed with text, if it did, ignore and continue
            if (data.type() == ZyncClipType.TEXT && data.data() == null) {
                continue;
            }

            history.add(data);
            System.out.println("Adding data " + data.type() + " to be displayed in history");
        }

        int historySize = history.size() - 1;

        for (int i = 0; i < history.size(); i++) {
            final ZyncClipData data = history.get(i);

            LinearLayout mainLayout = (LinearLayout) findViewById(R.id.history_layout);
            boolean joining = false;
            boolean second = false;

            /*
             * Check if we are in queue to be joined with another
             * entry in the same row
             */
            if (prevLayout != null && (i != 0 && canJoin(i - 1, data))) {
                mainLayout = prevLayout;
                prevLayout = null;
                joining = true;
                second = true;
            }

            /*
             * Check if the current data entry and the next are able to be joined
             * on the same row, if so, create the layout that the items will go under
             * and set the appropriate variables for the next entry to be added
             */
            if (!joining && canJoin(i, data) && i != historySize && canJoin(i + 1, history.get(i + 1))) {
                LinearLayout layout = new LinearLayout(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cardLayoutHeight);
                layout.setLayoutParams(params);
                layout.setPadding(overallPadding, cardTopMargin, overallPadding, cardBottomMargin);

                // entries' weights must add up to 2
                layout.setWeightSum(2);

                // odd stuff to fix card shadows from being cut off due to boundaries
                layout.setOutlineProvider(ViewOutlineProvider.BOUNDS);
                layout.setOrientation(LinearLayout.HORIZONTAL);
                layout.setClipToPadding(false);
                layout.setBackgroundColor(getZyncApp().getColorSafe(android.R.color.background_light));

                // set variables for next entry
                prevLayout = layout;
                addViewUi(mainLayout, layout);
                mainLayout = layout;
                joining = true;
            }

            CardView card = new CardView(this);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, cardHeight);
            layoutParams.weight = 1;

            if (!joining) {
                // we aren't joining, thus must set the margins for
                // ourselves instead of using the padding that would
                // have been added in our parent LinearLayout
                layoutParams.width = LayoutParams.MATCH_PARENT;
                layoutParams.setMarginStart(overallPadding);
                layoutParams.setMarginEnd(overallPadding);
                layoutParams.topMargin = cardTopMargin;
            } else {
                // bottom margin for shadows
                layoutParams.bottomMargin = cardBottomMargin;

                // if we are even (and joining), add a start margin to separate the two entries
                if (second) {
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
                    params.setMarginEnd(textMargin);

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

                case IMAGE:
                    BitmapFactory.Options bitmapOptions = imageOptions.get(i);

                    if (bitmapOptions == null) {
                        canJoin(i, data); // will load and insert loaded info to imageOptions
                        bitmapOptions = imageOptions.get(i);
                    }

                    bitmapOptions.inJustDecodeBounds = false; // we want the image this time

                    // calculate sample size to reduce loaded image size in memory
                    // if possible; scaling down appropriately to card dimensions
                    // on screen
                    bitmapOptions.inSampleSize = calculateInSampleSize(bitmapOptions, card.getWidth(), imageHeight);

                    Bitmap bitmap = null;

                    try {
                        bitmap = BitmapFactory.decodeStream(
                                // image must already be loaded as we are already making calculations with it
                                new FileInputStream(getZyncApp().getDataManager().fileFor(data, false)),
                                null,
                                bitmapOptions
                        );
                    } catch (FileNotFoundException ignored) {
                    }

                    ImageView imagePreview = new ImageView(this);

                    setLayout(imagePreview, LayoutParams.MATCH_PARENT, imageHeight, -1);
                    imagePreview.setImageBitmap(bitmap);
                    imagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    card.addView(imagePreview);
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

            addViewUi(mainLayout, card);
        }
    }

    private void addViewUi(final LinearLayout layout, final View view) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                layout.addView(view);
            }
        });
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
                    case IMAGE:
                        shareImage(data);
                        break;
                }
            }
        });

        return view;
    }

    private void shareImage(ZyncClipData data) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                this,
                "co.zync.android.fileprovider",
                getZyncApp().getDataManager().fileFor(data, false)
        ));
        sendIntent.setType("image/png");
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_via)));
    }

    private void shareText(byte[] data) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, new String(data));
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_via)));
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

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }
}
