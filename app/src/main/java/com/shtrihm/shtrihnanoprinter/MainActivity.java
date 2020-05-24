package com.shtrihm.shtrihnanoprinter;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int READ_REQUEST_CODE = 42;

    private static final int PRINTER_WIDTH = 384;

    private static final String TAG = "ShtrihNanoPrinterMA";

    int currentview;
    int usedinterface = ShtrihNanoPrinter.INTERFACE_BT;
    String tcpaddress;
    SharedPreferences sharedPref;

    ArrayList<Bitmap> monochrome;

    ShtrihNanoPrinter printer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPref = getPreferences(Context.MODE_PRIVATE);
        usedinterface = sharedPref.getInt("usedinterface", 0);;
        tcpaddress = sharedPref.getString("tcpaddress", "");

        printer = new ShtrihNanoPrinter();

        setMainView();

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        Bitmap logobm = BitmapFactory.decodeResource(getResources(),R.drawable.logo,opts);
        setBitmapForPrinting(logobm);

        if(isStoragePermissionGranted())
            handleIntent();
    }


    public boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG,"Permission is granted");
                return true;
            } else {

                Log.v(TAG,"Permission is revoked");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
                return false;
            }
        }
        else { //permission is automatically granted on sdk<23 upon installation
            Log.v(TAG,"Permission is granted");
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
            Log.v(TAG,"Permission: "+permissions[0]+ "was "+grantResults[0]);
            //resume tasks needing this permission
            handleIntent();
        }
    }

    private void handleIntent() {
        Uri uri = getIntent().getData();
        if (uri == null) {
            //tellUserThatCouldntOpenFile();
            return;
        }

        processUriToOpen(uri);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if(currentview == R.layout.activity_main)
            getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            if(printer.isPrinting()){
                return true;
            }
            setSettingsView();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    void setSettingsView(){
        setContentView(R.layout.settingslayout);
        currentview = R.layout.settingslayout;

        Button btn2 = findViewById(R.id.backFromSettingsButton);

        btn2.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        saveSettings();
                                        setMainView();
                                    }
                                }
        );
        RadioButton btradio = findViewById(R.id.bluetoothRadioButton);
        RadioButton tcpradio = findViewById(R.id.tcpRadioButton);
        final EditText tcpaddressedit = findViewById(R.id.addressEditText);

        tcpradio.setOnCheckedChangeListener(new RadioButton.OnCheckedChangeListener(){
            @Override
            public  void onCheckedChanged(CompoundButton buttonView, boolean isChecked){
                if(isChecked)
                    tcpaddressedit.setVisibility(View.VISIBLE);
                else
                    tcpaddressedit.setVisibility(View.INVISIBLE);
            }
        });

        if(usedinterface == ShtrihNanoPrinter.INTERFACE_BT)
            btradio.setChecked(true);
        if(usedinterface == ShtrihNanoPrinter.INTERFACE_TCP)
            tcpradio.setChecked(true);
        tcpaddressedit.setText(tcpaddress);

        Toolbar toolbar = findViewById(R.id.toolbar_settings);
        setSupportActionBar(toolbar);
        toolbar.setTitle("Настройки");
    }

    void setMainView(){
        setContentView(R.layout.activity_main);
        currentview = R.layout.activity_main;

        Button browsebtn = findViewById(R.id.browseButton);

        browsebtn.setOnClickListener(new View.OnClickListener() {
                                         @Override
                                         public void onClick(View v) {
                                             performFileSearch();
                                         }
                                     }
        );
        Button printbtn = findViewById(R.id.printButton);

        printbtn.setOnClickListener(new View.OnClickListener() {
                                         @Override
                                         public void onClick(View v) {
                                             performPrinting();
                                         }
                                     }
        );

        if(monochrome != null){
            if(monochrome.size() != 0){
                ImageView imgview = findViewById(R.id.rasterizedImageView);
                imgview.setImageBitmap(monochrome.get(0));
            }
        }

        Toolbar toolbar = findViewById(R.id.toolbar_main);
        setSupportActionBar(toolbar);
    }

    @Override
    public void onBackPressed(){
        if(currentview == R.layout.activity_main) {
            super.onBackPressed();
            //moveTaskToBack(true);
            return;
        }
        if(currentview == R.layout.settingslayout) {
            saveSettings();
        }

        setMainView();
    }

    void saveSettings(){
        RadioButton btradio = findViewById(R.id.bluetoothRadioButton);
        RadioButton tcpradio = findViewById(R.id.tcpRadioButton);
        EditText tcpaddressedit = findViewById(R.id.addressEditText);
        SharedPreferences.Editor editor = sharedPref.edit();
        if(btradio.isChecked())
            usedinterface = ShtrihNanoPrinter.INTERFACE_BT;
        if(tcpradio.isChecked())
            usedinterface = ShtrihNanoPrinter.INTERFACE_TCP;
        tcpaddress = tcpaddressedit.getText().toString();

        editor.putInt("usedinterface", usedinterface);
        editor.putString("tcpaddress", tcpaddress);
        editor.commit();
    }

    public void performFileSearch() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimetypes = {"image/*", "application/pdf"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);

        startActivityForResult(intent, READ_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                processUriToOpen(uri);
            }
        }else {
            super.onActivityResult(requestCode, resultCode, resultData);
        }
    }

    void processUriToOpen(Uri uri){
        boolean ispdf = false;
        Log.i(TAG, "Uri: " + uri.toString());
        ContentResolver cR = getContentResolver();
        MimeTypeMap mime = MimeTypeMap.getSingleton();
        String type = cR.getType(uri);
        String subtype = mime.getExtensionFromMimeType(type);
        if(subtype != null){
            if(subtype.equals("pdf")){
                ispdf = true;
            }
        }else{
            String uristr = uri.toString(); // trying by name
            if(uristr.length() > 4) {
                subtype = uristr.substring(uristr.length() - 3);
                if (subtype.equals("pdf")) {
                    ispdf = true;
                }
            }
        }
        if(ispdf){
            showPdf(uri);
        }else {
            showImage(uri);
        }
    }

    private void showImage(Uri uri) {
        try {
            ParcelFileDescriptor parcelFileDescriptor =
                    getContentResolver().openFileDescriptor(uri, "r");
            FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
            Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor);
            parcelFileDescriptor.close();
            setBitmapForPrinting(image);
        }catch(IOException e){
            Log.i(TAG, "Can't decode image " + uri.toString());
        }
    }

    private void showPdf(Uri uri) {

        ArrayList<Bitmap> bitmaps = new ArrayList<>();

        try {
            ParcelFileDescriptor parcelFileDescriptor =
                    getContentResolver().openFileDescriptor(uri, "r");
            PdfRenderer renderer = new PdfRenderer(parcelFileDescriptor);

            Bitmap bitmap;
            int pageCount = renderer.getPageCount();
            //if(pageCount > 16)
                //pageCount = 16; //LIMIT
            for (int i = 0; i < pageCount; i++) {
                PdfRenderer.Page page = renderer.openPage(i);

                int width = PRINTER_WIDTH;//getResources().getDisplayMetrics().densityDpi / 72 * page.getWidth();
                int height = (page.getHeight() * PRINTER_WIDTH) / page.getWidth();//getResources().getDisplayMetrics().densityDpi / 72 * page.getHeight();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                bitmaps.add(bitmap);

                // close the page
                page.close();

                //break;//ony one page now
            }

            // close the renderer
            renderer.close();
        } catch (Exception ex) {
            Log.i(TAG, "Can't decode pdf " + uri.toString());
            ex.printStackTrace();
            return;
        }

        if(bitmaps.size() > 0) {
            monochrome = bitmaps;
            ImageView imgview = findViewById(R.id.rasterizedImageView);
            imgview.setImageBitmap(monochrome.get(0));
            //setBitmapForPrinting(bitmaps.get(0));
            addSpcingBitmap();
        }
    }

    void setBitmapForPrinting(Bitmap bm){
        boolean dorotate = false, doscale = false;
        if(bm.getWidth() > PRINTER_WIDTH){
            if(bm.getHeight() > bm.getWidth()){
                dorotate = false;
                doscale = true;
            }else{
                if(bm.getHeight() > PRINTER_WIDTH)
                    doscale = true;
                dorotate = true;
            }
        }
        if(dorotate || doscale) {
            Matrix m = new Matrix();
            //int newh;
            //int neww = PRINTER_WIDTH;
            if(doscale) {
                float scale;
                if(dorotate) {
                    //newh = ((bm.getWidth()*PRINTER_WIDTH)/bm.getHeight());
                    scale = (float)PRINTER_WIDTH/((float)bm.getHeight());
                }else{
                    //newh = ((bm.getHeight()*PRINTER_WIDTH)/bm.getWidth());
                    scale = (float)PRINTER_WIDTH/((float)bm.getWidth());
                }
                m.setScale(scale, scale);
            }else{
                //newh = bm.getWidth();
            }
            if(dorotate)
                m.postRotate(90 );

            Bitmap transformedBitmap = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);

            bm = transformedBitmap;
        }
        int[] pixels = new int[bm.getWidth()*bm.getHeight()];
        bm.getPixels(pixels,0, bm.getWidth(),0,0, bm.getWidth(), bm.getHeight());
        for(int i=0; i<pixels.length; i++) {
            int rgb = pixels[i];
            int a = ((rgb >> 24) & 0xff);
            int grey = ((((rgb >> 16) & 0xff)*11 + ((rgb >> 8) & 0xff)*16 + (rgb & 0xff)*5)/32)*(a)/255 + (255 - a);
            if(grey < 128){
                rgb = 0xFF000000;
            }else{
                rgb = 0xFFFFFFFF;
            }
            pixels[i] = rgb;
        }
        monochrome = new ArrayList<Bitmap>();
        Bitmap cmonochrome = Bitmap.createBitmap(PRINTER_WIDTH, bm.getHeight(), Bitmap.Config.ARGB_8888);
        cmonochrome.eraseColor(0xFFFFFFFF);
        cmonochrome.setPixels(pixels,0, bm.getWidth(),0,0, bm.getWidth(), bm.getHeight());

        ImageView imgview = findViewById(R.id.rasterizedImageView);
        imgview.setImageBitmap(cmonochrome);

        monochrome.add(cmonochrome);
        addSpcingBitmap();
    }

    void addSpcingBitmap(){
        Bitmap cmonochrome = Bitmap.createBitmap(PRINTER_WIDTH, 70, Bitmap.Config.ARGB_8888);
        cmonochrome.eraseColor(0xFFFFFFFF);
        monochrome.add(cmonochrome);
    }

    public void performPrinting() {
        if(monochrome == null)
            return;
        if(printer.isPrinting()) {
            printer.stopPrining();
            return;
        }
        if(usedinterface == ShtrihNanoPrinter.INTERFACE_BT) {
            printer.setBTConnection();
        }else if(usedinterface == ShtrihNanoPrinter.INTERFACE_TCP) {
            printer.setTCPConnection(tcpaddress);
        }
        printer.setBitmaps(monochrome);
        ProgressBar pb = findViewById(R.id.printingProgressBar);
        pb.setVisibility(View.VISIBLE);
        pb.setMax(1000);
        pb.setProgress(0);
        final Handler mainHandler = new Handler(getMainLooper());
        if(printer.startPrining(new ShtrihNanoPrinter.PrintingResultCallback() {
            @Override
            public void onProgress(final int percent) {
                Runnable myRunnable = new Runnable() {
                    @Override
                    public void run() {
                        ProgressBar pb = findViewById(R.id.printingProgressBar);
                        pb.setProgress(percent);
                    }
                };
                mainHandler.post(myRunnable);
            }

            @Override
            public void onError(final String errstr) {
                Runnable myRunnable = new Runnable() {
                    @Override
                    public void run() {
                        ProgressBar pb = findViewById(R.id.printingProgressBar);
                        pb.setVisibility(View.INVISIBLE);
                        Button printbutton = findViewById(R.id.printButton);
                        printbutton.setText("Печать");
                    }
                };
                mainHandler.post(myRunnable);
            }

            @Override
            public void onComplete() {
                Runnable myRunnable = new Runnable() {
                    @Override
                    public void run() {
                        ProgressBar pb = findViewById(R.id.printingProgressBar);
                        pb.setVisibility(View.INVISIBLE);
                        Button printbutton = findViewById(R.id.printButton);
                        printbutton.setText("Печать");
                    }
                };
                mainHandler.post(myRunnable);
            }
        })){
            Button printbutton = findViewById(R.id.printButton);
            printbutton.setText("Отмена");
        }
    }
}
