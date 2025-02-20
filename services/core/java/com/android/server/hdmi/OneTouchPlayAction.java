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
package com.android.server.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient.OneTouchPlayCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature action that performs one touch play against TV/Display device. This action is initiated
 * via {@link android.hardware.hdmi.HdmiPlaybackClient#oneTouchPlay(OneTouchPlayCallback)} from the
 * Android system working as playback device to turn on the TV, and switch the input.
 * <p>
 * Package-private, accessed by {@link HdmiControlService} only.
 */
final class OneTouchPlayAction extends HdmiCecFeatureAction {
    private static final String TAG = "OneTouchPlayAction";

    // State in which the action is waiting for <Report Power Status>. In normal situation
    // source device can simply send <Text|Image View On> and <Active Source> in succession
    // since the standard requires that the TV/Display should buffer the <Active Source>
    // if the TV is brought of out standby state.
    //
    // But there are TV's that fail to buffer the <Active Source> while getting out of
    // standby mode, and do not accept the command until their power status becomes 'ON'.
    // For a workaround, we send <Give Device Power Status> commands periodically to make sure
    // the device switches its status to 'ON'. Then we send additional <Active Source>.
    private static final int STATE_WAITING_FOR_REPORT_POWER_STATUS = 1;

    // The maximum number of times we send <Give Device Power Status> before we give up.
    // We wait up to RESPONSE_TIMEOUT_MS * LOOP_COUNTER_MAX = 20 seconds.
    private static final int LOOP_COUNTER_MAX = 10;

    private final int mTargetAddress;
    private final List<IHdmiControlCallback> mCallbacks = new ArrayList<>();

    private int mPowerStatusCounter = 0;

    private final SendMessageCallback mSendMessageCallback = new SendMessageCallback() {
        @Override
        public void onSendCompleted(int error) {
            HdmiLogger.debug("OneTouchPlayAction.onSendCompleted  error= " + error + ", mState= " + mState);

            if (error != SendMessageResult.SUCCESS) {
                if (mState == STATE_WAITING_FOR_REPORT_POWER_STATUS) {
                    /**
                     * Send message failed.
                     * invokeCallback(result) with result being negative error value of
                     * SendMessageResult.{NACK, BUSY, FAIL}, effectively:
                     * - SendMessageResult.NACK = -1
                     * - SendMessageResult.BUSY = -2
                     * - SendMessageResult.FAIL = -3
                     */
                    invokeCallback(-error);
                    finish();
                }
            }
        }
    };

    // Factory method. Ensures arguments are valid.
    static OneTouchPlayAction create(HdmiCecLocalDeviceSource source,
            int targetAddress, IHdmiControlCallback callback) {
        HdmiLogger.debug("OneTouchPlayAction.create targetAddress= " + targetAddress);        
        if (source == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new OneTouchPlayAction(source, targetAddress,
                callback);
    }

    private OneTouchPlayAction(HdmiCecLocalDevice localDevice, int targetAddress,
            IHdmiControlCallback callback) {
        super(localDevice);
        mTargetAddress = targetAddress;
        addCallback(callback);
    }

    @Override
    boolean start() {
        sendCommand(HdmiCecMessageBuilder.buildTextViewOn(getSourceAddress(), mTargetAddress));
        HdmiLogger.debug("OneTouchPlayAction.start");
        sendCommand(HdmiCecMessageBuilder.buildTextViewOn(getSourceAddress(), mTargetAddress),
                mSendMessageCallback);
        broadcastActiveSource();
        queryDevicePowerStatus();
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        return true;
    }

    private void broadcastActiveSource() {
        // Because only source device can create this action, it's safe to cast.
        HdmiCecLocalDeviceSource source = source();
        source.mService.setAndBroadcastActiveSourceFromOneDeviceType(
                mTargetAddress, getSourcePath());
        // When OneTouchPlay is called, client side should be responsible to send out the intent
        // of which internal source, for example YouTube, it would like to switch to.
        // Here we only update the active port and the active source records in the local
        // device as well as claiming Active Source.
        if (source.mService.audioSystem() != null) {
            source = source.mService.audioSystem();
        }
        source.setRoutingPort(Constants.CEC_SWITCH_HOME);
        source.setLocalActivePort(Constants.CEC_SWITCH_HOME);
    }

    private void queryDevicePowerStatus() {
        HdmiLogger.debug("OneTouchPlayAction.queryDevicePowerStatus");
        mState = STATE_WAITING_FOR_REPORT_POWER_STATUS;
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(),
                mTargetAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_POWER_STATUS
                || mTargetAddress != cmd.getSource()) {
            return false;
        }
        if (cmd.getOpcode() == Constants.MESSAGE_REPORT_POWER_STATUS) {
            HdmiLogger.debug("OneTouchPlayAction.processCommand for cmd= " + cmd);
            int status = cmd.getParams()[0];
            if (status == HdmiControlManager.POWER_STATUS_ON) {
                broadcastActiveSource();
                invokeCallback(HdmiControlManager.RESULT_SUCCESS);
                finish();
            }
            return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        HdmiLogger.debug("OneTouchPlayAction.handleTimerEvent for state= " + state + ", correntState= " + mState + ", mPowerStatusCounter= " + mPowerStatusCounter);
        if (mState != state) {
            return;
        }
        if (state == STATE_WAITING_FOR_REPORT_POWER_STATUS) {
            if (mPowerStatusCounter++ < LOOP_COUNTER_MAX) {
                queryDevicePowerStatus();
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
            } else {
                // Couldn't wake up the TV for whatever reason. Report failure.
                invokeCallback(HdmiControlManager.RESULT_TIMEOUT);
                finish();
            }
        }
    }

    public void addCallback(IHdmiControlCallback callback) {
        HdmiLogger.debug("OneTouchPlayAction.addCallback mCallbacks.size= " + mCallbacks.size());
        mCallbacks.add(callback);
    }

    private void invokeCallback(int result) {
        HdmiLogger.debug("OneTouchPlayAction.invokeCallback("+result+") mCallbacks.size= " + mCallbacks.size());
        try {
            for (IHdmiControlCallback callback : mCallbacks) {
                callback.onComplete(result);
            }
        } catch (RemoteException e) {
            HdmiLogger.error("OneTouchPlayAction.invokeCallback("+result+") failed:" + e);
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
