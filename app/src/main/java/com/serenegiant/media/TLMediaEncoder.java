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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
	protected static final int MSG_FRAME_AVAILABLE = 1;
	protected static final int MSG_PAUSE_RECORDING = 8;
	protected static final int MSG_STOP_RECORDING = 9;

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
	/**
	 * Flag that indicate this encoder is capturing now.
	 */
    protected volatile boolean mIsCapturing;
    /**
     * Flag to request stop capturing
     */
    protected volatile boolean mRequestStop;
	/**
	 * Flag to request pause capturing
	 */
	protected volatile boolean mRequestPause;
    /**
     * Flag that indicate encoder received EOS(End Of Stream)
     */
    protected boolean mIsEOS;
    /**
     * MediaCodec instance for encoding
     */
    protected MediaCodec mMediaCodec;				// API >= 16(Android4.1.2)
	protected MediaFormat mFormat;
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
    private EncoderHandler mHandler;
    protected final MediaEncoderListener mListener;

	private final Context mContext;
	private final File mBaseDir;
	private final int mType;
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
    	if (TextUtils.isEmpty(movie_name)) throw new IllegalArgumentException("movie_name should not be null");
		mContext = context;
		mBaseDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), movie_name);
		mBaseDir.mkdirs();
		mType = type;
		mListener = listener;
        synchronized (mSync) {
            // create BufferInfo here for effectiveness(to reduce GC)
            mBufferInfo = new MediaCodec.BufferInfo();
            // wait for Handler is ready
            new Thread(mEncoderTask, getClass().getSimpleName()).start();
            try {
            	mSync.wait();
            } catch (InterruptedException e) {
            }
        }
	}

    /**
     * calling this method notify encoder that the input data is already available or will be available soon
     * @return return tur if this encoder can accept input data
     */
    public boolean frameAvailableSoon() {
//    	if (DEBUG) Log.v(TAG, "frameAvailableSoon");
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop || mRequestPause) {
                return false;
            }
            mHandler.removeMessages(MSG_FRAME_AVAILABLE);
            mHandler.sendEmptyMessage(MSG_FRAME_AVAILABLE);
        }
        return true;
    }

	private final Runnable mEncoderTask = new Runnable() {
		/**
		 * message handling loop
		 */
		@Override
		public void run() {
			// create Looper and Handler to access to this thread
			Looper.prepare();
			synchronized (mSync) {
				mHandler = new EncoderHandler(TLMediaEncoder.this);
				mRequestStop = mRequestPause = false;
				mSync.notify();
			}

			Looper.loop();

			if (DEBUG) Log.d(TAG, "Encoder thread exiting");
			synchronized (mSync) {
				mIsCapturing = false;
				mRequestStop = true;
				mHandler = null;
			}
		}
	};

	/*
    * prepare encoder. This method will be called once.
    * @throws IOException
    */
	public abstract void prepare() throws IOException;

	/**
	 * configure encoder. This method will be called every time on resuming
	 * @param format
	 * @throws IOException
	 */
	protected abstract void configure(MediaFormat format) throws IOException;

   /**
    * start encoder
    */
	public void start() throws IOException {
		start(false);
	}

	/**
	 * start encoder
	 * @param pauseAfterStarted if this flag is true, encoder become pause state immediately
	 * @throws IOException
	 */
	public void start(boolean pauseAfterStarted) throws IOException {
   	if (DEBUG) Log.v(TAG, "start_from_encoder");
		synchronized (mSync) {
			mIsCapturing = mRequestPause = true;
			mRequestStop = false;
			mSequence = -1;
			if (!pauseAfterStarted) {
				resume();
			}
		}
	}

   /**
    * request stop encoder
    */
   public void stop() {
		if (DEBUG) Log.v(TAG, "stop");
		synchronized (mSync) {
			if (!mIsCapturing || mRequestStop) {
				return;
			}
			mRequestStop = true;	// for rejecting newer frame
//			mSync.notifyAll();
            mHandler.removeMessages(MSG_FRAME_AVAILABLE);
	        // request encoder handler to stop encoding
	        mHandler.sendEmptyMessage(MSG_STOP_RECORDING);
	        // We can not know when the encoding and writing finish.
	        // so we return immediately after request to avoid delay of caller thread
			try {
				mSync.wait();
			} catch (InterruptedException e) {
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
		if (mFormat == null)
			prepare();
		synchronized (mSync) {
			if (!mIsCapturing || mRequestStop) {
				return;
			}
			configure(mFormat);
			changeOutputStream();
			mMediaCodec.start();
			encoderOutputBuffers = mMediaCodec.getOutputBuffers();
			encoderInputBuffers = mMediaCodec.getInputBuffers();
			mNumFrames = num_frames;
			mFrameCounts = 0;
			mRequestPause = false;
		}
		if (mListener != null) {
			mListener.onResume(this);
		}
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
	public void pause() {
		if (DEBUG) Log.v(TAG, "pause");
		synchronized (mSync) {
			if (!mIsCapturing || mRequestStop || mRequestPause) {
				return;
			}
			internal_pause();
			try {
				mSync.wait();
			} catch (InterruptedException e) {
			}
		}
	}

	private final void internal_pause() {
		if (mRequestPause) return;
		mRequestPause = true;	// for rejecting newer frame
		mHandler.removeMessages(MSG_FRAME_AVAILABLE);
		// request encoder handler to pause encoding
		mHandler.sendEmptyMessage(MSG_PAUSE_RECORDING);
	}

	/**
	 * get whether this encoder is pause state
	 * @return
	 */
	public boolean isPaused() {
		synchronized (mSync) {
			return mRequestPause;
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
//********************************************************************************
//********************************************************************************
	/**
	 * handle pausing request
	 * this method is called from message handler of EncoderHandler
	 */
	private final void handlePauseRecording() {
		if (DEBUG) Log.d(TAG, "handlePauseRecording");
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
				Log.e(TAG, "internal_pause:", e);
			}
		mCurrentOutputStream = null;
		mMediaCodec.stop();
		if (mListener != null) {
			mListener.onPause(this);
		}
	}

    /**
     * handle stopping request
     * this method is called from message handler of EncoderHandler
     */
    private final void handleStopRecording() {
		if (DEBUG) Log.d(TAG, "handleStopRecording");
		handlePauseRecording();
        // release all related objects
        release();
    }

    /**
     * Release all related objects
     */
    protected void release() {
		if (DEBUG) Log.d(TAG, "release:");
		try {
			mListener.onStopped(this);
		} catch (Exception e) {
			Log.e(TAG, "failed onStopped", e);
		}
		mIsCapturing = false;
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

    /**
     * Method to set byte array to the MediaCodec encoder
	 * if you use Surface to input data to encoder, you should not call this method
     * @param buffer
     * @param length　length of byte array, zero means EOS.
     * @param presentationTimeUs
     */
    protected void encode(final byte[] buffer, final int length, final long presentationTimeUs) {
    	if (!mIsCapturing || mRequestPause) return;
    	int ix = 0, sz;
//		final ByteBuffer[] encoderInputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
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
        int encoderStatus, count = 0;
LOOP:	while (mIsCapturing) {
			// get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
			try {
				encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
			} catch (IllegalStateException e) {
				break;
			}
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
                	if (++count > 5)	
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
                	// encoded data is ready, clear waiting counter
            		count = 0;
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
					internal_pause();	// pause要求する
				}
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                	// when EOS come.
               		mIsCapturing = false;
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
    private static final class EncoderHandler extends Handler {
        private final WeakReference<TLMediaEncoder> mWeakEncoder;

        public EncoderHandler(TLMediaEncoder encoder) {
            mWeakEncoder = new WeakReference<TLMediaEncoder>(encoder);
        }

        /**
         * message handler
         */
        @Override 
        public final void handleMessage(final Message inputMessage) {
            final int what = inputMessage.what;
            final TLMediaEncoder encoder = mWeakEncoder.get();
            if (encoder == null) {
                Log.w("EncoderHandler", "EncoderHandler#handleMessage: encoder is null");
                return;
            }
            switch (what) {
                case MSG_FRAME_AVAILABLE:
               		encoder.drain();
                    break;
				case MSG_PAUSE_RECORDING:
					encoder.handlePauseRecording();
					synchronized (encoder.mSync) {
						encoder.mSync.notifyAll();
					}
					break;
                case MSG_STOP_RECORDING:
                    encoder.handleStopRecording();
					synchronized (encoder.mSync) {
						encoder.mSync.notifyAll();
					}
                    Looper.myLooper().quit();
                    break;
                default:
                    throw new RuntimeException("unknown message what=" + what);
            }
        }
    }

}
