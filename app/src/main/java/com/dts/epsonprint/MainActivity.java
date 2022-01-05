package com.dts.epsonprint;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.epson.epos2.Epos2Exception;
import com.epson.epos2.printer.Printer;
import com.epson.epos2.printer.PrinterStatusInfo;
import com.epson.epos2.printer.ReceiveListener;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class MainActivity extends Activity implements  ReceiveListener {

    private RelativeLayout relPrint;
    private EditText edtWarnings;

    private Context mContext = null;
    private Printer  mPrinter = null;

    private String mac="";
    private String fname="";
    private String fnameQR="";
    private String QRCodeStr="";
    private int copies=1;
    private int askprint=0;
    private File ffile;

    private Bitmap BitmapQR;

    private static final int REQUEST_PERMISSION = 100;

    int xQR=20,yQR=10,widhtQR=300,heighQR=300;

//    public MainActivity(String fnameQR) {
//        this.fnameQR = fnameQR;
//    }

    int vContador =-1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {

            mContext = this;

            requestRuntimePermission();

            mContext = this;

            relPrint = (RelativeLayout) findViewById(R.id.relPrint);

            edtWarnings = (EditText)findViewById(R.id.edtWarnings);

            Bundle bundle = getIntent().getExtras();

            processBundle(bundle);

            if (mac.isEmpty())      mac="BT:DC:0D:30:B8:23:B9";

            if (fname.isEmpty())    fname=Environment.getExternalStorageDirectory().getAbsolutePath()+"/print.txt";

            Handler mtimer = new Handler();
            Runnable mrunner= this::runPrint;
            mtimer.postDelayed(mrunner,500);

        } catch (Exception e) {
             showException(e,vContador);
        }

    }

    //region Handlers

    @Override
    protected void onActivityResult(int requestCode, final int resultCode, final Intent data) {
        try {
            if (data != null && resultCode == RESULT_OK) {
                //ShowMsg.showMsg("OOOOK", mContext);
                String target = data.getStringExtra(getString(R.string.title_target));
                if (target != null) {
                    EditText mEdtTarget = (EditText)findViewById(R.id.edtTarget);
                    mEdtTarget.setText(target);
                }
            }else{
                //ShowMsg.showMsg("NOT____OOOK", mContext);
            }
        } catch (Exception e) {
             showException(e,2);
        }
    }

    @Override
    public void onPtrReceive(final Printer printerObj, final int code, final PrinterStatusInfo status, final String printJobId) {
        runOnUiThread(new Runnable() {
            @Override
            public synchronized void run() {

                try {

                    if (code!=0) ShowMsg.showResult(code, makeErrorMessage(status), mContext);

                    dispPrinterWarnings(status);

                    new Thread(() -> disconnectPrinter()).start();

                } catch (Exception e) {
                     showException(e,3);
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != REQUEST_PERMISSION || grantResults.length == 0) {
            return;
        }

        List<String> requestPermissions = new ArrayList<>();

        for (int i = 0; i < permissions.length; i++) {
            if (permissions[i].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    && grantResults[i] == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(permissions[i]);
            }
            if (permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)
                    && grantResults[i] == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(permissions[i]);
            }
        }

        if (!requestPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, requestPermissions.toArray(new String[0]), REQUEST_PERMISSION);
        }
    }

    @Override
    public void onDestroy() {

        try {
            disconnectPrinter();
            SystemClock.sleep(500);
        } catch (Exception e) {}

        super.onDestroy();
    }

    //endregion

    //region Events

    public void doPrint(View view) {

    }

    //endregion

    //region Main

    private void runPrint(){
        int rslt;

        rslt= printFile();

        if (rslt==1) {
            relPrint.setVisibility(View.INVISIBLE);
            endSession();
        } else if (rslt==-1) {
            Handler mtimer = new Handler();
            Runnable mrunner=new Runnable() {
                @Override
                public void run() {
                    endSession();
                }
            };
            mtimer.postDelayed(mrunner,500);
        } else if (rslt==0) {
            endSession();
        }

    }

    private int printFile() {

        try {
            File file1 = new File(fname);
            ffile = new File(file1.getPath());
        } catch (Exception e) {
            ShowMsg.showMsg("No se puede leer archivo de impresión", mContext);return -1;
        }

        if (!initializeObject()) return 0;

        if (!createPrintData()) {
            finalizeObject();return 0;
        }

        if (!printData()) {

            try {
                finalizeObject();
            } catch (Exception ignored) {
            }
            return 0;
        }

        return 1;
    }

    private boolean createPrintData() {

        try {

            File file1 = new File(fname);
            ffile = new File(file1.getPath());

        } catch (Exception e) {
            ShowMsg.showMsg("No se puede leer archivo de impresión", mContext);
            return false;
        }

        BufferedReader dfile = null;
        BufferedReader dfileQR;
        StringBuilder textData = new StringBuilder();
        StringBuilder textDataQR = new StringBuilder();
        String ss,ss1;

        if (mPrinter == null) return false;

        try {
            FileInputStream fIn = new FileInputStream(ffile);
            dfile = new BufferedReader(new InputStreamReader(fIn));
        } catch (Exception e) {
            ShowMsg.showMsg("No se puede leer archivo de impresión " + e.getMessage(), mContext);
            return false;
        }

        try {

            mPrinter.addFeedLine(1);
            textData.delete(0, textData.length());

            while ((ss = dfile.readLine()) != null) {
                textData.append(ss).append("\n");
            }

            for (int i = 0; i <copies; i++) {
                mPrinter.addText(textData.toString());
                try {
                    if (!QRCodeStr.isEmpty()){
                        try {
                            makeQrCode();
                            mPrinter.addFeedLine(2);
                        } catch (Exception e) {}
                    } else {}
                } catch (Exception e) {}

                mPrinter.addText("\n");
                mPrinter.addFeedLine(2);
                mPrinter.addCut(Printer.CUT_FEED);

                if (copies>1) SystemClock.sleep(1000);
            }

            QRCodeStr="";

            mPrinter.addPulse(Printer.PARAM_DEFAULT, mPrinter.PULSE_300);
            //mPrinter.clearCommandBuffer();

        } catch (Exception e) {
            showException(e,4);
            return false;
        }

        return true;
    }

    private boolean printData() {

        try {

            if (mPrinter == null) {
                return false;
            }

            if (!connectPrinter()) {
                new Thread(() -> disconnectPrinter()).start();
                return false;
            }

            PrinterStatusInfo status = mPrinter.getStatus();

            dispPrinterWarnings(status);

            if (!isPrintable(status)) {

                ShowMsg.showMsg(makeErrorMessage(status), mContext);

                try {
                    mPrinter.disconnect();
                } catch (Exception ex) {
                    // Do nothing
                }
                return false;
            }

            try {
                mPrinter.sendData(Printer.PARAM_DEFAULT);
            }   catch (Exception e) {
                showException(e,6);
                try {
                    mPrinter.disconnect();
                }
                catch (Exception ex) {
                    // Do nothing
                }
                return false;
            }

        } catch (Exception e) {
             showException(e,5);
        }

        return true;
    }

    private void endSession() {
        finish();
    }

    //endregion

    //region Printer handling

    private boolean initializeObject() {

        //ShowMsg.showMsg("c", mContext);

        try {

            mPrinter = new Printer(1,0,mContext); // Model,Language,Context

        }  catch (Exception e) {
            showException(e,7);
            return false;
        }

        mPrinter.setReceiveEventListener(this);

        return true;
    }

    private void finalizeObject() {

        try {

            if (mPrinter == null) return;

            mPrinter.clearCommandBuffer();
            mPrinter.setReceiveEventListener(null);
            mPrinter = null;

        } catch (Exception e) {
             showException(e,8);
        }

    }

    private boolean connectPrinter() {

        boolean isBeginTransaction = false;

        if (mPrinter == null) return false;

        try {
            mPrinter.connect(mac,Printer.PARAM_DEFAULT);
        }  catch (Epos2Exception e) {
            showException(e,12);
            return false;
        }

        try {
            mPrinter.beginTransaction();
            isBeginTransaction = true;
        }  catch (Exception e) {
            showException(e,9);
        }

        if (!isBeginTransaction) {
            try {
                mPrinter.disconnect();
            } catch (Epos2Exception e) {
                return false;
            }
        }

        return true;
    }

    private void disconnectPrinter() {

        if (mPrinter == null) {
            return;
        }

        try {
            mPrinter.endTransaction();
        }  catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override
                public synchronized void run() {
                    showException(e,10);
                }
            });
        }

        try {
            mPrinter.disconnect();
        }   catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override
                public synchronized void run() {
                    showException(e,11);
                }
            });
        }

        finalizeObject();

    }

    //endregion

    //region Error Handling

    public void showException(Exception e, int Id) {
        try {
            String msg;
            if (e instanceof Epos2Exception) {
                msg ="Id " + Id + getEposExceptionText(((Epos2Exception) e).getErrorStatus());
            }  else {
                msg = "Id " + Id +  "Err: " + e.toString();
            }
            msgAsk(msg);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    @SuppressLint("DefaultLocale")
    private String getEposExceptionText(int state) {
        String return_text;
        switch (state) {
            case    Epos2Exception.ERR_PARAM:
                return_text = "Error de configuración de la impresora";//"ERR_PARAM"
                break;
            case    Epos2Exception.ERR_CONNECT:
                return_text = "Error en conexión a la impresora";//"ERR_CONNECT"
                break;
            case    Epos2Exception.ERR_TIMEOUT:
                return_text = "Timeout de conexión ha expirado"; //"ERR_TIMEOUT"
                break;
            case    Epos2Exception.ERR_MEMORY:
                return_text = "Error de memoria de la impresora"; //"ERR_MEMORY"
                break;
            case    Epos2Exception.ERR_ILLEGAL:
                return_text = "Illegal commando de impresión";//"ERR_ILLEGAL"
                break;
            case    Epos2Exception.ERR_PROCESSING:
                return_text = "Error interno de impresora : ERR_PROCESSING";
                break;
            case    Epos2Exception.ERR_NOT_FOUND:
                return_text = "Error interno de impresora : ERR_NOT_FOUND";
                break;
            case    Epos2Exception.ERR_IN_USE:
                return_text = "Error interno de impresora : ERR_IN_USE";
                break;
            case    Epos2Exception.ERR_TYPE_INVALID:
                return_text = "Error interno de impresora : ERR_TYPE_INVALID";
                break;
            case    Epos2Exception.ERR_DISCONNECT:
                return_text = "Impresora desconectada";//"ERR_DISCONNECT"
                break;
            case    Epos2Exception.ERR_ALREADY_OPENED:
                return_text = "Error interno de impresora : ERR_ALREADY_OPENED";
                break;
            case    Epos2Exception.ERR_ALREADY_USED:
                return_text = "Error interno de impresora : ERR_ALREADY_USED";
                break;
            case    Epos2Exception.ERR_BOX_COUNT_OVER:
                return_text = "Error interno de impresora : ERR_BOX_COUNT_OVER";
                break;
            case    Epos2Exception.ERR_BOX_CLIENT_OVER:
                return_text = "Error interno de impresora : ERR_BOX_CLIENT_OVER";
                break;
            case    Epos2Exception.ERR_UNSUPPORTED:
                return_text = "Error interno de impresora : ERR_UNSUPPORTED";
                break;
            case    Epos2Exception.ERR_FAILURE:
                return_text = "Error interno de impresora : ERR_FAILURE";
                break;
            default:
                return_text = String.format("%d", state);
                break;
        }
        return return_text;
    }

    //endregion

    //region Aux

    private void processBundle(Bundle b) {
        String flog=Environment.getExternalStorageDirectory()+"/logprint.txt";
        String ss="";
        String lf="\r\n";


        try {
            //FileOutputStream fos=new FileOutputStream(flog);

            try {
                mac=b.getString("mac");
                ss=mac;
            } catch (Exception e) {
                mac="BT:00:01:90:85:0D:8C";ss=e.getMessage();
            }
            //fos.write(ss.getBytes());fos.write(lf.getBytes());

            try {
                fname=b.getString("fname");ss=fname;
            } catch (Exception e) {
                fname="";ss=e.getMessage();
            }
            //fos.write(ss.getBytes());fos.write(lf.getBytes());

            try {
                QRCodeStr=b.getString("QRCodeStr");ss=QRCodeStr;
            } catch (Exception e) {
                QRCodeStr="";ss=e.getMessage();
            }
            //fos.write(ss.getBytes());fos.write(lf.getBytes());

            //#EJC20210917..
            edtWarnings.setText("El QR a imprimir es: " + QRCodeStr);

            try {
                askprint=b.getInt("askprint");ss=""+askprint;
            } catch (Exception e) {
                askprint=0;ss=e.getMessage();
            }
            //fos.write(ss.getBytes());fos.write(lf.getBytes());

            try {
                copies=b.getInt("copies");ss=""+copies;
            } catch (Exception e) {
                copies=1;ss=e.getMessage();
            }
            //fos.write(ss.getBytes());fos.write(lf.getBytes());

            //fos.close();
        } catch (Exception e) {
            mac="";fname="";ss="ERR : "+e.getMessage();
        }

        if (mac.isEmpty()) mac="BT:00:01:90:85:0D:8C";
        if (fname.isEmpty()) fname=Environment.getExternalStorageDirectory()+"/print.txt";

    }

    private void requestRuntimePermission() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)  return;

        int permissionStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int permissionLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);

        List<String> requestPermissions = new ArrayList<>();

        if (permissionStorage == PackageManager.PERMISSION_DENIED) {
            requestPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (permissionLocation == PackageManager.PERMISSION_DENIED) {
            requestPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (!requestPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, requestPermissions.toArray(new String[0]), REQUEST_PERMISSION);
        }

        if (Build.VERSION.SDK_INT >= 23) {
            int REQUEST_CODE_PERMISSION_STORAGE = 100;
            String[] permissions = {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };

            for (String str : permissions) {
                if (this.checkSelfPermission(str) != PackageManager.PERMISSION_GRANTED) {
                    this.requestPermissions(permissions, REQUEST_CODE_PERMISSION_STORAGE);
                    return;
                }
            }
        }
    }

    private String makeErrorMessage(PrinterStatusInfo status) {
        String msg = "";

        if (status.getOnline() == Printer.FALSE) {
            msg += getString(R.string.handlingmsg_err_offline);
        }
        if (status.getConnection() == Printer.FALSE) {
            msg += getString(R.string.handlingmsg_err_no_response);
        }
        if (status.getCoverOpen() == Printer.TRUE) {
            msg += getString(R.string.handlingmsg_err_cover_open);
        }
        if (status.getPaper() == Printer.PAPER_EMPTY) {
            msg += getString(R.string.handlingmsg_err_receipt_end);
        }
        if (status.getPaperFeed() == Printer.TRUE || status.getPanelSwitch() == Printer.SWITCH_ON) {
            msg += getString(R.string.handlingmsg_err_paper_feed);
        }
        if (status.getErrorStatus() == Printer.MECHANICAL_ERR || status.getErrorStatus() == Printer.AUTOCUTTER_ERR) {
            msg += getString(R.string.handlingmsg_err_autocutter);
            msg += getString(R.string.handlingmsg_err_need_recover);
        }
        if (status.getErrorStatus() == Printer.UNRECOVER_ERR) {
            msg += getString(R.string.handlingmsg_err_unrecover);
        }
        if (status.getErrorStatus() == Printer.AUTORECOVER_ERR) {
            if (status.getAutoRecoverError() == Printer.HEAD_OVERHEAT) {
                msg += getString(R.string.handlingmsg_err_overheat);
                msg += getString(R.string.handlingmsg_err_head);
            }
            if (status.getAutoRecoverError() == Printer.MOTOR_OVERHEAT) {
                msg += getString(R.string.handlingmsg_err_overheat);
                msg += getString(R.string.handlingmsg_err_motor);
            }
            if (status.getAutoRecoverError() == Printer.BATTERY_OVERHEAT) {
                msg += getString(R.string.handlingmsg_err_overheat);
                msg += getString(R.string.handlingmsg_err_battery);
            }
            if (status.getAutoRecoverError() == Printer.WRONG_PAPER) {
                msg += getString(R.string.handlingmsg_err_wrong_paper);
            }
        }
        if (status.getBatteryLevel() == Printer.BATTERY_LEVEL_0) {
            msg += getString(R.string.handlingmsg_err_battery_real_end);
        }

        return msg;
    }

    private void dispPrinterWarnings(PrinterStatusInfo status) {

        EditText edtWarnings = (EditText)findViewById(R.id.edtWarnings);
        String warningsMsg = "";

        if (status == null) {
            return;
        }

        if (status.getPaper() == Printer.PAPER_NEAR_END) {
            warningsMsg += getString(R.string.handlingmsg_warn_receipt_near_end);
        }

        if (status.getBatteryLevel() == Printer.BATTERY_LEVEL_1) {
            warningsMsg += getString(R.string.handlingmsg_warn_battery_near_end);
        }

        edtWarnings.setText(warningsMsg);
    }

    private boolean isPrintable(PrinterStatusInfo status) {

        if (status == null) {
            return false;
        }

        if (status.getConnection() == Printer.FALSE) {
            return false;
        }
        else return status.getOnline() != Printer.FALSE;
        //print available
    }

    private void msgAsk(String msg) {

        AlertDialog.Builder dialog = new AlertDialog.Builder(this);

        dialog.setTitle("Epson print");
        dialog.setMessage(msg+"\n\n¿Imprimír de nuevo?");

        dialog.setPositiveButton("Si", (dialog1, which) -> runPrint());

        dialog.setNegativeButton("No", (dialog12, which) -> {
            relPrint.setVisibility(View.INVISIBLE);
            try {
                ffile.delete();
            } catch (Exception ignored) {}
            finish();
        });

        dialog.show();
    }

    public void toast(String msg) {
        Toast toast= Toast.makeText(mContext,msg,Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }

    private boolean makeQrCode() {
        String method = "";

        if (mPrinter == null) {
            return false;
        }

        String qrCode = QRCodeStr;
        // QR code size
        final int qrcodeWidth = 5;
        final int qrcodeHeight = 5;

        try {

            // QR Code
            method = "addSymbol";
            mPrinter.addSymbol(qrCode,
                    Printer.SYMBOL_QRCODE_MODEL_2,
                    Printer.LEVEL_L,
                    qrcodeWidth,
                    qrcodeHeight,
                    0);

        } catch (Epos2Exception e) {
            ShowMsg.sshowException(e, method, mContext);
            return false;
        }

        return true;
    }

    public boolean FileExists(String fname) {
        File file = getBaseContext().getFileStreamPath(fname);
        return file.exists();
    }

    //endregion

    //region QR

    public Bitmap createQRImage(String url, int width, int height){

        try{

            if (url == null || "".equals(url) || url.length() <1){//Judging URL legality
                return null;
            }

            Hashtable<EncodeHintType, String> hints = new Hashtable<>();
            hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
            //Image data conversion, using matrix conversion
            BitMatrix bitMatrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, width, height, hints);
            int[] pixels = new int[width * height];
            //Following here is to generate the pictures of the QR code one by one according to the algorithm of the QR code,
            //The two for loops are the result of the horizontal scan of the picture
            for (int y = 0; y < height; y++){
                for (int x = 0; x < width; x++){
                    if (bitMatrix.get(x, y)){
                        pixels[y * width + x] = 0xff000000;
                    }
                    else{
                        pixels[y * width + x] = 0xffffffff;
                    }
                }
            }
            //Generate the format of the QR code image, use ARGB_8888
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;

        }catch (WriterException e){
             showException(e,13);
            return  null;
        }
    }

    //endregion

}