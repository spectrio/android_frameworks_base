package com.android.server.hdmi;

/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature action that send standby message. This action is initiated via
 * {@link HdmiPlaybackClient#setStandby(SendStandbyCallback)} from the Android system.
 * <p>
 * Package-private, accessed by {@link HdmiControlService} only.
 */
final class StandbyAction extends HdmiCecFeatureAction {
    private static final String TAG = "StandbyAction";

    private static final int STATE_WAITING_FOR_ACK = 1;

    private final int mTargetAddress;
    private final List<IHdmiControlCallback> mCallbacks = new ArrayList<>();

    private final SendMessageCallback mSendMessageCallback = new SendMessageCallback() {
        @Override
        public void onSendCompleted(int error) {
            HdmiLogger.debug("StandbyAction.onSendCompleted  ailed to send  error= " + error + ", mState= " + mState);
            if (mState == STATE_WAITING_FOR_ACK) {
                /**
                 * Send message failed.
                 * invokeCallback(result) with result being negative error value of
                 * SendMessageResult.{SUCCESS, NACK, BUSY, FAIL}
                 * Effectively:
                 * - SendMessageResult.SUCCESS = 0
                 * - SendMessageResult.NACK = -1
                 * - SendMessageResult.BUSY = -2
                 * - SendMessageResult.FAIL = -3
                 */
                invokeCallback(-error);
                finish();
            }
        }
    };

    static StandbyAction create(HdmiCecLocalDevice source,
                                int targetAddress, IHdmiControlCallback callback) {
        HdmiLogger.debug("StandbyAction.create targetAddress= " + targetAddress);
        if (source == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            HdmiLogger.debug("StandbyAction.create Wrong arguments");
            return null;
        }
        return new StandbyAction(source, targetAddress, callback);
    }

    private StandbyAction(HdmiCecLocalDevice localDevice,
                          int targetAddress, IHdmiControlCallback callback) {
        super(localDevice);
        mTargetAddress = targetAddress;
        addCallback(callback);
    }

    @Override
    boolean start() {
        HdmiLogger.debug("StandbyAction.start");
        sendCommand(HdmiCecMessageBuilder.buildStandby(getSourceAddress(), mTargetAddress),
                mSendMessageCallback);
        mState = STATE_WAITING_FOR_ACK;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        HdmiLogger.debug("StandbyAction.handleTimerEvent for state= " + state + ", correntState= " + mState);
        if (mState != state) {
            return;
        }
        if (state == STATE_WAITING_FOR_ACK) {
            // Got no response from TV.
            invokeCallback(-SendMessageResult.NACK);
            finish();
        }
    }

    public void addCallback(IHdmiControlCallback callback) {
        mCallbacks.add(callback);
        HdmiLogger.debug("StandbyAction.addCallback mCallbacks.size= " + mCallbacks.size());
    }

    private void invokeCallback(int result) {
        HdmiLogger.debug("StandbyAction.invokeCallback("+result+") mCallbacks.size= " + mCallbacks.size());
        try {
            for (IHdmiControlCallback callback : mCallbacks) {
                callback.onComplete(result);
            }
        } catch (RemoteException e) {
            HdmiLogger.debug("StandbyAction.invokeCallback("+result+") failed:" + e);
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
