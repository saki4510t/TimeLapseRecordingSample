package com.serenegiant.timelapserecordingsample;

/*
 * TimeLapseRecordingSample
 * Sample project to capture audio and video periodically from internal mic/camera
 * and save as time lapsed MPEG4 file.
 *
 * Copyright (c) 2015 saki t_saki@serenegiant.com
 *
 * File name: CameraFragment.java
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

import android.app.Activity;
import android.app.Fragment;
import android.content.pm.ActivityInfo;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import com.serenegiant.media.TLMediaAudioEncoder;
import com.serenegiant.media.TLMediaEncoder;
import com.serenegiant.media.TLMediaMovieBuilder;
import com.serenegiant.media.TLMediaVideoEncoder;

import java.io.IOException;

public class CameraFragment extends Fragment {
	private static final boolean DEBUG = false;	// TODO set false on releasing
	private static final String TAG = "CameraFragment";
	
	/**
	 * for camera preview display
	 */
	private CameraGLView mCameraView;
	/**
	 * button for start/stop recording
	 */
	private ImageButton mRecordButton;
	private TLMediaVideoEncoder mVideoEncoder;
	private TLMediaAudioEncoder mAudioEncoder;
	private TLMediaMovieBuilder mMuxer;
	private boolean mIsRecording;
	private String mMovieName;

	public CameraFragment() {
		// need default constructor
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_camera, container, false);
		mCameraView = rootView.findViewById(R.id.cameraView);
		mCameraView.setVideoSize(1280, 720);
		mCameraView.setOnTouchListener(mOnTouchListener);
		mRecordButton = rootView.findViewById(R.id.record_button);
		mRecordButton.setOnClickListener(mOnClickListener);
		return rootView;
	}

	@Override
	public void onResume() {
		super.onResume();
		if (DEBUG) Log.v(TAG, "onResume:");
		mCameraView.onResume();
	}

	@Override
	public void onPause() {
		if (DEBUG) Log.v(TAG, "onPause:");
		stopRecording();
		mCameraView.onPause();
		super.onPause();
	}

	/*
	 *
	 */
	public final void fixedScreenOrientation(final boolean fixed) {
		getActivity().setRequestedOrientation(
			fixed ? ActivityInfo.SCREEN_ORIENTATION_LOCKED : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
	}

	/**
	 * method when touch record button
	 */
	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(View view) {
			switch (view.getId()) {
			case R.id.record_button:
				if (!mIsRecording) {
					startRecording();
				} else {
					stopRecording();
				}
				break;
			}
		}
	};

	private final View.OnTouchListener mOnTouchListener = new View.OnTouchListener() {
		@Override
		public boolean onTouch(View v, MotionEvent event) {
			if (mIsRecording) {
				switch (event.getAction()) {
					case MotionEvent.ACTION_DOWN:
						resumeRecording();
						break;
					case MotionEvent.ACTION_MOVE:
						break;
					case MotionEvent.ACTION_CANCEL:
					case MotionEvent.ACTION_UP:
						pauseRecording();
						break;
				}
				return true;
			} else
				return false;
		}
	};

	/**
	 * start recording
	 * This is a sample project and call this on UI thread to avoid being complicated
	 * but basically this should be called on private thread because preparing
	 * of encoder may be heavy work on some devices
	 */
	private void startRecording() {
		if (mIsRecording) return;
		if (DEBUG) Log.v(TAG, "start:");
		try {
			mRecordButton.setColorFilter(0xffffff00);	// turn yellow
			mMovieName = TAG; // + System.nanoTime();
			if (true) {
				// for video capturing
				mVideoEncoder = new TLMediaVideoEncoder(getActivity(), mMovieName, mMediaEncoderListener);
				try {
					mVideoEncoder.setFormat(mCameraView.getVideoWidth(), mCameraView.getVideoHeight());
					mVideoEncoder.prepare();
				} catch (Exception e) {
					Log.e(TAG, "startRecording:", e);
					mVideoEncoder.release();
					mVideoEncoder = null;
					throw e;
				}
			}
			if (true) {
				// for audio capturing
				mAudioEncoder = new TLMediaAudioEncoder(getActivity(), mMovieName, mMediaEncoderListener);
				try {
					mAudioEncoder.prepare();
				} catch (Exception e) {
					Log.e(TAG, "startRecording:", e);
					mAudioEncoder.release();
					mAudioEncoder = null;
					throw e;
				}
			}
			if (mVideoEncoder != null) {
				mVideoEncoder.start(true);
			}
			if (mAudioEncoder != null) {
				mAudioEncoder.start(true);
			}
			mIsRecording = true;
			Toast.makeText(getActivity(), R.string.recording_hint, Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			mRecordButton.setColorFilter(0);
			Log.e(TAG, "startCapture:", e);
		}
		fixedScreenOrientation(mIsRecording);
	}

	/**
	 * request stop recording
	 */
	private void stopRecording() {
		if (!mIsRecording) return;
		if (DEBUG) Log.v(TAG, "stop");
		mIsRecording = false;
		mRecordButton.setColorFilter(0);    // return to default color
		if (mVideoEncoder != null) {
			mVideoEncoder.stop();
			mVideoEncoder.release();
		}
		if (mAudioEncoder != null) {
			mAudioEncoder.stop();
			mAudioEncoder.release();
		}
		fixedScreenOrientation(mIsRecording);
		try {
			mMuxer = new TLMediaMovieBuilder(getActivity(), mMovieName);
			mMuxer.build(mTLMediaMovieBuilderCallback);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * resume recording
	 */
	private void resumeRecording() {
		if (!mIsRecording) return;
		mRecordButton.setColorFilter(0xffff0000);	// turn red
		try {
			if (mVideoEncoder != null) {
				if (mVideoEncoder.isPaused())
					mVideoEncoder.resume();
			}
			if (mAudioEncoder != null) {
				if (mAudioEncoder.isPaused())
					mAudioEncoder.resume();
			}
		} catch (IOException e) {
			stopRecording();
		}
	}

	/**
	 * pause recording
	 */
	private void pauseRecording() {
		if (!mIsRecording) return;
		mRecordButton.setColorFilter(0xffffff00);	// turn yellow
		if ((mVideoEncoder != null) && !mVideoEncoder.isPaused())
		try {
			mVideoEncoder.pause();
		} catch (Exception e) {
			Log.e(TAG, "pauseRecording:", e);
			mVideoEncoder.release();
			mVideoEncoder = null;
		}
		if ((mAudioEncoder != null) && !mAudioEncoder.isPaused())
		try {
			mAudioEncoder.pause();
		} catch (Exception e) {
			Log.e(TAG, "pauseRecording:", e);
			mAudioEncoder.release();
			mAudioEncoder = null;
		}
	}

	/**
	 * callback methods from encoder
	 */
	private final TLMediaEncoder.MediaEncoderListener mMediaEncoderListener
		= new TLMediaEncoder.MediaEncoderListener() {

		@Override
		public void onPrepared(TLMediaEncoder encoder) {
			if (DEBUG) Log.v(TAG, "onPrepared:encoder=" + encoder);
		}

		@Override
		public void onStopped(TLMediaEncoder encoder) {
			if (DEBUG) Log.v(TAG, "onStopped:encoder=" + encoder);
		}

		@Override
		public void onResume(TLMediaEncoder encoder) {
			if (DEBUG) Log.v(TAG, "onResume:encoder=" + encoder);
			if (encoder instanceof TLMediaVideoEncoder)
				mCameraView.setVideoEncoder((TLMediaVideoEncoder)encoder);
		}

		@Override
		public void onPause(TLMediaEncoder encoder) {
			if (DEBUG) Log.v(TAG, "onPause:encoder=" + encoder);
			if (encoder instanceof TLMediaVideoEncoder)
				mCameraView.setVideoEncoder(null);
		}
	};

	/**
	 * callback methods from TLMediaMovieBuilder
	 */
	private TLMediaMovieBuilder.TLMediaMovieBuilderCallback mTLMediaMovieBuilderCallback
		= new TLMediaMovieBuilder.TLMediaMovieBuilderCallback() {

		@Override
		public void onFinished(String output_path) {
			if (DEBUG) Log.v(TAG, "onFinished:");
			mMuxer = null;
			if (!TextUtils.isEmpty(output_path)) {
				final Activity activity = CameraFragment.this.getActivity();
				if ((activity == null) || activity.isFinishing()) return;
				// add movie to gallery
				MediaScannerConnection.scanFile(activity, new String[] {output_path}, null, null);
			}
		}

		@Override
		public void onError(Exception e) {
			if (DEBUG) Log.v(TAG, "onError:" + e.getMessage());
		}
	};
}
