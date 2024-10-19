package de.linux4.samsungfwbot;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class CapSolver {

    private static final String CAPSOLVER_API = "https://api.capsolver.com/";
    private static final String CAPSOLVER_CREATE = CAPSOLVER_API + "createTask";
    private static final String CAPSOLVER_GET = CAPSOLVER_API + "getTaskResult";
    private static final String CAPSOLVER_APP_ID = "83CEF493-F610-44C4-BCA2-9783EB4823E4";

    public enum CaptchaType {
        HCAPTCHA("HCaptchaTaskProxyLess");

        public final String taskType;

        CaptchaType(String taskType) {
            this.taskType = taskType;
        }
    }

    private final String apiKey;

    public CapSolver(String apiKey) {
        this.apiKey = apiKey;

        new File("db/capsolver").mkdirs();
    }

    public String solve(CaptchaType type, String siteKey, String siteUrl) {
        JSONObject payload = new JSONObject();
        payload.put("clientKey", apiKey);
        payload.put("appId", CAPSOLVER_APP_ID);
        JSONObject task = new JSONObject();
        task.put("type", type.taskType);
        task.put("websiteKey", siteKey);
        task.put("websiteURL", siteUrl);
        payload.put("task", task);

        boolean success = false;
        int retries = 0;
        do {
            retries++;

            try {
                URL url = new URL(CAPSOLVER_CREATE);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoInput(true);
                conn.setDoOutput(true);

                PrintWriter writer = new PrintWriter(new OutputStreamWriter(conn.getOutputStream()));
                writer.println(payload);
                writer.close();

                InputStream stream = conn.getErrorStream();
                if (stream == null) {
                    stream = conn.getInputStream();
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder respBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    respBuilder.append(line).append('\n');
                }
                reader.close();

                JSONObject resp = new JSONObject(respBuilder.toString());
                String taskId = resp.getString("taskId");
                if (taskId != null) {
                    String status = "";
                    while (!status.equalsIgnoreCase("ready")
                            && !status.equalsIgnoreCase("failed")
                            && resp.get("errorId") != null) {
                        Thread.sleep(1000);
                        payload = new JSONObject();
                        payload.put("clientKey", apiKey);
                        payload.put("taskId", taskId);

                        url = new URL(CAPSOLVER_GET);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoInput(true);
                        conn.setDoOutput(true);

                        writer = new PrintWriter(new OutputStreamWriter(conn.getOutputStream()));
                        writer.println(payload);
                        writer.close();

                        reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        respBuilder = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                            respBuilder.append(line).append('\n');
                        }
                        reader.close();

                        resp = new JSONObject(respBuilder.toString());
                        status = resp.getString("status");
                    }

                    if (status.equalsIgnoreCase("ready")) {
                        String solution = resp.getJSONObject("solution").getString("gRecaptchaResponse");
                        FileUtils.write(new File("db/capsolver/" + siteKey), solution);

                        return solution;
                    }
                } else {
                    System.err.println("Failed to create task");
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        } while (!success && retries < 10);

        System.err.println("Capsolver failed");
        return null;
    }

    public String solveCached(CaptchaType type, String siteKey, String siteUrl) {
        File file = new File("db/capsolver/" + siteKey);

        if (file.exists()) {
            try {
                return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return solve(type, siteKey, siteUrl);
    }
}
