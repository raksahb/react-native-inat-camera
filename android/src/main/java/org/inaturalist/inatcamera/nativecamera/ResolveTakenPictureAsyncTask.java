package org.inaturalist.inatcamera.nativecamera;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import androidx.exifinterface.media.ExifInterface;
import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import java.util.List;
import org.inaturalist.inatcamera.classifier.Prediction;

import android.location.Location;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ResolveTakenPictureAsyncTask extends AsyncTask<Void, Void, WritableMap> {
    private static final String TAG = "ResolveTakenPictureAsyncTask";
    private static final String ERROR_TAG = "E_TAKING_PICTURE_FAILED";
    private Promise mPromise;
    private byte[] mImageData;
    private ReadableMap mOptions;
    private File mCacheDirectory;
    private Bitmap mBitmap;
    private int mDeviceOrientation;
    private PictureSavedDelegate mPictureSavedDelegate;
    private RNCameraView mCameraView;

    public ResolveTakenPictureAsyncTask(byte[] imageData, Promise promise, ReadableMap options, File cacheDirectory, int deviceOrientation, PictureSavedDelegate delegate, RNCameraView cameraView) {
        mPromise = promise;
        mOptions = options;
        mCameraView = cameraView;
        mImageData = imageData;
        mCacheDirectory = cacheDirectory;
        mDeviceOrientation = deviceOrientation;
        mPictureSavedDelegate = delegate;
    }

    private int getQuality() {
        if (mOptions.hasKey("quality")) {
            return (int) (mOptions.getDouble("quality") * 100);
        } else {
            return 100;
        }
    }

    @Override
    protected WritableMap doInBackground(Void... voids) {
        Log.d(TAG, "doInBackground");
        WritableMap response = Arguments.createMap();
        ByteArrayInputStream inputStream = null;

        response.putInt("deviceOrientation", mDeviceOrientation);
        response.putInt("pictureOrientation", mOptions.hasKey("orientation") ? mOptions.getInt("orientation") : mDeviceOrientation);

        if (mOptions.hasKey("skipProcessing")) {
            try {
                // Prepare file output
                File imageFile = new File(RNFileUtils.getOutputFilePath(mCacheDirectory, ".jpg"));
                imageFile.createNewFile();
                FileOutputStream fOut = new FileOutputStream(imageFile);

                // Save byte array (it is already a JPEG)
                fOut.write(mImageData);

                // get image size
                if (mBitmap == null) {
                    mBitmap = BitmapFactory.decodeByteArray(mImageData, 0, mImageData.length);
                }
                if(mBitmap == null){
                    throw new IOException("Failed to decode Image bitmap.");
                }

                response.putInt("width", mBitmap.getWidth());
                response.putInt("height", mBitmap.getHeight());

                // Return file system URI
                String fileUri = Uri.fromFile(imageFile).toString();
                response.putString("uri", fileUri);

            } catch (Resources.NotFoundException e) {
                response = null; // do not resolve
                mPromise.reject(ERROR_TAG, "Documents directory of the app could not be found.", e);
                e.printStackTrace();
            } catch (IOException e) {
                response = null; // do not resolve
                mPromise.reject(ERROR_TAG, "An unknown I/O exception has occurred.", e);
                e.printStackTrace();
            }

            return response;
        }

        Log.d(TAG, "doInBackground 2");
        // we need the stream only for photos from a device
        if (mBitmap == null) {
            mBitmap = BitmapFactory.decodeByteArray(mImageData, 0, mImageData.length);
            inputStream = new ByteArrayInputStream(mImageData);
        }

        try {
            WritableMap fileExifData = null;

            if (inputStream != null) {
                ExifInterface exifInterface = new ExifInterface(inputStream);
                // Get orientation of the image from mImageData via inputStream
                int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED);

                // Rotate the bitmap to the proper orientation if needed
                boolean fixOrientation = mOptions.hasKey("fixOrientation")
                        && mOptions.getBoolean("fixOrientation")
                        && orientation != ExifInterface.ORIENTATION_UNDEFINED;
                if (fixOrientation) {
                    mBitmap = rotateBitmap(mBitmap, getImageRotation(orientation));
                }

                if (mOptions.hasKey("width")) {
                    mBitmap = resizeBitmap(mBitmap, mOptions.getInt("width"));
                }

                if (mOptions.hasKey("mirrorImage") && mOptions.getBoolean("mirrorImage")) {
                    mBitmap = flipHorizontally(mBitmap);
                }

                WritableMap exifData = null;
                ReadableMap exifExtraData = null;
                boolean writeExifToResponse = mOptions.hasKey("exif") && mOptions.getBoolean("exif");
                boolean writeExifToFile = true;
                if (mOptions.hasKey("writeExif")) {
                    switch (mOptions.getType("writeExif")) {
                        case Boolean:
                            writeExifToFile = mOptions.getBoolean("writeExif");
                            break;
                        case Map:
                            exifExtraData = mOptions.getMap("writeExif");
                            writeExifToFile = true;
                            break;
                    }
                }

                // Read Exif data if needed
                if (writeExifToResponse || writeExifToFile) {
                    exifData = RNCameraViewHelper.getExifData(exifInterface);
                }

                // Write Exif data to output file if requested
                if (writeExifToFile) {
                    fileExifData = Arguments.createMap();
                    fileExifData.merge(exifData);
                    fileExifData.putInt("width", mBitmap.getWidth());
                    fileExifData.putInt("height", mBitmap.getHeight());
                    if (fixOrientation) {
                        fileExifData.putInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    }
                    if (exifExtraData != null) {
                        fileExifData.merge(exifExtraData);
                    }
                }

                // Write Exif data to the response if requested
                if (writeExifToResponse) {
                    response.putMap("exif", exifData);
                }
            }

            // Upon rotating, write the image's dimensions to the response
            response.putInt("width", mBitmap.getWidth());
            response.putInt("height", mBitmap.getHeight());

            // Cache compressed image in imageStream
            ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
            mBitmap.compress(Bitmap.CompressFormat.JPEG, getQuality(), imageStream);

            // Get predictions for that image
            List<Prediction> predictions = mCameraView.getPredictionsForImage(mBitmap);
            mCameraView.fillResults(response, predictions);

            // Write compressed image to file in cache directory unless otherwise specified
            if (!mOptions.hasKey("doNotSave") || !mOptions.getBoolean("doNotSave")) {
                String filePath = writeStreamToFile(imageStream);
                if (fileExifData != null) {
                    ExifInterface fileExifInterface = new ExifInterface(filePath);


                    RNCameraViewHelper.setExifData(fileExifInterface, fileExifData);

                    // Add Location EXIF
                    Location location = mCameraView.getLocation();

                    if (location != null) {
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();

                        fileExifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GPSEncoder.convert(latitude));
                        fileExifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, GPSEncoder.latitudeRef(latitude));
                        fileExifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GPSEncoder.convert(longitude));
                        fileExifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, GPSEncoder.longitudeRef(longitude));
                    }

                    fileExifInterface.saveAttributes();
                }
                File imageFile = new File(filePath);
                String fileUri = Uri.fromFile(imageFile).toString();
                response.putString("uri", fileUri);
            }

            // Write base64-encoded image to the response if requested
            if (mOptions.hasKey("base64") && mOptions.getBoolean("base64")) {
                response.putString("base64", Base64.encodeToString(imageStream.toByteArray(), Base64.NO_WRAP));
            }

            // Cleanup
            imageStream.close();
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }

            Log.d(TAG, "doInBackground 3");
            return response;
        } catch (Resources.NotFoundException e) {
            mPromise.reject(ERROR_TAG, "Documents directory of the app could not be found.", e);
            e.printStackTrace();
        } catch (IOException e) {
            mPromise.reject(ERROR_TAG, "An unknown I/O exception has occurred.", e);
            e.printStackTrace();
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // An exception had to occur, promise has already been rejected. Do not try to resolve it again.
        return null;
    }

    private Bitmap rotateBitmap(Bitmap source, int angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private Bitmap resizeBitmap(Bitmap bm, int newWidth) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleRatio = (float) newWidth / (float) width;

        return Bitmap.createScaledBitmap(bm, newWidth, (int) (height * scaleRatio), true);
    }

    private Bitmap flipHorizontally(Bitmap source) {
        Matrix matrix = new Matrix();
        matrix.preScale(-1.0f, 1.0f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    // Get rotation degrees from Exif orientation enum

    private int getImageRotation(int orientation) {
        int rotationDegrees = 0;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                rotationDegrees = 90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                rotationDegrees = 180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                rotationDegrees = 270;
                break;
        }
        return rotationDegrees;
    }

    private String writeStreamToFile(ByteArrayOutputStream inputStream) throws IOException {
        String outputPath = null;
        IOException exception = null;
        FileOutputStream outputStream = null;

        try {
            outputPath = RNFileUtils.getOutputFilePath(mCacheDirectory, ".jpg");
            outputStream = new FileOutputStream(outputPath);
            inputStream.writeTo(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
            exception = e;
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (exception != null) {
            throw exception;
        }

        return outputPath;
    }

    @Override
    protected void onPostExecute(WritableMap response) {
        super.onPostExecute(response);

        // If the response is not null everything went well and we can resolve the promise.
        if (response != null) {
            if (mOptions.hasKey("fastMode") && mOptions.getBoolean("fastMode")) {
                WritableMap wrapper = Arguments.createMap();
                wrapper.putInt("id", mOptions.getInt("id"));
                wrapper.putMap("data", response);
                mPictureSavedDelegate.onPictureSaved(wrapper);
            } else {
                mPromise.resolve(response);
            }
        }
    }

}