/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.displaystation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.pwmspeaker.Speaker;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;

public class DisplayActivity extends Activity {

    private static final String TAG = DisplayActivity.class.getSimpleName();

    private AlphanumericDisplay mDisplay;
    private int[] mRainbow = new int[7];
    private Apa102 mLedstrip;
    private int ledPosition;
    private static final int LEDSTRIP_BRIGHTNESS = 1;
    private String displayMessage = "HomeAway Traveler Android";
    private int messageStartingPoint;
    private boolean up = true;

    Handler mainHandler;

    private Runnable updateLedThread = new Runnable() {
        public void run() {
            updateLed();
            mainHandler.postDelayed(this, 77);
        }
    };

    private Runnable updateDisplayThread = new Runnable() {
        public void run() {
            updateDisplay();
            mainHandler.postDelayed(this, 777);
        }
    };

    private Gpio mLed;
    private int SPEAKER_READY_DELAY_MS = 300;
    private Speaker mSpeaker;
    private ImageView mImageView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        mImageView = findViewById(R.id.imageView);

        try {
            mDisplay = new AlphanumericDisplay(BoardDefaults.getI2cBus());
            mDisplay.setEnabled(true);
            mDisplay.clear();
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing display", e);
            Log.d(TAG, "Display disabled");
            mDisplay = null;
        }

        // SPI ledstrip
        try {
            mLedstrip = new Apa102(BoardDefaults.getSpiBus(), Apa102.Mode.BGR);
            mLedstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
            for (int i = 0; i < mRainbow.length; i++) {
                float[] hsv = {i * 360.f / mRainbow.length, 1.0f, 1.0f};
                mRainbow[i] = Color.HSVToColor(255, hsv);
            }
        } catch (IOException e) {
            mLedstrip = null; // Led strip is optional.
        }

        mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(updateLedThread);
        mainHandler.post(updateDisplayThread);

        // GPIO led
        try {
            PeripheralManagerService pioService = new PeripheralManagerService();
            mLed = pioService.openGpio(BoardDefaults.getLedGpioPin());
            mLed.setEdgeTriggerType(Gpio.EDGE_NONE);
            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }

        // PWM speaker
        try {
            mSpeaker = new Speaker(BoardDefaults.getSpeakerPwmPin());
            final ValueAnimator slide = ValueAnimator.ofFloat(440, 440 * 4);
            slide.setDuration(50);
            slide.setRepeatCount(5);
            slide.setInterpolator(new LinearInterpolator());
            slide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    try {
                        float v = (float) animation.getAnimatedValue();
                        mSpeaker.play(v);
                    } catch (IOException e) {
                        throw new RuntimeException("Error sliding speaker", e);
                    }
                }
            });
            slide.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        mSpeaker.stop();
                    } catch (IOException e) {
                        throw new RuntimeException("Error sliding speaker", e);
                    }
                }
            });
            Handler handler = new Handler(getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    slide.start();
                }
            }, SPEAKER_READY_DELAY_MS);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing speaker", e);
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mDisplay != null) {
            try {
                mDisplay.clear();
                mDisplay.setEnabled(false);
                mDisplay.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling display", e);
            } finally {
                mDisplay = null;
            }
        }

        if (mLedstrip != null) {
            try {
                mLedstrip.setBrightness(0);
                mLedstrip.write(new int[7]);
                mLedstrip.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling ledstrip", e);
            } finally {
                mLedstrip = null;
            }
        }

        if (mLed != null) {
            try {
                mLed.setValue(false);
                mLed.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling led", e);
            } finally {
                mLed = null;
            }
        }
    }

    private void updateDisplay() {
        if (mDisplay != null) {
            try {
                messageStartingPoint = (messageStartingPoint + 1) % displayMessage.length();
                mDisplay.display(displayMessage.substring(messageStartingPoint));
            } catch (IOException e) {
                Log.e(TAG, "Error setting display", e);
            }
        }
    }

    private void updateLed() {
        // Update led strip.
        if (mLedstrip == null) {
            return;
        }
        if (up) {
            ledPosition = (ledPosition + 1);
            if (ledPosition >= mRainbow.length - 1) {
                up = !up;
            }
        } else {
            ledPosition = (ledPosition - 1);
            if (ledPosition <= 0) {
                up = !up;
            }
        }

        int[] colors = new int[mRainbow.length];
        colors[ledPosition] = mRainbow[ledPosition];
        try {
            mLedstrip.write(colors);
        } catch (IOException e) {
            Log.e(TAG, "Error setting ledstrip", e);
        }
    }
}
