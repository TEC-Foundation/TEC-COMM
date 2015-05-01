package org.tec_hub.tecuniversalcomm.connection;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;

import com.google.common.base.Preconditions;

import org.tec_hub.tecuniversalcomm.TerminalActivity;
import org.tec_hub.tecuniversalcomm.intents.BluetoothReceiveIntent;
import org.tec_hub.tecuniversalcomm.intents.TECIntent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Created by Nick Mosher on 4/23/15.
 */
public class BluetoothConnectionService extends Service {

    private static boolean launched = false;

    public void onCreate() {
        launched = true;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TECIntent.ACTION_BLUETOOTH_CONNECT);
        intentFilter.addAction(TECIntent.ACTION_BLUETOOTH_DISCONNECT);
        intentFilter.addAction(TECIntent.ACTION_BLUETOOTH_SEND_DATA);

        LocalBroadcastManager.getInstance(this).registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final BluetoothConnection bluetoothConnection = intent.getParcelableExtra(TECIntent.BLUETOOTH_CONNECTION_DATA);
                Preconditions.checkNotNull(bluetoothConnection);

                switch(intent.getAction()) {

                    //Received action to establish connection
                    case TECIntent.ACTION_BLUETOOTH_CONNECT:
                        System.out.println("Service -> Connecting...");
                        //Create callbacks for successful connection and disconnection
                        bluetoothConnection.putOnStatusChangedListener(
                                BluetoothConnectionService.this,
                                new Connection.OnStatusChangedListener() {

                                    //Thread to run an input reading loop
                                    ReceiveInputThread receiveInputThread;

                                    @Override
                                    public void onConnect() {
                                        System.out.println("Service -> onConnect");
                                        if (receiveInputThread != null) {
                                            receiveInputThread.interrupt();
                                        }
                                        receiveInputThread = new ReceiveInputThread(bluetoothConnection);
                                        receiveInputThread.start();
                                    }

                                    @Override
                                    public void onDisconnect() {
                                        System.out.println("Service -> onDisconnect");
                                        if(receiveInputThread != null) {
                                            receiveInputThread.interrupt();
                                        }
                                        receiveInputThread = null;
                                    }
                                });
                        //Initiate connecting
                        new BluetoothConnectTask(bluetoothConnection).execute();
                        break;

                    //Received action to disconnect
                    case TECIntent.ACTION_BLUETOOTH_DISCONNECT:
                        System.out.println("Service -> Disconnecting...");
                        //Initiate disconnecting
                        new BluetoothDisconnectTask(bluetoothConnection).execute();
                        break;

                    //Received intent with data to send
                    case TECIntent.ACTION_BLUETOOTH_SEND_DATA:
                        //System.out.println("Service -> Sending Data...");
                        String sendData = intent.getStringExtra(TECIntent.BLUETOOTH_SEND_DATA);
                        sendBluetoothData(bluetoothConnection, sendData);
                        break;

                    default:
                }
            }
        }, intentFilter);

        return Service.START_STICKY;
    }

    public void onDestroy() {
        launched = false;
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isLaunched() {
        return launched;
    }

    private void sendBluetoothData(BluetoothConnection connection, String data) {
        Preconditions.checkNotNull(connection);
        Preconditions.checkNotNull(data);

        if(!data.equals("")) {
            if(connection.isConnected()) {
                try {
                    connection.getOutputStream().write(data.getBytes());
                } catch(IOException e) {
                    e.printStackTrace();
                }
            } else {
                System.out.println("Connection is not connected!");
            }
        } else {
            System.out.println("Data to send is blank!");
        }
    }

    private class BluetoothConnectTask extends AsyncTask<Void, Void, Boolean> {

        private BluetoothConnection mConnection;
        private BluetoothAdapter mBluetoothAdapter;
        private BluetoothSocket mBluetoothSocket;

        public BluetoothConnectTask(BluetoothConnection connection) {
            mConnection = Preconditions.checkNotNull(connection);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        protected Boolean doInBackground(Void... params) {
            //Check if BT is enabled
            if (!mBluetoothAdapter.isEnabled()) {
                System.out.println("Bluetooth not enabled!"); //TODO better handling.
                throw new IllegalStateException("Cannot connect, Bluetooth is disabled!");
            }

            //Define a BluetoothDevice with the address from our Connection.
            String address = mConnection.getAddress();
            BluetoothDevice device;
            if(address != null  && BluetoothAdapter.checkBluetoothAddress(address)) {
                device = mBluetoothAdapter.getRemoteDevice(address);
            } else {
                throw new IllegalStateException("Error connecting to bluetooth! Problem with address.");
            }

            //Try to retrieve a BluetoothSocket from the BluetoothDevice.
            try {
                mBluetoothSocket = device.createRfcommSocketToServiceRecord(BluetoothConnection.BLUETOOTH_SERIAL_UUID);
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalStateException("Error retrieving bluetooth socket!");
            }

            //Shouldn't need to be discovering at this point.
            mBluetoothAdapter.cancelDiscovery();

            //Attempt to connect to the bluetooth device and receive a BluetoothSocket
            try {
                mBluetoothSocket.connect();
                mConnection.setBluetoothSocket(mBluetoothSocket);
            } catch (IOException e) {
                e.printStackTrace();
                try {
                    mBluetoothSocket.close();
                } catch (IOException e2) {
                    e2.printStackTrace();
                    throw new IllegalStateException("Error closing BT socket after error connecting!");
                }
                throw new IllegalStateException("Error connecting to BT socket!");
            }

            //If we've made it this far, must have been a success.
            return true;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            mConnection.notifyConnected();
        }
    }

    private class BluetoothDisconnectTask extends AsyncTask<Void, Void, Void> {

        private BluetoothConnection mConnection;

        public BluetoothDisconnectTask(BluetoothConnection connection) {
            mConnection = Preconditions.checkNotNull(connection);
        }

        @Override
        protected Void doInBackground(Void... params) {
            if(mConnection.isConnected()) {
                try {
                    mConnection.getBluetoothSocket().close();
                } catch(IOException e) {
                    e.printStackTrace();
                    throw new IllegalStateException("Error closing BT socket at disconnect!");
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void param) {
            super.onPostExecute(param);
            if(!mConnection.isConnected()) {
                mConnection.notifyDisconnected();
            }
        }
    }

    private class ReceiveInputThread extends Thread {

        private BluetoothConnection mConnection;
        private boolean isRunning;

        public ReceiveInputThread(BluetoothConnection connection) {
            mConnection = Preconditions.checkNotNull(connection);
            isRunning = true;
        }

        @Override
        public void run() {
            super.run();
            String line = "";
            BufferedReader bufferedReader = null;
            while(mConnection.isConnected() && isRunning) {
                try {
                    if(bufferedReader == null) {
                        InputStream inputStream = Preconditions.checkNotNull(mConnection.getInputStream());
                        bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                    }
                    line = bufferedReader.readLine();

                } catch(IOException e) {
                    e.printStackTrace();
                }
                System.out.println(line);
                BluetoothReceiveIntent receivedInputIntent = new BluetoothReceiveIntent(BluetoothConnectionService.this, TerminalActivity.class, line);
                LocalBroadcastManager.getInstance(BluetoothConnectionService.this).sendBroadcast(receivedInputIntent);

                try {
                    Thread.sleep(50);
                } catch(InterruptedException e) {
                    e.printStackTrace();
                    isRunning = false;
                }
            }
            System.out.println("Ended ReceiveInputThread");
        }
    }
}