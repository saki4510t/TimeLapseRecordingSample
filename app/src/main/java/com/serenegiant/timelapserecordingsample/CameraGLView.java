package com.serenegiant.timelapserecordingsample;

/*
 * TimeLapseRecordingSample
 * Sample project to capture audio and video periodically from internal mic/camera
 * and save as time lapsed MPEG4 file.
 *
 * Copyright (c) 2015 saki t_saki@serenegiant.com
 *
 * File name: CameraGLView.java
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
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.serenegiant.glutils.GLDrawer2D;
import com.serenegiant.media.TLMediaVideoEncoder;

import java.io.IOException;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Sub class of GLSurfaceView to display camera preview and write video frame to capturing surface
 */
public final class CameraGLView extends GLSurfaceView {

	private static final boolean DEBUG = false; // TODO set false on releasing
	private static final String TAG = "CameraGLView";

	private static final int CAMERA_ID = 0;

	private static final int SCALE_STRETCH_FIT = 0;
	private static final int SCALE_KEEP_ASPECT_VIEWPORT = 1;
	private static final int SCALE_KEEP_ASPECT = 2;
	private static final int SCALE_CROP_CENTER = 3;

	private final CameraSurfaceRenderer mRenderer;
	private boolean mHasSurface;
	private final CameraHandler mCameraHandler;
	private int mVideoWidth, mVideoHeight;
	private int mRotation;
	private int mScaleMode = SCALE_CROP_CENTER;

	public CameraGLView(Context context) {
		this(context, null, 0);
	}

	public CameraGLView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CameraGLView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs);
		if (DEBUG) Log.v(TAG, "CameraGLView:");
		mRenderer = new CameraSurfaceRenderer();
		setEGLContextClientVersion(2);	// GLES 2.0, API >= 8
		setRenderer(mRenderer);
		final CameraThread thread = new CameraThread();
		thread.start();
		mCameraHandler = thread.getHandler();
	}

	@Override
	public void onResume() {
		if (DEBUG) Log.v(TAG, "onResume:");
		super.onResume();
		if (mHasSurface) {
			if (DEBUG) Log.v(TAG, "surface already exist");
			mCameraHandler.startPreview(getWidth(), getHeight());
		}
	}

	@Override
	public void onPause() {
		if (DEBUG) Log.v(TAG, "onPause:");
		// just request stop previewing
		mCameraHandler.stopPreview();
		super.onPause();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		if (DEBUG) Log.v(TAG, "surfaceDestroyed:");
		// wait for finish previewing here
		// otherwise camera try to display on un-exist Surface and some error will occur
		mCameraHandler.release(true);
		mHasSurface = false;
		mRenderer.onSurfaceDestroyed();
		super.surfaceDestroyed(holder);
	}

	public void setScaleMode(final int mode) {
		if (mScaleMode != mode) {
			mScaleMode = mode;
			queueEvent(new Runnable() {
				@Override
				public void run() {
					mRenderer.updateViewport();
				}
			});
		}
	}

	public int getScaleMode() {
		return mScaleMode;
	}

	@SuppressWarnings("SuspiciousNameCombination")
	public void setVideoSize(final int width, final int height) {
		if ((mRotation % 180) == 0) {
			mVideoWidth = width;
			mVideoHeight = height;
		} else {
			mVideoWidth = height;
			mVideoHeight = width;
		}
		queueEvent(new Runnable() {
			@Override
			public void run() {
				mRenderer.updateViewport();
			}
		});
	}

	public int getVideoWidth() {
		return mVideoWidth;
	}

	public int getVideoHeight() {
		return mVideoHeight;
	}

	public SurfaceTexture getSurfaceTexture() {
		if (DEBUG) Log.v(TAG, "getSurfaceTexture:");
		return mRenderer != null ? mRenderer.mSTexture : null;
	}

	public void setVideoEncoder(final TLMediaVideoEncoder encoder) {
		if (DEBUG) Log.v(TAG, "setVideoEncoder:tex_id=" + mRenderer.hTex);
		queueEvent(new Runnable() {
			@Override
			public void run() {
				synchronized (mRenderer) {
					try {
						if (encoder != null) {
							encoder.setEglContext(EGL14.eglGetCurrentContext(), mRenderer.hTex);
						}
						mRenderer.mVideoEncoder = encoder;
					} catch (RuntimeException e) {
						mRenderer.mVideoEncoder = null;
					}
				}
			}
		});
	}

//********************************************************************************
//********************************************************************************
	/**
	 * GLSurfaceViewのRenderer
	 */
	private final class CameraSurfaceRenderer
		implements GLSurfaceView.Renderer,
					SurfaceTexture.OnFrameAvailableListener {	// API >= 11

		private SurfaceTexture mSTexture;	// API >= 11
		private int hTex;
		private GLDrawer2D mDrawer;
		private final float[] mStMatrix = new float[16];
		private final float[] mMvpMatrix = new float[16];
		private TLMediaVideoEncoder mVideoEncoder;

		public CameraSurfaceRenderer() {
			if (DEBUG) Log.v(TAG, "CameraSurfaceRenderer:");
			Matrix.setIdentityM(mMvpMatrix, 0);
		}

		@Override
		public void onSurfaceCreated(GL10 unused, EGLConfig config) {
			if (DEBUG) Log.v(TAG, "onSurfaceCreated:");
			// This renderer required OES_EGL_image_external extension
			final String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);	// API >= 8
//			if (DEBUG) Log.i(TAG, "onSurfaceCreated:Gl extensions: " + extensions);
			if (!extensions.contains("OES_EGL_image_external"))
				throw new RuntimeException("This system does not support OES_EGL_image_external.");
			// create texture ID
			hTex = GLDrawer2D.initTex();
			// create SurfaceTexture using the texture ID.
			mSTexture = new SurfaceTexture(hTex);
			mSTexture.setOnFrameAvailableListener(this);
			// XXX clear screen with yellow color
			// so that let easy to see the actual view rectangle and camera images for testing.
			GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);
			mHasSurface = true;
			// create object for preview display
			mDrawer = new GLDrawer2D();
			mDrawer.setMatrix(mMvpMatrix, 0);
		}

		@Override
		public void onSurfaceChanged(GL10 unused, int width, int height) {
			if (DEBUG) Log.v(TAG, "onSurfaceChanged:");
			// if at least with or height is zero, initialization of this view is still progress.
			if ((width == 0) || (height == 0)) return;
			updateViewport();
			mCameraHandler.startPreview(width, height);
		}

		public void onSurfaceDestroyed() {
			if (DEBUG) Log.v(TAG, "onSurfaceDestroyed:");
			mDrawer = null;
			if (mSTexture != null) {
				mSTexture.release();
				mSTexture = null;
			}
		}

		private final void updateViewport() {
			final int view_width = getWidth();
			final int view_height = getHeight();
			if (view_width == 0 || view_height == 0) return;
			GLES20.glViewport(0, 0, view_width, view_height);
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
			final float video_width = mVideoWidth;
			final float video_height = mVideoHeight;
			if (video_width == 0 || video_height == 0) return;
			Matrix.setIdentityM(mMvpMatrix, 0);
			final float view_aspect = view_width / (float)view_height;
			switch (mScaleMode) {
			case SCALE_STRETCH_FIT:
				break;
			case SCALE_KEEP_ASPECT_VIEWPORT: {
				final float req = video_width / video_height;
				int x, y;
				int width, height;
				if (view_aspect > req) {
					// if view is wider than camera image, calc width of drawing area based on view height
					y = 0;
					height = view_height;
					width = (int) (req * view_height);
					x = (view_width - width) / 2;
				} else {
					// if view is higher than camera image, calc height of drawing area based on view width
					x = 0;
					width = view_width;
					height = (int) (view_width / req);
					y = (view_height - height) / 2;
				}
				// set viewport to draw keeping aspect ration of camera image
				GLES20.glViewport(x, y, width, height);
				break;
			}
			case SCALE_KEEP_ASPECT:
			case SCALE_CROP_CENTER: {
				final float scale_x = view_width / video_width;
				final float scale_y = view_height / video_height;
				final float scale = (mScaleMode == SCALE_CROP_CENTER
						? Math.max(scale_x, scale_y) : Math.min(scale_x, scale_y));
				final float width = scale * video_width;
				final float height = scale * video_height;
				Matrix.scaleM(mMvpMatrix, 0, width / view_width, height / view_height, 1.0f);
				break;
			}
			}
			if (mDrawer != null)
				mDrawer.setMatrix(mMvpMatrix, 0);
		}

		private volatile boolean requestUpdateTex = false;
		private boolean flip = true;
		/**
		 * drawing to GLSurface
		 * we set renderMode to GLSurfaceView.RENDERMODE_WHEN_DIRTY,
		 * this method is only called when #requestRender is called(= when texture is required to update)
		 * if you don't set RENDERMODE_WHEN_DIRTY, this method is called at maximum 60fps
		 */
		@Override
		public void onDrawFrame(GL10 unused) {
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

			if (requestUpdateTex) {
				requestUpdateTex = false;
				// update texture(came from camera)
				mSTexture.updateTexImage();
				// get texture matrix
				mSTexture.getTransformMatrix(mStMatrix);
			}
			// draw to preview screen
			mDrawer.draw(hTex, mStMatrix);
			flip = !flip;
			if (flip) {	// ~30fps
				synchronized (this) {
					if (mVideoEncoder != null) {
						// notify to capturing thread that the camera frame is available.
						mVideoEncoder.frameAvailableSoon(mStMatrix);
					}
				}
			}
		}

		@Override
		public void onFrameAvailable(SurfaceTexture st) {
			requestUpdateTex = true;
		}
	}

	/**
	 * Handler class for asynchronous camera operation
	 */
	private static final class CameraHandler extends Handler {
		private static final int MSG_PREVIEW_START = 1;
		private static final int MSG_PREVIEW_STOP = 2;
		private static final int MSG_RELEASE = 9;
		private CameraThread mThread;

		public CameraHandler(CameraThread thread) {
			mThread = thread;
		}

		public void startPreview(int width, int height) {
			sendMessage(obtainMessage(MSG_PREVIEW_START, width, height));
		}

		/**
		 * request to stop camera preview
		 */
		public void stopPreview() {
			synchronized (this) {
				if (mThread != null && mThread.mIsRunning) {
					sendEmptyMessage(MSG_PREVIEW_STOP);
				}
			}
		}

		/**
		 * request to release camera thread and handler
		 * @param needWait need to wait
		 */
		public void release(boolean needWait) {
			synchronized (this) {
				if (mThread != null && mThread.mIsRunning) {
					sendEmptyMessage(MSG_RELEASE);
					if (needWait) {
						try {
							if (DEBUG) Log.d(TAG, "wait for terminating of camera thread");
							wait();
						} catch (InterruptedException e) {
						}
					}
				}
			}
		}

		/**
		 * message handler for camera thread
		 */
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_PREVIEW_START:
				mThread.startPreview(msg.arg1, msg.arg2);
				break;
			case MSG_PREVIEW_STOP:
				mThread.stopPreview();
				break;
			case MSG_RELEASE:
				mThread.stopPreview();
				Looper.myLooper().quit();
				synchronized (this) {
					notifyAll();
					mThread = null;
				}
				break;
			default:
				throw new RuntimeException("unknown message:what=" + msg.what);
			}
		}
	}

	/**
	 * Thread for asynchronous operation of camera preview
	 */
	@SuppressWarnings("deprecation")
	private final class CameraThread extends Thread {
    	private final Object mReadyFence = new Object();
    	private CameraHandler mHandler;
    	private volatile boolean mIsRunning = false;
		private Camera mCamera;
		private boolean mIsFrontFace;

    	public CameraThread() {
			super("Camera thread");
    	}

    	public CameraHandler getHandler() {
            synchronized (mReadyFence) {
            	try {
            		mReadyFence.wait();
            	} catch (InterruptedException e) {
                }
            }
            return mHandler;
    	}

    	/**
    	 * message loop
    	 * prepare Looper and create Handler for this thread
    	 */
		@Override
		public void run() {
            if (DEBUG) Log.d(TAG, "Camera thread start");
            Looper.prepare();
            synchronized (mReadyFence) {
                mHandler = new CameraHandler(this);
                mIsRunning = true;
                mReadyFence.notify();
            }
            Looper.loop();
            if (DEBUG) Log.d(TAG, "Camera thread finish");
            synchronized (mReadyFence) {
                mHandler = null;
                mIsRunning = false;
            }
		}

		/**
		 * start camera preview
		 * @param width
		 * @param height
		 */
		private final void startPreview(int width, int height) {
			if (DEBUG) Log.v(TAG, "startPreview:");
			if (mCamera == null) {
				// This is a sample project so just use 0 as camera ID.
				// it is better to selecting camera is available
				try {
					mCamera = Camera.open(CAMERA_ID);
					final Camera.Parameters params = mCamera.getParameters();
					final List<String> focusModes = params.getSupportedFocusModes();
					if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
					} else if(focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
					} else {
						if (DEBUG) Log.i(TAG, "Camera does not support autofocus");
					}
					// let's try fastest frame rate. You will get near 60fps, but your device become hot.
					final List<int[]> supportedFpsRange = params.getSupportedPreviewFpsRange();
//					final int n = supportedFpsRange != null ? supportedFpsRange.size() : 0;
//					int[] range;
//					for (int i = 0; i < n; i++) {
//						range = supportedFpsRange.get(i);
//						Log.i(TAG, String.format("supportedFpsRange(%d)=(%d,%d)", i, range[0], range[1]));
//					}
					final int[] max_fps = supportedFpsRange.get(supportedFpsRange.size() - 1);
					if (DEBUG) Log.i(TAG, String.format("fps:%d-%d", max_fps[0], max_fps[1]));
					params.setPreviewFpsRange(max_fps[0], max_fps[1]);
					params.setRecordingHint(true);
					// request preview size
					// this is a sample project and just use fixed value
					// if you want to use other size, you also need to change the recording size.
					params.setPreviewSize(1280, 720);
//					final Size sz = params.getPreferredPreviewSizeForVideo();
//					if (sz != null)
//						params.setPreviewSize(sz.width, sz.height);
					// rotate camera preview according to the device orientation
					setRotation(params);
					mCamera.setParameters(params);
					// get the actual preview size
					final Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
					Log.i(TAG, String.format("previewSize(%d, %d)", previewSize.width, previewSize.height));
					// adjust view size with keeping the aspect ration of camera preview.
					// here is not a UI thread and we should request parent view to execute.
					CameraGLView.this.post(new Runnable() {
						@Override
						public void run() {
							setVideoSize(previewSize.width, previewSize.height);
						}
					});
					final SurfaceTexture st = getSurfaceTexture();
					st.setDefaultBufferSize(previewSize.width, previewSize.height);
					mCamera.setPreviewTexture(st);
				} catch (IOException e) {
					Log.e(TAG, "startPreview:", e);
					if (mCamera != null) {
						mCamera.release();
						mCamera = null;
					}
				} catch (RuntimeException e) {
					Log.e(TAG, "startPreview:", e);
					if (mCamera != null) {
						mCamera.release();
						mCamera = null;
					}
				}
				if (mCamera != null) {
					// start camera preview display
					mCamera.startPreview();
				}
			} // if (mCamera == null)
		}

		/**
		 * stop camera preview
		 */
		private void stopPreview() {
			if (DEBUG) Log.v(TAG, "stopPreview:");
			if (mCamera != null) {
				mCamera.stopPreview();
		        mCamera.release();
		        mCamera = null;
			}
		}

		/**
		 * rotate preview screen according to the device orientation
		 * @param params
		 */
		private final void setRotation(Camera.Parameters params) {
			if (DEBUG) Log.v(TAG, "setRotation:");

			final Display display = ((WindowManager)getContext()
				.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
			int rotation = display.getRotation();
			int degrees = 0;
			switch (rotation) {
				case Surface.ROTATION_0: degrees = 0; break;
				case Surface.ROTATION_90: degrees = 90; break;
				case Surface.ROTATION_180: degrees = 180; break;
				case Surface.ROTATION_270: degrees = 270; break;
			}
			// get whether the camera is front camera or back camera
			final Camera.CameraInfo info =
					new android.hardware.Camera.CameraInfo();
				android.hardware.Camera.getCameraInfo(CAMERA_ID, info);
			mIsFrontFace = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
			if (mIsFrontFace) {	// front camera
				degrees = (info.orientation + degrees) % 360;
				degrees = (360 - degrees) % 360;  // reverse
			} else {  // back camera
				degrees = (info.orientation - degrees + 360) % 360;
			}
			// apply rotation setting
			mCamera.setDisplayOrientation(degrees);
			mRotation = degrees;
			// XXX This method fails to call and camera stops working on some devices.
//			params.setRotation(degrees);
		}

	}
}
