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
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Encoder class to encode audio data with AAC encoder and save into intermediate files
 */
public final class TLMediaAudioEncoder extends AbstractTLMediaAudioEncoder {
	private static final boolean DEBUG = true;
	private static final String TAG = "TLMediaAudioEncoder";

	/**
	 * Constructor(this class only support monaural audio source)
	 * @param context
	 * @param base_path
	 * @param listener
	 */
	public TLMediaAudioEncoder(final Context context, final String base_path, final MediaEncoderListener listener) {
		super(context, base_path, listener, DEFAULT_SAMPLE_RATE, DEFAULT_BIT_RATE);
	}

    /**
	 * Constructor(this class only support monaural audio source)
	 * @param context
	 * @param base_path
	 * @param listener
	 * @param sample_rate default value is 44100(44.1kHz, 44.1KHz is only guarantee value on all devices)
	 * @param bit_rate  default value is 64000(64kbps)
     */
	public TLMediaAudioEncoder(final Context context, final String base_path, final MediaEncoderListener listener,
							   final int sample_rate, final int bit_rate) {
		super(context, base_path, listener, sample_rate, bit_rate);
	}

	@Override
	protected void recordingLoop() {
        final int buf_sz = AudioRecord.getMinBufferSize(
        	mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4;
        Log.i(TAG, "buf_sz=" + buf_sz);
        final AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
        	mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf_sz);
        try {
        	if (mIsCapturing) {
				if (DEBUG) Log.v(TAG, "AudioThread:start_from_encoder audio recording");
                final byte[] buf = new byte[buf_sz];
                int readBytes;
                audioRecord.startRecording();
                try {
		    		while (mIsCapturing && !mRequestStop && !mIsEOS) {
		    			// read audio data from internal mic
		    			readBytes = audioRecord.read(buf, 0, buf_sz);
		    			if (readBytes > 0) {
		    			    // set audio data to encoder
		    				encode(buf, readBytes, getPTSUs());
		    				frameAvailableSoon();
		    			}
		    		}
    				frameAvailableSoon();
                } finally {
                	audioRecord.stop();
                }
        	}
        } finally {
        	audioRecord.release();
        }
	}

}
