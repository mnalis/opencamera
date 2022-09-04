package net.sourceforge.opencamera;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

/** Helper class for testing. This method should not include any code specific to any test framework
 *  (e.g., shouldn't be specific to ActivityInstrumentationTestCase2).
 */
public class TestUtils {
    private static final String TAG = "TestUtils";

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

}
