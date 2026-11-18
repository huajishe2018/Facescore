package com.example.facescore;

import android.graphics.Bitmap;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

final class BackendClient {
    private static final String BASE = BuildConfig.FACESCORE_BACKEND_URL;

    static String signIn(String username, String password, boolean register) throws Exception {
        byte[] body = new JSONObject().put("username", username).put("password", password)
                .toString().getBytes(StandardCharsets.UTF_8);
        return post(register ? "/register" : "/login", "application/json", body, null)
                .getString("token");
    }

    static String review(Bitmap photo, String session) throws Exception {
        int edge = Math.max(photo.getWidth(), photo.getHeight());
        float scale = Math.min(1f, 1024f / edge);
        Bitmap image = Bitmap.createScaledBitmap(photo, Math.max(1, Math.round(photo.getWidth() * scale)),
                Math.max(1, Math.round(photo.getHeight() * scale)), true);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (!image.compress(Bitmap.CompressFormat.JPEG, 85, data)) throw new IOException("照片编码失败");
        return post("/review", "image/jpeg", data.toByteArray(), session).getString("review");
    }

    private static JSONObject post(String path, String type, byte[] body, String token) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(BASE + path).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(55000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", type);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (var out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            try (var in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                if (in == null) throw new IOException("服务器返回 " + status);
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (response.size() + count > 65536) throw new IOException("响应过长");
                    response.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(response.toString("UTF-8"));
                if (status != 200) throw new ApiException(status, result.optString("error", "请求失败"));
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private BackendClient() {}
}
