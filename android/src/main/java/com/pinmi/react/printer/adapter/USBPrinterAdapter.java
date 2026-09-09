package com.pinmi.react.printer.adapter;

import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * USB thermal printer adapter.
 *
 * Image printing now uses ESC/POS GS v 0 (continuous raster mode)
 * instead of the legacy ESC * 33 per-24-dot-band mode.
 *
 * Rationale: ESC * mode issues a separate print+feed command for every
 * 24-row band, forcing the print head to stop/start at every band
 * boundary. On slow/cheap thermal heads this produces visible pause +
 * contrast banding at band edges. GS v 0 sends the whole image as one
 * raster block so the head prints in one continuous motion.
 *
 * bulkTransfer() is still chunked internally (MAX_USB_CHUNK) purely as
 * a safety measure against a known Android USB host bug where single
 * transfers above ~16KB can silently truncate on some devices/kernels -
 * this chunking is transparent to the printer, it's still one
 * continuous raster stream, not separate print commands.
 */
public class USBPrinterAdapter implements PrinterAdapter {

    @SuppressLint("StaticFieldLeak")
    private static USBPrinterAdapter mInstance;

    private final String LOG_TAG = "RNUSBPrinter";

    private static final int USB_TIMEOUT = 100000;

    // Safe chunk size for bulkTransfer(); well under the ~16KB threshold
    // where some Android USB host stacks silently truncate transfers.
    private static final int MAX_USB_CHUNK = 4096;

    private Context mContext;
    private UsbManager mUSBManager;
    private PendingIntent mPermissionIndent;

    private UsbDevice mUsbDevice;
    private UsbDeviceConnection mUsbDeviceConnection;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mEndPoint;

    private static final String ACTION_USB_PERMISSION =
            "com.pinmi.react.USBPrinter.USB_PERMISSION";

    private static final String EVENT_USB_DEVICE_ATTACHED =
            "usbAttached";

    private final static char ESC_CHAR = 0x1B;

    private static final byte[] CENTER_ALIGN = {
            0x1B,
            0x61,
            0x31
    };

    private final static byte[] LINE_FEED = new byte[]{
            0x0A
    };

    private USBPrinterAdapter() {
    }

    public static USBPrinterAdapter getInstance() {
        if (mInstance == null) {
            mInstance = new USBPrinterAdapter();
        }

        return mInstance;
    }

    // ---------------------------------------------------------
    // USB INITIALIZATION
    // ---------------------------------------------------------

    private final BroadcastReceiver mUsbDeviceReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {
                    String action = intent.getAction();

                    if (ACTION_USB_PERMISSION.equals(action)) {

                        synchronized (this) {

                            UsbDevice usbDevice =
                                    intent.getParcelableExtra(
                                            UsbManager.EXTRA_DEVICE
                                    );

                            if (intent.getBooleanExtra(
                                    UsbManager.EXTRA_DEVICE,
                                    false
                            )) {
                                if (usbDevice != null) {
                                    Log.i(
                                            LOG_TAG,
                                            "USB permission granted: "
                                                    + usbDevice.getDeviceId()
                                    );

                                    mUsbDevice = usbDevice;
                                }
                            } else {
                                if (usbDevice != null) {
                                    Toast.makeText(
                                            context,
                                            "User refuses to obtain USB device permissions "
                                                    + usbDevice.getDeviceName(),
                                            Toast.LENGTH_LONG
                                    ).show();
                                }
                            }
                        }

                    } else if (
                            UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)
                    ) {

                        if (mUsbDevice != null) {
                            Toast.makeText(
                                    context,
                                    "USB device has been turned off",
                                    Toast.LENGTH_LONG
                            ).show();

                            closeConnectionIfExists();
                        }

                    } else if (
                            UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)
                                    || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    ) {

                        synchronized (this) {

                            if (mContext != null) {

                                ((ReactApplicationContext) mContext)
                                        .getJSModule(
                                                DeviceEventManagerModule
                                                        .RCTDeviceEventEmitter.class
                                        )
                                        .emit(
                                                EVENT_USB_DEVICE_ATTACHED,
                                                null
                                        );
                            }
                        }
                    }
                }
            };

    @SuppressLint("UnspecifiedImmutableFlag")
    public void init(
            ReactApplicationContext reactContext,
            Callback successCallback,
            Callback errorCallback
    ) {

        this.mContext = reactContext;

        this.mUSBManager =
                (UsbManager) this.mContext.getSystemService(
                        Context.USB_SERVICE
                );

        Intent usbPermissionIntent =
                new Intent(ACTION_USB_PERMISSION);

        usbPermissionIntent.setPackage(
                mContext.getPackageName()
        );

        this.mPermissionIndent =
                PendingIntent.getBroadcast(
                        mContext,
                        0,
                        usbPermissionIntent,
                        PendingIntent.FLAG_MUTABLE
                                | PendingIntent.FLAG_UPDATE_CURRENT
                );

        IntentFilter filter =
                new IntentFilter(ACTION_USB_PERMISSION);

        filter.addAction(
                UsbManager.ACTION_USB_DEVICE_DETACHED
        );

        filter.addAction(
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        );

        filter.addAction(
                UsbManager.ACTION_USB_DEVICE_ATTACHED
        );

        ContextCompat.registerReceiver(
                mContext,
                mUsbDeviceReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        Log.v(
                LOG_TAG,
                "RNUSBPrinter initialized"
        );

        successCallback.invoke();
    }

    // ---------------------------------------------------------
    // CONNECTION
    // ---------------------------------------------------------

    public void closeConnectionIfExists() {

        try {

            if (mUsbDeviceConnection != null) {

                if (mUsbInterface != null) {
                    mUsbDeviceConnection.releaseInterface(
                            mUsbInterface
                    );
                }

                mUsbDeviceConnection.close();
            }

        } catch (Exception e) {

            Log.e(
                    LOG_TAG,
                    "Failed closing USB connection",
                    e
            );

        } finally {

            mUsbInterface = null;
            mEndPoint = null;
            mUsbDeviceConnection = null;
        }
    }

    public List<PrinterDevice> getDeviceList(
            Callback errorCallback
    ) {

        List<PrinterDevice> lists =
                new ArrayList<>();

        if (mUSBManager == null) {

            errorCallback.invoke(
                    "USBManager is not initialized while get device list"
            );

            return lists;
        }

        for (
                UsbDevice usbDevice :
                mUSBManager.getDeviceList().values()
        ) {

            lists.add(
                    new USBPrinterDevice(usbDevice)
            );
        }

        return lists;
    }

    @Override
    public void selectDevice(
            PrinterDeviceId printerDeviceId,
            Callback successCallback,
            Callback errorCallback
    ) {

        if (mUSBManager == null) {

            errorCallback.invoke(
                    "USBManager is not initialized before select device"
            );

            return;
        }

        USBPrinterDeviceId usbPrinterDeviceId =
                (USBPrinterDeviceId) printerDeviceId;

        if (
                mUsbDevice != null
                        && mUsbDevice.getVendorId()
                        == usbPrinterDeviceId.getVendorId()
                        && mUsbDevice.getProductId()
                        == usbPrinterDeviceId.getProductId()
        ) {

            Log.i(
                    LOG_TAG,
                    "already selected device, do not need repeat to connect"
            );

            if (!mUSBManager.hasPermission(mUsbDevice)) {

                closeConnectionIfExists();

                mUSBManager.requestPermission(
                        mUsbDevice,
                        mPermissionIndent
                );
            }

            successCallback.invoke(
                    new USBPrinterDevice(
                            mUsbDevice
                    ).toRNWritableMap()
            );

            return;
        }

        closeConnectionIfExists();

        if (
                mUSBManager.getDeviceList().size() == 0
        ) {

            errorCallback.invoke(
                    "Device list is empty, can not choose device"
            );

            return;
        }

        for (
                UsbDevice usbDevice :
                mUSBManager.getDeviceList().values()
        ) {

            if (
                    usbDevice.getVendorId()
                            == usbPrinterDeviceId.getVendorId()
                            && usbDevice.getProductId()
                            == usbPrinterDeviceId.getProductId()
            ) {

                Log.v(
                        LOG_TAG,
                        "request for device: vendor_id: "
                                + usbPrinterDeviceId.getVendorId()
                                + " product_id: "
                                + usbPrinterDeviceId.getProductId()
                );

                closeConnectionIfExists();

                mUsbDevice = usbDevice;

                mUSBManager.requestPermission(
                        usbDevice,
                        mPermissionIndent
                );

                successCallback.invoke(
                        new USBPrinterDevice(
                                usbDevice
                        ).toRNWritableMap()
                );

                return;
            }
        }

        errorCallback.invoke(
                "can not find specified device"
        );
    }

    private boolean openConnection() {

        if (mUsbDevice == null) {

            Log.e(
                    LOG_TAG,
                    "USB Device is not initialized"
            );

            return false;
        }

        if (mUSBManager == null) {

            Log.e(
                    LOG_TAG,
                    "USB Manager is not initialized"
            );

            return false;
        }

        if (mUsbDeviceConnection != null) {

            return true;
        }

        UsbInterface usbInterface =
                mUsbDevice.getInterface(0);

        for (
                int i = 0;
                i < usbInterface.getEndpointCount();
                i++
        ) {

            UsbEndpoint ep =
                    usbInterface.getEndpoint(i);

            if (
                    ep.getType()
                            == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection()
                            == UsbConstants.USB_DIR_OUT
            ) {

                UsbDeviceConnection connection =
                        mUSBManager.openDevice(
                                mUsbDevice
                        );

                if (connection == null) {

                    Log.e(
                            LOG_TAG,
                            "failed to open USB Connection"
                    );

                    return false;
                }

                if (
                        connection.claimInterface(
                                usbInterface,
                                true
                        )
                ) {

                    mEndPoint = ep;
                    mUsbInterface = usbInterface;
                    mUsbDeviceConnection = connection;

                    Log.i(
                            LOG_TAG,
                            "USB device connected"
                    );

                    return true;

                } else {

                    connection.close();

                    Log.e(
                            LOG_TAG,
                            "failed to claim usb connection"
                    );

                    return false;
                }
            }
        }

        Log.e(
                LOG_TAG,
                "No suitable bulk-out endpoint found"
        );

        return false;
    }

    // ---------------------------------------------------------
    // LOW LEVEL USB WRITE
    // ---------------------------------------------------------

    /**
     * Single bulkTransfer() call for one chunk. Kept separate from
     * writeUsb() so raw prints (small payloads) can still call this
     * directly without going through the chunking wrapper.
     */
    private boolean writeUsbChunk(
            byte[] data,
            int offset,
            int length
    ) {

        if (
                mUsbDeviceConnection == null
                        || mEndPoint == null
        ) {

            return false;
        }

        byte[] chunk;

        if (offset == 0 && length == data.length) {
            chunk = data;
        } else {
            chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
        }

        int result =
                mUsbDeviceConnection.bulkTransfer(
                        mEndPoint,
                        chunk,
                        chunk.length,
                        USB_TIMEOUT
                );

        if (result < 0) {

            Log.e(
                    LOG_TAG,
                    "USB bulkTransfer failed. size="
                            + chunk.length
                            + " result="
                            + result
            );

            return false;
        }

        if (result != chunk.length) {

            Log.w(
                    LOG_TAG,
                    "Partial USB transfer. expected="
                            + chunk.length
                            + " actual="
                            + result
            );

            return false;
        }

        return true;
    }

    /**
     * Sends a (possibly large) buffer as a sequence of bulkTransfer()
     * calls, each capped at MAX_USB_CHUNK. This is purely a transport-
     * level safety measure — the printer still receives one continuous
     * raster stream with no extra commands or line feeds injected
     * between chunks, so the print head does not stop/start between
     * them the way it did with the old per-band ESC * approach.
     */
    private boolean writeUsb(
            byte[] data
    ) {

        if (
                data == null
                        || data.length == 0
        ) {
            return true;
        }

        int offset = 0;

        while (offset < data.length) {

            int length =
                    Math.min(
                            MAX_USB_CHUNK,
                            data.length - offset
                    );

            if (!writeUsbChunk(data, offset, length)) {
                return false;
            }

            offset += length;
        }

        return true;
    }

    // ---------------------------------------------------------
    // ESC/POS GS v 0 FULL RASTER BUILDER
    // ---------------------------------------------------------

    /**
     * Builds a single GS v 0 raster command containing the entire
     * image, replacing the old per-24-row ESC * band loop. The printer
     * receives width/height once and then a continuous bit-packed
     * pixel stream, letting the head print in one uninterrupted pass.
     */
private byte[] buildFullRasterImage(int[][] pixels) {

    if (pixels == null || pixels.length == 0) {
        return null;
    }

    int height = pixels.length;
    int width = pixels[0].length;
    int widthBytes = (width + 7) / 8; // 1 bit per pixel, MSB first

    ByteArrayOutputStream buffer =
            new ByteArrayOutputStream(
                    8 + (widthBytes * height)
            );

    // GS v 0 m xL xH yL yH
    buffer.write(0x1D); // GS
    buffer.write(0x76); // v
    buffer.write(0x30); // 0
    buffer.write(0x00); // m = normal mode, no scaling

    buffer.write(widthBytes & 0xFF);        // xL
    buffer.write((widthBytes >> 8) & 0xFF); // xH
    buffer.write(height & 0xFF);             // yL
    buffer.write((height >> 8) & 0xFF);      // yH

    for (int y = 0; y < height; y++) {

        int[] row = pixels[y];

        for (int bx = 0; bx < widthBytes; bx++) {

            byte b = 0;

            for (int bit = 0; bit < 8; bit++) {

                int x = bx * 8 + bit;

                if (x < width) {

                    boolean black =
                            UtilsImage.shouldPrintColor(row[x]);

                    if (black) {
                        b |= (byte) (1 << (7 - bit));
                    }
                }
            }

            buffer.write(b);
        }
    }

    return buffer.toByteArray();
}

    // ---------------------------------------------------------
    // RAW PRINT
    // ---------------------------------------------------------

    public void printRawData(
            String data,
            Callback errorCallback
    ) {

        if (!openConnection()) {

            errorCallback.invoke(
                    "failed to connect to device"
            );

            return;
        }

        try {

            byte[] bytes =
                    Base64.decode(
                            data,
                            Base64.DEFAULT
                    );

            if (!writeUsb(bytes)) {

                errorCallback.invoke(
                        "USB raw transfer failed"
                );
            }

        } catch (Exception e) {

            Log.e(
                    LOG_TAG,
                    "Raw print failed",
                    e
            );

            errorCallback.invoke(
                    "Raw print failed: "
                            + e.getMessage()
            );
        }
    }

    // ---------------------------------------------------------
    // URL IMAGE
    // ---------------------------------------------------------

    public static Bitmap getBitmapFromURL(
            String src
    ) {

        try {

            URL url =
                    new URL(src);

            HttpURLConnection connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setDoInput(true);
            connection.connect();

            InputStream input =
                    connection.getInputStream();

            Bitmap bitmap =
                    BitmapFactory.decodeStream(input);

            input.close();
            connection.disconnect();

            return bitmap;

        } catch (IOException e) {

            Log.e(
                    "RNUSBPrinter",
                    "Failed to download bitmap",
                    e
            );

            return null;
        }
    }

    // ---------------------------------------------------------
    // IMAGE PRINT
    // ---------------------------------------------------------

    @Override
    public void printImageData(
            final String imageUrl,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        final Bitmap bitmapImage =
                getBitmapFromURL(imageUrl);

        if (bitmapImage == null) {

            errorCallback.invoke(
                    "image not found"
            );

            return;
        }

        printBitmap(
                bitmapImage,
                imageWidth,
                imageHeight,
                errorCallback
        );
    }

    @Override
    public void printImageBase64(
            final Bitmap bitmapImage,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        if (bitmapImage == null) {

            errorCallback.invoke(
                    "image not found"
            );

            return;
        }

        printBitmap(
                bitmapImage,
                imageWidth,
                imageHeight,
                errorCallback
        );
    }

    private synchronized void printBitmap(
            Bitmap bitmapImage,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        if (!openConnection()) {

            errorCallback.invoke(
                    "failed to connect to device"
            );

            return;
        }

        try {

            Log.i(
                    LOG_TAG,
                    "Start printing bitmap: "
                            + bitmapImage.getWidth()
                            + "x"
                            + bitmapImage.getHeight()
            );

            int[][] pixels =
                    getPixelsSlow(
                            bitmapImage,
                            imageWidth,
                            imageHeight
                    );

            // Center alignment still applies to the raster block on
            // most ESC/POS clones.
            if (!writeUsb(CENTER_ALIGN)) {
                throw new IOException("Failed to set alignment");
            }

            byte[] rasterImage =
                    buildFullRasterImage(pixels);

            if (
                    rasterImage == null
                            || rasterImage.length == 0
            ) {

                throw new IOException(
                        "Failed to build raster image"
                );
            }

            Log.i(
                    LOG_TAG,
                    "Sending raster image: "
                            + rasterImage.length
                            + " bytes as one continuous stream ("
                            + ((rasterImage.length + MAX_USB_CHUNK - 1) / MAX_USB_CHUNK)
                            + " USB chunks)"
            );

            if (!writeUsb(rasterImage)) {
                throw new IOException("USB raster transfer failed");
            }

            if (!writeUsb(LINE_FEED)) {
                throw new IOException("Failed final line feed");
            }

            Log.i(
                    LOG_TAG,
                    "USB image print completed"
            );

        } catch (Exception e) {

            Log.e(
                    LOG_TAG,
                    "USB image print failed",
                    e
            );

            closeConnectionIfExists();

            errorCallback.invoke(
                    "Print failed: "
                            + e.getMessage()
            );
        }
    }
}