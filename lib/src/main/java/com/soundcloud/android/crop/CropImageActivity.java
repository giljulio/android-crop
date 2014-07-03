/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soundcloud.android.crop;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.view.Window;

/*
 * Modified from original in AOSP.
 */
public class CropImageActivity extends FragmentActivity implements CropCallbacks {

    CropImageFragment cropImageFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.crop__activity_crop);
        initViews();

        if(savedInstanceState == null) {
            setupFromIntent();
        }
    }

    private void initViews() {

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cropImageFragment.onSaveClicked();
            }
        });
    }

    private void setupFromIntent() {
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();

        if (extras == null)
            extras = new Bundle();

        extras.putParcelable(Crop.Extra.URI, intent.getData());

        cropImageFragment = new CropImageFragment();
        cropImageFragment.setArguments(extras);
        getSupportFragmentManager()
                .beginTransaction()
                .add(R.id.container, cropImageFragment)
                .commit();
    }


    @Override
    public void onSuccessfulCrop(Uri uri) {
        setResult(RESULT_OK, new Intent().putExtra(MediaStore.EXTRA_OUTPUT, uri));
    }

    @Override
    public void onFailedCrop(Throwable throwable) {
        setResult(Crop.RESULT_ERROR, new Intent().putExtra(Crop.Extra.ERROR, throwable));
    }
}

