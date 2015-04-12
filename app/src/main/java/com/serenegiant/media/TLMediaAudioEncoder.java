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

import java.nio.ByteBuffer;

/**
 * Encoder class to encode audio data with AAC encoder and save into intermediate files
 */
public final class TLMediaAudioEncoder extends AbstractTLMediaAudioEncoder {
	private static final boolean DEBUG = false;
	private static final String TAG = "TLMediaAudioEncoder";

    private static final int SAMPLES_PER_FRAME = 1024;	// AAC, bytes/frame/channel
	private static final int FRAMES_PER_BUFFER = 25; 	// AAC, frame/buffer/sec

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
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
		try {
			final int min_buffer_size = AudioRecord.getMinBufferSize(
					mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			int buffer_size = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
			if (buffer_size < min_buffer_size)
				buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

			final AudioRecord audioRecord = new AudioRecord(
				MediaRecorder.AudioSource.MIC, mSampleRate,
				AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer_size);
			try {
				if ((audioRecord.getState() == AudioRecord.STATE_INITIALIZED) && (mIsRunning)) {
					if (DEBUG) Log.v(TAG, "AudioThread:start_from_encoder audio recording");
					final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
					int readBytes;
					audioRecord.startRecording();
					try {
						while (mIsRunning && isRecording()) {
							// read audio data from internal mic
							buf.clear();
							readBytes = audioRecord.read(buf, SAMPLES_PER_FRAME);
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
		} catch (Exception e) {
			Log.e(TAG, "AudioThread#run", e);
		} finally {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
		}
	}

}
