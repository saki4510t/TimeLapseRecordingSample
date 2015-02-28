package com.serenegiant.media;

/*
 * TimeLapseRecordingSample
 * Sample project to capture audio and video periodically from internal mic/camera
 * and save as time lapsed MPEG4 file.
 *
 * Copyright (c) 2015 saki t_saki@serenegiant.com
 *
 * File name: TLMediaEncoder.java
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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

/**
 * abstract class to audio/video frames into intermediate files
 * using MediaCodec encoder so that pause / resume feature is available.
 */
public abstract class TLMediaEncoder {
	private static final boolean DEBUG = true;
	private static final String TAG_STATIC = "TLMediaEncoder";
	private final String TAG = getClass().getSimpleName();

	protected static final int TIMEOUT_USEC = 10000;	// 10[msec]   

	private static final int STATE_RELEASE = 0;
	private static final int STATE_INITIALIZED = 1;
	private static final int STATE_PREPARING = 2;
	private static final int STATE_PREPARED = 3;
	private static final int STATE_PAUSING = 4;
	private static final int STATE_PAUSED = 5;
	private static final int STATE_RESUMING = 6;
	private static final int STATE_RUNNING = 7;

	private static final int REQUEST_NON = 0;
	private static final int REQUEST_PREPARE = 1;
	private static final int REQUEST_RESUME = 2;
	private static final int REQUEST_STOP = 3;
	private static final int REQUEST_PAUSE = 4;
	private static final int REQUEST_DRAIN = 5;

//	protected static final int MSG_FRAME_AVAILABLE = 1;
//	protected static final int MSG_PAUSE_RECORDING = 8;
//	protected static final int MSG_STOP_RECORDING = 9;

	protected static final int TYPE_VIDEO = 0;
	protected static final int TYPE_AUDIO = 1;
	/**
	 * callback listener
	 */
	public interface MediaEncoderListener {
		/**
		 * called when encoder finished preparing
		 * @param encoder
		 */
		public void onPrepared(TLMediaEncoder encoder);
		/**
		 * called when encoder stopped
		 * @param encoder
		 */
		public void onStopped(TLMediaEncoder encoder);
		/**
		 * called when resuming
		 * @param encoder
		 */
		public void onResume(TLMediaEncoder encoder);
		/**
		 * called when pausing
		 * @param encoder
		 */
		public void onPause(TLMediaEncoder encoder);
	}
	
	private final Object mSync = new Object();
	private final LinkedBlockingDeque<Integer> mRequestQueue = new LinkedBlockingDeque<Integer>();
	/**
	 * Flag that indicate this encoder is capturing now.
	 */
    protected volatile boolean mIsRunning;
    /**
     * Flag that indicate encoder received EOS(End Of Stream)
     */
    protected boolean mIsEOS;
    /**
     * MediaCodec instance for encoding
     */
    protected MediaCodec mMediaCodec;				// API >= 16(Android4.1.2)
	private MediaFormat mFormat;
    /**
     * 
     */
    protected ByteBuffer[] encoderOutputBuffers;
    protected ByteBuffer[] encoderInputBuffers;
    /**
     * BufferInfo instance for dequeuing
     */
    private MediaCodec.BufferInfo mBufferInfo;		// API >= 16(Android4.1.2)
    /**
     * Handler of encoding thread
     */
//	private EncoderHandler mHandler;
    protected final MediaEncoderListener mListener;

	private final Context mContext;
	private final File mBaseDir;
	private final int mType;
	private Exception mCurrentException;
	private volatile int mState = STATE_RELEASE;
	private ObjectOutputStream mCurrentOutputStream;
	private int mSequence;
	private int mNumFrames = -1;
	private int mFrameCounts;

    /**
     * constructor
     * @param movie_name this values is used as a directory name for intermediate files
     * @param listener
     */
    public TLMediaEncoder(final Context context, final String movie_name, final int type, final MediaEncoderListener listener) {
		if (DEBUG) Log.v(TAG, "TLMediaEncoder");
    	if (TextUtils.isEmpty(movie_name)) throw new IllegalArgumentException("movie_name should not be null");
		mContext = context;
		mBaseDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), movie_name);
		mBaseDir.mkdirs();
		mType = type;
		mListener = listener;
		mBufferInfo = new MediaCodec.BufferInfo();
		new Thread(mEncoderTask, getClass().getSimpleName()).start();
		synchronized (mSync) {
			try {
				mSync.wait();
			} catch (InterruptedException e) {
			}
		}
	}

	protected abstract MediaFormat internal_prepare() throws IOException;
	protected abstract void internal_configure(MediaFormat format) throws IOException;

	/*
	 * prepare encoder. This method will be called once.
	 * @throws IOException
	 */
	public final void prepare() throws Exception {
		if (DEBUG) Log.v(TAG, "prepare");
		if (!mIsRunning || (mState != STATE_INITIALIZED))
			throw new IllegalStateException("not ready/already released:" + mState);
		setRequestAndWait(REQUEST_PREPARE);
	}

	/**
	 * start encoder
	 */
	public final void start() throws IOException {
		start(-1, false);
	}

	/**
	 * start encoder
	 * @param pauseAfterStarted if this flag is true, encoder become pause state immediately
	 * @throws IOException
	 */
	public final void start(boolean pauseAfterStarted) throws IOException {
		start(-1, pauseAfterStarted);
	}

	/**
	 * start encoder with specific sequence
	 * @param sequence
	 * @param pauseAfterStarted
	 * @throws IOException
	 */
	public void start(final int sequence, boolean pauseAfterStarted) throws IOException {
		if (DEBUG) Log.v(TAG, "start");
		if (!mIsRunning || ((mState != STATE_PREPARING) && (mState != STATE_PREPARED)))
			throw new IllegalStateException("not prepare/already released:" + mState);
		synchronized (mSync) {
			mSequence = sequence;
			// wait for Handler is ready
			if (!pauseAfterStarted) {
				resume(-1);
			} else {
				setRequest(REQUEST_PAUSE);
			}
		}
	}

	/**
	 * request stop encoder
	 */
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop");
		if (mState > STATE_INITIALIZED) {
			removeRequest(REQUEST_DRAIN);
			try {
				setRequestAndWait(REQUEST_STOP);
			} catch (Exception e) {
				Log.w(TAG, "stop:", e);
			}
		}
	}

	/**
	 * request resume encoder
	 * @throws IOException
	 */
	public void resume() throws IOException {
		resume(-1);
	}

	/**
	 * request resume encoder. after obtaining more than specific frames, automatically become pause state.
	 * @param num_frames if num_frames is negative value, automatic pausing is disabled.
	 * @throws IOException
	 */
	public void resume(final int num_frames) throws IOException {
		if (DEBUG) Log.v(TAG, "resume");
		if (!mIsRunning
			|| 	( (mState != STATE_PREPARING) && (mState != STATE_PREPARED)
					&& (mState != STATE_PAUSING) && (mState != STATE_PAUSED)))
			throw new IllegalStateException("not ready to resume:" + mState);
		mNumFrames = num_frames;
		setRequest(REQUEST_RESUME);
	}

	/**
	 * change intermediate file for next sequence
	 * @throws IOException
	 */
	private final void changeOutputStream() throws IOException {
		if (mCurrentOutputStream != null)
			try {
				mCurrentOutputStream.flush();
				mCurrentOutputStream.close();
			} catch (IOException e) {
				Log.e(TAG, "changeOutputStream: failed to flush temporary file", e);
				throw e;
			}
		mSequence++;
		final String path = getSequenceFilePath(mBaseDir, mType, mSequence);
		mCurrentOutputStream = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
	}

	/**
	 * request pause encoder
	 */
	public void pause() throws Exception {
		if (DEBUG) Log.v(TAG, "pause");

//		removeRequest(REQUEST_DRAIN);
		setRequestFirst(REQUEST_PAUSE);
//		setState(STATE_PAUSING, null);
	}

	/**
	 * get whether this encoder is pause state
	 * @return
	 */
	public boolean isPaused() {
		synchronized (mSync) {
			return (mState == STATE_PAUSING) || (mState == STATE_PAUSED);
		}
	}

	/**
	 * set sequence number
	 * @param sequence
	 */
	public void setSequence(final int sequence) {
		mSequence = sequence;
	}

	/**
	 * get sequence number
	 * @return
	 */
	public int getSequence() {
		synchronized (mSync) {
			return mSequence;
		}
	}

	/**
     * calling this method notify encoder that the input data is already available or will be available soon
     * @return return tur if this encoder can accept input data
     */
    public boolean frameAvailableSoon() {
    	if (DEBUG) Log.v(TAG, "frameAvailableSoon");
		if (mState != STATE_RUNNING) {
			return false;
		}
		removeRequest(REQUEST_DRAIN);
		setRequest(REQUEST_DRAIN);
        return true;
    }

	public void release() {
		removeRequest(REQUEST_DRAIN);
		setRequestFirst(REQUEST_STOP);
	}

	protected void callOnResume() {
		if (mListener != null) {
			mListener.onResume(this);
		}
	}

	protected void callOnPause() {
		if (mListener != null) {
			mListener.onPause(this);
		}
	}

	private final void setState(final int state, final Exception e) {
		synchronized (mSync) {
			mState = state;
			mCurrentException = e;
			mSync.notifyAll();
		}
	}

	private final void setRequest(final int request) {
		mRequestQueue.offer(Integer.valueOf(request));
		synchronized (mSync) {
			mSync.notifyAll();
		}
	}

	private final void setRequestFirst(final int request) {
		mRequestQueue.offerFirst(Integer.valueOf(request));
		synchronized (mSync) {
			mSync.notifyAll();
		}
	}

	private final void removeRequest(final int request) {
		while (mRequestQueue.remove(Integer.valueOf(request))) {};
	}

	private final void setRequestAndWait(final int request) throws Exception {
		mRequestQueue.offer(Integer.valueOf(request));
		synchronized (mSync) {
			try {
				mSync.wait();
				if (mCurrentException != null)
					throw mCurrentException;
			} catch (InterruptedException e) {
			}
		}
	}

	/**
	 * 要求キューのコマンドを待機する
	 * @return
	 */
	private final int waitRequest() {
		if (DEBUG) Log.v(TAG, "waitRequest:");
		Integer request = null;
		try {
			request = mRequestQueue.take();
		} catch (InterruptedException e) {
		}
		return request != null ? request : REQUEST_NON;
	}

	/**
	 * 要求キューにコマンドがあるかどうかをチェックする, 無ければREQUEST_NONを返す
	 * 要求キューからは取り除かない
	 * @return
	 */
	private final int checkRequest() {
		final Integer request = mRequestQueue.peek();
		return request != null ? request : REQUEST_NON;
	}

	private final Runnable mEncoderTask = new Runnable() {
		@Override
		public void run() {
			boolean local_drain;
			boolean local_pause;
			int request = REQUEST_NON;
			if (DEBUG) Log.v(TAG, "#run");
			mIsRunning = true;
			setState(STATE_INITIALIZED, null);
			for (; mIsRunning; ) {
				if (request == REQUEST_NON) {    // 未処理の要求コマンドが無い時
					request = waitRequest();
				}
				if (request == REQUEST_STOP) {
					handlePauseRecording();
					mIsRunning = false;
					break;
				}
				switch (mState) {
//					case STATE_RELEASE:	// ここには来ないはず
//						mIsRunning = false;
//						continue;
					case STATE_INITIALIZED:
						if (DEBUG) Log.v(TAG, "STATE_INITIALIZED");
						if (request == REQUEST_PREPARE) {
							setState(STATE_PREPARING, null);
						} else {
							request = REQUEST_NON;
							setState(STATE_INITIALIZED, new IllegalStateException("state=" + mState + ",request=" + request));
						}
						break;
					case STATE_PREPARING:
						if (DEBUG) Log.v(TAG, "STATE_PREPARING");
						request = REQUEST_NON;
						try {
							mFormat = internal_prepare();
							if (mFormat != null)
								setState(STATE_PREPARED, null);
							if (mListener != null) {
								try {
									mListener.onPrepared(TLMediaEncoder.this);
								} catch (Exception e) {
									Log.e(TAG, "error occurred in #onPrepared:", e);
								}
							}
						} catch (IOException e) {
							setState(STATE_INITIALIZED, e);
						}
						break;
					case STATE_PREPARED:
						if (DEBUG) Log.v(TAG, "STATE_PREPARED");
						switch (request) {
						case REQUEST_RESUME:
							setState(STATE_RESUMING, null);
							break;
						case REQUEST_PAUSE:
							setState(STATE_PAUSING, null);
							break;
						default:
							request = REQUEST_NON;
							setState(STATE_INITIALIZED, new IllegalStateException("state=" + mState + ",request=" + request));
						}
						break;
					case STATE_PAUSING:
						if (DEBUG) Log.v(TAG, "STATE_PAUSING");
						request = REQUEST_NON;
						handlePauseRecording();
						setState(STATE_PAUSED, null);
						callOnPause();
						break;
					case STATE_PAUSED:
						if (DEBUG) Log.v(TAG, "STATE_PAUSED");
						switch (request) {
						case REQUEST_RESUME:
							setState(STATE_RESUMING, null);
							break;
						default:
							request = REQUEST_NON;
							setState(STATE_INITIALIZED, new IllegalStateException("state=" + mState + ",request=" + request));
						}
						break;
					case STATE_RESUMING:
						if (DEBUG) Log.v(TAG, "STATE_RESUMING");
						request = REQUEST_NON;
						try {
							internal_configure(mFormat);
							changeOutputStream();
							mMediaCodec.start();
							encoderOutputBuffers = mMediaCodec.getOutputBuffers();
							encoderInputBuffers = mMediaCodec.getInputBuffers();
							mFrameCounts = 0;
							setState(STATE_RUNNING, null);
							callOnResume();
						} catch (IOException e) {
							setState(STATE_INITIALIZED, e);
							break;
						}
						break;
					case STATE_RUNNING:
						if (DEBUG) Log.v(TAG, "STATE_RUNNING");
						switch (request) {
						case REQUEST_PAUSE:
							setState(STATE_PAUSING, null);
							break;
						case REQUEST_DRAIN:
							request = REQUEST_NON;
							drain();
							break;
						default:
							request = REQUEST_NON;
							setState(STATE_INITIALIZED, new IllegalStateException("state=" + mState + ",request=" + request));
						}
						break;
				} // end of switch
			} // end of for
			if (DEBUG) Log.v(TAG, "#run:finished");
			setState(STATE_RELEASE, null);
			// internal_release all related objects
			internal_release();
		}
	};

//********************************************************************************
//********************************************************************************
	/**
	 * handle pausing request
	 * this method is called from message handler of EncoderHandler
	 */
	private final void handlePauseRecording() {
		if (DEBUG) Log.v(TAG, "handlePauseRecording");
		// process all available output data
		drain();
		// request stop recording
		signalEndOfInputStream();
		// process output data again for EOS signal
		drain();
		if (mCurrentOutputStream != null)
			try {
				mCurrentOutputStream.flush();
				mCurrentOutputStream.close();
			} catch (IOException e) {
				Log.e(TAG, "handlePauseRecording:", e);
			}
		mCurrentOutputStream = null;
		if (mMediaCodec != null)
			mMediaCodec.stop();
	}

    /**
     * Release all related objects
     */
    protected void internal_release() {
		if (DEBUG) Log.d(TAG, "internal_release:");
		try {
			mListener.onStopped(this);
		} catch (Exception e) {
			Log.e(TAG, "failed onStopped", e);
		}
		mIsRunning = false;
		encoderOutputBuffers = encoderInputBuffers = null;
        if (mMediaCodec != null) {
			try {
	            mMediaCodec.stop();
	            mMediaCodec.release();
	            mMediaCodec = null;
			} catch (Exception e) {
				Log.e(TAG, "failed releasing MediaCodec", e);
			}
        }
        mBufferInfo = null;
    }

    protected void signalEndOfInputStream() {
		if (DEBUG) Log.d(TAG, "sending EOS to encoder");
        // signalEndOfInputStream is only available for video encoding with surface
        // and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag.
//		mMediaCodec.signalEndOfInputStream();	// API >= 18
        encode(null, 0, getPTSUs());
	}

	protected boolean isRecording() {
		return mIsRunning && (mState == STATE_RUNNING);
	}

    /**
     * Method to set byte array to the MediaCodec encoder
	 * if you use Surface to input data to encoder, you should not call this method
     * @param buffer
     * @param length　length of byte array, zero means EOS.
     * @param presentationTimeUs
     */
    protected void encode(final byte[] buffer, final int length, final long presentationTimeUs) {
    	if (!mIsRunning || !isRecording()) return;
    	int ix = 0, sz;
//		final ByteBuffer[] encoderInputBuffers = mMediaCodec.getInputBuffers();
        while (mIsRunning && ix < length) {
	        final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
	        if (inputBufferIndex >= 0) {
	            final ByteBuffer inputBuffer = encoderInputBuffers[inputBufferIndex];
	            inputBuffer.clear();
	            sz = inputBuffer.remaining();
	            sz = (ix + sz < length) ? sz : length - ix; 
	            if (sz > 0 && (buffer != null)) {
	            	inputBuffer.put(buffer, ix, sz);
	            }
	            ix += sz;
	            if (length <= 0) {
	            	// send EOS
	            	mIsEOS = true;
	            	if (DEBUG) Log.i(TAG, "send BUFFER_FLAG_END_OF_STREAM");
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
	            		presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
		            break;
	            } else {
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
	            		presentationTimeUs, 0);
	            }
	        } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
	        	// wait for MediaCodec encoder is ready to encode
	        	// nothing to do here because MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
	        	// will wait for maximum TIMEOUT_USEC(10msec) on each call
	        }
        }
    }

	/**
	 * working buffer
	 */
	private byte[] writeBuffer = new byte[1024];
    /**
     * drain encoded data and write them to intermediate file
     */
    protected void drain() {
    	if (mMediaCodec == null) return;
        int encoderStatus;
LOOP:	while (mIsRunning && (mState == STATE_RUNNING)) {
			// get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
			try {
				encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
			} catch (IllegalStateException e) {
				break;
			}
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
               		break LOOP;		// out of while
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            	if (DEBUG) Log.v(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                // this should not come when encoding
                encoderOutputBuffers = mMediaCodec.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            	if (DEBUG) Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
            	// this status indicate the output format of codec is changed
                // this should come only once before actual encoded data
            	// but this status never come on Android4.3 or less
            	// and in that case, you should treat when MediaCodec.BUFFER_FLAG_CODEC_CONFIG come.
				// get output format from codec and pass them to muxer
				// getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise crash.
				if (mSequence == 0) {	// sequence 0 is for saving MediaFormat
					final MediaFormat format = mMediaCodec.getOutputFormat(); // API >= 16
					try {
						writeFormat(mCurrentOutputStream, format);
						changeOutputStream();	// change to next intermediate file
					} catch (IOException e) {
						Log.e(TAG, "drain:failed to write MediaFormat ", e);
					}
				}
            } else if (encoderStatus < 0) {
            	// unexpected status
            	if (DEBUG) Log.w(TAG, "drain:unexpected result from encoder#dequeueOutputBuffer: " + encoderStatus);
            } else {
                final ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                	// this never should come...may be a MediaCodec internal error
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                	// You should set output format to muxer here when you target Android4.3 or less
                	// but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
                	// therefor we should expand and prepare output format from buffer data.
                	// This sample is for API>=18(>=Android 4.3), just ignore this flag here
					if (DEBUG) Log.d(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG");
					mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
					mFrameCounts++;
                    if (mCurrentOutputStream == null) {
                        throw new RuntimeException("drain:temporary file not ready");
                    }
                    // write encoded data to muxer(need to adjust presentationTimeUs.
                   	mBufferInfo.presentationTimeUs = getPTSUs();
					try {
						writeStream(mCurrentOutputStream, mBufferInfo, encodedData, writeBuffer);
					} catch (IOException e) {
						throw new RuntimeException("drain:failed to writeStream:" + e.getMessage());
					}
					prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }
                // return buffer to encoder
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);
				if ((mNumFrames > 0) && (mFrameCounts > mNumFrames)) {
					setState(STATE_PAUSING, null);	// pause要求する
				}
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                	// when EOS come.
               		mIsRunning = false;
                    break;      // out of while
                }
            }
        }
    }

    /**
     * 前回エンコード時の時刻[マイクロ秒]
     */
	private long prevOutputPTSUs = 0;
	/**
	 * エンコード開始時刻[マイクロ秒]
	 */
	private long firstTimeUs = -1;
	/**
	 * get next encoding presentationTimeUs
	 * @return
	 */
    protected long getPTSUs() {
    	if (firstTimeUs < 0) firstTimeUs = System.nanoTime() / 1000L;
		long result = System.nanoTime() / 1000L - firstTimeUs;
    	// presentationTimeUs should be monotonic
    	// otherwise muxer fail to write
		if (result < prevOutputPTSUs) {
			final long delta = prevOutputPTSUs - result + 8333;	// add approx 1/120 sec as a bias
			result += delta;
			firstTimeUs += delta;
		}
		return result;
    }

	/*package*/static String getSequenceFilePath(File base_dir, int type, int sequence) {
		final File file = new File(base_dir, String.format("%s-%d.raw", ((type == 1 ? "audio" : "video")), sequence));
		return file.getAbsolutePath();
	}

	/**
	 * write frame header
	 * @param presentation_time_us
	 * @param size
	 * @throws IOException
	 */
	protected static void writeHeader(final ObjectOutputStream out, final long presentation_time_us, final int size, final int flag) throws IOException {
		out.writeLong(presentation_time_us);
		out.writeInt(size);
		out.writeInt(flag);
		//
		out.writeLong(0);
		out.writeLong(0);
		out.writeLong(0);
		out.writeLong(0);
		out.writeLong(0);
		out.writeLong(0);
	}

	/**
	 * read frame header as BufferInfo
	 * @param in
	 * @param info
	 * @return
	 * @throws IOException
	 */
	/*package*/static MediaCodec.BufferInfo readHeader(final ObjectInputStream in, final MediaCodec.BufferInfo info) throws IOException {
		info.size = 0;
		info.presentationTimeUs = in.readLong();
		info.size = in.readInt();
		info.flags = in.readInt();
		in.skipBytes(48);	// long x 6
		return info;
	}

	/**
	 * read frame header and only returns size of frame
	 * @param in
	 * @return
	 * @throws IOException
	 */
	/*package*/static int readHeader(final ObjectInputStream in) throws IOException {
		in.readLong();
		final int size = in.readInt();
		in.skipBytes(52);	// long x 6 + int x 1
		return size;
	}

	/**
	 * convert ByteBuffer into String
	 * @param buffer
	 * @return
	 */
	private static final String asString(ByteBuffer buffer) {
		final byte[] temp = new byte[16];
		final StringBuilder sb = new StringBuilder();
		int n = (buffer != null ? buffer.limit() : 0);
		if (n > 0) {
			buffer.rewind();
			int sz = (n > 16 ? 16 : n);
			n -= sz;
			for (; sz > 0; sz = (n > 16 ? 16 : n), n -= sz) {
				buffer.get(temp, 0, sz);
				for (int i = 0; i < sz; i++) {
					sb.append(temp[i]).append(',');
				}
			}
		}
		return sb.toString();
	}

	/**
	 * convert transcribe String into ByteBuffer
	 * @param str
	 * @return
	 */
	private static final ByteBuffer asByteBuffer(String str) {
		final String[] hex = str.split(",");
		final int m = hex.length;
		final byte[] temp = new byte[m];
		int n = 0;
		for (int i = 0; i < m; i++) {
			if (!TextUtils.isEmpty(hex[i]))
				temp[n++] = (byte)Integer.parseInt(hex[i]);
		}
		if (n > 0)
			return ByteBuffer.wrap(temp, 0, n);
		else
			return null;
	}

	/**
	 * write MediaFormat data into intermediate file
	 * @param out
	 * @param format
	 */
	/*package*/static void writeFormat(final ObjectOutputStream out, final MediaFormat format) throws IOException {
		if (DEBUG) Log.v(TAG_STATIC, "writeFormat:format=" + format);
		final HashMap<String, Object> map = new HashMap<String, Object>();
		if (format.containsKey(MediaFormat.KEY_MIME))
			map.put(MediaFormat.KEY_MIME, format.getString(MediaFormat.KEY_MIME));
		if (format.containsKey(MediaFormat.KEY_WIDTH))
			map.put(MediaFormat.KEY_WIDTH, format.getInteger(MediaFormat.KEY_WIDTH));
		if (format.containsKey(MediaFormat.KEY_HEIGHT))
			map.put(MediaFormat.KEY_HEIGHT, format.getInteger(MediaFormat.KEY_HEIGHT));
		if (format.containsKey(MediaFormat.KEY_BIT_RATE))
			map.put(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE));
		if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT))
			map.put(MediaFormat.KEY_COLOR_FORMAT, format.getInteger(MediaFormat.KEY_COLOR_FORMAT));
		if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
			map.put(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE));
		if (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL))
			map.put(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
		if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
			map.put(MediaFormat.KEY_MAX_INPUT_SIZE, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
		if (format.containsKey(MediaFormat.KEY_DURATION))
			map.put(MediaFormat.KEY_DURATION, format.getInteger(MediaFormat.KEY_DURATION));
		if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
			map.put(MediaFormat.KEY_CHANNEL_COUNT, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
		if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
			map.put(MediaFormat.KEY_SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
		if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK))
			map.put(MediaFormat.KEY_CHANNEL_MASK, format.getInteger(MediaFormat.KEY_CHANNEL_MASK));
		if (format.containsKey(MediaFormat.KEY_AAC_PROFILE))
			map.put(MediaFormat.KEY_AAC_PROFILE, format.getInteger(MediaFormat.KEY_AAC_PROFILE));
		if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
			map.put(MediaFormat.KEY_MAX_INPUT_SIZE, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
		if (format.containsKey("what"))
			map.put("what", format.getInteger("what"));
		if (format.containsKey("csd-0"))
			map.put("csd-0", asString(format.getByteBuffer("csd-0")));
		if (format.containsKey("csd-1"))
			map.put("csd-1", asString(format.getByteBuffer("csd-1")));

		if (DEBUG) Log.v(TAG_STATIC, "writeFormat:map=" + map);
		final String format_str = map.toString();
		final int size = TextUtils.isEmpty(format_str) ? 0 : format_str.length();
		try {
			writeHeader(out, -1, size, 0);
			out.writeObject(map);
		} catch (IOException e) {
			Log.e(TAG_STATIC, "writeFormat:", e);
			throw e;
		}
	}

	/*package*/static MediaFormat readFormat(final ObjectInputStream in) {
		MediaFormat format = null;
		try {
			final int size = readHeader(in);
			final HashMap<String, Object> map = (HashMap<String, Object>)in.readObject();
			if (map != null) {
				if (DEBUG) Log.v(TAG_STATIC, "readFormat:map=" + map);
				format = new MediaFormat();
				if (map.containsKey(MediaFormat.KEY_MIME))
					format.setString(MediaFormat.KEY_MIME, (String)map.get(MediaFormat.KEY_MIME));
				if (map.containsKey(MediaFormat.KEY_WIDTH))
					format.setInteger(MediaFormat.KEY_WIDTH, (Integer)map.get(MediaFormat.KEY_WIDTH));
				if (map.containsKey(MediaFormat.KEY_HEIGHT))
					format.setInteger(MediaFormat.KEY_HEIGHT, (Integer)map.get(MediaFormat.KEY_HEIGHT));
				if (map.containsKey(MediaFormat.KEY_BIT_RATE))
					format.setInteger(MediaFormat.KEY_BIT_RATE, (Integer)map.get(MediaFormat.KEY_BIT_RATE));
				if (map.containsKey(MediaFormat.KEY_COLOR_FORMAT))
					format.setInteger(MediaFormat.KEY_COLOR_FORMAT, (Integer)map.get(MediaFormat.KEY_COLOR_FORMAT));
				if (map.containsKey(MediaFormat.KEY_FRAME_RATE))
					format.setInteger(MediaFormat.KEY_FRAME_RATE, (Integer)map.get(MediaFormat.KEY_FRAME_RATE));
				if (map.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL))
					format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, (Integer)map.get(MediaFormat.KEY_I_FRAME_INTERVAL));
				if (map.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
					format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (Integer)map.get(MediaFormat.KEY_MAX_INPUT_SIZE));
				if (map.containsKey(MediaFormat.KEY_DURATION))
					format.setInteger(MediaFormat.KEY_DURATION, (Integer)map.get(MediaFormat.KEY_DURATION));
				if (map.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
					format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, (Integer) map.get(MediaFormat.KEY_CHANNEL_COUNT));
				if (map.containsKey(MediaFormat.KEY_SAMPLE_RATE))
					format.setInteger(MediaFormat.KEY_SAMPLE_RATE, (Integer) map.get(MediaFormat.KEY_SAMPLE_RATE));
				if (map.containsKey(MediaFormat.KEY_CHANNEL_MASK))
					format.setInteger(MediaFormat.KEY_CHANNEL_MASK, (Integer) map.get(MediaFormat.KEY_CHANNEL_MASK));
				if (map.containsKey(MediaFormat.KEY_AAC_PROFILE))
					format.setInteger(MediaFormat.KEY_AAC_PROFILE, (Integer) map.get(MediaFormat.KEY_AAC_PROFILE));
				if (map.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
					format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (Integer) map.get(MediaFormat.KEY_MAX_INPUT_SIZE));
				if (map.containsKey("what"))
					format.setInteger("what", (Integer)map.get("what"));
				if (map.containsKey("csd-0"))
					format.setByteBuffer("csd-0", asByteBuffer((String)map.get("csd-0")));
				if (map.containsKey("csd-1"))
					format.setByteBuffer("csd-1", asByteBuffer((String)map.get("csd-1")));
			}
		} catch (ClassNotFoundException e) {
			Log.e(TAG_STATIC, "readFormat:", e);
		} catch (IOException e) {
			Log.e(TAG_STATIC, "readFormat:", e);
		}
		if (DEBUG) Log.v(TAG_STATIC, "readFormat:format=" + format);
		return format;
	}

	/**
	 * 生ビットストリームを一時ファイルへ出力する
	 * @param info
	 * @param buffer
	 */
	protected static void writeStream(final ObjectOutputStream out, final MediaCodec.BufferInfo info,
		final ByteBuffer buffer, byte[] writeBuffer) throws IOException {

		if (DEBUG) Log.v(TAG_STATIC, String.format("writeStream:size=%d", info.size));
		if (writeBuffer.length < info.size) {
			writeBuffer = new byte[info.size];
		}
		buffer.position(info.offset);
		buffer.get(writeBuffer, 0, info.size);
		try {
			writeHeader(out, info.presentationTimeUs, info.size, info.flags);
			out.write(writeBuffer, 0, info.size);
		} catch (IOException e) {
			if (DEBUG) Log.e(TAG_STATIC, "writeStream:", e);
			throw e;
		}
	}

	/*package*/static void readStream(final ObjectInputStream in, final MediaCodec.BufferInfo info,
		final ByteBuffer buffer, byte[] readBuffer) throws IOException, BufferOverflowException {

		readHeader(in, info);
//		if (DEBUG) Log.v(TAG_STATIC, String.format("readStream:size=%d,capacity=%d", info.size, buffer.capacity()));
		buffer.clear();
		if (info.size > buffer.capacity()) {
			in.skipBytes(info.size);	// このフレームはスキップする
			throw new BufferOverflowException();
		}
		final int max_bytes = Math.min(readBuffer.length, info.size);
		int read_bytes = 0;
		for (int i = info.size; i > 0; i -= read_bytes) {
			read_bytes = in.read(readBuffer, 0, Math.min(i, max_bytes));
//				if (DEBUG) Log.v(TAG_STATIC, String.format("readStream:read_bytes=%d", read_bytes));
			if (read_bytes <= 0) break;
			buffer.put(readBuffer, 0, read_bytes);
		}
		buffer.flip();
//		if (DEBUG) Log.v(TAG_STATIC, String.format("readStream:offset=%d,size=%d,buffer=", info.offset, info.size) + buffer);
	}

    /**
     * Handler class to handle the asynchronous request to encoder thread
     */
//	private static final class EncoderHandler extends Handler {
//		private final WeakReference<TLMediaEncoder> mWeakEncoder;
//
//		public EncoderHandler(TLMediaEncoder encoder) {
//			mWeakEncoder = new WeakReference<TLMediaEncoder>(encoder);
//		}
//
//		/**
//		 * message handler
//		 */
//		@Override
//		public final void handleMessage(final Message inputMessage) {
//			final int what = inputMessage.what;
//			final TLMediaEncoder encoder = mWeakEncoder.get();
//			if (encoder == null) {
//				Log.w("EncoderHandler", "EncoderHandler#handleMessage: encoder is null");
//				return;
//			}
//			switch (what) {
//			case MSG_FRAME_AVAILABLE:
//				encoder.drain();
//				break;
//			case MSG_PAUSE_RECORDING:
//				encoder.handlePauseRecording();
//				synchronized (encoder.mSync) {
//					encoder.mSync.notifyAll();
//				}
//				break;
//			case MSG_STOP_RECORDING:
//				encoder.handleStopRecording();
//				synchronized (encoder.mSync) {
//					encoder.mSync.notifyAll();
//				}
//				Looper.myLooper().quit();
//				break;
//			default:
//				throw new RuntimeException("unknown message what=" + what);
//			}
//		}
//	}

}
