package app.outerheaven.compute;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class ComputeService extends Service {
    private static final int PORT = 8765;
    private static final int MAX_FILE = 50 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    private volatile ServerSocket server;
    private ExecutorService connections;

    @Override
    public void onCreate() {
        super.onCreate();

        String channel = "ohc_server";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
            channel, "Outer Heaven Compute", NotificationManager.IMPORTANCE_LOW));

        Notification notification = new Notification.Builder(this, channel)
            .setContentTitle("Outer Heaven Compute")
            .setContentText("Servidor ativo na porta " + PORT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build();

        startForeground(1, notification);
        connections = Executors.newFixedThreadPool(4);
        startServer();
    }

    private void startServer() {
        new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;

                while (!Thread.currentThread().isInterrupted()) {
                    Socket client = ss.accept();
                    client.setSoTimeout(60000);
                    connections.execute(() -> handle(client));
                }
            } catch (Exception e) {
                Log.e("OHC", "Servidor encerrado", e);
            }
        }, "OHC-Server").start();
    }

    private void handle(Socket socket) {
        File temporaryUpload = null;

        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String line = readLine(in);
            if (line == null || line.isEmpty()) return;

            String[] request = line.split(" ");
            if (request.length < 2) return;

            Map<String,String> headers = new HashMap<>();
            String h;

            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int p = h.indexOf(':');
                if (p > 0) {
                    headers.put(
                        h.substring(0, p).trim().toLowerCase(Locale.ROOT),
                        h.substring(p + 1).trim()
                    );
                }
            }

            String expected = getSharedPreferences("ohc", 0)
                .getString("token", "");
            String supplied = headers.getOrDefault("x-token", "");

            if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
                respond(out, 401, "application/json",
                    bytes("{\"error\":\"Token inválido\"}"));
                return;
            }

            URI uri = new URI(request[1]);
            Map<String,String> query = parseQuery(uri.getRawQuery());
            String path = uri.getPath();
            String method = request[0];

            if ("GET".equals(method) && "/status".equals(path)) {
                respond(out, 200, "application/json",
                    status().toString().getBytes(StandardCharsets.UTF_8));

            } else if ("GET".equals(method) && "/compute".equals(path)) {
                int seconds = Math.max(1, Math.min(30,
                    Integer.parseInt(query.getOrDefault("seconds", "5"))));

                int workers = Math.max(1, Math.min(8,
                    Math.min(Runtime.getRuntime().availableProcessors(),
                    Integer.parseInt(query.getOrDefault("workers", "8")))));

                respond(out, 200, "application/json",
                    benchmark(seconds, workers).toString()
                        .getBytes(StandardCharsets.UTF_8));

            } else if ("GET".equals(method) && "/files".equals(path)) {
                respond(out, 200, "application/json",
                    files().toString().getBytes(StandardCharsets.UTF_8));

            } else if ("POST".equals(method) && "/upload".equals(path)) {
                String name = safeName(query.get("name"));

                if (name == null) {
                    respond(out, 400, "text/plain", bytes("Nome inválido"));
                    return;
                }

                long length;
                try {
                    length = Long.parseLong(
                        headers.getOrDefault("content-length", "-1"));
                } catch (NumberFormatException e) {
                    respond(out, 400, "text/plain",
                        bytes("Content-Length inválido"));
                    return;
                }

                if (length < 0) {
                    respond(out, 400, "text/plain",
                        bytes("Content-Length obrigatório"));
                    return;
                }

                if (length > MAX_FILE) {
                    respond(out, 413, "text/plain", bytes("Limite: 50 MB"));
                    return;
                }

                File directory = sharedDir();
                temporaryUpload = File.createTempFile(
                    "ohc-upload-", ".part", directory);

                try (FileOutputStream fos =
                         new FileOutputStream(temporaryUpload)) {
                    copyExactly(in, fos, length);
                }

                File target = new File(directory, name);

                Files.move(
                    temporaryUpload.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );

                temporaryUpload = null;

                JSONObject result = new JSONObject();
                result.put("saved", name);
                result.put("bytes", length);

                respond(out, 200, "application/json",
                    result.toString().getBytes(StandardCharsets.UTF_8));

            } else if ("GET".equals(method) && "/download".equals(path)) {
                String name = safeName(query.get("name"));

                if (name == null) {
                    respond(out, 400, "text/plain", bytes("Nome inválido"));
                    return;
                }

                File file = new File(sharedDir(), name);

                if (!file.isFile()) {
                    respond(out, 404, "text/plain",
                        bytes("Arquivo não encontrado"));
                    return;
                }

                streamFile(out, file);

            } else {
                respond(out, 404, "application/json",
                    bytes("{\"error\":\"Rota inexistente\"}"));
            }

        } catch (Exception e) {
            Log.e("OHC", "Falha na requisição", e);

        } finally {
            if (temporaryUpload != null && temporaryUpload.exists()) {
                temporaryUpload.delete();
            }
        }
    }

    private JSONObject status() throws Exception {
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE))
            .getMemoryInfo(mem);

        JSONObject o = new JSONObject();
        o.put("device", Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("architecture", Build.SUPPORTED_ABIS[0]);
        o.put("cpu_threads", Runtime.getRuntime().availableProcessors());
        o.put("ram_total_mb", mem.totalMem / 1048576);
        o.put("ram_available_mb", mem.availMem / 1048576);
        o.put("port", PORT);
        return o;
    }

    private JSONObject benchmark(int seconds, int workers) throws Exception {
        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(seconds);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Long>> jobs = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            jobs.add(pool.submit(() -> {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] data = new byte[1024];
                long count = 0;

                while (System.nanoTime() < deadline) {
                    digest.update(data);
                    data = digest.digest();
                    count++;
                }

                return count;
            }));
        }

        pool.shutdown();

        long operations = 0;
        for (Future<Long> job : jobs) {
            operations += job.get(seconds + 10, TimeUnit.SECONDS);
        }

        JSONObject o = new JSONObject();
        o.put("task", "SHA-256 benchmark");
        o.put("workers", workers);
        o.put("seconds", seconds);
        o.put("operations", operations);
        o.put("elapsed_ms",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        return o;
    }

    private JSONObject files() throws Exception {
        JSONArray array = new JSONArray();
        File[] list = sharedDir().listFiles();

        if (list != null) {
            for (File f : list) {
                if (f.isFile()) {
                    JSONObject item = new JSONObject();
                    item.put("name", f.getName());
                    item.put("bytes", f.length());
                    array.put(item);
                }
            }
        }

        JSONObject o = new JSONObject();
        o.put("files", array);
        return o;
    }

    private File sharedDir() {
        File dir = getExternalFilesDir("shared");

        if (dir == null) {
            dir = new File(getFilesDir(), "shared");
        }

        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String safeName(String name) {
        if (name == null
            || !name.matches("[A-Za-z0-9._-]{1,80}")
            || name.equals(".")
            || name.equals("..")) {
            return null;
        }

        return name;
    }

    private Map<String,String> parseQuery(String raw) throws Exception {
        Map<String,String> map = new HashMap<>();

        if (raw == null) return map;

        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);

            map.put(
                URLDecoder.decode(pair[0], "UTF-8"),
                pair.length > 1
                    ? URLDecoder.decode(pair[1], "UTF-8")
                    : ""
            );
        }

        return map;
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;

        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }

        if (c == -1 && b.size() == 0) return null;

        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private void copyExactly(
        InputStream in, OutputStream out, long length
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;

        while (remaining > 0) {
            int amount = (int)Math.min(buffer.length, remaining);
            int count = in.read(buffer, 0, amount);

            if (count < 0) {
                throw new EOFException("Upload incompleto");
            }

            out.write(buffer, 0, count);
            remaining -= count;
        }

        out.flush();
    }

    private void streamFile(OutputStream out, File file)
        throws IOException {
        long length = file.length();

        String header =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: " + length + "\r\n" +
            "Connection: close\r\n\r\n";

        out.write(header.getBytes(StandardCharsets.US_ASCII));

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;

            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }

        out.flush();
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void respond(
        OutputStream out, int code, String type, byte[] body
    ) throws IOException {
        String reason =
            code == 200 ? "OK" :
            code == 400 ? "Bad Request" :
            code == 401 ? "Unauthorized" :
            code == 404 ? "Not Found" :
            code == 413 ? "Payload Too Large" :
            "Internal Server Error";

        String header =
            "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Connection: close\r\n\r\n";

        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (server != null) server.close();
            if (connections != null) connections.shutdownNow();
        } catch (Exception ignored) {}

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
