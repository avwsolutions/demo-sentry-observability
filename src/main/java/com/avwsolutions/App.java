package com.avwsolutions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class App {

    static List<byte[]> leakyList = new ArrayList<>();
    static Random random = new Random();

    public static void main(String[] args) throws Exception {

        Sentry.init(options -> {
            options.setDsn(System.getenv("SENTRY_DSN"));

            // Capture everything for demo
            options.setTracesSampleRate(1.0);

            options.setDebug(true);

            options.setEnvironment("demo");
        });

        HttpServer server =
                HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", App::handleRoot);
        server.createContext("/error", App::handleError);
        server.createContext("/slow", App::handleSlow);
        server.createContext("/leak", App::handleLeak);

        server.setExecutor(null);

        server.start();

        System.out.println("Server started on port 8080");

        // Background traffic generator
        startTrafficGenerator();
    }

    static void startTrafficGenerator() {

        Thread t = new Thread(() -> {

            while (true) {

                try {

                    int choice = random.nextInt(100);

                    if (choice < 50) {

                        callEndpoint("/");

                    } else if (choice < 75) {

                        callEndpoint("/slow");

                    } else if (choice < 90) {

                        callEndpoint("/error");

                    } else {

                        callEndpoint("/leak");
                    }

                    Thread.sleep(2000);

                } catch (Exception e) {

                    Sentry.captureException(e);
                }
            }
        });

        t.setDaemon(true);
        t.start();
    }

    static void callEndpoint(String path) {

        try {

            URL url =
                    new URL("http://localhost:8080" + path);

            HttpURLConnection con =
                    (HttpURLConnection) url.openConnection();

            con.setRequestMethod("GET");

            int code = con.getResponseCode();

            System.out.println(
                    "Called " + path + " -> " + code);

            con.disconnect();

        } catch (Exception e) {

            Sentry.captureException(e);
        }
    }

    static void handleRoot(HttpExchange exchange)
            throws IOException {

        ITransaction transaction =
                Sentry.startTransaction("/", "http.server");

        String response = "OK";

        exchange.sendResponseHeaders(200, response.length());

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }

        transaction.finish(SpanStatus.OK);
    }

    static void handleError(HttpExchange exchange)
            throws IOException {

        ITransaction transaction =
                Sentry.startTransaction("/error", "http.server");

        try {

            // Intentional crash
            int[] arr = new int[1];
            int crash = arr[5];

            String response = String.valueOf(crash);

            exchange.sendResponseHeaders(200, response.length());

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }

            transaction.finish(SpanStatus.OK);

        } catch (Exception e) {

            Sentry.captureException(e);

            String response = "Intentional error triggered";

            exchange.sendResponseHeaders(500, response.length());

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }

            transaction.finish(SpanStatus.INTERNAL_ERROR);
        }
    }

    static void handleSlow(HttpExchange exchange)
            throws IOException {

        ITransaction transaction =
                Sentry.startTransaction("/slow", "http.server");

        try {

            // Random slowness
            int sleep =
                    1000 + random.nextInt(5000);

            Thread.sleep(sleep);

            String response =
                    "Slow endpoint took " + sleep + "ms";

            exchange.sendResponseHeaders(200, response.length());

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }

            transaction.finish(SpanStatus.OK);

        } catch (InterruptedException e) {

            Sentry.captureException(e);

            transaction.finish(SpanStatus.INTERNAL_ERROR);
        }
    }

    static void handleLeak(HttpExchange exchange)
            throws IOException {

        ITransaction transaction =
                Sentry.startTransaction("/leak", "http.server");

        // Leak between 1MB and 5MB randomly
        int mb = 1 + random.nextInt(5);

        leakyList.add(new byte[mb * 1024 * 1024]);

        String response =
                "Leaked " + mb +
                "MB | Total allocations: " +
                leakyList.size();

        exchange.sendResponseHeaders(200, response.length());

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }

        transaction.finish(SpanStatus.OK);
    }
}