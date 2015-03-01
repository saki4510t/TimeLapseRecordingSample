package com.serenegiant.media;

/*
 * TimeLapseRecordingSample
 * Sample project to capture audio and video periodically from internal mic/camera
 * and save as time lapsed MPEG4 file.
 *
 * Copyright (c) 2015 saki t_saki@serenegiant.com
 *
 * File name: TLMediaVideoEncoder.java
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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.EGLContext;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.glutils.RenderHandler;

import java.io.IOException;

/**
 * Video Encoder
 */
public class TLMediaVideoEncoder extends TLMediaEncoder {
	private static final boolean DEBUG = true;
	private static final String TAG = TLMediaVideoEncoder.class.getSimpleName();

	private static final String MIME_TYPE = "video/avc";
    private static final int DEFAULT_VIDEO_WIDTH = 640;
    private static final int DEFAULT_VIDEO_HEIGHT = 480;
    private static final int DEFAULT_FRAME_RATE = 30;
    private static final float DEFAULT_BPP = 0.25f;
    private static final int DEFAULT_IFRAME_INTERVALS = 3;
    private static final int MAX_BITRATE = 17825792;	// 17Mbps

    private int mWidth = DEFAULT_VIDEO_WIDTH;
    private int mHeight = DEFAULT_VIDEO_HEIGHT;
    private int mFrameRate = DEFAULT_FRAME_RATE;
    private int mBitRate = -1;
    private int mIFrameIntervals = DEFAULT_IFRAME_INTERVALS;

	private RenderHandler mRenderHandler;
    private Surface mSurface;

	/**
	 * Constructor
	 * @param context
	 * @param base_path
	 * @param listener
	 */
	public TLMediaVideoEncoder(final Context context, final String base_path, final MediaEncoderListener listener) {
		super(context, base_path, 0, listener);
		if (DEBUG) Log.i(TAG, "TLMediaVideoEncoder: ");
		mRenderHandler = RenderHandler.createHandler(TAG);
	}

	/**
	* get Surface for input
	*/
	public final Surface getInputSurface() {
		if (mSurface == null)
			throw new IllegalStateException("encoder have not initialized yet");
		return mSurface;
	}

	public boolean frameAvailableSoon(final float[] tex_matrix) {
		boolean result;
		if (result = super.frameAvailableSoon())
			mRenderHandler.draw(tex_matrix);
		return result;
	}

	@Override
	public boolean frameAvailableSoon() {
		boolean result;
		if (result = super.frameAvailableSoon())
			mRenderHandler.draw(null);
		return result;
	}

	/**
	 * setup video encoder. should be called before #prepare
	 * @param width negative value means using default value(640)
	 * @param height negative value means using default value(480)
	 * @param framerate negative value means using default value(30fps)
	 * @param bitrate negative value means using default value(calculate from BPP0.25, width,height and framerate)
	 */
	public void setFormat(final int width, final int height, final int framerate, final int bitrate) {
		setFormat(width, height, framerate, bitrate, mIFrameIntervals);
	}
	/**
	 * setup video encoder. should be called before #prepare
	 * @param width negative value means using default value(640)
	 * @param height negative value means using default value(480)
	 * @param framerate negative value means using default value(30fps)
	 * @param bitrate negative value means using default value(calculate from BPP0.25, width,height and framerate)
	 * @param iframe_intervals negative value means using default value(10)
	 */
	public void setFormat(final int width, final int height, final int framerate, final int bitrate, final int iframe_intervals) {
		if (DEBUG) Log.v(TAG, String.format("requested setFormat:size(%d,%d),fps=%d,bps=%d,iframe=%d", width, height, framerate, bitrate, iframe_intervals));
		if (mSurface != null)
			throw new IllegalStateException("already prepared");
		if (width > 0) mWidth = width;
		else mWidth = DEFAULT_VIDEO_WIDTH;

		if (height > 0) mHeight = height;
		else mHeight = DEFAULT_VIDEO_HEIGHT;

		if (framerate > 0) mFrameRate = framerate;
		else mFrameRate = DEFAULT_FRAME_RATE;

		mBitRate = bitrate;

		if (iframe_intervals > 0) mIFrameIntervals = iframe_intervals;
		else mIFrameIntervals = DEFAULT_IFRAME_INTERVALS;
		if (DEBUG) Log.v(TAG, String.format("setFormat:size(%d,%d),fps=%d,bps=%d,iframe=%d", mWidth, mHeight, mFrameRate, mBitRate, mIFrameIntervals));
	}

	@Override
	protected MediaFormat internal_prepare() throws IOException {
		if (DEBUG) Log.i(TAG, "prepare: ");

        final MediaCodecInfo videoCodecInfo = selectVideoCodec(MIME_TYPE);
        if (videoCodecInfo == null) {
            Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
            return null;
        }
		if (DEBUG) Log.i(TAG, "selected codec: " + videoCodecInfo.getName());

        final MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        if (DEBUG) Log.i(TAG, "prepare finishing:format=" + format);
		return format;
	}

	@Override
	protected MediaCodec internal_configure(MediaCodec previous_codec, final MediaFormat format) throws IOException {
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);	// API >= 18
		format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate > 0 ? mBitRate : calcBitRate());
		format.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mIFrameIntervals);
		if (DEBUG) Log.i(TAG, "format: " + format);

		if (previous_codec == null)
			previous_codec = MediaCodec.createEncoderByType(MIME_TYPE);
		previous_codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mSurface = previous_codec.createInputSurface();	// API >= 18
		return previous_codec;
	}

	public void setEglContext(EGLContext shared_context, int tex_id) {
		mRenderHandler.setEglContext(shared_context, tex_id, mSurface, true);
	}

	@Override
    protected void internal_release() {
		if (DEBUG) Log.i(TAG, "internal_release: ");
		if (mSurface != null) {
			mSurface.release();
			mSurface = null;
		}
		if (mRenderHandler != null) {
			mRenderHandler.release();
			mRenderHandler = null;
		}
		super.internal_release();
	}

	/**
	 * calculate bit rate
	 * @return
	 */
	private final int calcBitRate() {
		int bitrate = (int)(DEFAULT_BPP * mFrameRate * mWidth * mHeight);
		if (bitrate > MAX_BITRATE) bitrate = MAX_BITRATE;
		Log.i(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate / 1024f / 1024f));
		return bitrate;
	}
	
    /**
     * select first encoder matched to specific MIME
     * @param mimeType
     * @return return null if not found
     */
    protected static final MediaCodecInfo selectVideoCodec(final String mimeType) {
    	if (DEBUG) Log.v(TAG, "selectVideoCodec:");

    	// get the list of available codecs
        final int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
        	final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {	// skipp decoder
                continue;
            }
            // select first codec that match a specific MIME type and color format
            final String[] types = codecInfo.getSupportedTypes();
			for (String type : types) {
				if (type.equalsIgnoreCase(mimeType)) {
					if (DEBUG) Log.i(TAG, "codec:" + codecInfo.getName() + ",MIME=" + type);
					int format = selectColorFormat(codecInfo, mimeType);
					if (format > 0) {
						return codecInfo;
					}
				}
			}
        }
        return null;
    }

    /**
     * select color format that the specific codec supports
     * @return return 0 if not found
     */
    protected static final int selectColorFormat(final MediaCodecInfo codecInfo, final String mimeType) {
		if (DEBUG) Log.i(TAG, "selectColorFormat: ");
    	int result = 0;
    	final MediaCodecInfo.CodecCapabilities caps;
    	try {
    		Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
    		caps = codecInfo.getCapabilitiesForType(mimeType);
    	} finally {
    		Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
    	}
        for (int colorFormat: caps.colorFormats) {
            if (isRecognizedVideoFormat(colorFormat)) {
           		result = colorFormat;
                break;
            }
        }
        if (result == 0)
        	Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return result;
    }

	/**
	 * color format values that this class supports(only COLOR_FormatSurface)
	 */
    protected static int[] recognizedFormats;
	static {
		recognizedFormats = new int[] {
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
        	MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
		};
	}

	/**
	 * return whether specific color format can be used on this class
	 * @param colorFormat
	 * @return return true if this class supports specific color format
	 */
    private static final boolean isRecognizedVideoFormat(final int colorFormat) {
		if (DEBUG) Log.i(TAG, "isRecognizedVideoFormat:colorFormat=" + colorFormat);
    	final int n = recognizedFormats != null ? recognizedFormats.length : 0;
    	for (int i = 0; i < n; i++) {
    		if (recognizedFormats[i] == colorFormat) {
    			return true;
    		}
    	}
    	return false;
    }

}
