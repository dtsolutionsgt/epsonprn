package com.dts.epsonprint;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.EditText;
import android.widget.RelativeLayout;
import com.epson.epos2.Epos2Exception;
import com.epson.epos2.printer.Printer;
import com.epson.epos2.printer.PrinterStatusInfo;
import com.epson.epos2.printer.ReceiveListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements  ReceiveListener {

    private RelativeLayout relPrint;

    private Context mContext = null;
    private Printer  mPrinter = null;

    private String mac,fname;
    private int askprint,copies;

    private File ffile;

    private static final int REQUEST_PERMISSION = 100;
    private int printstatus=-1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestRuntimePermission();
        mContext = this;

        relPrint = (RelativeLayout) findViewById(R.id.relPrint);

        Bundle bundle = getIntent().getExtras();
        processBundle(bundle);

        Handler mtimer = new Handler();
        Runnable mrunner=new Runnable() {
            @Override
            public void run() {
                //ShowMsg.showMsg(mac+"::"+fname+"::"+askprint,mContext);
                runPrint();
              }
        };
        mtimer.postDelayed(mrunner,500);

    }

    //region Handlers

    @Override
    protected void onActivityResult(int requestCode, final int resultCode, final Intent data) {
        if (data != null && resultCode == RESULT_OK) {
            String target = data.getStringExtra(getString(R.string.title_target));
            if (target != null) {
                EditText mEdtTarget = (EditText)findViewById(R.id.edtTarget);
                mEdtTarget.setText(target);
            }
        }
    }

    @Override
    public void onPtrReceive(final Printer printerObj, final int code, final PrinterStatusInfo status, final String printJobId) {
        runOnUiThread(new Runnable() {
            @Override
            public synchronized void run() {

                int a=1;

                if (code==0) printstatus=1;else printstatus=0;
                if (code!=0) ShowMsg.showResult(code, makeErrorMessage(status), mContext);

                dispPrinterWarnings(status);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        disconnectPrinter();
                    }
                }).start();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
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
            ActivityCompat.requestPermissions(this, requestPermissions.toArray(new String[requestPermissions.size()]), REQUEST_PERMISSION);
        }
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

            try {
                ffile.delete();
            } catch (Exception e) {}

            finish();
        } else if (rslt==-1) {

            try {
                ffile.delete();
            } catch (Exception e) {}

            Handler mtimer = new Handler();
            Runnable mrunner=new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            };
            mtimer.postDelayed(mrunner,2000);

       } else if (rslt==0) {
            try {
                ffile.delete();
            } catch (Exception e) {}
            finish();
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
            } catch (Exception e) {
                String ss=e.getMessage();
            }
            return 0;
        }

        return 1;
    }

    private boolean createPrintData() {

        BufferedReader dfile;
        StringBuilder textData = new StringBuilder();
        String method = "",ss;

        if (mPrinter == null) return false;

        try {
            FileInputStream fIn = new FileInputStream(ffile);
            dfile = new BufferedReader(new InputStreamReader(fIn));
        } catch (Exception e) {
            ShowMsg.showMsg("No se puede leer archivo de impresión", mContext);return false;
        }

        try {

            method = "addFeedLine";
            mPrinter.addFeedLine(1);
            textData.delete(0, textData.length());

            while ((ss = dfile.readLine()) != null) {
                textData.append(ss+"\n");
            }

            for (int i = 0; i <copies; i++) {
                mPrinter.addText(textData.toString());
                method = "addCut";
                mPrinter.addCut(Printer.CUT_FEED);
            }

            mPrinter.addPulse(Printer.PARAM_DEFAULT, mPrinter.PULSE_300);

        } catch (Exception e) {
            showException(e, method, mContext);
            return false;
        }

        textData = null;

        return true;
    }

    private boolean printData() {
        if (mPrinter == null) {
            return false;
        }

        if (!connectPrinter()) {
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
            showException(e, "sendData", mContext);
            try {
                mPrinter.disconnect();
            }
            catch (Exception ex) {
                // Do nothing
            }
            return false;
        }

        return true;
    }

    private void processBundle(Bundle b) {
        try {

            try {
                mac=b.getString("mac");
            } catch (Exception e) {
                mac="BT:00:01:90:85:0D:8C";
            }

            try {
                fname=b.getString("fname");
            } catch (Exception e) {
                fname="";
            }

            try {
                askprint=b.getInt("askprint");
            } catch (Exception e) {
                askprint=0;
            }

            try {
                copies=b.getInt("copies");
            } catch (Exception e) {
                copies=1;
            }

        } catch (Exception e) {
            mac="";fname="";askprint=0;
        }

        if (mac.isEmpty())      mac="BT:00:01:90:85:0D:8C";
        if (fname.isEmpty())    fname=Environment.getExternalStorageDirectory()+"/print.txt";

    }

    //endregion

    //region Printer handling

    private boolean initializeObject() {
        try {
             mPrinter = new Printer(1,0,mContext); // Model,Language,Context
        }  catch (Exception e) {
            showException(e, "Printer", mContext);
            return false;
        }

        mPrinter.setReceiveEventListener(this);

        return true;
    }

    private void finalizeObject() {
        if (mPrinter == null) return;

        mPrinter.clearCommandBuffer();

        mPrinter.setReceiveEventListener(null);

        mPrinter = null;
    }

    private boolean connectPrinter() {
        boolean isBeginTransaction = false;

        if (mPrinter == null) return false;

        try {
            mPrinter.connect(mac,Printer.PARAM_DEFAULT);
        }  catch (Exception e) {
            showException(e, "connect", mContext);
            return false;
        }

        try {
            mPrinter.beginTransaction();
            isBeginTransaction = true;
        }  catch (Exception e) {
            showException(e, "beginTransaction", mContext);
        }

        if (isBeginTransaction == false) {
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
                   showException(e, "endTransaction", mContext);
                }
            });
        }

        try {
            mPrinter.disconnect();
        }   catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override
                public synchronized void run() {
                    showException(e, "disconnect", mContext);
                }
            });
        }

        finalizeObject();
    }

    //endregion

    //region Error Handling

    public void showException(Exception e, String method, Context context) {
        String msg;
        if (e instanceof Epos2Exception) {
            msg = getEposExceptionText(((Epos2Exception) e).getErrorStatus());
        }  else {
            msg = e.toString();
        }
        msgAsk(msg);
    }

    private String getEposExceptionText(int state) {
        String return_text = "";
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
                return_text = "Ilegal comando de impresión";//"ERR_ILLEGAL"
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
            ActivityCompat.requestPermissions(this, requestPermissions.toArray(new String[requestPermissions.size()]), REQUEST_PERMISSION);
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
        else if (status.getOnline() == Printer.FALSE) {
            return false;
        }
        else {
            ;//print available
        }

        return true;
    }

    private void msgAsk(String msg) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);

        dialog.setTitle("Epson print");
        dialog.setMessage(msg+"\n\n¿Imprimír de nuevo?");

        dialog.setPositiveButton("Si", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                runPrint();
            }
        });

        dialog.setNegativeButton("No", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                relPrint.setVisibility(View.INVISIBLE);
                try {
                    ffile.delete();
                } catch (Exception e) {}
                finish();
            }
        });

        dialog.show();
    }

    //endregion

}
