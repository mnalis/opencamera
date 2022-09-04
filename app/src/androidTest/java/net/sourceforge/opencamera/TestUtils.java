package net.sourceforge.opencamera;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Helper class for testing. This method should not include any code specific to any test framework
 *  (e.g., shouldn't be specific to ActivityInstrumentationTestCase2).
 */
public class TestUtils {
    private static final String TAG = "TestUtils";

    final public static String images_base_path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();

    public static void setDefaultIntent(Intent intent) {
        intent.putExtra("test_project", true);
    }

    public static void initTest(Context context, boolean test_camera2) {
        Log.d(TAG, "initTest: " + test_camera2);
        // initialise test statics (to avoid the persisting between tests in a test suite run!)
        MainActivity.test_preview_want_no_limits = false;
        MainActivity.test_preview_want_no_limits_value = false;
        ImageSaver.test_small_queue_size = false;

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = settings.edit();
        editor.clear();
        if( test_camera2 ) {
            MainActivity.test_force_supports_camera2 = true;
            //editor.putBoolean(PreferenceKeys.UseCamera2PreferenceKey, true);
            editor.putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2");
        }
        editor.apply();

        Log.d(TAG, "initTest: done");
    }

    /** Converts a path to a Uri for com.android.providers.media.documents.
     */
    private static Uri getDocumentUri(String filename) throws FileNotFoundException {
        Log.d(TAG, "getDocumentUri: " + filename);

        // convert from File path format to Storage Access Framework form
        Uri treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FtestOpenCamera");
        Log.d(TAG, "treeUri: " + treeUri);
        if( !filename.startsWith(images_base_path) ) {
            Log.e(TAG, "unknown base for: " + filename);
            throw new FileNotFoundException();
        }
        String stem = filename.substring(images_base_path.length());
        Uri stemUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM" + stem.replace("/", "%2F"));
        Log.d(TAG, "stem: " + stem);
        Log.d(TAG, "stemUri: " + stemUri);
        //String docID = "primary:DCIM" + stem;
        String docID = DocumentsContract.getTreeDocumentId(stemUri);
        Log.d(TAG, "docID: " + docID);
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docID);

        if( uri == null ) {
            throw new FileNotFoundException();
        }
        return uri;
    }

    public static Bitmap getBitmapFromFile(MainActivity activity, String filename) throws FileNotFoundException {
        return getBitmapFromFile(activity, filename, 1);
    }

    /** Loads bitmap from supplied filename.
     *  Note that on Android 10+ (with scoped storage), this uses Storage Access Framework, which
     *  means Open Camera must have SAF permission to the folder DCIM/testOpenCamera.
     */
    public static Bitmap getBitmapFromFile(MainActivity activity, String filename, int inSampleSize) throws FileNotFoundException {
        Log.d(TAG, "getBitmapFromFile: " + filename);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        //options.inSampleSize = inSampleSize;
        if( inSampleSize > 1 ) {
            // use inDensity for better quality, as inSampleSize uses nearest neighbour
            // see same code in ImageSaver.setBitmapOptionsSampleSize()
            options.inDensity = inSampleSize;
            options.inTargetDensity = 1;
        }

        Uri uri = null;
        Bitmap bitmap;

        if( MainActivity.useScopedStorage() ) {
            uri = getDocumentUri(filename);
            Log.d(TAG, "uri: " + uri);
            InputStream is = activity.getContentResolver().openInputStream(uri);
            bitmap = BitmapFactory.decodeStream(is, null, options);
            try {
                is.close();
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }
        else {
            bitmap = BitmapFactory.decodeFile(filename, options);
        }
        if( bitmap == null )
            throw new FileNotFoundException();
        Log.d(TAG, "    done: " + bitmap);

        // now need to take exif orientation into account, as some devices or camera apps store the orientation in the exif tag,
        // which getBitmap() doesn't account for
        ParcelFileDescriptor parcelFileDescriptor = null;
        FileDescriptor fileDescriptor;
        try {
            ExifInterface exif = null;
            if( uri != null ) {
                parcelFileDescriptor = activity.getContentResolver().openFileDescriptor(uri, "r");
                if( parcelFileDescriptor != null ) {
                    fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                    exif = new ExifInterface(fileDescriptor);
                }
            }
            else {
                exif = new ExifInterface(filename);
            }
            if( exif != null ) {
                int exif_orientation_s = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
                boolean needs_tf = false;
                int exif_orientation = 0;
                // from http://jpegclub.org/exif_orientation.html
                // and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
                if( exif_orientation_s == ExifInterface.ORIENTATION_UNDEFINED || exif_orientation_s == ExifInterface.ORIENTATION_NORMAL ) {
                    // leave unchanged
                }
                else if( exif_orientation_s == ExifInterface.ORIENTATION_ROTATE_180 ) {
                    needs_tf = true;
                    exif_orientation = 180;
                }
                else if( exif_orientation_s == ExifInterface.ORIENTATION_ROTATE_90 ) {
                    needs_tf = true;
                    exif_orientation = 90;
                }
                else if( exif_orientation_s == ExifInterface.ORIENTATION_ROTATE_270 ) {
                    needs_tf = true;
                    exif_orientation = 270;
                }
                else {
                    // just leave unchanged for now
                    Log.e(TAG, "    unsupported exif orientation: " + exif_orientation_s);
                }
                Log.d(TAG, "    exif orientation: " + exif_orientation);

                if( needs_tf ) {
                    Log.d(TAG, "    need to rotate bitmap due to exif orientation tag");
                    Matrix m = new Matrix();
                    m.setRotate(exif_orientation, bitmap.getWidth() * 0.5f, bitmap.getHeight() * 0.5f);
                    Bitmap rotated_bitmap = Bitmap.createBitmap(bitmap, 0, 0,bitmap.getWidth(), bitmap.getHeight(), m, true);
                    if( rotated_bitmap != bitmap ) {
                        bitmap.recycle();
                        bitmap = rotated_bitmap;
                    }
                }
            }
        }
        catch(IOException e) {
            e.printStackTrace();
        }
        finally {
            if( parcelFileDescriptor != null ) {
                try {
                    parcelFileDescriptor.close();
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
            }
        }
        /*{
            for(int y=0;y<bitmap.getHeight();y++) {
                for(int x=0;x<bitmap.getWidth();x++) {
                    int color = bitmap.getPixel(x, y);
                    Log.d(TAG, x + "," + y + ": " + Color.red(color) + "," + Color.green(color) + "," + Color.blue(color));
                }
            }
        }*/
        return bitmap;
    }

}
