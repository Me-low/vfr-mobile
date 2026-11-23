package fr.vfr.briefing;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends BridgeActivity implements SensorEventListener, LocationListener {

    private FastPdfTileEngine oaciTileEngine;
    private VacPdfRenderer vacPdfRenderer;
    private SensorManager sensorManager;
    private LocationManager locationManager;
    private Location lastLocation;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private String pendingVacId = "active_vac";

    private ActivityResultLauncher<String[]> oaciPickerLauncher;
    private ActivityResultLauncher<String[]> vacPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        oaciTileEngine = new FastPdfTileEngine(this);
        vacPdfRenderer = new VacPdfRenderer(this);

        oaciPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleOaciPdfPicked
        );

        vacPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleVacPdfPicked
        );

        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WebView webView = this.bridge.getWebView();
        webView.addJavascriptInterface(new VfrNativeBridge(), "VfrNative");
        webView.setWebViewClient(new BridgeWebViewClient(this.bridge) {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && "appassets.androidplatform.net".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path != null) {
                        if (path.startsWith("/tiles/oaci/")) {
                            WebResourceResponse tile = routeOaciTile(path);
                            if (tile != null) return tile;
                        } else if (path.startsWith("/vac/")) {
                            WebResourceResponse vacResp = routeVacAsset(path, uri);
                            if (vacResp != null) return vacResp;
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        requestRuntimePermissionsIfNeeded();
    }

    private void requestRuntimePermissionsIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1001);
        } else {
            startNativeLocationUpdates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startNativeLocationUpdates();
        }
    }

    private void startNativeLocationUpdates() {
        if (locationManager == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
            Location cached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (cached != null) onLocationChanged(cached);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null) {
            Sensor pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            if (pressure != null) {
                sensorManager.registerListener(this, pressure, SensorManager.SENSOR_DELAY_UI);
            }
            Sensor rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotVec != null) {
                sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_UI);
            }
        }
        startNativeLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            float hpa = event.values[0];
            dispatchJs(String.format(Locale.US, "window.onNativeBarometer && window.onNativeBarometer(%.2f);", hpa));
        } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            double magneticAzimuth = (Math.toDegrees(orientationAngles[0]) + 360.0) % 360.0;
            double trueAzimuth = magneticAzimuth;
            if (lastLocation != null) {
                GeomagneticField geoField = new GeomagneticField(
                        (float) lastLocation.getLatitude(),
                        (float) lastLocation.getLongitude(),
                        (float) lastLocation.getAltitude(),
                        System.currentTimeMillis()
                );
                trueAzimuth = (magneticAzimuth + geoField.getDeclination() + 360.0) % 360.0;
            }
            dispatchJs(String.format(Locale.US,
                    "window.onNativeCompass && window.onNativeCompass(%.2f, %.2f, %d);",
                    trueAzimuth, magneticAzimuth, event.accuracy));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onLocationChanged(Location loc) {
        lastLocation = loc;
        double speedMps = loc.hasSpeed() ? loc.getSpeed() : -1.0;
        double bearing = loc.hasBearing() ? loc.getBearing() : -1.0;
        double altM = loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
        double accM = loc.hasAccuracy() ? loc.getAccuracy() : -1.0;
        String altStr = Double.isNaN(altM) ? "null" : String.format(Locale.US, "%.2f", altM);
        dispatchJs(String.format(Locale.US,
                "window.onNativeLocation && window.onNativeLocation(%.7f, %.7f, %.2f, %.2f, %s, %.1f);",
                loc.getLatitude(), loc.getLongitude(), speedMps, bearing, altStr, accM));
    }

    private WebResourceResponse routeOaciTile(String path) {
        try {
            // /tiles/oaci/{z}/{x}_{y}.webp
            String sub = path.substring("/tiles/oaci/".length()).replace(".webp", "");
            String[] parts = sub.split("/");
            if (parts.length != 2) return null;
            int z = Integer.parseInt(parts[0]);
            String[] xy = parts[1].split("_");
            if (xy.length != 2) return null;
            int x = Integer.parseInt(xy[0]);
            int y = Integer.parseInt(xy[1]);
            return oaciTileEngine.serveTile(z, x, y);
        } catch (Exception e) {
            return null;
        }
    }

    private WebResourceResponse routeVacAsset(String path, Uri uri) {
        try {
            // /vac/{vacId}/raw.pdf or /vac/{vacId}/page_{page}.png?w=2048
            String sub = path.substring("/vac/".length());
            int slash = sub.indexOf('/');
            if (slash <= 0) return null;
            String vacId = sub.substring(0, slash);
            String filePart = sub.substring(slash + 1);
            if ("raw.pdf".equals(filePart)) {
                return vacPdfRenderer.serveVacPdfRaw(vacId);
            } else if (filePart.startsWith("page_") && filePart.endsWith(".png")) {
                int pageIdx = Integer.parseInt(filePart.substring(5, filePart.length() - 4));
                String wParam = uri.getQueryParameter("w");
                int w = wParam != null ? Integer.parseInt(wParam) : 2048;
                return vacPdfRenderer.serveVacPageImage(vacId, pageIdx, w);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void handleOaciPdfPicked(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            boolean ok = oaciTileEngine.importFromUri(uri);
            int[] dims = ok ? oaciTileEngine.getDimensions() : null;
            if (dims != null) {
                dispatchJs(String.format(Locale.US,
                        "window.onOaciPdfImported && window.onOaciPdfImported(true, %d, %d);",
                        dims[0], dims[1]));
            } else {
                dispatchJs("window.onOaciPdfImported && window.onOaciPdfImported(false, 0, 0);");
            }
        }).start();
    }

    private void handleVacPdfPicked(Uri uri) {
        if (uri == null) return;
        final String vacId = pendingVacId;
        new Thread(() -> {
            String metaJson = vacPdfRenderer.importVacPdf(vacId, uri);
            if (metaJson != null) {
                String escaped = JSONObject.quote(metaJson);
                dispatchJs("window.onVacPdfImported && window.onVacPdfImported(true, " + escaped + ");");
            } else {
                dispatchJs("window.onVacPdfImported && window.onVacPdfImported(false, null);");
            }
        }).start();
    }

    private void dispatchJs(String script) {
        runOnUiThread(() -> {
            if (this.bridge != null && this.bridge.getWebView() != null) {
                this.bridge.getWebView().evaluateJavascript(script, null);
            }
        });
    }

    public class VfrNativeBridge {
        @JavascriptInterface
        public void pickOaciPdf() {
            runOnUiThread(() -> oaciPickerLauncher.launch(new String[]{"application/pdf"}));
        }

        @JavascriptInterface
        public void pickVacPdf(String vacId) {
            pendingVacId = (vacId != null && !vacId.isEmpty()) ? vacId : "active_vac";
            runOnUiThread(() -> vacPickerLauncher.launch(new String[]{"application/pdf"}));
        }

        @JavascriptInterface
        public String getOaciDimensionsJson() {
            int[] dims = oaciTileEngine.getDimensions();
            if (dims == null) return "";
            return "{"width":" + dims[0] + ","height":" + dims[1] + "}";
        }

        @JavascriptInterface
        public String getVacMetadataJson(String vacId) {
            String res = vacPdfRenderer.getVacMetadataJson(vacId);
            return res != null ? res : "";
        }
    }
}
