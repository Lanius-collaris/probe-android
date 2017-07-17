package org.openobservatory.ooniprobe.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.v4.os.AsyncTaskCompat;
import android.util.Log;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Observable;

import org.json.JSONException;
import org.json.JSONObject;
import org.openobservatory.measurement_kit.android.DnsUtils;
import org.openobservatory.measurement_kit.common.LogSeverity;
import org.openobservatory.measurement_kit.nettests.*;
import org.openobservatory.ooniprobe.BuildConfig;
import org.openobservatory.ooniprobe.activity.MainActivity;
import org.openobservatory.ooniprobe.model.NetworkMeasurement;
import org.openobservatory.ooniprobe.model.OONITests;
import org.openobservatory.ooniprobe.model.UnknownTest;
import org.openobservatory.ooniprobe.utils.NotificationHandler;

public class TestData extends Observable {
    private static final String TAG = "TestData";
    private static TestData instance;
    public static ArrayList<NetworkMeasurement> runningTests;
    public static ArrayList<NetworkMeasurement> finishedTests;
    public static LinkedHashMap<String, Boolean> availableTests;
    public static MainActivity activity;
    public static Context context;

    public static TestData getInstance(final Context c, final MainActivity a) {
        if (instance == null) {
            context = c;
            activity = a;
            instance = new TestData();
            runningTests = new ArrayList<>();
            finishedTests = TestStorage.loadTests(context);
            availableTests = new LinkedHashMap<>();
            availableTests.put(OONITests.WEB_CONNECTIVITY, true);
            availableTests.put(OONITests.HTTP_INVALID_REQUEST_LINE, true);
            availableTests.put(OONITests.HTTP_HEADER_FIELD_MANIPULATION, true);
            availableTests.put(OONITests.NDT_TEST, true);
        }
        else if (activity == null && a != null){
            activity = a;
        }
        return instance;
    }

    public static void doNetworkMeasurements(final Context ctx, final String testName) {
        final String inputPath = ctx.getFilesDir() + "/hosts.txt";
        final String inputUrlsPath = ctx.getFilesDir() + "/global.txt";

        final NetworkMeasurement currentTest = new NetworkMeasurement(testName);
        final String outputPath = ctx.getFilesDir() + "/"  + currentTest.json_file;
        final String logPath = ctx.getFilesDir() + "/"  + currentTest.log_file;

        final String geoip_asn = ctx.getFilesDir() + "/GeoIPASNum.dat";
        final String geoip_country = ctx.getFilesDir() + "/GeoIP.dat";

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(ctx);

        final Boolean include_ip = preferences.getBoolean("include_ip", false);
        final Boolean include_asn = preferences.getBoolean("include_asn", false);
        final Boolean include_cc = preferences.getBoolean("include_cc", true);
        final Boolean upload_results = preferences.getBoolean("upload_results", true);
        final String collector_address = preferences.getString("collector_address", OONITests.COLLECTOR_ADDRESS);
        final String max_runtime = preferences.getString("max_runtime", OONITests.MAX_RUNTIME);

        TestStorage.addTest(ctx, currentTest);
        runningTests.add(currentTest);
        availableTests.put(testName, false);
        if (activity != null) TestData.getInstance(context, activity).notifyObservers();

        final String nameserver = DnsUtils.get_device_dns();
        Log.v(TAG, "nameserver: " + nameserver);

        Log.v(TAG, "doNetworkMeasurements " + testName + "...");

        /*
        Using AsyncTask  may not be the optimal solution since OONI tests could take a long time to complete
        For more info read: http://developer.android.com/reference/android/os/AsyncTask.html
        https://developer.android.com/reference/android/support/v4/os/AsyncTaskCompat.html
        */
        AsyncTaskCompat.executeParallel(
                new AsyncTask<String, String, Boolean>(){
                    @Override
                    protected Boolean doInBackground(String... params)
                    {
                        try
                        {
                            Log.v(TAG, "running test...");
                            if (testName.compareTo(OONITests.DNS_INJECTION) == 0) {
                                DnsInjectionTest w = new DnsInjectionTest();
                                Log.v(TAG, "running dns_injection test...");
                                w.use_logcat();
                                w.set_input_filepath(inputPath);
                                w.set_output_filepath(outputPath);
                                w.set_error_filepath(logPath);
                                w.set_verbosity(LogSeverity.LOG_INFO);
                                w.set_options("geoip_country_path", geoip_country);
                                w.set_options("geoip_asn_path", geoip_asn);
                                w.set_options("save_real_probe_ip", boolToString(include_ip));
                                w.set_options("save_real_probe_asn", boolToString(include_asn));
                                w.set_options("save_real_probe_cc", boolToString(include_cc));
                                w.set_options("no_collector", boolToString(!upload_results));
                                w.set_options("collector_base_url", collector_address);
                                w.run();
                            } else if (testName.compareTo(OONITests.HTTP_INVALID_REQUEST_LINE) == 0) {
                                Log.v(TAG, "running http_invalid_request_line test...");
                                new HttpInvalidRequestLineTest()
                                        .use_logcat()
                                        .set_output_filepath(outputPath)
                                        .set_error_filepath(logPath)
                                        .set_verbosity(LogSeverity.LOG_INFO)
                                        .set_options("geoip_country_path", geoip_country)
                                        .set_options("geoip_asn_path", geoip_asn)
                                        .set_options("save_real_probe_ip", boolToString(include_ip))
                                        .set_options("save_real_probe_asn", boolToString(include_asn))
                                        .set_options("save_real_probe_cc", boolToString(include_cc))
                                        .set_options("no_collector", boolToString(!upload_results))
                                        .set_options("collector_base_url", collector_address)
                                        .set_options("software_name", "ooniprobe-android")
                                        .set_options("software_version", BuildConfig.VERSION_NAME)
                                        .on_progress(new org.openobservatory.measurement_kit.nettests.ProgressCallback() {
                                            @Override
                                            public void callback(double percent, String msg) {
                                                currentTest.progress = (int)(percent*100);
                                                if (activity != null){
                                                    activity.runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            TestData.getInstance(context, activity).notifyObservers();
                                                        }
                                                    });
                                                }
                                            }
                                        })
                                        .on_entry(new org.openobservatory.measurement_kit.nettests.EntryCallback() {
                                            @Override
                                            public void callback(String entry) {
                                                setAnomaly_hirl(entry, currentTest);
                                            }
                                        })
                                        .run();
                            }
                            else if (testName.compareTo(OONITests.HTTP_HEADER_FIELD_MANIPULATION) == 0) {
                                Log.v(TAG, "running http_header_field_manipulation test...");
                                new HttpHeaderFieldManipulationTest()
                                        .use_logcat()
                                        .set_output_filepath(outputPath)
                                        .set_error_filepath(logPath)
                                        .set_verbosity(LogSeverity.LOG_INFO)
                                        .set_options("geoip_country_path", geoip_country)
                                        .set_options("geoip_asn_path", geoip_asn)
                                        .set_options("save_real_probe_ip", boolToString(include_ip))
                                        .set_options("save_real_probe_asn", boolToString(include_asn))
                                        .set_options("save_real_probe_cc", boolToString(include_cc))
                                        .set_options("no_collector", boolToString(!upload_results))
                                        .set_options("collector_base_url", collector_address)
                                        .set_options("software_name", "ooniprobe-android")
                                        .set_options("software_version", BuildConfig.VERSION_NAME)
                                        .on_progress(new org.openobservatory.measurement_kit.nettests.ProgressCallback() {
                                            @Override
                                            public void callback(double percent, String msg) {
                                                currentTest.progress = (int)(percent*100);
                                                if (activity != null){
                                                    activity.runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            TestData.getInstance(context, activity).notifyObservers();
                                                        }
                                                    });
                                                }
                                            }
                                        })
                                        .on_entry(new org.openobservatory.measurement_kit.nettests.EntryCallback() {
                                            @Override
                                            public void callback(String entry) {
                                                setAnomaly_hhfm(entry, currentTest);
                                            }
                                        })
                                        .run();
                            }
                            else if (testName.compareTo(OONITests.TCP_CONNECT) == 0) {
                                Log.v(TAG, "running tcp-connect test...");
                                TcpConnectTest w = new TcpConnectTest();
                                w.use_logcat();
                                w.set_input_filepath(inputPath);
                                w.set_output_filepath(outputPath);
                                w.set_error_filepath(logPath);
                                w.set_verbosity(LogSeverity.LOG_INFO);
                                w.set_options("geoip_country_path", geoip_country);
                                w.set_options("geoip_asn_path", geoip_asn);
                                w.set_options("save_real_probe_ip", boolToString(include_ip));
                                w.set_options("save_real_probe_asn", boolToString(include_asn));
                                w.set_options("save_real_probe_cc", boolToString(include_cc));
                                w.set_options("no_collector", boolToString(!upload_results));
                                w.set_options("collector_base_url", collector_address);
                                w.run();
                            }
                            else if (testName.compareTo(OONITests.WEB_CONNECTIVITY) == 0) {
                                Log.v(TAG, "running web-connectivity test...");
                                new WebConnectivityTest()
                                        .use_logcat()
                                        .set_input_filepath(inputUrlsPath)
                                        .set_output_filepath(outputPath)
                                        .set_error_filepath(logPath)
                                        .set_verbosity(LogSeverity.LOG_INFO)
                                        .set_options("geoip_country_path", geoip_country)
                                        .set_options("geoip_asn_path", geoip_asn)
                                        .set_options("save_real_probe_ip", boolToString(include_ip))
                                        .set_options("save_real_probe_asn", boolToString(include_asn))
                                        .set_options("save_real_probe_cc", boolToString(include_cc))
                                        .set_options("no_collector", boolToString(!upload_results))
                                        .set_options("collector_base_url", collector_address)
                                        .set_options("max_runtime", max_runtime)
                                        .set_options("software_name", "ooniprobe-android")
                                        .set_options("software_version", BuildConfig.VERSION_NAME)
                                        .on_progress(new org.openobservatory.measurement_kit.nettests.ProgressCallback() {
                                            @Override
                                            public void callback(double percent, String msg) {
                                                currentTest.progress = (int)(percent*100);
                                                if (activity != null){
                                                    activity.runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            TestData.getInstance(context, activity).notifyObservers();
                                                        }
                                                    });
                                                }
                                            }
                                        })
                                        .on_entry(new org.openobservatory.measurement_kit.nettests.EntryCallback() {
                                            @Override
                                            public void callback(String entry) {
                                                setAnomaly_wc(entry, currentTest);
                                            }
                                        })
                                        .run();
                            }
                            else if (testName.compareTo(OONITests.NDT_TEST) == 0) {
                                Log.v(TAG, "running ndt test...");
                                new NdtTest()
                                        .use_logcat()
                                        .set_input_filepath(inputPath)
                                        .set_output_filepath(outputPath)
                                        .set_error_filepath(logPath)
                                        .set_verbosity(LogSeverity.LOG_INFO)
                                        .set_options("geoip_country_path", geoip_country)
                                        .set_options("geoip_asn_path", geoip_asn)
                                        .set_options("save_real_probe_ip", boolToString(include_ip))
                                        .set_options("save_real_probe_asn", boolToString(include_asn))
                                        .set_options("save_real_probe_cc", boolToString(include_cc))
                                        .set_options("no_collector", boolToString(!upload_results))
                                        .set_options("collector_base_url", collector_address)
                                        .set_options("software_name", "ooniprobe-android")
                                        .set_options("software_version", BuildConfig.VERSION_NAME)
                                        .on_progress(new org.openobservatory.measurement_kit.nettests.ProgressCallback() {
                                            @Override
                                            public void callback(double percent, String msg) {
                                                currentTest.progress = (int)(percent*100);
                                                if (activity != null){
                                                    activity.runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            TestData.getInstance(context, activity).notifyObservers();
                                                        }
                                                    });
                                                }
                                            }
                                        })
                                        .on_entry(new org.openobservatory.measurement_kit.nettests.EntryCallback() {
                                            @Override
                                            public void callback(String entry) {
                                                setAnomaly_ndt(entry, currentTest);
                                            }
                                        })
                                        .run();
                            } else {
                                throw new UnknownTest(testName);
                            }
                            Log.v(TAG, "running test... done");
                        }
                        catch (Exception e){
                            e.printStackTrace();
                        }
                        return false;
                    }

                    protected void onProgressUpdate(String... values) {
                        /* Nothing */
                    }

                    protected void onPostExecute(Boolean success) {
                        TestStorage.setCompleted(ctx, currentTest);
                        currentTest.entry = true;
                        runningTests.remove(currentTest);
                        finishedTests.add(currentTest);
                        availableTests.put(testName, true);
                        if (activity != null) TestData.getInstance(context, activity).notifyObservers(testName);
                        NotificationHandler.notifyTestEnded(ctx, testName);
                        Log.v(TAG, "doNetworkMeasurements " + testName + "... done");
                    }
                }
        );
    }

    public static void setAnomaly_wc(String entry, NetworkMeasurement test){
        if(!test.entry) {
            TestStorage.setEntry(context, test);
            test.entry = true;
        }
        try {
            int anomaly = 0;
            JSONObject jsonObj = new JSONObject(entry);
            JSONObject test_keys = jsonObj.getJSONObject("test_keys");
            Object blocking = test_keys.get("blocking");
            if(blocking instanceof String)
                anomaly = 2;
            else if(blocking instanceof Boolean)
                anomaly = 0;
            else
                anomaly = 1;
            if (test.anomaly < anomaly) {
                test.anomaly = anomaly;
                TestStorage.setAnomaly(context, test.test_id, anomaly);
            }
        } catch (JSONException e) {
        }
    }

    //TODO document this functions
    //TODO rewrite these functions to avoid repeating so much code
    public static void setAnomaly_hirl(String entry, NetworkMeasurement test){
        if(!test.entry) {
            TestStorage.setEntry(context, test);
            test.entry = true;
        }
        try {
            int anomaly = 0;
            JSONObject jsonObj = new JSONObject(entry);
            JSONObject test_keys = jsonObj.getJSONObject("test_keys");
            if (test_keys.has("tampering")) {
                Boolean tampering = test_keys.getBoolean("tampering");
                if (tampering == null)
                    anomaly = 1;
                else if (tampering == true)
                    anomaly = 2;
            }
            if (test.anomaly < anomaly) {
                test.anomaly = anomaly;
                TestStorage.setAnomaly(context, test.test_id, anomaly);
            }
        } catch (JSONException e) {
        }
    }

    public static void setAnomaly_hhfm(String entry, NetworkMeasurement test){
        if(!test.entry) {
            TestStorage.setEntry(context, test);
            test.entry = true;
        }
        try {
            int anomaly = 0;
            JSONObject jsonObj = new JSONObject(entry);
            JSONObject test_keys = jsonObj.getJSONObject("test_keys");
            Object failure = test_keys.get("failure");
            System.out.println("failure " + failure);
            System.out.println("test_keys " + test_keys);

            if(failure == null)
                anomaly = 1;
            else {
                JSONObject tampering = test_keys.getJSONObject("tampering");
                System.out.println("tampering " + tampering);

                Boolean header_field_name = false;
                Boolean header_field_number = false;
                Boolean header_field_value = false;
                Boolean header_name_capitalization = false;
                Boolean request_line_capitalization = false;
                Boolean total = false;

                if (test_keys.has("header_field_name"))
                    header_field_name = tampering.getBoolean("header_field_name");
                if (test_keys.has("header_field_number"))
                    header_field_number = tampering.getBoolean("header_field_number");
                if (test_keys.has("header_field_value"))
                    header_field_value = tampering.getBoolean("header_field_value");
                if (test_keys.has("header_name_capitalization"))
                    header_name_capitalization = tampering.getBoolean("header_name_capitalization");
                if (test_keys.has("request_line_capitalization"))
                    request_line_capitalization = tampering.getBoolean("request_line_capitalization");
                if (test_keys.has("total"))
                    total = tampering.getBoolean("total");

                if (header_field_name || header_field_number || header_field_value || header_name_capitalization || request_line_capitalization || total)
                    anomaly = 2;
            }
            System.out.println("anomaly "+ anomaly);
            if (test.anomaly < anomaly) {
                test.anomaly = anomaly;
                TestStorage.setAnomaly(context, test.test_id, anomaly);
            }
        } catch (JSONException e) {
            System.out.println("JSONException "+ e);
        }
    }

    public static void setAnomaly_ndt(String entry, NetworkMeasurement test){
        if(!test.entry) {
            TestStorage.setEntry(context, test);
            test.entry = true;
        }
        try {
            int anomaly = 0;
            JSONObject jsonObj = new JSONObject(entry);
            JSONObject test_keys = jsonObj.getJSONObject("test_keys");
            if (test_keys.has("failure")) {
                Object failure = test_keys.get("failure");
                if (failure == null)
                    anomaly = 1;
            }
            if (test.anomaly < anomaly) {
                test.anomaly = anomaly;
                TestStorage.setAnomaly(context, test.test_id, anomaly);
            }
        } catch (JSONException e) {
        }
    }

    @Override
    public void notifyObservers(Object type) {
        setChanged(); // Set the changed flag to true, otherwise observers won't be notified.
        super.notifyObservers(type);
    }

    public NetworkMeasurement getTestWithName(String name) {
        for (int j = 0; j < TestData.getInstance(context, activity).runningTests.size(); j++) {
            NetworkMeasurement current = TestData.getInstance(context, activity).runningTests.get(j);
            if (current.testName.equals(name)) return current;
        }
    return null;
    }

    public void removeTest(NetworkMeasurement test) {
        if (finishedTests != null){
            for(int i = 0; i < finishedTests.size(); i++) {
                NetworkMeasurement n = finishedTests.get(i);
                if (n.test_id == test.test_id) {
                    finishedTests.remove(i);
                    break;
                }
            }
        }
    }

    private static String boolToString(Boolean b) {
        return b ? "1" : "0";
    }
}
