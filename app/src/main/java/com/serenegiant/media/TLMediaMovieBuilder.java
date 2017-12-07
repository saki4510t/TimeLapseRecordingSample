package com.serenegiant.media;

/*
 * TimeLapseRecordingSample
 * Sample project to capture audio and video periodically from internal mic/camera
 * and save as time lapsed MPEG4 file.
 *
 * Copyright (c) 2015 saki t_saki@serenegiant.com
 *
 * File name: TLMediaMovieBuilder.java
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
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Builder class to build actual mp4 file from intermediate files made by TLMediaEncoder and it's inheritor
 */
public class TLMediaMovieBuilder {
	private static final boolean DEBUG = false;
	private static final String TAG = "TLMediaMovieBuilder";

	private static final long MSEC30US = 1000000 / 30;
	private static String DIR_NAME = "TimeLapseRecordingSample";

	private final File mBaseDir;
	private String mOutputPath;
	private MuxerTask mMuxerTask;

	public interface TLMediaMovieBuilderCallback {
		/**
		 * called when finished movie building
		 * @param output_path output movie file path, may null when canceled or error occurred etc.
		 */
		public void onFinished(String output_path);
		/**
		 * called when error occurred while movie building
		 * @param e
		 */
		public void onError(Exception e);
	}

	/**
	 * set output directory name
	 * the actual directory is {DIRECTORY_MOVIES}/dir_name
	 * @param dir_name
	 */
	public static final void setDirName(final String dir_name) {
		if (TextUtils.isEmpty(dir_name))
			throw new IllegalArgumentException("dir_name should not be null/empty");
		DIR_NAME = dir_name;
	}

	/**
	 * Constructor
	 * @param movie_name directory name where intermediate files exist
	 * @throws IOException
	 */
	public TLMediaMovieBuilder(final Context context,
		final String movie_name) throws IOException {

		mBaseDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), movie_name);
		mOutputPath  = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4").toString();
	}

	/**
	 * get output movie file path
	 * @return
	 */
	public String getOutputPath() {
		return mOutputPath;
	}

	/**
	 * set output movie file path, should be called before #build
	 * @param path
	 */
	public void setOutputPath(final String path) {
		mOutputPath = path;
	}

	/**
	 * build movie file from intermediate file.
	 * this method is executed asynchronously.
	 */
	public synchronized void build(final TLMediaMovieBuilderCallback callback) {
		if (DEBUG) Log.v(TAG, "build:");
		cancel();
		mMuxerTask = new MuxerTask(this, callback);
		mMuxerTask.start();
	}

	public synchronized void cancel() {
		if (mMuxerTask != null) {
			mMuxerTask.cancel();
		}
	}

	private final synchronized void finishBuild(MuxerTask muxer_task) {
		if (muxer_task.equals(mMuxerTask))
			mMuxerTask = null;
	}

//**********************************************************************
//**********************************************************************
    /**
     * make output file name
     * @param type Environment.DIRECTORY_MOVIES / Environment.DIRECTORY_DCIM etc.
     * @param ext .mp4(.m4a for audio) or .png
     * @return return null when this app has no writing permission to external storage.
     */
    public static final File getCaptureFile(final String type, final String ext) {
		final File dir = new File(Environment.getExternalStoragePublicDirectory(type), DIR_NAME);
		Log.d(TAG, "path=" + dir.toString());
		dir.mkdirs();
        if (dir.canWrite()) {
        	return new File(dir, getDateTimeString() + ext);
        }
    	return null;
    }

    /**
     * make String came from current datetime
     * @return
     */
    private static final String getDateTimeString() {
    	final GregorianCalendar now = new GregorianCalendar();
		final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
    	return dateTimeFormat.format(now.getTime());
    }

	/**
	 * building task executing on private thread
	 */
	private static final class MuxerTask extends Thread {
		private final Object mSync = new Object();
		private final TLMediaMovieBuilder mBuilder;
		private final File mMovieDir;
		private final TLMediaMovieBuilderCallback mCallback;
		private final String mMuxerFilePath;

		private volatile boolean mIsRunning = true;

		public MuxerTask(final TLMediaMovieBuilder builder, final TLMediaMovieBuilderCallback callback) {
			super(TAG);
			mBuilder = builder;
			mMovieDir = builder.mBaseDir;
			mCallback = callback;
			mMuxerFilePath = builder.mOutputPath;
		}

		public void cancel() {
			mIsRunning = false;
		}

		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "MuxerTask#run");
			boolean isMuxerStarted = false;
 			try {
				final MediaMuxer muxer = new MediaMuxer(mMuxerFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				if (muxer != null)
				try {
					int videoTrack = -1;
					int audioTrack = -1;
					final DataInputStream videoIn = TLMediaEncoder.openInputStream(mMovieDir, TLMediaEncoder.TYPE_VIDEO, 0);
					if (videoIn != null) {
						final MediaFormat format = TLMediaEncoder.readFormat(videoIn);
						if (format != null) {
							videoTrack = muxer.addTrack(format);
							if (DEBUG) Log.v(TAG, "found video data:format=" + format + "track=" + videoTrack);
						}
					}
					final DataInputStream audioIn = TLMediaEncoder.openInputStream(mMovieDir, TLMediaEncoder.TYPE_AUDIO, 0);
					if (audioIn != null) {
						final MediaFormat format = TLMediaEncoder.readFormat(audioIn);
						if (format != null) {
							audioTrack = muxer.addTrack(format);
							if (DEBUG) Log.v(TAG, "found audio data:format=" + format + "track=" + audioTrack);
						}
					}
					if ((videoTrack >= 0) || (audioTrack >= 0)) {
						if (DEBUG) Log.v(TAG, "start muxing");
						ByteBuffer videoBuf = null;
						MediaCodec.BufferInfo videoBufInfo = null;
						TLMediaEncoder.TLMediaFrameHeader videoFrameHeader = null;
						if (videoTrack >= 0) {
							videoBufInfo = new MediaCodec.BufferInfo();
							videoFrameHeader = new TLMediaEncoder.TLMediaFrameHeader();
						}
						ByteBuffer audioBuf = null;
						MediaCodec.BufferInfo audioBufInfo = new MediaCodec.BufferInfo();
						TLMediaEncoder.TLMediaFrameHeader audioFrameHeader = null;
						if (audioTrack >= 0) {
							audioBufInfo = new MediaCodec.BufferInfo();
							audioFrameHeader = new TLMediaEncoder.TLMediaFrameHeader();
						}
						final byte[] readBuf = new byte[64 * 1024];
						isMuxerStarted = true;
						int videoSequence = 0;
						int audioSequence = 0;
						long videoTimeOffset = -1, videoPresentationTimeUs = -MSEC30US;
						long audioTimeOffset = -1, audioPresentationTimeUs = -MSEC30US;
						muxer.start();
						for (; mIsRunning && ((videoTrack >= 0) || (audioTrack >= 0)); ) {
							if (videoTrack >= 0) {
								try {
									videoBuf = TLMediaEncoder.readStream(videoIn, videoFrameHeader, videoBuf, readBuf);
									videoFrameHeader.asBufferInfo(videoBufInfo);
									if (videoSequence !=  videoFrameHeader.sequence) {
										videoSequence = videoFrameHeader.sequence;
										videoTimeOffset = videoPresentationTimeUs - videoBufInfo.presentationTimeUs + MSEC30US;
									}
									videoBufInfo.presentationTimeUs += videoTimeOffset;
									muxer.writeSampleData(videoTrack, videoBuf, videoBufInfo);
									videoPresentationTimeUs = videoBufInfo.presentationTimeUs;
								} catch (IllegalArgumentException e) {
									if (DEBUG) Log.d(TAG, String.format("MuxerTask(video):size=%d,presentationTimeUs=%d,",
										videoBufInfo.size, videoBufInfo.presentationTimeUs) + videoFrameHeader, e);
									videoTrack = -1;	// end
								} catch (IOException e) {
									videoTrack = -1;	// end
								}
							}
							if (audioTrack >= 0) {
								try {
									audioBuf = TLMediaEncoder.readStream(audioIn, audioFrameHeader, audioBuf, readBuf);
									audioFrameHeader.asBufferInfo(audioBufInfo);
									if (audioSequence !=  audioFrameHeader.sequence) {
										audioSequence = audioFrameHeader.sequence;
										audioTimeOffset = audioPresentationTimeUs - audioBufInfo.presentationTimeUs + MSEC30US;
									}
									audioBufInfo.presentationTimeUs += audioTimeOffset;
									muxer.writeSampleData(audioTrack, audioBuf, audioBufInfo);
									audioPresentationTimeUs = audioBufInfo.presentationTimeUs;
								} catch (IllegalArgumentException e) {
									if (DEBUG) Log.d(TAG, String.format("MuxerTask(audio):size=%d,presentationTimeUs=%d,",
										audioBufInfo.size, audioBufInfo.presentationTimeUs) + audioFrameHeader, e);
									audioTrack = -1;	// end
								} catch (IOException e) {
									audioTrack = -1;	// end
								}
							}
						}
						muxer.stop();
					}
					if (videoIn != null) {
						videoIn.close();
					}
					if (audioIn != null) {
						audioIn.close();
					}
				} finally {
					muxer.release();
				}
			} catch (Exception e) {
				Log.w(TAG, "failed to build movie file:", e);
				mIsRunning = false;
				synchronized (mSync) {
					if (mCallback != null) {
						mCallback.onError(e);
					}
				}
			}
			// remove intermediate files and its directory
			TLMediaEncoder.delete(mMovieDir);
			mBuilder.finishBuild(this);
			if (DEBUG) Log.v(TAG, "MuxerTask#finished");
			synchronized (mSync) {
				if (mCallback != null) {
					mCallback.onFinished(mIsRunning && isMuxerStarted ? mMuxerFilePath : null);
				}
			}
		}
	}
}
