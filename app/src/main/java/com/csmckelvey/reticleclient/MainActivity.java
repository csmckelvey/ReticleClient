package com.csmckelvey.reticleclient;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Vibrator;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class MainActivity extends Activity implements OnItemSelectedListener {
    Vibrator vibe;
    TextView console = null;
    Button clearButton = null;
    Button executeButton = null;
    Spinner commands = null;
    ProgressBar progress = null;

    private boolean inProgress = false;
    private BluetoothServerSocket serverSocket;
    private static final String clientBluetoothAddress = "B8:27:EB:35:72:39";
    private static final String bluetoothCommandServiceUUIDString = "00001200-0000-1000-8000-00805f9b9999";
    private static final String bluetoothResponseServiceUUIDString = "00001200-0000-1000-8000-00805f9b0000";
    private static final UUID bluetoothResponseServiceUUID = UUID.fromString(bluetoothResponseServiceUUIDString);

    public StringBuilder consoleBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        console = (TextView) findViewById(R.id.console);
        console.setText("");

        executeButton = (Button) findViewById(R.id.execute);
        executeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                vibe.vibrate(100);
                doExecute();
            }
        });

        clearButton = (Button) findViewById(R.id.clear);
        clearButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                vibe.vibrate(100);
                console.setText("");
            }
        });

        commands = (Spinner) findViewById(R.id.spinner);
        commands.setOnItemSelectedListener(this);

        List<String> commandList = new ArrayList<String>();
        commandList.add("Bluetooth Test");
        commandList.add("Is Wifi Connected");
        commandList.add("Get Available Wifi");
        commandList.add("Get Wifi Config");
        commandList.add("Simple Network Map");
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, commandList);
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        commands.setAdapter(dataAdapter);

        progress = (ProgressBar) findViewById(R.id.progressBar);
        progress.setVisibility(View.INVISIBLE);

        //Dump any console content that was added by other threads, every 1 second
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() { public void run() { updateGUI(); handler.postDelayed(this, 1000); } }, 1000);

        verifyBluetooth();
    }

    private void doExecute() {
        boolean validRequest = true;
        JSONObject requestObject = null;
        String currentCommand = (String) commands.getSelectedItem();

        try {
            int requestID = ThreadLocalRandom.current().nextInt();
            requestObject = new JSONObject();
            requestObject.put("id",  requestID);

            switch (currentCommand) {
                case "Bluetooth Test":
                    consoleBuffer.append("Executing Bluetooth Test!\n\n");
                    requestObject.put("commandName", "bluetoothTest");
                    requestObject.put("options", "");
                    break;
                case "Is Wifi Connected":
                    consoleBuffer.append("Is Wifi Connected!\n\n");
                    requestObject.put("commandName", "isWifiConnected");
                    requestObject.put("options", "");
                    break;
                case "Get Available Wifi":
                    consoleBuffer.append("Executing Get Available Wifi!\n\n");
                    requestObject.put("commandName", "getAvailableWifi");
                    requestObject.put("options", "");
                    break;
                case "Get Wifi Config":
                    consoleBuffer.append("Executing Get Wifi Config!\n\n");
                    requestObject.put("commandName", "getWifiConfig");
                    requestObject.put("options", "");
                    break;
                case "Simple Network Map":
                    consoleBuffer.append("Executing Simple Network Map!\n\n");
                    requestObject.put("commandName", "simpleNetworkMap");
                    requestObject.put("options", "");
                    break;
                default:
                    validRequest = false;
                    consoleBuffer.append("Unknown Command");
                    break;
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (validRequest) {
            new Thread(new ReticleResponseProcess(requestObject)).start();
        }
    }

    private void verifyBluetooth() {
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (!bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.enable();
        }
    }

    private ParcelUuid findServiceUUIDObject(ParcelUuid[] list, String uuid) {
        ParcelUuid result = null;
        for (int i = 0; i < list.length; i++) {
            if (list[i].getUuid().toString().equals(uuid)) {  result = list[i]; }
        }
        return result;
    }

    public void updateGUI() {
        if (consoleBuffer.length() > 0) {
            console.append(consoleBuffer.toString());
            consoleBuffer.setLength(0);
        }

        if (inProgress) {
            progress.setVisibility(View.VISIBLE);
        }
        else {
            progress.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        String item = parent.getItemAtPosition(position).toString();
    }

    public void onNothingSelected(AdapterView<?> arg0) {}

    private class ReticleResponseProcess implements Runnable {

        private JSONObject requestObject;
        private BluetoothServerSocket serverSocket;

        public ReticleResponseProcess(JSONObject requestObject) {
            this.requestObject = requestObject;
        }

        @Override
        public void run() {
            inProgress = true;
            verifyBluetooth();
            startBluetoothServer();

            BluetoothSocket socket = null;
            while (socket == null) {
                verifyBluetooth();
                sendRequest(requestObject);

                try {
                    socket = serverSocket.accept(10000);
                }
                catch (IOException e) {}
            }

            JSONObject responseObject;
            try {
                responseObject = readResponseFromServer(socket);
                vibe.vibrate(500);

                String message = responseObject.getString("responseText");
                String parsedMessage = message.replaceAll("~~~~", "\n");

                consoleBuffer.append("Received response for Request [" + responseObject.getInt("responseFor") + "]\n");
                consoleBuffer.append("With message:\n\t[" + parsedMessage + "]\n\n");
            } catch (JSONException e) {}

            inProgress = false;
        }

        private void startBluetoothServer() {
            BluetoothAdapter bluetoothAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

            try {
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("ReticleResponse", bluetoothResponseServiceUUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void sendRequest(JSONObject request) {
            BluetoothDevice device = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getRemoteDevice(clientBluetoothAddress);

            if (!device.fetchUuidsWithSdp()) {
                consoleBuffer.append("Failed to find remote UUIDs with SDP - not a good sign for anything bluetooth related\n");
            }

            ParcelUuid serviceUUIDObject = findServiceUUIDObject(device.getUuids(), bluetoothCommandServiceUUIDString);
            if (serviceUUIDObject != null) {
                try {
                    BluetoothSocket clientSocket = device.createInsecureRfcommSocketToServiceRecord(serviceUUIDObject.getUuid());

                    consoleBuffer.append("Connecting\n");
                    clientSocket.connect();

                    consoleBuffer.append("Sending request to Reticle Server\n");
                    OutputStream out = clientSocket.getOutputStream();
                    out.write(request.toString().getBytes());
                    out.flush();
                    out.close();
                    consoleBuffer.append("Request [" + request.getInt("id") + "] sent!\n\n");

                    clientSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }

        private JSONObject readResponseFromServer(BluetoothSocket socket) {
            JSONObject responseObject = null;

            try {
                InputStream in = socket.getInputStream();
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in));

                vibe.vibrate(100);
                consoleBuffer.append("Reading AWK\n");
                bufferedReader.readLine();  //Eats the AWK response
                consoleBuffer.append("Waiting for response ...\n");
                String response = bufferedReader.readLine();

                in.close();
                bufferedReader.close();
                serverSocket.close();

                responseObject = new JSONObject(response);
            }
            catch (JSONException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return responseObject;
        }

    }
}
