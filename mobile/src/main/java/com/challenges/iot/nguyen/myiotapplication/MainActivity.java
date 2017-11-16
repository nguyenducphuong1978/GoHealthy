/*
* GoHealthy mobile application.
* The source is based on the SDK samples for the mqtt publish/subscribe
* https://github.com/awslabs/aws-sdk-android-samples/tree/master/AndroidPubSubWebSocket
*
* GoHealthy app: the mobile send the sensor data (EMF = electromagnetic field data) to the mqtt
* The AWS IoT rule will send the received data to the Kinesis Firehose stream
* A Kinesis Analytics app will do real time analysis on the Firehose stream and detect the unusual EMF data
* The result will be sent to two Kinesis streams and two Lambda functions will  be triggered
* and compose the warning messages and send the message back to the mqtt topic
* The GoHealthy mobile app is subscribed to the mqtt topic and see the warning in real-time.
* The warning messages is about when the user visit a place with overvalue EMF or the user is closed to
* a hidden source that generate unusual high EMF value.
* It is strongly recommend that people should not stay long in places in which EMF values is over a certain value.
* This kind of high levels EMF source can cause a bad effect on people health.
* */


package com.challenges.iot.nguyen.myiotapplication;

import android.graphics.Color;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ToggleButton;
import android.widget.TextView;
import android.view.Menu;
import android.view.MenuItem;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.GeomagneticField;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.UnsupportedEncodingException;
import android.content.Context;
import android.widget.Toast;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import de.nitri.gauge.Gauge;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    static final String LOG_TAG = MainActivity.class.getCanonicalName();

    private TextView magnetSensorvalue;
    private SensorManager sensorManager;
    public static DecimalFormat DECIMAL_FORMATTER;
    private boolean isSensorAccuracyStatus = true;



    // --- Constants to modify per your configuration ---

    // Customer specific IoT endpoint
    // AWS Iot CLI describe-endpoint call returns: XXXXXXXXXX.iot.<region>.amazonaws.com,
    private static final String CUSTOMER_SPECIFIC_ENDPOINT = "a1v7w14ym09nkn.iot.us-east-1.amazonaws.com";

    // Cognito pool ID. For simplicity, pool needs to be unauthenticated pool with
    // AWS IoT permissions.
    private static final String COGNITO_POOL_ID = "us-east-1:cc1b8022-bef8-46db-8318-e1f81b8a0e4f";

    // Region of AWS IoT
    private static final Regions MY_REGION = Regions.US_EAST_1;

    private static final String EMF_TOPIC = "/device/mobile/emf";
    private static final String EMF_ALERT_TOPIC = "/device/mobile/alert";

    private static final double DELAY_SENSOR_READ = 0.1; //read every 1/10 second

    AWSIotMqttManager mqttManager;
    String clientId;

    AWSCredentials awsCredentials;
    CognitoCachingCredentialsProvider credentialsProvider;

    boolean isMqttConnected = false;

    //UI declaration
    TextView tvLastMessage;
    TextView tvClientId;
    TextView tvStatus;
    TextView tvAlertMessage;

    ToggleButton btnConnect;
    Button btnSubscribe;
    Button btnPublish;
    ToggleButton btnDisconnect;
    Gauge gauge;

    long lSensorLastRead = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //init UI: buttons, etc.
        gauge = (Gauge) findViewById(R.id.gaugeEMF);
        tvClientId = (TextView) findViewById(R.id.tvClientId);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvAlertMessage = (TextView) findViewById(R.id.tvAlertMessage);
        btnConnect = (ToggleButton) findViewById(R.id.btnConnect);
        btnConnect.setOnClickListener(connectClick);
        btnConnect.setEnabled(false);
        btnDisconnect = (ToggleButton) findViewById(R.id.btnDisconnect);
        btnDisconnect.setOnClickListener(disconnectClick);
        lSensorLastRead = System.currentTimeMillis();

        //init connection to the AWS MQTT
        initAWSIoT();

        magnetSensorvalue = (TextView) findViewById(R.id.magnetSensorTextView);
        magnetSensorvalue.setText("Initializing IoT Magnetic field sensor...");
        // define decimal formatter
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        DECIMAL_FORMATTER = new DecimalFormat("#.000", symbols);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        double iElapsedSensorReadTime = (double)((System.currentTimeMillis() - lSensorLastRead)/1000);
        //magnetSensorvalue.setText("iElapsedSensorReadTime=" + iElapsedSensorReadTime);
        if(isMqttConnected && isSensorAccuracyStatus){
            if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD && iElapsedSensorReadTime > DELAY_SENSOR_READ) {
                // get values for each axes X,Y,Z
                float magX = event.values[0];
                float magY = event.values[1];
                float magZ = event.values[2];

                double dEMF = Math.sqrt((magX * magX) + (magY * magY) + (magZ * magZ));
                gauge.moveToValue((float)dEMF);
                // Put to MQTT and then set value on the screen
                String message = composeIoTMessage(dEMF);
                putEMFToMqtt(message,EMF_TOPIC);
                lSensorLastRead = System.currentTimeMillis();
                magnetSensorvalue.setText(DECIMAL_FORMATTER.format(dEMF) + " \u00B5Tesla");
            }
        }//only shown EMF value if the mqtt is connected.
    }

    @Override
    protected void onResume() {
        super.onResume();
        // register this class as a listener for the magnetic sensors
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if(accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH  	||
                    accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM){
                //flag to know that the sensor is at high accuracy, else ignore its values
                isSensorAccuracyStatus = true;
            }else{
                isSensorAccuracyStatus = false;
            }
        }//end if
    }


    private String composeIoTMessage(double dEMF){
        //initialization
        String message = "";
        String strDateTime ="";

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        strDateTime = sdf.format(new Date());

        //mobile sensor EMF value
        try{
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("emfValue",dEMF);

            jsonObj.put("deviceID", clientId );
            jsonObj.put("dateTime",strDateTime);
            message = jsonObj.toString() ;
        } catch(JSONException ex) {
            ex.printStackTrace();
        }

        return message;
    }

    private void putEMFToMqtt(String message,String topic){
         try {
            mqttManager.publishString(message, topic, AWSIotMqttQos.QOS0);
         } catch (Exception e) {
        Log.e(LOG_TAG, "Publish error because:.", e);
        }
    }

    private void initAWSIoT(){
        // MQTT client IDs are required to be unique per AWS IoT account.
        // This UUID is "practically unique" but does not _guarantee_
        // uniqueness.
        clientId = UUID.randomUUID().toString();
        tvClientId.setText(clientId);

        // Initialize the AWS Cognito credentials provider
        credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(), // context
                COGNITO_POOL_ID, // Identity Pool ID
                MY_REGION // Region
        );

        Region region = Region.getRegion(MY_REGION);

        // MQTT Client
        mqttManager = new AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT);

        // The following block uses IAM user credentials for authentication with AWS IoT.
        //awsCredentials = new BasicAWSCredentials("ACCESS_KEY_CHANGE_ME", "SECRET_KEY_CHANGE_ME");
        //btnConnect.setEnabled(true);

        // The following block uses a Cognito credentials provider for authentication with AWS IoT.
        new Thread(new Runnable() {
            @Override
            public void run() {
                awsCredentials = credentialsProvider.getCredentials();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnConnect.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void subscribeToAlertEMF(){
        try {
            mqttManager.subscribeToTopic(EMF_ALERT_TOPIC, AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        if(!message.contains("null")) {
                                            tvAlertMessage.setText("Warning");
                                            tvAlertMessage.setTextColor(Color.RED);
                                            Context context = getApplicationContext();
                                            try{
                                                JSONObject jsonObj = new JSONObject(message);
                                                message = jsonObj.getString("alertMessage");
                                                if(message.contains("overvalue")) {
                                                    message = message + " at " + jsonObj.getString("DateTime");
                                                }
                                                int duration = Toast.LENGTH_LONG;
                                                Toast toast = Toast.makeText(context, message, duration);
                                                toast.getView().setBackgroundColor(Color.MAGENTA);
                                                toast.show();
                                            } catch(JSONException ex) {
                                                int duration = Toast.LENGTH_LONG;
                                                message = ex.getMessage();
                                                Toast toast = Toast.makeText(context, message, duration);
                                                toast.getView().setBackgroundColor(Color.MAGENTA);
                                                toast.show();
                                                ex.printStackTrace();
                                            }
                                        }

                                    } catch (UnsupportedEncodingException e) {
                                        Log.e(LOG_TAG, "Message encoding error.", e);
                                    }
                                }
                            });
                        }
                    });
        } catch (Exception e) {
            Log.e(LOG_TAG, "Subscription error.", e);
        }
    }

    // UI Listener implementation
    View.OnClickListener connectClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            Log.d(LOG_TAG, "clientId = " + clientId);

            try {
                mqttManager.connect(credentialsProvider, new AWSIotMqttClientStatusCallback() {
                    @Override
                    public void onStatusChanged(final AWSIotMqttClientStatus status,
                                                final Throwable throwable) {
                        Log.d(LOG_TAG, "Status = " + String.valueOf(status));

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (status == AWSIotMqttClientStatus.Connecting) {
                                    tvStatus.setText("Connecting...");

                                } else if (status == AWSIotMqttClientStatus.Connected) {
                                    tvStatus.setText("Connected");isMqttConnected = true;
                                    btnConnect.setChecked(true);
                                    btnDisconnect.setChecked(false);
                                    subscribeToAlertEMF();

                                } else if (status == AWSIotMqttClientStatus.Reconnecting) {
                                    if (throwable != null) {
                                        Log.e(LOG_TAG, "Connection error.", throwable);
                                    }
                                    tvStatus.setText("Reconnecting");isMqttConnected = false;
                                } else if (status == AWSIotMqttClientStatus.ConnectionLost) {
                                    if (throwable != null) {
                                        Log.e(LOG_TAG, "Connection error.", throwable);
                                        throwable.printStackTrace();
                                    }
                                    tvStatus.setText("Disconnected"); isMqttConnected = false;
                                } else {
                                    tvStatus.setText("Disconnected");

                                }
                            }
                        });
                    }
                });
            } catch (final Exception e) {
                Log.e(LOG_TAG, "Connection error.", e);
                tvStatus.setText("Error! " + e.getMessage());
                isMqttConnected = false;
            }
        }
    }; //End Connect button

    View.OnClickListener disconnectClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            try {
                mqttManager.disconnect(); isMqttConnected = false;
                btnConnect.setChecked(false);
                btnDisconnect.setChecked(true);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Disconnect error.", e);
            }

        }
    }; //End Disconnect button

    // End UI Listener implementation

} //end class implementation
