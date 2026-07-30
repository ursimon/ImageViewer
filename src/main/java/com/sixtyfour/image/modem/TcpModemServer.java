package com.sixtyfour.image.modem;

import com.sixtyfour.image.Blob;
import com.sixtyfour.image.ImageCache;
import com.sixtyfour.image.ImageViewerService;
import com.sixtyfour.image.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

public class TcpModemServer {

    private static final int DEFAULT_PORT = 8192;
    private static final int CLIENT_IDLE_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int DEFAULT_BAUD_RATE = 600;
    private static final int BITS_PER_BYTE_ON_WIRE = 8;

    private static final String IMAGE_PATH = "/imagedata/";
    private static ImageViewerService service = new ImageViewerService();
    private static final ThreadLocal<BaudRateState> BAUD_RATE_STATE =
            ThreadLocal.withInitial(() -> new BaudRateState(DEFAULT_BAUD_RATE));

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                Logger.log("Invalid port: " + args[0] + ". Using default " + DEFAULT_PORT);
                port = DEFAULT_PORT;
            }
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Logger.log("TCP modem server listening on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(clientSocket));
                clientThread.setDaemon(true);
                clientThread.start();
            }
        } catch (IOException e) {
            Logger.log("Server error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (Socket socket = clientSocket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            String remote = socket.getRemoteSocketAddress().toString();
            Logger.log("Client connected: " + remote);
            socket.setSoTimeout(CLIENT_IDLE_TIMEOUT_MS);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("ile")) {
                    line="f"+line;
                }
                Logger.log("[" + remote + "] [" + line + "]");
                if ("/exit/".equalsIgnoreCase(line.trim())) {
                    Logger.log("Exit command received from: " + remote);
                    break;
                }
                Map<String, String> params = parseParameters(line);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                process(params, os);
                byte[] res = os.toByteArray();
                Logger.log("Content length: "+res.length);
                writeBaudLimitedByte(socket.getOutputStream(), 255);
                writeBaudLimitedByte(socket.getOutputStream(), (res.length & 255));
                writeBaudLimitedByte(socket.getOutputStream(), (res.length / 256));
                writeBaudLimitedBytes(socket.getOutputStream(), res);
            }

            Logger.log("Client disconnected: " + remote);
        } catch (SocketTimeoutException e) {
            Logger.log("Client timed out after 5 minutes of inactivity: " + clientSocket.getRemoteSocketAddress(), e);
        } catch (IOException e) {
            Logger.log("Client handling error (1): " + e.getMessage(), e);
        } catch (Exception e) {
            Logger.log("Client handling error (2): " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }


    private static Map<String, String> parseParameters(String input) {
        Map<String, String> params = new LinkedHashMap<>();
        if (input == null || input.trim().isEmpty()) {
            return params;
        }

        String query = input.trim();
        int qPos = query.indexOf('?');
        if (qPos >= 0) {
            if (qPos + 1 < query.length()) {
                query = query.substring(qPos + 1);
            } else {
                return params;
            }
        }

        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }

            int eq = pair.indexOf('=');
            String key;
            String value;

            if (eq < 0) {
                key = pair;
                value = "";
            } else {
                key = pair.substring(0, eq);
                value = pair.substring(eq + 1);
            }

            key = URLDecoder.decode(key, StandardCharsets.UTF_8).trim();
            value = URLDecoder.decode(value, StandardCharsets.UTF_8).trim();

            if (!key.isEmpty()) {
                params.put(key, value);
            }
        }

        return params;
    }

    private static void process(Map<String, String> params, OutputStream os) throws Exception {
        String clear = params.get("clear");
        if (clear != null) {
            ImageCache.clear();
        }

        String file = params.get("file");
        boolean needsCropping = false;
        boolean d42Mode = false;

        if (service.URL_SHORTENER.containsKey(file)) {
            needsCropping = file.contains("ai=1");
            d42Mode = file.contains("d42=1");
            Logger.log("Replacing URL " + file + " with " + service.URL_SHORTENER.get(file));
            file = service.URL_SHORTENER.get(file);
        }

        boolean hires = params.get("hi") != null && params.get("hi").equals("1");

        if (hires) {
            Logger.log("Hires mode enabled!");
        }

        if (file==null) {
            Logger.log("File is null!");
            return;
        }

        if (file.startsWith("empty:")) {
            Logger.log("Sending empty reply!");
            os.flush();
            return;
        }

        String dither = params.get("dither");
        boolean keepRatio = Boolean.parseBoolean(params.get("ar"));
        if (file.contains("..") || file.contains("\\") || file.startsWith("/")) {
            Logger.log("Invalid file name: " + file);
            service.printError(os, "Invalid file name!");
            return;
        }
        float dithy = 1;
        if (dither != null) {
            try {
                dithy = Float.parseFloat(dither) / 100f;
                dithy = Math.min(1, Math.max(0, dithy));
            } catch (Exception e) {
                //
            }
        }
        Logger.log("Dithering is set to " + dithy);

        String key = ImageCache.getKey(file, dithy, keepRatio, hires);
        Blob blob = ImageCache.get(key);
        if (blob == null) {
            blob = service.convert(file, IMAGE_PATH, os, dithy, keepRatio, needsCropping, d42Mode, hires);
            if (blob == null) {
                // No image but a file list...
                return;
            }
            ImageCache.put(key, blob);
            if (blob.isError()) {
                // The actual error has already been transmitted by the convert()-method
                return;
            }
        }
        if (blob.isError()) {
            // Cached error, re-transmit it...
            service.printError(os, blob.getError());
            return;
        }

        try (InputStream is = blob.getAsStream()) {
            // Transfer whole blob...
            is.transferTo(os);
        } catch (Exception e) {
            Logger.log("Failed to transfer file: " + blob.getSource(), e);
            return;
        }
        os.flush();
        Logger.log("Download and conversion finished!");
    }

    public static void setThreadBaudRate(int baudRate) {
        if (baudRate <= 0) {
            throw new IllegalArgumentException("Baud rate must be > 0 but was " + baudRate);
        }
        BaudRateState state = BAUD_RATE_STATE.get();
        state.baudRate = baudRate;
        state.nextWriteNanos = 0;
    }

    public static void writeBaudLimitedByte(OutputStream os, int value) throws IOException {
        writeBaudLimitedByte(os, value, BAUD_RATE_STATE.get().baudRate);
    }

    public static void writeBaudLimitedByte(OutputStream os, int value, int baudRate) throws IOException {
        throttleCurrentThread(baudRate);
        os.write(value & 0xff);
    }

    public static void writeBaudLimitedString(OutputStream os, String value) throws IOException {
        writeBaudLimitedString(os, value, BAUD_RATE_STATE.get().baudRate);
    }

    public static void writeBaudLimitedString(OutputStream os, String value, int baudRate) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        writeBaudLimitedBytes(os, value.getBytes(StandardCharsets.UTF_8), baudRate);
    }

    private static void writeBaudLimitedBytes(OutputStream os, byte[] data) throws IOException {
        writeBaudLimitedBytes(os, data, BAUD_RATE_STATE.get().baudRate);
    }

    private static void writeBaudLimitedBytes(OutputStream os, byte[] data, int baudRate) throws IOException {
        if (data == null || data.length == 0) {
            return;
        }
        for (byte datum : data) {
            writeBaudLimitedByte(os, datum, baudRate);
        }
    }

    private static void throttleCurrentThread(int baudRate) {
        if (baudRate <= 0) {
            throw new IllegalArgumentException("Baud rate must be > 0 but was " + baudRate);
        }

        BaudRateState state = BAUD_RATE_STATE.get();
        if (state.baudRate != baudRate) {
            state.baudRate = baudRate;
            state.nextWriteNanos = 0;
        }

        long now = System.nanoTime();
        if (state.nextWriteNanos > now) {
            LockSupport.parkNanos(state.nextWriteNanos - now);
        }

        long nanosPerByte = Math.max(1L, (BITS_PER_BYTE_ON_WIRE * 1_000_000_000L) / baudRate);
        long scheduleBase = Math.max(System.nanoTime(), state.nextWriteNanos);
        state.nextWriteNanos = scheduleBase + nanosPerByte;
    }

    private static final class BaudRateState {
        private int baudRate;
        private long nextWriteNanos;

        private BaudRateState(int baudRate) {
            this.baudRate = baudRate;
            this.nextWriteNanos = 0;
        }
    }
}
