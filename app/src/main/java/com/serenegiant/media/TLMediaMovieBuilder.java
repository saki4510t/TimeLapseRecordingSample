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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

/**
 * Builder class to build actual mp4 file from intermediate files made by TLMediaEncoder and it's inheritor
 */
public class TLMediaMovieBuilder {
	private static final boolean DEBUG = true;
	private static final String TAG = "TLMediaMovieBuilder";

	private static final int MAX_BUF_SIZE = 1024 * 1024;
	private static final long MSEC30US = 1000000 / 30;
	private static String DIR_NAME = "Serenegiant";
    private static final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);

	private final Object mSync = new Object();
	private final File mBaseDir;
	private String mOutputPath;
	private TLMediaMovieBuilderCallback mCallback;
	private volatile boolean mIsRunning;

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
	public TLMediaMovieBuilder(Context context, String movie_name) throws IOException {
		mBaseDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), movie_name);
		mOutputPath  = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4").toString();
	}

	public void setCallback(TLMediaMovieBuilderCallback callback) {
		synchronized (mSync) {
			mCallback = callback;
		}
	}

	public TLMediaMovieBuilderCallback getCallback() {
		synchronized (mSync) {
			return mCallback;
		}
	}

	/**
	 * get output movie file path
	 * @return
	 */
	public String getOutputPath() {
		synchronized (mSync) {
			return mOutputPath;
		}
	}

	/**
	 * set output movie file path, should be called before #build
	 * @param path
	 */
	public void setOutputPath(final String path) {
		synchronized (mSync) {
			mOutputPath = path;
		}
	}

	/**
	 * build movie file from intermediate file.
	 * this method is executed asynchronously.
	 */
	public void build() {
		if (DEBUG) Log.v(TAG, "build:");
		new Thread(MuxerTask, TAG).start();
	}

	/**
	 * cancel movie building.
	 * This is only valid while building.
	 */
	public void cancel() {
		mIsRunning = false;
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
    	return mDateTimeFormat.format(now.getTime());
    }

	/**
	 * building task executing on private thread
	 */
	private final Runnable MuxerTask = new Runnable() {
		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "MuxerTask#run");
			String path;
			synchronized (mSync) {
				path = mOutputPath;
			}
			boolean isMuxerStarted = false;
			mIsRunning = true;
 			try {
				final MediaMuxer muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				if (muxer != null)
				try {
					int videoSequence = -1;
					int audioSequence = -1;
					int videoTrack = -1;
					int audioTrack = -1;
					long videoTimeOffset = 0, videoPresentationTimeUs = -1;
					long audioTimeOffset = 0, audioPresentationTimeUs = -1;
					ObjectInputStream videoIn = changeInput(null, 0, ++videoSequence);
					if (videoIn != null) {
						final MediaFormat format = TLMediaEncoder.readFormat(videoIn);
						if (format != null) {
							videoTrack = muxer.addTrack(format);
							if (DEBUG) Log.v(TAG, "found video data:format=" + format + "track=" + videoTrack);
							videoIn = changeInput(videoIn, 0, ++videoSequence);
							if (videoIn == null)
								videoTrack = -1;
							if (DEBUG) Log.v(TAG, "videoIn=" + videoIn);
						}
					}
					ObjectInputStream audioIn = changeInput(null, 1, ++audioSequence);
					if (audioIn != null) {
						final MediaFormat format = TLMediaEncoder.readFormat(audioIn);
						if (format != null) {
							audioTrack = muxer.addTrack(format);
							if (DEBUG) Log.v(TAG, "found audio data:format=" + format + "track=" + audioTrack);
							audioIn = changeInput(audioIn, 1, ++audioSequence);
							if (audioIn == null)
								audioTrack = -1;
							if (DEBUG) Log.v(TAG, "audioIn=" + audioIn);
						}
					}
					if ((videoTrack >= 0) || (audioTrack >= 0)) {
						if (DEBUG) Log.v(TAG, "start muxing");
						ByteBuffer videoBuf = null;
						MediaCodec.BufferInfo videoBufInfo = null;
						if (videoTrack >= 0) {
							videoBuf = ByteBuffer.allocateDirect(64 * 1024);
							videoBufInfo = new MediaCodec.BufferInfo();
						}
						ByteBuffer audioBuf = null;
						MediaCodec.BufferInfo audioBufInfo = new MediaCodec.BufferInfo();
						if (audioTrack >= 0) {
							audioBuf = ByteBuffer.allocateDirect(64 * 1024);
							audioBufInfo = new MediaCodec.BufferInfo();
						}
						byte[] readBuf = new byte[64 * 1024];
						isMuxerStarted = true;
						muxer.start();
						for (; mIsRunning && ((videoTrack >= 0) || (audioTrack >= 0)); ) {
							if (videoTrack >= 0) {
								if (videoIn != null) {
									try {
										TLMediaEncoder.readStream(videoIn, videoBufInfo, videoBuf, readBuf);
										if (videoPresentationTimeUs < 0) {
											videoTimeOffset = -videoPresentationTimeUs - videoBufInfo.presentationTimeUs + MSEC30US;
										}
										videoBufInfo.presentationTimeUs += videoTimeOffset;
										muxer.writeSampleData(videoTrack, videoBuf, videoBufInfo);
										videoPresentationTimeUs = videoBufInfo.presentationTimeUs;
									} catch (BufferOverflowException e) {
										if ((videoBufInfo.size > 0) && (videoBufInfo.size < MAX_BUF_SIZE) && (videoBuf.capacity() < videoBufInfo.size))
											videoBuf = ByteBuffer.allocateDirect(videoBufInfo.size);
									} catch (IOException e) {
										videoIn = changeInput(videoIn, 0, ++videoSequence);
										videoPresentationTimeUs = -videoPresentationTimeUs;
									}
								} else {
									videoTrack = -1;	// end
								}
							}
							if (audioTrack >= 0) {
								if (audioIn != null) {
									try {
										TLMediaEncoder.readStream(audioIn, audioBufInfo, audioBuf, readBuf);
										if (audioPresentationTimeUs < 0) {
											audioTimeOffset = -audioPresentationTimeUs - audioBufInfo.presentationTimeUs + MSEC30US;
										}
										audioBufInfo.presentationTimeUs += audioTimeOffset;
										muxer.writeSampleData(audioTrack, audioBuf, audioBufInfo);
										audioPresentationTimeUs = audioBufInfo.presentationTimeUs;
									} catch (BufferOverflowException e) {
										if ((audioBufInfo.size > 0) && (audioBufInfo.size < MAX_BUF_SIZE) && (audioBuf.capacity() < audioBufInfo.size))
											audioBuf = ByteBuffer.allocateDirect(audioBufInfo.size);
									} catch (IOException e) {
										audioIn = changeInput(audioIn, 1, ++audioSequence);
										audioPresentationTimeUs = -audioPresentationTimeUs;
									}
								} else {
									audioTrack = -1;	// end
								}
							}
						}
						muxer.stop();
					}
				} finally {
					muxer.release();
				}
			} catch (Exception e) {
				Log.w(TAG, "failed to build movie file:", e);
				synchronized (mSync) {
					mOutputPath = null;
					if (mCallback != null) {
						mCallback.onError(e);
					}
				}
			}
			// remove intermediate files and its directory
			delete(mBaseDir);
			if (DEBUG) Log.v(TAG, "MuxerTask#finished");
			synchronized (mSync) {
				if (mCallback != null) {
					mCallback.onFinished(mIsRunning && isMuxerStarted ? mOutputPath : null);
				}
			}
		}

		private final ObjectInputStream changeInput(ObjectInputStream old, int type, int sequence) throws IOException {
			if (DEBUG) Log.v(TAG, "changeInput:type=" + type + ",sequence=" + sequence);
			if (old != null) {
				old.close();
			}
			final String path = TLMediaEncoder.getSequenceFilePath(mBaseDir, type, sequence);
			ObjectInputStream in = null;
			try {
				in = new ObjectInputStream(new BufferedInputStream((new FileInputStream(path))));
			} catch (EOFException e) {
//				if (DEBUG) Log.e(TAG, "changeInput:" + path, e);
			} catch (FileNotFoundException e) {
//				if (DEBUG) Log.e(TAG, "changeInput:" + path, e);
			}
			return in;
		}

		/**
		 * delete specific file/directory recursively
		 * @param path
		 */
		private final void delete(final File path) {
			if (path.isDirectory()) {
				File[] files = path.listFiles();
				final int n = files != null ? files.length : 0;
				for (int i = 0; i < n; i++)
					delete(files[i]);
			}
			path.delete();
		}
	};
}
