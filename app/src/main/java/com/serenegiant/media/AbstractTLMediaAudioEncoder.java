package com.serenegiant.media;

/*
 * TimeLapseRecordingSample
 * Sample project to capture audio and video periodically from internal mic/camera
 * and save as time lapsed MPEG4 file.
 *
 * Copyright (c) 2015 saki t_saki@serenegiant.com
 *
 * File name: TLMediaAudioEncoder.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
*/

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;

/**
 * Encoder class to encode audio data with AAC encoder and save into intermediate files
 */
public abstract class AbstractTLMediaAudioEncoder extends TLMediaEncoder {
	private static final boolean DEBUG = false;
	private final String TAG = getClass().getSimpleName();

	private static final String MIME_TYPE = "audio/mp4a-latm";
    protected static final int DEFAULT_SAMPLE_RATE = 44100;	// 44.1[KHz] is only setting guaranteed to be available on all devices.
    protected static final int DEFAULT_BIT_RATE = 64000;

    protected final int mSampleRate;	// 44100 = 44.1[KHz] is only setting guaranteed to be available on all devices.
    protected final int mBitRate;		// 64000
    
    private AudioThread mAudioThread = null;

	/**
	 * Constructor(this class only support monaural audio source)
	 * @param context
	 * @param base_path
	 * @param listener
	 * @param sample_rate default value is 44100(44.1kHz, 44.1KHz is only guarantee value on all devices)
	 * @param bit_rate  default value is 64000(64kbps)
	 */
	public AbstractTLMediaAudioEncoder(final Context context, final String base_path, final MediaEncoderListener listener,
									   final int sample_rate, final int bit_rate) {

		super(context, base_path, 1, listener);
		mSampleRate = sample_rate > 0 ? sample_rate : DEFAULT_SAMPLE_RATE;
		mBitRate = bit_rate > 0 ? bit_rate : DEFAULT_BIT_RATE;
	}

	@Override
	protected MediaFormat internal_prepare() throws IOException {
		if (DEBUG) Log.v(TAG, "prepare:");
		// prepare MediaCodec for AAC encoding of audio data from inernal mic.
		final MediaCodecInfo audioCodecInfo = selectAudioCodec(MIME_TYPE);
		if (audioCodecInfo == null) {
			Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
			return null;
		}
		if (DEBUG) Log.i(TAG, "selected codec: " + audioCodecInfo.getName());

		final MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, mSampleRate, 1);
		format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		format.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
		format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
		format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
//		format.setLong(MediaFormat.KEY_MAX_INPUT_SIZE, inputFile.length());
//      format.setLong(MediaFormat.KEY_DURATION, (long)durationInMs );
		if (DEBUG) Log.i(TAG, "prepare finishing:format=" + format);
		return format;
	}

	@Override
	protected MediaCodec internal_configure(MediaCodec codec, final MediaFormat format) throws IOException {
		if (DEBUG) Log.v(TAG, "internal_configure:");
		if (codec == null)
			codec = MediaCodec.createEncoderByType(MIME_TYPE);
		codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		return codec;
	}

	@Override
	protected void callOnResume() {
		super.callOnResume();
		// create and execute audio capturing thread using internal mic
		if (mAudioThread == null) {
			mAudioThread = new AudioThread();
			mAudioThread.start();
		}
	}

	@Override
	protected void callOnPause() {
		mAudioThread = null;
	}

	/**
	 * audio sampling loop. this method is executed on private thread
	 * this method should return if mIsRunning=false or mRequestStop=true or mIsEOS=true.
	 */
	protected abstract void recordingLoop();

	/**
	 * Thread to capture audio data from internal mic as uncompressed 16bit PCM data
	 * and write them to the MediaCodec encoder
	 */
    private final class AudioThread extends Thread {
    	@Override
    	public final void run() {
    		try {
				recordingLoop();
    		} catch (Exception e) {
    			Log.e(TAG, "AudioThread#run", e);
    		}
			if (DEBUG) Log.v(TAG, "AudioThread:finished");
    	}
    }

    /**
     * select the first codec that match a specific MIME type
     * @param mimeType
     * @return
     */
    private static final MediaCodecInfo selectAudioCodec(final String mimeType) {
    	if (DEBUG) Log.v("AbstractTLMediaAudioEncoder", "selectAudioCodec:");

    	MediaCodecInfo result = null;
    	// get the list of available codecs
        final int numCodecs = MediaCodecList.getCodecCount();
LOOP:	for (int i = 0; i < numCodecs; i++) {
        	final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {	// skip decoder
                continue;
            }
            final String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
            	if (DEBUG) Log.i("AbstractTLMediaAudioEncoder", "supportedType:" + codecInfo.getName() + ",MIME=" + types[j]);
                if (types[j].equalsIgnoreCase(mimeType)) {
               		result = codecInfo;
           			break LOOP;
                }
            }
        }
   		return result;
    }

}
