package id.sequre.sdk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Utils {
    public static final String URL = "https://smobile.sequre.id";

    public static final String ACTION_VALIDATE = "/api/sdk/validate";
    public static final String ACTION_CHECK_QR = "/api/check-qr";
    private static OkHttpClient client = new OkHttpClient();

    public static void api(Context context, final String method, final String path, JSONObject payload, final Callback callback) {
        RequestBody body = createBody(payload);
        Request.Builder builder = new Request.Builder().url(Utils.URL + path);
        if (method.equalsIgnoreCase("post")) {
            builder.post(body);
        } else if (method.equalsIgnoreCase("put")) {
            builder.put(body);
        }
        Request request = builder.build();
        Utils.client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                call.cancel();
                e.printStackTrace();
                callback.onFinish(null);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    JSONObject payload = new JSONObject(response.body().string());
                    ApiResponse apiResponse = new ApiResponse();
                    if (payload.has("code")) {
                        apiResponse.code = Integer.parseInt(String.format("%s", payload.get("code")));
                    }
                    if (payload.has("message")) {
                        apiResponse.message = payload.getString("message");
                    }
                    if (payload.has("status")) {
                        apiResponse.status = payload.getString("status");
                    }
                    if (payload.has("data")) {
                        apiResponse.data = payload.get("data");
                    }
                    callback.onFinish(apiResponse);
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                }
                callback.onFinish(null);
            }
        });
    }

    private static RequestBody createBody(JSONObject payload) {
        // check if payload is multipart
        boolean multipart = false;
        Iterator<String> it = payload.keys();
        while (it.hasNext()) {
            try {
                String key = it.next();
                if (payload.get(key) instanceof JSONObject) {
                    multipart = true;
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (multipart) {
            MultipartBody.Builder builder = new MultipartBody.Builder();
            builder.setType(MultipartBody.FORM);
            it = payload.keys();
            while (it.hasNext()) {
                try {
                    String key = it.next();
                    if (payload.get(key) instanceof JSONObject) {
                        JSONObject value = payload.getJSONObject(key);
                        RequestBody body = MultipartBody.create(new File(value.getString("file")), MediaType.parse(value.getString("type")));
                        builder.addFormDataPart(key, value.getString("name"), body);
                    } else {
                        builder.addFormDataPart(key, payload.getString(key));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return builder.build();
        } else {
            return RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        }
    }

    public static void alert(Context context, String title, String message) {
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(context)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }


    public static ApiRequest newApiRequest() {
        return new ApiRequest();
    }


    public static class ApiRequest {
        private JSONObject body = new JSONObject();

        public ApiRequest put(String key, Object value) {
            try {
                body.put(key, value);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return this;
        }

        public String string() {
            return body.toString();
        }

        public JSONObject json() {
            return body;
        }
    }

    public static class ApiResponse {
        int code;
        String status, message;
        Object data;

        public int getCode() {
            return code;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public Object getData() {
            return data;
        }

        @Override
        public String toString() {
            return "ApiResponse{" +
                    "code=" + code +
                    ", status='" + status + '\'' +
                    ", message='" + message + '\'' +
                    ", data=" + data +
                    '}';
        }
    }

    public static interface Callback {
        public void onFinish(ApiResponse response);
    }
}
