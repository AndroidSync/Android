package co.zync.android.api;

import co.zync.android.R;

public enum ZyncClipType {
    TEXT, IMAGE, VIDEO, BINARY;

    public int presentableName() {
        switch (this) {
            case TEXT:
                return R.string.zync_text;

            case IMAGE:
                return R.string.zync_image;

            case VIDEO:
                return R.string.zync_video;

            default:
                return R.string.zync_binary;
        }
    }
}
