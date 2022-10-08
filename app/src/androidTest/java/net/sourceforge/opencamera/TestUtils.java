package net.sourceforge.opencamera;

import static org.junit.Assert.*;

import android.annotation.TargetApi;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Helper class for testing. This method should not include any code specific to any test framework
 *  (e.g., shouldn't be specific to ActivityInstrumentationTestCase2).
 */
public class TestUtils {
    private static final String TAG = "TestUtils";

    final private static String images_base_path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();
    final public static String hdr_images_path = images_base_path + "/testOpenCamera/testdata/hdrsamples/";
    final public static String avg_images_path = images_base_path + "/testOpenCamera/testdata/avgsamples/";
    final public static String logprofile_images_path = images_base_path + "/testOpenCamera/testdata/logprofilesamples/";
    final public static String panorama_images_path = images_base_path + "/testOpenCamera/testdata/panoramasamples/";

    public static void setDefaultIntent(Intent intent) {
        intent.putExtra("test_project", true);
    }

    /** Code to call before running each test.
     */
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

    public static Bitmap getBitmapFromFile(MainActivity activity, String filename) {
        return getBitmapFromFile(activity, filename, 1);
    }

    public static Bitmap getBitmapFromFile(MainActivity activity, String filename, int inSampleSize) {
        try {
            return getBitmapFromFileCore(activity, filename, inSampleSize);
        }
        catch(FileNotFoundException e) {
            e.printStackTrace();
            fail("FileNotFoundException loading: " + filename);
            return null;
        }
    }

    /** Loads bitmap from supplied filename.
     *  Note that on Android 10+ (with scoped storage), this uses Storage Access Framework, which
     *  means Open Camera must have SAF permission to the folder DCIM/testOpenCamera.
     */
    private static Bitmap getBitmapFromFileCore(MainActivity activity, String filename, int inSampleSize) throws FileNotFoundException {
        Log.d(TAG, "getBitmapFromFileCore: " + filename);
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

    /** Returns the mediastore Uri for the supplied filename inside the supplied baseUri, or null
     *  if an entry can't be found.
     */
    private static Uri getUriFromName(MainActivity activity, Uri baseUri, String name) {
        Uri uri = null;
        String [] projection = new String[]{MediaStore.Images.ImageColumns._ID};
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(baseUri, projection, MediaStore.Images.ImageColumns.DISPLAY_NAME + " LIKE ?", new String[]{name}, null);
            if( cursor != null && cursor.moveToFirst() ) {
                Log.d(TAG, "found: " + cursor.getCount());
                long id = cursor.getLong(0);
                uri = ContentUris.withAppendedId(baseUri, id);
                Log.d(TAG, "id: " + id);
                Log.d(TAG, "uri: " + uri);
            }
        }
        catch(Exception e) {
            Log.e(TAG, "Exception trying to find uri from filename");
            e.printStackTrace();
        }
        finally {
            if( cursor != null ) {
                cursor.close();
            }
        }
        return uri;
    }

    public static void saveBitmap(MainActivity activity, Bitmap bitmap, String name) {
        try {
            saveBitmapCore(activity, bitmap, name);
        }
        catch(IOException e) {
            e.printStackTrace();
            fail("IOException saving: " + name);
        }
    }

    private static void saveBitmapCore(MainActivity activity, Bitmap bitmap, String name) throws IOException {
        Log.d(TAG, "saveBitmapCore: " + name);

        File file = null;
        ContentValues contentValues = null;
        Uri uri = null;
        OutputStream outputStream;
        if( MainActivity.useScopedStorage() ) {
            Uri folder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) :
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            // first try to delete pre-existing image
            Uri old_uri = getUriFromName(activity, folder, name);
            if( old_uri != null ) {
                Log.d(TAG, "delete: " + old_uri);
                activity.getContentResolver().delete(old_uri, null, null);
            }

            contentValues = new ContentValues();
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            String extension = name.substring(name.lastIndexOf("."));
            String mime_type = activity.getStorageUtils().getImageMimeType(extension);
            Log.d(TAG, "mime_type: " + mime_type);
            contentValues.put(MediaStore.Images.Media.MIME_TYPE, mime_type);
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                String relative_path = Environment.DIRECTORY_DCIM + File.separator;
                Log.d(TAG, "relative_path: " + relative_path);
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, relative_path);
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            uri = activity.getContentResolver().insert(folder, contentValues);
            Log.d(TAG, "saveUri: " + uri);
            if( uri == null ) {
                throw new IOException();
            }
            outputStream = activity.getContentResolver().openOutputStream(uri);
        }
        else {
            file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + name);
            outputStream = new FileOutputStream(file);
        }

        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
        outputStream.close();

        if( MainActivity.useScopedStorage() ) {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                contentValues.clear();
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                activity.getContentResolver().update(uri, contentValues, null, null);
            }
        }
        else {
            activity.getStorageUtils().broadcastFile(file, true, false, true);
        }
    }

    public static class HistogramDetails {
        public final int min_value;
        public final int median_value;
        public final int max_value;

        HistogramDetails(int min_value, int median_value, int max_value) {
            this.min_value = min_value;
            this.median_value = median_value;
            this.max_value = max_value;
        }
    }

    /** Checks for the resultant histogram.
     *  We check that we have a single range of non-zero values.
     * @param bitmap The bitmap to compute and check a histogram for.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static HistogramDetails checkHistogram(MainActivity activity, Bitmap bitmap) {
        int [] histogram = activity.getApplicationInterface().getHDRProcessor().computeHistogram(bitmap, true);
        assertEquals(256, histogram.length);
        int total = 0;
        for(int i=0;i<histogram.length;i++) {
            Log.d(TAG, "histogram[" + i + "]: " + histogram[i]);
            total += histogram[i];
        }
        Log.d(TAG, "total: " + total);
        boolean started = false;
        int min_value = -1, median_value = -1, max_value = -1;
        int count = 0;
        int middle = total/2;
        for(int i=0;i<histogram.length;i++) {
            int value = histogram[i];
            if( !started ) {
                started = value != 0;
            }
            if( value != 0 ) {
                if( min_value == -1 )
                    min_value = i;
                max_value = i;
                count += value;
                if( count >= middle && median_value == -1 )
                    median_value = i;
            }
        }
        Log.d(TAG, "min_value: " + min_value);
        Log.d(TAG, "median_value: " + median_value);
        Log.d(TAG, "max_value: " + max_value);
        return new HistogramDetails(min_value, median_value, max_value);
    }

    public static HistogramDetails subTestHDR(MainActivity activity, List<Bitmap> inputs, String output_name, boolean test_dro, int iso, long exposure_time) {
        return subTestHDR(activity, inputs, output_name, test_dro, iso, exposure_time, HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD);
    }

    /** The testHDRX tests test the HDR algorithm on a given set of input images.
     *  By testing on a fixed sample, this makes it easier to finetune the HDR algorithm for quality and performance.
     *  To use these tests, the testdata/ subfolder should be manually copied to the test device in the DCIM/testOpenCamera/
     *  folder (so you have DCIM/testOpenCamera/testdata/). We don't use assets/ as we'd end up with huge APK sizes which takes
     *  time to transfer to the device everytime we run the tests.
     * @param iso The ISO of the middle image (for testing Open Camera's "smart" contrast enhancement). If set to -1, then use "always" contrast enhancement.
     * @param exposure_time The exposure time of the middle image (for testing Open Camera's "smart" contrast enhancement)
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static HistogramDetails subTestHDR(MainActivity activity, List<Bitmap> inputs, String output_name, boolean test_dro, int iso, long exposure_time, HDRProcessor.TonemappingAlgorithm tonemapping_algorithm/*, HDRTestCallback test_callback*/) {
        Log.d(TAG, "subTestHDR");

        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ) {
            Log.d(TAG, "renderscript requires Android Lollipop or better");
            return null;
        }

        try {
            Thread.sleep(1000); // wait for camera to open
        }
        catch(InterruptedException e) {
            e.printStackTrace();
        }

        Bitmap dro_bitmap_in = null;
        if( test_dro ) {
            // save copy of input bitmap to also test DRO (since the HDR routine will free the inputs)
            int mid = (inputs.size()-1)/2;
            dro_bitmap_in = inputs.get(mid);
            dro_bitmap_in = dro_bitmap_in.copy(dro_bitmap_in.getConfig(), true);
        }

        HistogramDetails hdrHistogramDetails = null;
        if( inputs.size() > 1 ) {
            String preference_hdr_contrast_enhancement = (iso==-1) ? "preference_hdr_contrast_enhancement_always" : "preference_hdr_contrast_enhancement_smart";
            float hdr_alpha = ImageSaver.getHDRAlpha(preference_hdr_contrast_enhancement, exposure_time, inputs.size());
            long time_s = System.currentTimeMillis();
            try {
                activity.getApplicationInterface().getHDRProcessor().processHDR(inputs, true, null, true, null, hdr_alpha, 4, true, tonemapping_algorithm, HDRProcessor.DROTonemappingAlgorithm.DROALGORITHM_GAINGAMMA);
                //test_callback.doHDR(inputs, tonemapping_algorithm, hdr_alpha);
            }
            catch(HDRProcessorException e) {
                e.printStackTrace();
                throw new RuntimeException();
            }
            Log.d(TAG, "HDR time: " + (System.currentTimeMillis() - time_s));

            saveBitmap(activity, inputs.get(0), output_name);
            hdrHistogramDetails = checkHistogram(activity, inputs.get(0));
        }
        inputs.get(0).recycle();
        inputs.clear();

        if( test_dro ) {
            inputs.add(dro_bitmap_in);
            long time_s = System.currentTimeMillis();
            try {
                activity.getApplicationInterface().getHDRProcessor().processHDR(inputs, true, null, true, null, 0.5f, 4, true, HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD, HDRProcessor.DROTonemappingAlgorithm.DROALGORITHM_GAINGAMMA);
                //test_callback.doHDR(inputs, HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD, 0.5f);
            }
            catch(HDRProcessorException e) {
                e.printStackTrace();
                throw new RuntimeException();
            }
            Log.d(TAG, "DRO time: " + (System.currentTimeMillis() - time_s));

            saveBitmap(activity, inputs.get(0), "dro" + output_name);
            checkHistogram(activity, inputs.get(0));
            inputs.get(0).recycle();
            inputs.clear();
        }
        try {
            Thread.sleep(500);
        }
        catch(InterruptedException e) {
            e.printStackTrace();
        }

        return hdrHistogramDetails;
    }

    public static void checkHDROffsets(MainActivity activity, int [] exp_offsets_x, int [] exp_offsets_y) {
        checkHDROffsets(activity, exp_offsets_x, exp_offsets_y, 1);
    }

    /** Checks that the HDR offsets used for auto-alignment are as expected.
     */
    public static void checkHDROffsets(MainActivity activity, int [] exp_offsets_x, int [] exp_offsets_y, int scale) {
        int [] offsets_x = activity.getApplicationInterface().getHDRProcessor().offsets_x;
        int [] offsets_y = activity.getApplicationInterface().getHDRProcessor().offsets_y;
        for(int i=0;i<offsets_x.length;i++) {
            Log.d(TAG, "offsets " + i + " ( " + offsets_x[i]*scale + " , " + offsets_y[i]*scale + " ), expected ( " + exp_offsets_x[i] + " , " + exp_offsets_y[i] + " )");
            // we allow some tolerance as different devices can produce different results (e.g., Nexus 6 vs OnePlus 3T; see testHDR5 on Nexus 6)
            assertTrue(Math.abs(offsets_x[i]*scale - exp_offsets_x[i]) <= 1);
            assertTrue(Math.abs(offsets_y[i]*scale - exp_offsets_y[i]) <= 1);
        }
    }

    public static void checkHistogramDetails(HistogramDetails hdrHistogramDetails, int exp_min_value, int exp_median_value, int exp_max_value) {
        Log.d(TAG, "checkHistogramDetails");
        Log.d(TAG, "compare min value " + hdrHistogramDetails.min_value + " to expected " + exp_min_value);
        Log.d(TAG, "compare median value " + hdrHistogramDetails.median_value + " to expected " + exp_median_value);
        Log.d(TAG, "compare max value " + hdrHistogramDetails.max_value + " to expected " + exp_max_value);
        // we allow some tolerance as different devices can produce different results (e.g., Nexus 6 vs OnePlus 3T; see testHDR18 on Nexus 6 which needs a tolerance of 2)
        // interestingly it's testHDR18 that also needs a higher tolerance for Nokia 8 vs Galaxy S10e
        assertTrue(Math.abs(exp_min_value - hdrHistogramDetails.min_value) <= 3);
        assertTrue(Math.abs(exp_median_value - hdrHistogramDetails.median_value) <= 3);
        assertTrue(Math.abs(exp_max_value - hdrHistogramDetails.max_value) <= 3);
    }

    public interface TestAvgCallback {
        void doneProcessAvg(int index); // called after every call to HDRProcessor.processAvg()
    }

    /** The following testAvgX tests test the Avg noise reduction algorithm on a given set of input images.
     *  By testing on a fixed sample, this makes it easier to finetune the algorithm for quality and performance.
     *  To use these tests, the testdata/ subfolder should be manually copied to the test device in the DCIM/testOpenCamera/
     *  folder (so you have DCIM/testOpenCamera/testdata/). We don't use assets/ as we'd end up with huge APK sizes which takes
     *  time to transfer to the device everytime we run the tests.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static HistogramDetails subTestAvg(MainActivity activity, List<String> inputs, String output_name, int iso, long exposure_time, float zoom_factor, TestAvgCallback cb) {
        Log.d(TAG, "subTestAvg");

        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ) {
            Log.d(TAG, "renderscript requires Android Lollipop or better");
            return null;
        }

        try {
            Thread.sleep(1000); // wait for camera to open
        }
        catch(InterruptedException e) {
            e.printStackTrace();
        }

        /*Bitmap nr_bitmap = getBitmapFromFile(activity, inputs.get(0));
        long time_s = System.currentTimeMillis();
        try {
            for(int i=1;i<inputs.size();i++) {
                Log.d(TAG, "processAvg for image: " + i);
                Bitmap new_bitmap = getBitmapFromFile(activity, inputs.get(i));
                float avg_factor = (float)i;
                activity.getApplicationInterface().getHDRProcessor().processAvg(nr_bitmap, new_bitmap, avg_factor, true);
                // processAvg recycles new_bitmap
                if( cb != null ) {
                    cb.doneProcessAvg(i);
                }
                //break; // test
            }
            //activity.getApplicationInterface().getHDRProcessor().processAvgMulti(inputs, hdr_strength, 4);
        }
        catch(HDRProcessorException e) {
            e.printStackTrace();
            throw new RuntimeException();
        }
        Log.d(TAG, "Avg time: " + (System.currentTimeMillis() - time_s));

        {
            activity.getApplicationInterface().getHDRProcessor().avgBrighten(nr_bitmap);
            Log.d(TAG, "time after brighten: " + (System.currentTimeMillis() - time_s));
        }*/

        Bitmap nr_bitmap;
        try {
            // initialise allocation from first two bitmaps
            //int inSampleSize = activity.getApplicationInterface().getHDRProcessor().getAvgSampleSize(inputs.size());
            int inSampleSize = activity.getApplicationInterface().getHDRProcessor().getAvgSampleSize(iso, exposure_time);
            Bitmap bitmap0 = getBitmapFromFile(activity, inputs.get(0), inSampleSize);
            Bitmap bitmap1 = getBitmapFromFile(activity, inputs.get(1), inSampleSize);
            int width = bitmap0.getWidth();
            int height = bitmap0.getHeight();

            float avg_factor = 1.0f;
            List<Long> times = new ArrayList<>();
            long time_s = System.currentTimeMillis();
            HDRProcessor.AvgData avg_data = activity.getApplicationInterface().getHDRProcessor().processAvg(bitmap0, bitmap1, avg_factor, iso, exposure_time, zoom_factor);
            Allocation allocation = avg_data.allocation_out;
            times.add(System.currentTimeMillis() - time_s);
            // processAvg recycles both bitmaps
            if( cb != null ) {
                cb.doneProcessAvg(1);
            }

            for(int i=2;i<inputs.size();i++) {
                Log.d(TAG, "processAvg for image: " + i);

                Bitmap new_bitmap = getBitmapFromFile(activity, inputs.get(i), inSampleSize);
                avg_factor = (float)i;
                time_s = System.currentTimeMillis();
                activity.getApplicationInterface().getHDRProcessor().updateAvg(avg_data, width, height, new_bitmap, avg_factor, iso, exposure_time, zoom_factor);
                times.add(System.currentTimeMillis() - time_s);
                // updateAvg recycles new_bitmap
                if( cb != null ) {
                    cb.doneProcessAvg(i);
                }
            }

            time_s = System.currentTimeMillis();
            nr_bitmap = activity.getApplicationInterface().getHDRProcessor().avgBrighten(allocation, width, height, iso, exposure_time);
            avg_data.destroy();
            //noinspection UnusedAssignment
            avg_data = null;
            times.add(System.currentTimeMillis() - time_s);

            long total_time = 0;
            Log.d(TAG, "*** times are:");
            for(long time : times) {
                total_time += time;
                Log.d(TAG, "    " + time);
            }
            Log.d(TAG, "    total: " + total_time);
        }
        catch(HDRProcessorException e) {
            e.printStackTrace();
            throw new RuntimeException();
        }

        saveBitmap(activity, nr_bitmap, output_name);
        HistogramDetails hdrHistogramDetails = checkHistogram(activity, nr_bitmap);
        nr_bitmap.recycle();
        System.gc();
        inputs.clear();

        try {
            Thread.sleep(500);
        }
        catch(InterruptedException e) {
            e.printStackTrace();
        }

        return hdrHistogramDetails;
    }
}
