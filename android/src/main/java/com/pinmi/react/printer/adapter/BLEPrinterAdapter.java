package com.pinmi.react.printer.adapter;
import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;
import static com.pinmi.react.printer.adapter.UtilsImage.recollectSlice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BLEPrinterAdapter implements PrinterAdapter{


    private static BLEPrinterAdapter mInstance;

    private final String LOG_TAG =
            "RNBLEPrinter";

    private static final int BAND_HEIGHT = 24;

    private static final int WRITE_CHUNK_SIZE =
            4096;

    private BluetoothDevice mBluetoothDevice;
    private BluetoothSocket mBluetoothSocket;

    private ReactApplicationContext mContext;

    private final static char ESC_CHAR = 0x1B;
    private static final byte[] SELECT_BIT_IMAGE_MODE = { 0x1B, 0x2A, 33 };
    private final static byte[] SET_LINE_SPACE_24 = new byte[]{ ESC_CHAR, 0x33, 24 };

    private final static byte[] SET_LINE_SPACE_32 =
            new byte[]{
                    ESC_CHAR,
                    0x33,
                    32
            };

    private final static byte[] LINE_FEED =
            new byte[]{
                    0x0A
            };

    private static final byte[] CENTER_ALIGN = {
            0x1B,
            0x61,
            0x31
    };

    private BLEPrinterAdapter() {
    }

    public static BLEPrinterAdapter getInstance() {

        if (mInstance == null) {
            mInstance =
                    new BLEPrinterAdapter();
        }

        return mInstance;
    }

    // ---------------------------------------------------------
    // INITIALIZATION
    // ---------------------------------------------------------

    @Override
    public void init(
            ReactApplicationContext reactContext,
            Callback successCallback,
            Callback errorCallback
    ) {

        this.mContext =
                reactContext;

        BluetoothAdapter bluetoothAdapter =
                getBTAdapter();

        if (bluetoothAdapter == null) {

            errorCallback.invoke(
                    "No bluetooth adapter available"
            );

            return;
        }

        if (!bluetoothAdapter.isEnabled()) {

            errorCallback.invoke(
                    "bluetooth adapter is not enabled"
            );

            return;
        }

        successCallback.invoke();
    }

    private static BluetoothAdapter getBTAdapter() {

        return BluetoothAdapter.getDefaultAdapter();
    }

    // ---------------------------------------------------------
    // DEVICES
    // ---------------------------------------------------------

    @Override
    public List<PrinterDevice> getDeviceList(
            Callback errorCallback
    ) {

        BluetoothAdapter bluetoothAdapter =
                getBTAdapter();

        List<PrinterDevice> printerDevices =
                new ArrayList<>();

        if (bluetoothAdapter == null) {

            errorCallback.invoke(
                    "No bluetooth adapter available"
            );

            return printerDevices;
        }

        if (!bluetoothAdapter.isEnabled()) {

            errorCallback.invoke(
                    "bluetooth is not enabled"
            );

            return printerDevices;
        }

        Set<BluetoothDevice> pairedDevices =
                bluetoothAdapter.getBondedDevices();

        for (
                BluetoothDevice device :
                pairedDevices
        ) {

            printerDevices.add(
                    new BLEPrinterDevice(device)
            );
        }

        return printerDevices;
    }

    // ---------------------------------------------------------
    // CONNECTION
    // ---------------------------------------------------------

    @Override
    public synchronized void selectDevice(
            PrinterDeviceId printerDeviceId,
            Callback successCallback,
            Callback errorCallback
    ) {

        BluetoothAdapter bluetoothAdapter =
                getBTAdapter();

        if (bluetoothAdapter == null) {

            errorCallback.invoke(
                    "No bluetooth adapter available"
            );

            return;
        }

        if (!bluetoothAdapter.isEnabled()) {

            errorCallback.invoke(
                    "bluetooth is not enabled"
            );

            return;
        }

        BLEPrinterDeviceId blePrinterDeviceId =
                (BLEPrinterDeviceId)
                        printerDeviceId;

        String targetMac =
                blePrinterDeviceId
                        .getInnerMacAddress();

        if (
                this.mBluetoothDevice != null
                        && this.mBluetoothSocket != null
                        && this.mBluetoothDevice
                        .getAddress()
                        .equals(targetMac)
        ) {

            Log.v(
                    LOG_TAG,
                    "Already connected"
            );

            successCallback.invoke(
                    new BLEPrinterDevice(
                            this.mBluetoothDevice
                    ).toRNWritableMap()
            );

            return;
        }

        closeConnectionIfExists();

        Set<BluetoothDevice> pairedDevices =
                bluetoothAdapter.getBondedDevices();

        for (
                BluetoothDevice device :
                pairedDevices
        ) {

            if (
                    device.getAddress()
                            .equals(targetMac)
            ) {

                try {

                    connectBluetoothDevice(
                            device,
                            false
                    );

                    successCallback.invoke(
                            new BLEPrinterDevice(
                                    this.mBluetoothDevice
                            ).toRNWritableMap()
                    );

                    return;

                } catch (IOException e) {

                    Log.w(
                            LOG_TAG,
                            "Normal RFCOMM connection failed, retrying",
                            e
                    );

                    try {

                        connectBluetoothDevice(
                                device,
                                true
                        );

                        successCallback.invoke(
                                new BLEPrinterDevice(
                                        this.mBluetoothDevice
                                ).toRNWritableMap()
                        );

                        return;

                    } catch (IOException er) {

                        Log.e(
                                LOG_TAG,
                                "Bluetooth connection failed",
                                er
                        );

                        errorCallback.invoke(
                                er.getMessage()
                        );

                        return;
                    }
                }
            }
        }

        String errorText =
                "Can not find the specified printing device, "
                        + "please perform Bluetooth pairing "
                        + "in the system settings first.";

        Toast.makeText(
                this.mContext,
                errorText,
                Toast.LENGTH_LONG
        ).show();

        errorCallback.invoke(
                errorText
        );
    }

    private void connectBluetoothDevice(
            BluetoothDevice device,
            Boolean retry
    ) throws IOException {

        UUID uuid =
                UUID.fromString(
                        "00001101-0000-1000-8000-00805f9b34fb"
                );

        if (retry) {

            try {

                this.mBluetoothSocket =
                        (BluetoothSocket)
                                device.getClass()
                                        .getMethod(
                                                "createRfcommSocket",
                                                new Class[]{
                                                        int.class
                                                }
                                        )
                                        .invoke(
                                                device,
                                                1
                                        );

            } catch (Exception e) {

                throw new IOException(
                        "Failed to create fallback RFCOMM socket",
                        e
                );
            }

        } else {

            this.mBluetoothSocket =
                    device.createInsecureRfcommSocketToServiceRecord(
                            uuid
                    );

            this.mBluetoothSocket.connect();
        }

        this.mBluetoothDevice =
                device;
    }

    @Override
    public synchronized void closeConnectionIfExists() {

        try {

            if (this.mBluetoothSocket != null) {

                this.mBluetoothSocket.close();

            }

        } catch (IOException e) {

            Log.w(
                    LOG_TAG,
                    "Error closing Bluetooth socket",
                    e
            );

        } finally {

            this.mBluetoothSocket =
                    null;

            this.mBluetoothDevice =
                    null;
        }
    }

    // ---------------------------------------------------------
    // LOW LEVEL BLUETOOTH WRITE
    // ---------------------------------------------------------

    private void writeBluetooth(
            OutputStream outputStream,
            byte[] data
    ) throws IOException {

        if (
                data == null
                        || data.length == 0
        ) {
            return;
        }

        /*
         * RFCOMM is a stream.
         *
         * We still chunk very large buffers to avoid
         * putting a huge amount of data into one write().
         *
         * 4096 bytes is intentionally conservative for
         * inexpensive thermal printers.
         */
        int offset = 0;

        while (
                offset < data.length
        ) {

            int length =
                    Math.min(
                            WRITE_CHUNK_SIZE,
                            data.length - offset
                    );

            outputStream.write(
                    data,
                    offset,
                    length
            );

            outputStream.flush();

            offset += length;
        }
    }

    // ---------------------------------------------------------
    // ESC/POS RASTER BAND
    // ---------------------------------------------------------

    private byte[] buildRasterBand(
            int[][] pixels,
            int y
    ) {

        if (
                pixels == null
                        || pixels.length == 0
                        || y >= pixels.length
        ) {

            return null;
        }

        int widthBytes =
                pixels[y].length;

        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream(
                        8
                                + (
                                widthBytes
                                        * BAND_HEIGHT
                        )
                );

        try {

            // ESC * 33
            buffer.write(
                    SELECT_BIT_IMAGE_MODE
            );

            // nL
            buffer.write(
                    widthBytes & 0xFF
            );

            // nH
            buffer.write(
                    (widthBytes >> 8) & 0xFF
            );

            /*
             * Every x contains one 24-dot vertical
             * slice = 3 bytes.
             */
            for (
                    int x = 0;
                    x < widthBytes;
                    x++
            ) {

                byte[] slice =
                        recollectSlice(
                                y,
                                x,
                                pixels
                        );

                if (slice != null) {

                    buffer.write(
                            slice
                    );
                }
            }

            // Move to next raster line.
            buffer.write(
                    LINE_FEED
            );

        } catch (IOException e) {

            Log.e(
                    LOG_TAG,
                    "Failed to build raster band",
                    e
            );

            return null;
        }

        return buffer.toByteArray();
    }

    // ---------------------------------------------------------
    // RAW DATA
    // ---------------------------------------------------------

    @Override
    public synchronized void printRawData(
            String rawBase64Data,
            Callback errorCallback
    ) {

        if (
                this.mBluetoothSocket == null
        ) {

            errorCallback.invoke(
                    "bluetooth connection is not built, "
                            + "may be you forgot to connectPrinter"
            );

            return;
        }

        try {

            byte[] bytes =
                    Base64.decode(
                            rawBase64Data,
                            Base64.DEFAULT
                    );

            OutputStream outputStream =
                    this.mBluetoothSocket
                            .getOutputStream();

            if (outputStream == null) {

                errorCallback.invoke(
                        "printer output stream is not available"
                );

                return;
            }

            writeBluetooth(
                    outputStream,
                    bytes
            );

            Log.i(
                    LOG_TAG,
                    "Raw print completed"
            );

        } catch (Exception e) {

            Log.e(
                    LOG_TAG,
                    "Raw print failed",
                    e
            );

            closeConnectionIfExists();

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

        HttpURLConnection connection =
                null;

        InputStream input =
                null;

        try {

            URL url =
                    new URL(src);

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setDoInput(true);
            connection.connect();

            input =
                    connection.getInputStream();

            return BitmapFactory.decodeStream(
                    input
            );

        } catch (IOException e) {

            Log.e(
                    "RNBLEPrinter",
                    "Failed to download bitmap",
                    e
            );

            return null;

        } finally {

            try {

                if (input != null) {
                    input.close();
                }

            } catch (IOException ignored) {
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------
    // IMAGE DATA
    // ---------------------------------------------------------

    @Override
    public synchronized void printImageData(
            String imageUrl,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        Bitmap bitmapImage =
                getBitmapFromURL(
                        imageUrl
                );

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

    // ---------------------------------------------------------
    // IMAGE BASE64
    // ---------------------------------------------------------

    @Override
    public synchronized void printImageBase64(
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

    // ---------------------------------------------------------
    // PRINT BITMAP
    // ---------------------------------------------------------

    private synchronized void printBitmap(
            Bitmap bitmapImage,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        if (
                this.mBluetoothSocket == null
        ) {

            errorCallback.invoke(
                    "bluetooth connection is not built, "
                            + "may be you forgot to connectPrinter"
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

            OutputStream outputStream =
                    this.mBluetoothSocket
                            .getOutputStream();

            if (outputStream == null) {

                errorCallback.invoke(
                        "printer output stream is not available"
                );

                return;
            }

            // Set 24-dot line spacing
            writeBluetooth(
                    outputStream,
                    SET_LINE_SPACE_24
            );

            // Center alignment
            writeBluetooth(
                    outputStream,
                    CENTER_ALIGN
            );

            int totalBands =
                    (
                            pixels.length
                                    + BAND_HEIGHT
                                    - 1
                    )
                            / BAND_HEIGHT;

            Log.i(
                    LOG_TAG,
                    "Printing "
                            + totalBands
                            + " raster bands"
            );

            for (
                    int y = 0;
                    y < pixels.length;
                    y += BAND_HEIGHT
            ) {

                byte[] band =
                        buildRasterBand(
                                pixels,
                                y
                        );

                if (
                        band == null
                                || band.length == 0
                ) {

                    throw new IOException(
                            "Failed to build raster band at y="
                                    + y
                    );
                }

                writeBluetooth(
                        outputStream,
                        band
                );

                Log.d(
                        LOG_TAG,
                        "Band "
                                + (
                                (y / BAND_HEIGHT)
                                        + 1
                        )
                                + "/"
                                + totalBands
                                + " sent, "
                                + band.length
                                + " bytes"
                );
            }

            // Restore line spacing
            writeBluetooth(
                    outputStream,
                    SET_LINE_SPACE_32
            );

            // Final line feed
            writeBluetooth(
                    outputStream,
                    LINE_FEED
            );

            outputStream.flush();

            Log.i(
                    LOG_TAG,
                    "Bluetooth image print completed"
            );

        } catch (Exception e) {

            Log.e(
                    LOG_TAG,
                    "Bluetooth image print failed",
                    e
            );

            closeConnectionIfExists();

            errorCallback.invoke(
                    "Print image failed: "
                            + e.getMessage()
            );
        }
    }
}