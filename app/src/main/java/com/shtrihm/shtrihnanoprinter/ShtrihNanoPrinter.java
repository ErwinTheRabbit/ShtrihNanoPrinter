package com.shtrihm.shtrihnanoprinter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.os.AsyncTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class ShtrihNanoPrinter {

    public static final int INTERFACE_BT = 0;
    public static final int INTERFACE_TCP = 1;

    private ArrayList<Bitmap> bitmaps;
    private PrintTask printingtask;
    private int printinginterface;
    BluetoothDevice connectingdevice;
    String tcpaddress;

    interface PrintingResultCallback{
        void onProgress(final int percent);
        void onError(final String errstr);
        void onComplete();
    }

    PrintingResultCallback callback;


    public boolean setBTConnection(String address){
        BluetoothDevice tmpconnectingdevice = null;
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null)
            return false;
        if(!mBluetoothAdapter.isEnabled())
            mBluetoothAdapter.enable();
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice dev : pairedDevices) {
            if(address.isEmpty()){
                if(dev.getName().contains("SHTRIH-NANO-F")){
                    tmpconnectingdevice = dev;
                    break;
                }
            }else{
                if(dev.getAddress().equalsIgnoreCase(address)){
                    tmpconnectingdevice = dev;
                    break;
                }
            }
        }
        if(tmpconnectingdevice != null) {
            printinginterface = INTERFACE_BT;
            connectingdevice = tmpconnectingdevice;
            return true;
        }
        return false;
    }

    public boolean setBTConnection(){
        return setBTConnection("");
    }

    public void setTCPConnection(String address){
        printinginterface = INTERFACE_TCP;
        tcpaddress = address;
    }

    public void setBitmap(Bitmap bm){
        bitmaps = new ArrayList<Bitmap>();
        bitmaps.add(bm);
    }

    public void setBitmaps(ArrayList<Bitmap> bm){
        bitmaps = bm;
    }

    public boolean isPrinting(){
        if(printingtask != null){
            if(printingtask.getStatus() == AsyncTask.Status.RUNNING)
                return true;
        }
        return false;
    }

    public boolean startPrining(PrintingResultCallback callback){
        if(printingtask != null){
            if(printingtask.getStatus() == AsyncTask.Status.RUNNING)
                return false;
        }
        if(bitmaps == null)
            return false;
        this.callback = callback;
        printingtask = new PrintTask();
        printingtask.execute(null, null, null);
        return true;
    }

    public void stopPrining(){
        if(printingtask == null)
            return;
        try {
            if(printingtask.btSocket != null) {
                if(printingtask.btSocket.isConnected())
                    printingtask.btSocket.close();
            }
            if(printingtask.tcpSocket != null) {
                if(printingtask.tcpSocket.isConnected())
                    printingtask.tcpSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class PrintTask extends AsyncTask<Void, Void, Void> {

        BluetoothSocket btSocket;
        Socket tcpSocket;
        InputStream is;
        OutputStream os;
        private static final int PACKETLIMIT = 240;
        private static final int PRINTER_WIDTH = 384;
        private static final int PRINTER_WIDTH_BYTES = PRINTER_WIDTH/8;
        byte prevsting[];
        @Override
        protected Void doInBackground(Void... voids) {
            try {
                if(printinginterface == INTERFACE_BT){
                    if(connectingdevice == null)
                        throw new IOException("There is no device to connect");
                    btSocket = connectingdevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
                    btSocket.connect();
                    is = btSocket.getInputStream();
                    os = btSocket.getOutputStream();
                }else if(printinginterface == INTERFACE_TCP){
                    tcpSocket = new Socket(tcpaddress, 7778);
                    tcpSocket.setSoTimeout(12000);
                    is = tcpSocket.getInputStream();
                    os = tcpSocket.getOutputStream();
                }

                byte flushprintdata[]={(byte)0xFE,(byte)0xA9};

                sendAndRecievedata(flushprintdata);
                prevsting = new byte[PRINTER_WIDTH_BYTES];

                ByteArrayOutputStream outstream = new ByteArrayOutputStream();

                for (Bitmap bitmap:
                     bitmaps) {
                    int lineid = 0;
                    while(lineid < bitmap.getHeight()){
                        byte start[] = {(byte)0xFE, (byte)0xA9};
                        outstream.reset();
                        outstream.write(start);
                        while(lineid < bitmap.getHeight()){
                            byte strdata[] = new byte[PRINTER_WIDTH_BYTES];
                            if(lineid < bitmap.getHeight()) {
                                for (int j = 0; j < bitmap.getWidth(); j++) {
                                    int dotid = (strdata.length - (j >> 3) - 1);
                                    if (dotid < 0)
                                        break;
                                    int rgb = bitmap.getPixel(bitmap.getWidth() - j - 1, lineid);
                                    int a = ((rgb >> 24) & 0xff);
                                    int grey = ((((rgb >> 16) & 0xff)*11 + ((rgb >> 8) & 0xff)*16 + (rgb & 0xff)*5)/32)*(a)/255 + (255 - a);
                                    if (grey < 128) {
                                        strdata[dotid] |= (1 << (j % 8));
                                    }
                                }
                            }
                            if(packAndStoreData(strdata,outstream)){
                                lineid++;
                            }else{
                                break;
                            }
                        }
                        callback.onProgress((lineid*100)/bitmap.getHeight());
                        sendAndRecievedata(outstream.toByteArray());
                    }
                }

                sendAndRecievedata(flushprintdata);

                if(printinginterface == INTERFACE_BT) {
                    if(btSocket != null)
                        btSocket.close();
                }else if(printinginterface == INTERFACE_TCP){
                    if(tcpSocket != null)
                        tcpSocket.close();
                }
            } catch (UnknownHostException e){
                callback.onError(e.toString());
                e.printStackTrace();
            } catch (IOException e) {
                callback.onError(e.toString());
                e.printStackTrace();
            }
            callback.onComplete();
            btSocket = null;
            tcpSocket = null;
            return null;
        }


        boolean packAndStoreData(byte[] data, ByteArrayOutputStream rdata){
            byte mask[] = new byte[6];
            byte diffstring[] = new byte[PRINTER_WIDTH_BYTES];
            int diffsize = 0;
            for(int i=0; i<PRINTER_WIDTH_BYTES; i++){
                diffstring[i] = (byte)(((int)data[i])^((int)prevsting[i]));
                if(diffstring[i] != 0){
                    diffsize++;
                    mask[i/8] |= (1 << (i%8));
                }
            }
            if((rdata.size() + 6 + diffsize) > PACKETLIMIT)
                return false;
            rdata.write(mask,0,6);
            for(int i=0; i<PRINTER_WIDTH_BYTES; i++){
                if(diffstring[i] != 0){
                    rdata.write(diffstring, i,1);
                }
            }
            for(int i=0; i<PRINTER_WIDTH_BYTES; i++){
                prevsting[i] = data[i];
            }
            return true;
        }

        int crc16_ccitt(final byte[] buffer, int crc) {
            for (int j = 0; j < buffer.length ; j++) {
                crc = ((crc  >>> 8) | (crc  << 8) )& 0xffff;
                crc ^= (buffer[j] & 0xff);//byte to int, trunc sign
                crc ^= ((crc & 0xff) >> 4);
                crc ^= (crc << 12) & 0xffff;
                crc ^= ((crc & 0xFF) << 5) & 0xffff;
            }
            crc &= 0xffff;
            return crc;
        }

        int packetiterator = 0;

        byte[] sendAndRecievedata(byte senddata[]) throws IOException {
            byte[] realsnddata = new byte[12 + senddata.length * 2];
            int realsize = 0;

            byte hdrdata[] = new byte[4];
            hdrdata[0] = (byte)(senddata.length + 2);
            hdrdata[1] = 0;
            hdrdata[2] = (byte)((++packetiterator) & 0xFF);
            hdrdata[3] = (byte)((packetiterator >> 8) & 0xFF);

            int crc16 = crc16_ccitt(hdrdata,0xFFFF);
            crc16 = crc16_ccitt(senddata,crc16);

            byte crc16data[] = new byte[2];
            crc16data[0] = (byte)((crc16) & 0xFF);
            crc16data[1] = (byte)((crc16 >> 8) & 0xFF);

            ArrayList<byte[]> arrays = new ArrayList<byte[]>();
            arrays.add(hdrdata);
            arrays.add(senddata);
            arrays.add(crc16data);

            realsnddata[realsize++] = (byte) 0x8F;

            for(byte[] pdata :  arrays) {
                for (int i = 0; i < pdata.length; i++) {
                    if (pdata[i] == (byte) 0x9F) {
                        realsnddata[realsize++] = (byte) 0x9F;
                        realsnddata[realsize++] = (byte) 0x83;
                    } else if (pdata[i] == (byte) 0x8F) {
                        realsnddata[realsize++] = (byte) 0x9F;
                        realsnddata[realsize++] = (byte) 0x81;
                    } else {
                        realsnddata[realsize++] = pdata[i];
                    }
                }
            }
            os.write(realsnddata,0,realsize);

            //reading answer
            boolean wasescape = false;
            byte[] readedarray = new byte[32];
            int readedarraysize = 0;

            while(true) {
                int c = is.read();
                if((byte)c == (byte)0x8F){
                    readedarraysize = 0;
                }else if((byte)c == (byte)0x9F){
                    wasescape = true;
                }else{
                    if(wasescape){
                        if((byte)c == (byte)0x81){
                            readedarray[readedarraysize++] = (byte) 0x8F;
                        }else if((byte)c == (byte)0x83){
                            readedarray[readedarraysize++] = (byte) 0x9F;
                        }else{
                            break;
                        }
                        wasescape = false;
                    }else{
                        readedarray[readedarraysize++] = (byte) c;
                    }
                    if(readedarraysize == readedarray.length)
                        break;
                    if(readedarraysize >= 2){
                        int size = ((int)readedarray[0]) & 0xFF;
                        if((size + 2) == readedarraysize)
                            break;
                    }
                }
            }
            return readedarray;
        }

        protected Void onProgressUpdate() {
            return null;
        }

        protected Void onPostExecute() {
            return null;
        }

    }
}
