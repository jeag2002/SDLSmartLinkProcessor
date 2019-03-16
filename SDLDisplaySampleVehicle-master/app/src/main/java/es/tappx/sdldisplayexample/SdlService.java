package es.tappx.sdldisplayexample;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.permission.PermissionElement;
import com.smartdevicelink.managers.permission.PermissionStatus;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.DisplayCapabilities;
import com.smartdevicelink.proxy.rpc.GetVehicleData;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.OnVehicleData;
import com.smartdevicelink.proxy.rpc.SubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.UnsubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;


import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public class SdlService extends Service {

	private static final String TAG 					= "SDL Service";

	private static final String APP_NAME 				= "SDL Display";
	private static final String APP_ID 					= "8678309";

	private static final String ICON_FILENAME 			= "hello_sdl_icon.png";

	public static final String BROADCAST_ACTION = "es.tappx.sdldisplayexample.event";


	private static final int FOREGROUND_SERVICE_ID = 111;

	// TCP/IP transport config
	// The default port is 12345
	// The IP is of the machine that is running SDL Core
	//private static final int TCP_PORT = 12345;
	//private static final String DEV_MACHINE_IP_ADDRESS = "192.168.0.1";

	private static final int TCP_PORT = 11830;
	private static final String DEV_MACHINE_IP_ADDRESS = "m.sdl.tools";

	private static final String TOPIC = "foo/bar";
	private static final String URL_DATA = "tcp://localhost:1883";
	//private static final String URL_DATA = "tcp://broker.hivemq.com:1883";
	//private static final String URL_DATA = "mqtt://broker.hivemq.com:1883";

	// variable to create and call functions of the SyncProxy
	private SdlManager sdlManager = null;

	private final Handler handler = new Handler();
	private Intent intent;
	private Integer rpm;

	private MqttClient client;




	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate");
		super.onCreate();

		rpm = 0;
		intent = new Intent(BROADCAST_ACTION);
		createMQTTConnection();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			enterForeground();
		}
	}


	private void createMQTTConnection(){
		try {
			//String clientId = MqttClient.generateClientId();
			//client = new MqttAndroidClient(this.getApplicationContext(),URL_DATA, clientId);

			client = new MqttClient("tcp://10.0.2.2:1883", "androidClient", new MemoryPersistence());
			MqttConnectOptions options = new MqttConnectOptions();
			options.setCleanSession(true);
			client.connect(options);
			Log.i("MQTT", "MQTT CLIENT CONFIGURED SUCCESSFULLY");

		}catch(Exception e){
			Log.i("MQTT Connection", "Something happened " + e.getMessage());
		}
	}


	// Helper method to let the service enter foreground mode
	@SuppressLint("NewApi")
	public void enterForeground() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT);
			NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
				Notification serviceNotification = new Notification.Builder(this, channel.getId())
						.setContentTitle("Connected through SDL")
						.setSmallIcon(R.drawable.ic_sdl)
						.build();
				startForeground(FOREGROUND_SERVICE_ID, serviceNotification);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		handler.removeCallbacks(sendUpdatesToUI);
		handler.postDelayed(sendUpdatesToUI, 1000); // 1 second

		startProxy();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			stopForeground(true);
		}

		if (sdlManager != null) {
			sdlManager.dispose();
		}

		handler.removeCallbacks(sendUpdatesToUI);

		super.onDestroy();
	}

	private void startProxy() {

		if (sdlManager == null) {
			Log.i(TAG, "Starting SDL Proxy");

			BaseTransportConfig transport = null;
			if (BuildConfig.TRANSPORT.equals("MULTI")) {
				int securityLevel;
				if (BuildConfig.SECURITY.equals("HIGH")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
				} else if (BuildConfig.SECURITY.equals("MED")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
				} else if (BuildConfig.SECURITY.equals("LOW")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
				} else {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
				}
				transport = new MultiplexTransportConfig(this, APP_ID, securityLevel);
			} else if (BuildConfig.TRANSPORT.equals("TCP")) {
				transport = new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true);
			} else if (BuildConfig.TRANSPORT.equals("MULTI_HB")) {
				MultiplexTransportConfig mtc = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
				mtc.setRequiresHighBandwidth(true);
				transport = mtc;
			}

			// The app type to be used
			final Vector<AppHMIType> appType = new Vector<>();
			appType.add(AppHMIType.MEDIA);


			// The manager listener helps you know when certain events that pertain to the SDL Manager happen
			// Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
			SdlManagerListener listener = new SdlManagerListener() {
                @Override
				public void onStart() {
					// HMI Status Listener
					sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
						@Override
						public void onNotified(RPCNotification notification) {

							OnHMIStatus status = (OnHMIStatus) notification;
							if (status.getHmiLevel() == HMILevel.HMI_FULL && ((OnHMIStatus) notification).getFirstRun()) {
								checkTemplateType();
								checkPermission();
                                setDisplayDefault();
							}
						}
					});


                    sdlManager.addOnRPCNotificationListener(FunctionID.ON_VEHICLE_DATA, new OnRPCNotificationListener() {
                        @Override
                        public void onNotified(RPCNotification notification) {
                            OnVehicleData onVehicleDataNotification = (OnVehicleData) notification;

                            sdlManager.getScreenManager().beginTransaction();

                            SdlArtwork artwork = null;

                            rpm = onVehicleDataNotification.getRpm();
                            Log.i("SdlService ", "RPM " + rpm.toString());
                            if (rpm != null){
                            	sdlManager.getScreenManager().setTextField1("RPM: " + onVehicleDataNotification.getRpm());
							}else{
                            	rpm = onVehicleDataNotification.getRpm();
							}

                            sdlManager.getScreenManager().commit(new CompletionListener() {
                                @Override
                                public void onComplete(boolean success) {
                                    if (success) {
                                        Log.i("SdlService", "change successful");
                                    }
                                }
                            });
                        }
                    });
				}

				@Override
				public void onDestroy() {

                	UnsubscribeVehicleData unsubscribeRequest = new UnsubscribeVehicleData();
                	unsubscribeRequest.setRpm(true);

					unsubscribeRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
						@Override
						public void onResponse(int correlationId, RPCResponse response) {
							if(response.getSuccess()){
								Log.i("SdlService", "Successfully unsubscribed to vehicle data.");
							}else{
								Log.i("SdlService", "Request to unsubscribe to vehicle data was rejected.");
							}
						}
					});
					sdlManager.sendRPC(unsubscribeRequest);

					SdlService.this.stopSelf();
				}

				@Override
				public void onError(String info, Exception e) {
				}
			};

			// Create App Icon, this is set in the SdlManager builder
			SdlArtwork appIcon = new SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true);

			// The manager builder sets options for your session
			SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
			builder.setAppTypes(appType);
			builder.setTransportType(transport);
			builder.setAppIcon(appIcon);
			sdlManager = builder.build();
			sdlManager.start();


		}
	}


	private void checkTemplateType(){

		Object result = sdlManager.getSystemCapabilityManager().getCapability(SystemCapabilityType.DISPLAY);
		if( result instanceof DisplayCapabilities){
			List<String> templates = ((DisplayCapabilities) result).getTemplatesAvailable();

			Log.i("Templete", templates.toString());

		}
	}

    private void checkPermission(){
        List<PermissionElement> permissionElements = new ArrayList<>();

        List<String> keys = new ArrayList<>();
        keys.add(GetVehicleData.KEY_RPM);
        permissionElements.add(new PermissionElement(FunctionID.GET_VEHICLE_DATA, keys));

        Map<FunctionID, PermissionStatus> status = sdlManager.getPermissionManager().getStatusOfPermissions(permissionElements);
        Log.i("Permission", "Allowed:" + status.get(FunctionID.GET_VEHICLE_DATA).getIsRPCAllowed());
        Log.i("Permission", "KEY_RPM　Allowed:" + status.get(FunctionID.GET_VEHICLE_DATA).getAllowedParameters().get(GetVehicleData.KEY_RPM));

    }


	private void setDisplayDefault(){

        sdlManager.getScreenManager().beginTransaction();
        sdlManager.getScreenManager().setTextField1("RPM : None");
        SdlArtwork artwork = new SdlArtwork("sample01.png", FileType.GRAPHIC_PNG, R.drawable.sample01, true);

        sdlManager.getScreenManager().setPrimaryGraphic(artwork);
        sdlManager.getScreenManager().commit(new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                if (success) {
                    SubscribeVehicleData subscribeRequest = new SubscribeVehicleData();
                    subscribeRequest.setRpm(true);
                    subscribeRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
                        @Override
                        public void onResponse(int correlationId, RPCResponse response) {
                            if (response.getSuccess()) {
                                Log.i("SdlService", "Successfully subscribed to vehicle data.");
                            } else {
                                Log.i("SdlService", "Request to subscribe to vehicle data was rejected.");
                            }
                        }
                    });
                    sdlManager.sendRPC(subscribeRequest);

                }
            }
		});
	}

	private Runnable sendUpdatesToUI = new Runnable() {
		public void run() {
			int percentaje = rpm * 100 / 20000;
			intent.putExtra("RPM", String.valueOf(percentaje));

			publishMessage("RPM: " + String.valueOf(percentaje) + "%");

			Log.i("SdlService","send RPM to UI");
			handler.postDelayed(this, 5000); // 5 seconds
			sendBroadcast(intent);
		}
	};


	public void publishMessage(String data){

		byte[] encodedPayload = new byte[0];

		try{
			encodedPayload = data.getBytes("UTF-8");
			MqttMessage message = new MqttMessage(encodedPayload);
			client.publish(TOPIC,message);
			Log.i("MQTT", "message (" + data + ") sent");
		}catch (Exception e){
			Log.i("MQTT", "Something happened " + e.getMessage());
		}

	}

}
