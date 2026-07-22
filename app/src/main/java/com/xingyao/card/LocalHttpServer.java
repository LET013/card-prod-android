package com.xingyao.card;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class LocalHttpServer {

    private static final String TAG = "LocalHttpServer";
    private static final int BUFFER_SIZE = 8192;

    private Context context;
    private int port;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private boolean isRunning = false;

    public LocalHttpServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    public void start() throws IOException {
        if (isRunning) {
            Log.w(TAG, "Server is already running");
            return;
        }

        serverSocket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
        isRunning = true;

        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        handleClient(clientSocket);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting connection", e);
                        }
                    }
                }
            }
        }, "LocalHttpServer");

        serverThread.start();
        Log.d(TAG, "Local HTTP server started on port " + port);
    }

    public void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            serverSocket = null;
        }
        if (serverThread != null) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }
        Log.d(TAG, "Local HTTP server stopped");
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead = in.read(buffer);
            if (bytesRead <= 0) {
                return;
            }

            String request = new String(buffer, 0, bytesRead, "UTF-8");
            String[] lines = request.split("\r\n");
            if (lines.length == 0) {
                return;
            }

            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) {
                return;
            }

            String method = requestLine[0];
            String uri = requestLine[1];
            Log.d(TAG, "Request: " + method + " " + uri);

            if (!"GET".equalsIgnoreCase(method)) {
                sendResponse(out, 405, "Method Not Allowed", "text/plain", "Method not allowed");
                return;
            }

            if (uri.equals("/") || uri.equals("/index.html")) {
                uri = "/index.html";
            }

            try {
                InputStream assetStream = context.getAssets().open(uri.substring(1));
                String mimeType = getMimeType(uri);
                byte[] data = readFully(assetStream);
                sendResponse(out, 200, "OK", mimeType, data);
            } catch (IOException e) {
                Log.d(TAG, "File not found: " + uri);
                sendResponse(out, 404, "Not Found", "text/plain", "File not found: " + uri);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error handling client", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }

    private void sendResponse(BufferedOutputStream out, int statusCode, String statusMessage,
                              String contentType, String content) throws IOException {
        sendResponse(out, statusCode, statusMessage, contentType, content.getBytes("UTF-8"));
    }

    private void sendResponse(BufferedOutputStream out, int statusCode, String statusMessage,
                              String contentType, byte[] content) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
        response.append("Content-Type: ").append(contentType).append("; charset=UTF-8\r\n");
        response.append("Content-Length: ").append(content.length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");

        out.write(response.toString().getBytes("UTF-8"));
        out.write(content);
        out.flush();
    }

    private byte[] readFully(InputStream in) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }

    private String getMimeType(String path) {
        if (path.endsWith(".js")) {
            return "application/javascript";
        } else if (path.endsWith(".mjs")) {
            return "application/javascript";
        } else if (path.endsWith(".css")) {
            return "text/css";
        } else if (path.endsWith(".html")) {
            return "text/html";
        } else if (path.endsWith(".json")) {
            return "application/json";
        } else if (path.endsWith(".png")) {
            return "image/png";
        } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (path.endsWith(".gif")) {
            return "image/gif";
        } else if (path.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (path.endsWith(".woff") || path.endsWith(".woff2")) {
            return "font/woff";
        } else if (path.endsWith(".ttf")) {
            return "font/ttf";
        } else if (path.endsWith(".ico")) {
            return "image/x-icon";
        } else if (path.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private static class ByteArrayOutputStream {
        private byte[] buffer = new byte[1024];
        private int size = 0;

        public void write(byte[] data, int offset, int length) {
            ensureCapacity(size + length);
            System.arraycopy(data, offset, buffer, size, length);
            size += length;
        }

        public byte[] toByteArray() {
            byte[] result = new byte[size];
            System.arraycopy(buffer, 0, result, 0, size);
            return result;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > buffer.length) {
                int newCapacity = Math.max(buffer.length * 2, minCapacity);
                byte[] newBuffer = new byte[newCapacity];
                System.arraycopy(buffer, 0, newBuffer, 0, size);
                buffer = newBuffer;
            }
        }
    }
}