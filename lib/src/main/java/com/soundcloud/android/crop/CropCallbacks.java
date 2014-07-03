package com.soundcloud.android.crop;

import android.net.Uri;

/**
 * Created by Gil on 03/07/2014.
 */
public interface CropCallbacks {

    public void onSuccessfulCrop(Uri uri);

    public void onFailedCrop(Throwable throwable);
}
