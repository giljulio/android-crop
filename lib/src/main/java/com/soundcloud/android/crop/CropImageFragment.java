package com.soundcloud.android.crop;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.opengl.GLES10;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;

import com.soundcloud.android.crop.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Created by Gil on 03/07/2014.
 */
public class CropImageFragment extends MonitoredFragment {

    private static final boolean IN_MEMORY_CROP = Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD_MR1;

    private final Handler handler = new Handler();

    private int aspectX;
    private int aspectY;

    // Output image size
    private int maxX;
    private int maxY;
    private int exifRotation;

    private Uri sourceUri;
    private Uri saveUri;

    private boolean isSaving;

    private int sampleSize;
    private RotateBitmap rotateBitmap;
    private CropImageView imageView;
    private HighlightView cropView;

    private CropCallbacks cropCallbacks;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        initViews();

        setupFromIntent();
        if (rotateBitmap == null) {
            return;
        }
        startCrop();
    }

    private void initViews() {
        imageView.context = getActivity();
        imageView.setRecycler(new ImageViewTouchBase.Recycler() {
            @Override
            public void recycle(Bitmap b) {
                b.recycle();
                System.gc();
            }
        });
    }

    private void setupFromIntent() {
        Bundle extras = getArguments();
        if (extras != null) {
            aspectX = extras.getInt(Crop.Extra.ASPECT_X);
            aspectY = extras.getInt(Crop.Extra.ASPECT_Y);
            maxX = extras.getInt(Crop.Extra.MAX_X);
            maxY = extras.getInt(Crop.Extra.MAX_Y);
            saveUri = extras.getParcelable(MediaStore.EXTRA_OUTPUT);
            sourceUri = extras.getParcelable(Crop.Extra.URI);
        }

        if (sourceUri != null) {
            exifRotation = CropUtil.getExifRotation(CropUtil.getFromMediaUri(getActivity().getContentResolver(), sourceUri));

            InputStream is = null;
            try {
                sampleSize = calculateBitmapSampleSize(sourceUri);
                is = getActivity().getContentResolver().openInputStream(sourceUri);
                BitmapFactory.Options option = new BitmapFactory.Options();
                option.inSampleSize = sampleSize;
                rotateBitmap = new RotateBitmap(BitmapFactory.decodeStream(is, null, option), exifRotation);
            } catch (IOException e) {
                Log.e("Error reading picture: " + e.getMessage(), e);
                cropCallbacks.onFailedCrop(e);
            } catch (OutOfMemoryError e) {
                Log.e("OOM while reading picture: " + e.getMessage(), e);
                cropCallbacks.onFailedCrop(e);
            } finally {
                CropUtil.closeSilently(is);
            }
        }
    }

    private int calculateBitmapSampleSize(Uri bitmapUri) throws IOException {
        InputStream is = null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            is = getActivity().getContentResolver().openInputStream(bitmapUri);
            BitmapFactory.decodeStream(is, null, options); // Just get image size
        } finally {
            CropUtil.closeSilently(is);
        }

        // Get max texture size of OpenGL as it limits bitmap size drawn on ImageView
        int[] maxSize = new int[1];
        GLES10.glGetIntegerv(GLES10.GL_MAX_TEXTURE_SIZE, maxSize, 0);

        int sampleSize = 1;
        while (options.outHeight / sampleSize > maxSize[0] || options.outWidth / sampleSize > maxSize[0]) {
            sampleSize = sampleSize << 1;
        }
        return sampleSize;
    }

    private void startCrop() {
        if (isRemoving()) {
            return;
        }
        imageView.setImageRotateBitmapResetBase(rotateBitmap, true);
        CropUtil.startBackgroundJob(this, null, getResources().getString(R.string.crop__wait),
                new Runnable() {
                    public void run() {
                        final CountDownLatch latch = new CountDownLatch(1);
                        handler.post(new Runnable() {
                            public void run() {
                                if (imageView.getScale() == 1F) {
                                    imageView.center(true, true);
                                }
                                latch.countDown();
                            }
                        });
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        new Cropper().crop();
                    }
                }, handler
        );
    }

    private class Cropper {

        private void makeDefault() {
            if (rotateBitmap == null) {
                return;
            }

            HighlightView hv = new HighlightView(imageView);
            final int width = rotateBitmap.getWidth();
            final int height = rotateBitmap.getHeight();

            Rect imageRect = new Rect(0, 0, width, height);

            // Make the default size about 4/5 of the width or height
            int cropWidth = Math.min(width, height) * 4 / 5;
            @SuppressWarnings("SuspiciousNameCombination")
            int cropHeight = cropWidth;

            if (aspectX != 0 && aspectY != 0) {
                if (aspectX > aspectY) {
                    cropHeight = cropWidth * aspectY / aspectX;
                } else {
                    cropWidth = cropHeight * aspectX / aspectY;
                }
            }

            int x = (width - cropWidth) / 2;
            int y = (height - cropHeight) / 2;

            RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
            hv.setup(imageView.getUnrotatedMatrix(), imageRect, cropRect, aspectX != 0 && aspectY != 0);
            imageView.add(hv);
        }

        public void crop() {
            handler.post(new Runnable() {
                public void run() {
                    makeDefault();
                    imageView.invalidate();
                    if (imageView.highlightViews.size() == 1) {
                        cropView = imageView.highlightViews.get(0);
                        cropView.setFocus(true);
                    }
                }
            });
        }
    }

    /*
     * TODO
     * This should use the decode/crop/encode single step API so that the whole
     * (possibly large) Bitmap doesn't need to be read into memory
     */
    public void onSaveClicked() {
        if (cropView == null || isSaving) {
            return;
        }
        isSaving = true;

        Bitmap croppedImage = null;
        Rect r = cropView.getScaledCropRect(sampleSize);
        int width = r.width();
        int height = r.height();

        int outWidth = width, outHeight = height;
        if (maxX > 0 && maxY > 0 && (width > maxX || height > maxY)) {
            float ratio = (float) width / (float) height;
            if ((float) maxX / (float) maxY > ratio) {
                outHeight = maxY;
                outWidth = (int) ((float) maxY * ratio + .5f);
            } else {
                outWidth = maxX;
                outHeight = (int) ((float) maxX / ratio + .5f);
            }
        }

        if (IN_MEMORY_CROP && rotateBitmap != null) {
            croppedImage = inMemoryCrop(rotateBitmap, croppedImage, r, width, height, outWidth, outHeight);
            if (croppedImage != null) {
                imageView.setImageBitmapResetBase(croppedImage, true);
                imageView.center(true, true);
                imageView.highlightViews.clear();
            }
        } else {
            try {
                croppedImage = decodeRegionCrop(croppedImage, r);
            } catch (IllegalArgumentException e) {
                cropCallbacks.onFailedCrop(e);
                return;
            }

            if (croppedImage != null) {
                imageView.setImageRotateBitmapResetBase(new RotateBitmap(croppedImage, exifRotation), true);
                imageView.center(true, true);
                imageView.highlightViews.clear();
            }
        }
        saveImage(croppedImage);
    }

    private void saveImage(Bitmap croppedImage) {
        if (croppedImage != null) {
            final Bitmap b = croppedImage;
            CropUtil.startBackgroundJob(this, null, getResources().getString(R.string.crop__saving),
                    new Runnable() {
                        public void run() {
                            saveOutput(b);
                        }
                    }, handler
            );
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
        try {
            cropCallbacks = (CropCallbacks) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement CropCallbacks");
        }
    }

    @TargetApi(10)
    private Bitmap decodeRegionCrop(Bitmap croppedImage, Rect rect) {
        // Release memory now
        clearImageView();

        InputStream is = null;
        try {
            is = getActivity().getContentResolver().openInputStream(sourceUri);
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(is, false);
            final int width = decoder.getWidth();
            final int height = decoder.getHeight();

            if (exifRotation != 0) {
                // Adjust crop area to account for image rotation
                Matrix matrix = new Matrix();
                matrix.setRotate(-exifRotation);

                RectF adjusted = new RectF();
                matrix.mapRect(adjusted, new RectF(rect));

                // Adjust to account for origin at 0,0
                adjusted.offset(adjusted.left < 0 ? width : 0, adjusted.top < 0 ? height : 0);
                rect = new Rect((int) adjusted.left, (int) adjusted.top, (int) adjusted.right, (int) adjusted.bottom);
            }

            try {
                croppedImage = decoder.decodeRegion(rect, new BitmapFactory.Options());

            } catch (IllegalArgumentException e) {
                // Rethrow with some extra information
                throw new IllegalArgumentException("Rectangle " + rect + " is outside of the image ("
                        + width + "," + height + "," + exifRotation + ")", e);
            }

        } catch (IOException e) {
            Log.e("Error cropping picture: " + e.getMessage(), e);
        } finally {
            CropUtil.closeSilently(is);
        }
        return croppedImage;
    }

    private Bitmap inMemoryCrop(RotateBitmap rotateBitmap, Bitmap croppedImage, Rect r,
                                int width, int height, int outWidth, int outHeight) {
        // In-memory crop means potential OOM errors,
        // but we have no choice as we can't selectively decode a bitmap with this API level
        System.gc();

        try {
            croppedImage = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565);

            Canvas canvas = new Canvas(croppedImage);
            RectF dstRect = new RectF(0, 0, width, height);

            Matrix m = new Matrix();
            m.setRectToRect(new RectF(r), dstRect, Matrix.ScaleToFit.FILL);
            m.preConcat(rotateBitmap.getRotateMatrix());
            canvas.drawBitmap(rotateBitmap.getBitmap(), m, null);

        } catch (OutOfMemoryError e) {
            Log.e("Error cropping picture: " + e.getMessage(), e);
            System.gc();
        }

        // Release bitmap memory as soon as possible
        clearImageView();
        return croppedImage;
    }

    private void clearImageView() {
        imageView.clear();
        if (rotateBitmap != null) {
            rotateBitmap.recycle();
        }
        System.gc();
    }

    private void saveOutput(Bitmap croppedImage) {
        if (saveUri != null) {
            OutputStream outputStream = null;
            try {
                outputStream = getActivity().getContentResolver().openOutputStream(saveUri);
                if (outputStream != null) {
                    croppedImage.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                }

            } catch (IOException e) {
                cropCallbacks.onFailedCrop(e);
                Log.e("Cannot open file: " + saveUri, e);
            } finally {
                CropUtil.closeSilently(outputStream);
            }

            if (!IN_MEMORY_CROP) {
                // In-memory crop negates the rotation
                CropUtil.copyExifRotation(
                        CropUtil.getFromMediaUri(getActivity().getContentResolver(), sourceUri),
                        CropUtil.getFromMediaUri(getActivity().getContentResolver(), saveUri)
                );
            }

            cropCallbacks.onSuccessfulCrop(saveUri);
        }

        final Bitmap b = croppedImage;
        handler.post(new Runnable() {
            public void run() {
                imageView.clear();
                b.recycle();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (rotateBitmap != null) {
            rotateBitmap.recycle();
        }
    }

    public boolean isSaving() {
        return isSaving;
    }

    /*private void setResultUri(Uri uri) {
        setResult(RESULT_OK, new Intent().putExtra(MediaStore.EXTRA_OUTPUT, uri));
    }

    private void setResultException(Throwable throwable) {
        setResult(Crop.RESULT_ERROR, new Intent().putExtra(Crop.Extra.ERROR, throwable));
    }*/
}
