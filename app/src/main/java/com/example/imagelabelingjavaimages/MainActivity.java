package com.example.imagelabelingjavaimages;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import android.database.Cursor;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_CODE = 1211;
    private static final int STORAGE_PERMISSION_CODE = 1212;
    private static final int MAX_IMAGE_DIMENSION = 1024; // Reduced from 2048 to prevent memory issues

    //TODO get the image from gallery and display it
    ActivityResultLauncher<Intent> galleryActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        image_uri = result.getData().getData();
                        Log.d(TAG, "Gallery image URI: " + image_uri);
                        Bitmap inputImage = uriToBitmap(image_uri);
                        if (inputImage != null) {
                            Bitmap rotated = rotateBitmap(inputImage);
                            innerImage.setImageBitmap(rotated);
                            performImageLabeling(rotated);
                            Log.d(TAG, "Gallery image loaded and displayed.");
                        } else {
                            showError("Failed to load image from gallery");
                            Log.e(TAG, "Failed to load bitmap from gallery URI: " + image_uri);
                        }
                    } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
                         Log.d(TAG, "Gallery activity cancelled by user.");
                    } else {
                         Log.e(TAG, "Gallery activity failed with result code: " + result.getResultCode());
                    }
                }
            });

    //TODO capture the image using camera and display it
    ActivityResultLauncher<Intent> cameraActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    Log.d(TAG, "Camera Activity Result received with code: " + result.getResultCode());
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Log.d(TAG, "Result OK from camera activity.");
                        try {
                            Bitmap capturedImage = null;
                            Intent data = result.getData();

                            // First try to load from the URI where the camera saved the image
                            if (image_uri != null) {
                                Log.d(TAG, "Loading image from camera URI: " + image_uri);
                                capturedImage = loadAndScaleBitmap(image_uri);
                                if (capturedImage != null) {
                                    Log.d(TAG, "Successfully loaded image from camera URI. Dimensions: " + 
                                        capturedImage.getWidth() + "x" + capturedImage.getHeight());
                                } else {
                                    Log.w(TAG, "Failed to load image from camera URI, trying thumbnail");
                                }
                            }

                            // If URI loading failed, try to get thumbnail from data intent
                            if (capturedImage == null && data != null && data.getExtras() != null) {
                                Log.d(TAG, "Attempting to load thumbnail from data extras");
                                Object thumbnail = data.getExtras().get("data");
                                if (thumbnail instanceof Bitmap) {
                                    capturedImage = (Bitmap) thumbnail;
                                    Log.d(TAG, "Loaded thumbnail from extras. Dimensions: " + 
                                        capturedImage.getWidth() + "x" + capturedImage.getHeight());
                                }
                            }

                            if (capturedImage == null) {
                                Log.e(TAG, "After attempting URI and data extras, capturedImage is still null.");
                            }

                            if (capturedImage != null) {
                                // Rotate the image if needed
                                Bitmap rotatedImage = rotateBitmap(capturedImage);
                                if (rotatedImage != null) {
                                    // Save to gallery first
                                    Uri savedImageUri = saveImageToGallery(rotatedImage);
                                    if (savedImageUri != null) {
                                        // Load the saved image to ensure it's properly saved
                                        Bitmap savedImage = loadAndScaleBitmap(savedImageUri);
                                        if (savedImage != null) {
                                            // Display the image
                                            runOnUiThread(() -> {
                                                try {
                                                    innerImage.setImageBitmap(null); // Clear previous image
                                                    innerImage.setImageBitmap(savedImage);
                                                    innerImage.requestLayout();
                                                    performImageLabeling(savedImage);
                                                    Log.d(TAG, "Image successfully displayed from saved URI");
                                                } catch (Exception e) {
                                                    Log.e(TAG, "Error displaying saved image: " + e.getMessage(), e);
                                                    showError("Error displaying image");
                                                }
                                            });
                                        } else {
                                            // Fallback to displaying the rotated image if saved image can't be loaded
                                            displayImage(rotatedImage);
                                        }
                                    } else {
                                        // If saving fails, still try to display the rotated image
                                        displayImage(rotatedImage);
                                    }
                                } else {
                                    Log.e(TAG, "Failed to rotate captured image");
                                    showError("Failed to process image");
                                }
                            } else {
                                Log.e(TAG, "No valid image data obtained from camera");
                                showError("Failed to capture image");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing camera result: " + e.getMessage(), e);
                            showError("Error processing image: " + e.getMessage());
                        }
                    } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
                        Log.d(TAG, "Camera activity cancelled by user");
                    } else {
                        Log.e(TAG, "Camera activity failed with result code: " + result.getResultCode());
                        showError("Photo capture failed");
                    }
                }
            });

    //TODO declare views
    ImageView innerImage;
    TextView resultTv;
    CardView cardImages, cardCamera;
    private Uri image_uri;

    // Declare ImageLabeler as a class member
    private ImageLabeler labeler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //TODO initialize views
        innerImage = findViewById(R.id.imageView2);
        ViewGroup.LayoutParams layoutParams = innerImage.getLayoutParams();
        layoutParams.height = getWidth() - 40;
        innerImage.setLayoutParams(layoutParams);
        // Keep the scaleType as fitXY to match the layout
        innerImage.setScaleType(ImageView.ScaleType.FIT_XY);
        resultTv = findViewById(R.id.textView);
        cardImages = findViewById(R.id.cardImages);
        cardCamera = findViewById(R.id.cardCamera);

        // Initialize the ImageLabeler
        // To use default options:
//        labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);

        // Or, to set the minimum confidence required:
         ImageLabelerOptions options = new ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build();
         labeler = ImageLabeling.getClient(options);

        //TODO choose images from gallery
        cardImages.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent galleryIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                galleryActivityResultLauncher.launch(galleryIntent);
            }
        });
        //TODO capture images using camera
        cardCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkAndRequestPermissions()) {
                    openCamera();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clear any bitmaps to prevent memory leaks
        if (innerImage != null) {
            innerImage.setImageBitmap(null);
        }
    }

    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean cameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean storagePermission = true; // Assume true for newer Android versions initially

            // For Android versions lower than 10 (Q), check WRITE_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                 storagePermission = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }

            if (!cameraPermission) {
                Log.d(TAG, "Requesting camera permission");
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
                return false;
            }
            
            // Request storage permission only if needed and not granted
            if (!storagePermission) {
                Log.d(TAG, "Requesting storage permission");
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
                return false;
            }
             Log.d(TAG, "Camera and storage permissions already granted.");
        } else {
             // Permissions are granted at install time for API levels below M
             Log.d(TAG, "Running on API level < M, permissions granted at install time.");
        }
        return true; // Permissions not needed or already granted
    }

    //TODO opens camera so that user can capture image
    private void openCamera() {
        Log.d(TAG, "Attempting to open camera");
        try {
            // Create camera intent
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

            // Check if there's a camera app available to handle this intent
            if (cameraIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Camera app found, attempting to create image file URI.");

                File photoFile = null;
                try {
                    // Create the image file in the directory defined in file_paths.xml
                    File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES + File.separator + "MyImageLabelingApp");
                    if (storageDir != null) {
                         // Ensure directory exists
                        if (!storageDir.exists()) {
                            if (!storageDir.mkdirs()) {
                                Log.e(TAG, "Failed to create directory for photo: " + storageDir.getAbsolutePath());
                                showError("Failed to prepare storage for photo.");
                                return;
                            }
                             Log.d(TAG, "Created directory for photo: " + storageDir.getAbsolutePath());
                        }
                         photoFile = File.createTempFile(
                                "IMG_",    /* prefix */
                                ".jpg",    /* suffix */
                                storageDir /* directory */
                        );
                         Log.d(TAG, "Created temporary photo file: " + photoFile.getAbsolutePath());
                    } else {
                         Log.e(TAG, "getExternalFilesDir returned null");
                         showError("Failed to access external storage.");
                         return;
                    }

                } catch (IOException ex) {
                    // Error occurred while creating the File
                    Log.e(TAG, "Error creating temporary image file: " + ex.getMessage(), ex);
                    showError("Error preparing to take photo.");
                    return;
                }

                // Continue only if the File was successfully created
                if (photoFile != null) {
                    // Get the content URI using FileProvider
                    image_uri = androidx.core.content.FileProvider.getUriForFile(
                            this,
                            getPackageName() + ".provider",
                            photoFile);

                    Log.d(TAG, "Generated FileProvider URI: " + image_uri);

                    // Set the output URI for the camera intent
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, image_uri);

                    // Grant URI permissions to the camera app
                    cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                     Log.d(TAG, "Added grant URI permission flags to camera intent.");

                    // Launch the camera activity
                    try {
                        cameraActivityResultLauncher.launch(cameraIntent);
                        Log.d(TAG, "Launched camera intent with FileProvider URI.");
                    } catch (Exception e) {
                        Log.e(TAG, "Error launching camera intent: " + e.getMessage(), e);
                        showError("Could not launch camera.");
                        image_uri = null; // Clear image_uri if launching fails
                    }
                } else {
                     Log.e(TAG, "Photo file was null after creation attempt.");
                     showError("Could not prepare storage for photo.");
                }

            } else {
                Log.e(TAG, "No camera app found on your device.");
                Toast.makeText(this, "No camera app found on your device", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "An unexpected error occurred while opening camera: " + e.getMessage(), e);
            showError("An unexpected error occurred: " + e.getMessage());
        }
    }

    //TODO perform image labeling on images
    public void performImageLabeling(Bitmap input) {
        InputImage image = InputImage.fromBitmap(input, 0 );
        resultTv.setText("");
        labeler.process(image)
                .addOnSuccessListener(new OnSuccessListener<List<ImageLabel>>() {
                    @Override
                    public void onSuccess(List<ImageLabel> labels) {
                        // Task completed successfully
                        // ...
                        for (ImageLabel label:labels){
                            resultTv.append(label.getText()+"   "+ String.format("%.2f",label.getConfidence()) +"\n");

                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // Task failed with an exception
                        // ...
                    }
                });
        // TODO: Add your actual image labeling logic here.
        // For now, we will just update the text view to show that the method was called.
        if (resultTv != null) {
            runOnUiThread(() -> {
                resultTv.setText("Image labeling process started...");
                // In a real app, you would perform labeling here and then update resultTv
                // with the actual results.
                // For example:
                // List<Label> labels = myImageLabeler.process(input);
                // resultTv.setText(formatLabels(labels));
            });
        }
        Log.d(TAG, "performImageLabeling called.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted.");
                // Camera permission granted, now check/request storage permission if needed
                 if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "Requesting storage permission after camera permission granted.");
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
                        return;
                    }
                 }
                // All required permissions granted, open camera
                openCamera();
            } else {
                Log.w(TAG, "Camera permission denied.");
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                 Log.d(TAG, "Storage permission granted.");
                // Storage permission granted, now check/request camera permission if needed
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Requesting camera permission after storage permission granted.");
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
                    return;
                }
                // All required permissions granted, open camera
                openCamera();
            } else {
                 Log.w(TAG, "Storage permission denied.");
                Toast.makeText(this, "Storage permission is required to save photos", Toast.LENGTH_SHORT).show();
                // Show settings dialog to help user enable permission
                showSettingsDialog();
            }
        }
    }

    private void showSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Permission Required");
        builder.setMessage("Storage permission is required to save photos. Please grant permission in Settings.");
        builder.setPositiveButton("Settings", (dialog, which) -> {
            dialog.dismiss();
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showError(String message) {
        if (!isFinishing()) {
            runOnUiThread(() -> {
                try {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show(); // Changed to LONG
                } catch (Exception e) {
                    Log.e(TAG, "Error showing toast: " + e.getMessage());
                }
            });
        }
    }

    //TODO takes URI of the image and returns bitmap
    private Bitmap uriToBitmap(Uri selectedFileUri) {
        try {
            ParcelFileDescriptor parcelFileDescriptor = getContentResolver().openFileDescriptor(selectedFileUri, "r");
            if (parcelFileDescriptor == null) {
                Log.e(TAG, "Failed to open file descriptor for URI: " + selectedFileUri);
                return null;
            }
            
            FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
            
            // Get the image dimensions
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
            
            // Calculate sample size to avoid OutOfMemoryError
            // Using MAX_IMAGE_DIMENSION for consistency
            int maxDimension = Math.max(options.outWidth, options.outHeight);
            int sampleSize = 1;
            while (maxDimension > MAX_IMAGE_DIMENSION) {
                sampleSize *= 2;
                maxDimension /= 2;
            }
            
            // Reset the file descriptor
            parcelFileDescriptor.close();
            parcelFileDescriptor = getContentResolver().openFileDescriptor(selectedFileUri, "r");
            fileDescriptor = parcelFileDescriptor.getFileDescriptor();
            
            // Decode the bitmap with the calculated sample size
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
            
            parcelFileDescriptor.close();
            
            if (image == null) {
                Log.e(TAG, "Failed to decode bitmap from URI: " + selectedFileUri);
            } else {
                Log.d(TAG, "Successfully loaded bitmap: " + image.getWidth() + "x" + image.getHeight());
            }
            
            return image;
        } catch (IOException e) {
            Log.e(TAG, "Error loading bitmap from URI: " + e.getMessage(), e);
            return null;
        }
    }

    //TODO rotate image if image captured on samsung devices
    @SuppressLint("Range")
    public Bitmap rotateBitmap(Bitmap input) {
        if (input == null) return null;
        
        try {
            // If using MediaStore URI, query for orientation
            if (image_uri != null && ContentResolver.SCHEME_CONTENT.equals(image_uri.getScheme())) {
                String[] orientationColumn = {MediaStore.Images.Media.ORIENTATION};
                Cursor cur = getContentResolver().query(image_uri, orientationColumn, null, null, null);
                int orientation = -1;
                if (cur != null && cur.moveToFirst()) {
                    orientation = cur.getInt(cur.getColumnIndex(orientationColumn[0]));
                     Log.d(TAG, "Image orientation from MediaStore: " + orientation);
                }
                if (cur != null) cur.close();
                 
                 // If orientation is valid and not 0, apply rotation
                if (orientation > 0) {
                     Matrix rotationMatrix = new Matrix();
                     rotationMatrix.setRotate(orientation);
                     Bitmap rotated = Bitmap.createBitmap(input, 0, 0, input.getWidth(), input.getHeight(), rotationMatrix, true);

                     if (rotated != input) {
                         input.recycle(); // Recycle the original bitmap if we created a new one
                     }
                     Log.d(TAG, "Bitmap rotated by " + orientation + " degrees.");
                     return rotated;
                }
                 Log.d(TAG, "No rotation needed or orientation not found from MediaStore.");
                return input; // No rotation needed

            } else {
                 // For file URIs or if image_uri is null, handle other potential orientation methods if necessary
                 // For simplicity, returning original input for now if not a content URI
                 Log.d(TAG, "Not a content URI, skipping MediaStore orientation check.");
                 return input;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error rotating bitmap: " + e.getMessage(), e);
            return input; // Return original bitmap in case of error
        }
    }

    private Bitmap loadAndScaleBitmap(Uri uri) {
        try {
            if (uri == null) {
                Log.e(TAG, "loadAndScaleBitmap received null URI.");
                return null;
            }
            
            // First get the image dimensions
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                     Log.e(TAG, "loadAndScaleBitmap: Failed to open file descriptor for URI: " + uri);
                     return null;
                }
                BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
            }

            // Calculate sample size
            int sampleSize = calculateSampleSize(options.outWidth, options.outHeight);
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;

            // Load the bitmap with the calculated sample size
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                     Log.e(TAG, "loadAndScaleBitmap: Failed to open file descriptor for URI (second attempt): " + uri);
                     return null;
                }
                Bitmap bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
                 if (bitmap == null) {
                     Log.e(TAG, "loadAndScaleBitmap: BitmapFactory.decodeFileDescriptor returned null for URI: " + uri);
                 }
                 return bitmap;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading or scaling bitmap from URI: " + uri + ": " + e.getMessage(), e);
            return null;
        }
    }

    private int calculateSampleSize(int width, int height) {
        int sampleSize = 1;
        int maxDimension = Math.max(width, height);
        while (maxDimension > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2;
            maxDimension /= 2;
        }
        return sampleSize;
    }

    private Bitmap scaleBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return bitmap;
        }

        float scale = Math.min(
            (float) MAX_IMAGE_DIMENSION / width,
            (float) MAX_IMAGE_DIMENSION / height
        );

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        try {
            Log.d(TAG, "Scaling bitmap from " + width + "x" + height + " to scale factor " + scale);
            Bitmap scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
             if (scaledBitmap != bitmap) {
                // We don't recycle here because the input bitmap might be needed elsewhere
                // The loading functions should handle recycling of intermediate bitmaps
             }
             return scaledBitmap;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory while scaling bitmap", e);
            // In case of OOM, return the original unscaled bitmap as a last resort
            return bitmap;
        } catch (Exception e) {
             Log.e(TAG, "Error during bitmap scaling", e);
             return bitmap;
        }
    }

    // New method to save bitmap to gallery
    private Uri saveImageToGallery(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "saveImageToGallery received null bitmap.");
            return null;
        }

        String displayName = "IMG_" + System.currentTimeMillis() + ".jpg";
        String mimeType = "image/jpeg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);

        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above, use MediaStore
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "MyImageLabelingApp");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Log.d(TAG, "Using MediaStore for Android Q+ with RELATIVE_PATH: " + values.getAsString(MediaStore.Images.Media.RELATIVE_PATH));
        } else {
            // For older Android versions
            File imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File appDir = new File(imagesDir, "MyImageLabelingApp");
            if (!appDir.exists()) {
                Log.d(TAG, "Directory 'MyImageLabelingApp' does not exist. Attempting to create: " + appDir.getAbsolutePath());
                if (!appDir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory: " + appDir.getAbsolutePath());
                    showError("Failed to create directory for saving photos");
                    return null;
                }
                 Log.d(TAG, "Directory 'MyImageLabelingApp' created successfully.");
            }
            File imageFile = new File(appDir, displayName);
            values.put(MediaStore.Images.Media.DATA, imageFile.getAbsolutePath());
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Log.d(TAG, "Using File path for older Android: " + imageFile.getAbsolutePath());
        }

        Uri imageUri = null;
        try {
            // Insert the image into MediaStore
            Log.d(TAG, "Attempting to insert image into MediaStore...");
            imageUri = getContentResolver().insert(collection, values);
            if (imageUri == null) {
                Log.e(TAG, "Failed to create new MediaStore record. insert returned null.");
                showError("Failed to save photo to gallery");
                return null;
            }
            Log.d(TAG, "MediaStore record created with URI: " + imageUri);

            // Write the bitmap to the output stream
            Log.d(TAG, "Attempting to open output stream for URI: " + imageUri);
            try (OutputStream os = getContentResolver().openOutputStream(imageUri)) {
                if (os == null) {
                    Log.e(TAG, "Failed to open output stream for URI. openOutputStream returned null.");
                    getContentResolver().delete(imageUri, null, null);
                    showError("Failed to save photo");
                    return null;
                }
                Log.d(TAG, "Output stream opened successfully. Attempting to compress and write bitmap...");
                
                // Compress and write the bitmap
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)) {
                    Log.e(TAG, "Failed to compress bitmap. bitmap.compress returned false.");
                    getContentResolver().delete(imageUri, null, null);
                    showError("Failed to save photo");
                    return null;
                }
                Log.d(TAG, "Bitmap successfully compressed and written to output stream.");
            }

            // For Android 10 and above, update the IS_PENDING flag
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Android Q+ device. Attempting to clear IS_PENDING flag for URI: " + imageUri);
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                int updated = getContentResolver().update(imageUri, values, null, null);
                if (updated == 0) {
                    Log.e(TAG, "Failed to update IS_PENDING flag. update returned 0.");
                    getContentResolver().delete(imageUri, null, null);
                    showError("Failed to finalize photo save");
                    return null;
                }
                Log.d(TAG, "Successfully cleared IS_PENDING flag for URI: " + imageUri);
            } else {
                // For older Android versions, trigger media scanner
                Log.d(TAG, "Older Android device. Attempting to trigger media scanner.");
                File imageFile = new File(values.getAsString(MediaStore.Images.Media.DATA));
                if (imageFile.exists()) {
                    Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    mediaScanIntent.setData(Uri.fromFile(imageFile));
                    sendBroadcast(mediaScanIntent);
                    Log.d(TAG, "Triggered media scanner for file: " + imageFile.getAbsolutePath());
                } else {
                     Log.e(TAG, "File not found for media scanning: " + imageFile.getAbsolutePath());
                }
            }

            // Show success message
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Photo saved to gallery", Toast.LENGTH_SHORT).show());
            Log.d(TAG, "Image successfully saved to gallery: " + imageUri);
            return imageUri;

        } catch (IOException e) {
            Log.e(TAG, "IOException while saving bitmap to gallery: " + e.getMessage(), e);
            if (imageUri != null) {
                getContentResolver().delete(imageUri, null, null);
            }
            showError("Failed to save photo: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error while saving image to gallery: " + e.getMessage(), e);
            if (imageUri != null) {
                getContentResolver().delete(imageUri, null, null);
            }
            showError("Failed to save photo");
            return null;
        }
    }

    // Helper method to display image
    private void displayImage(Bitmap bitmap) {
        if (bitmap == null) return;
        
        runOnUiThread(() -> {
            try {
                innerImage.setImageBitmap(null); // Clear previous image
                innerImage.setImageBitmap(bitmap);
                innerImage.requestLayout();
                performImageLabeling(bitmap);
                Log.d(TAG, "Image displayed successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error displaying image: " + e.getMessage(), e);
                showError("Error displaying image");
            }
        });
    }

    public int getWidth() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics.widthPixels;
    }
}
