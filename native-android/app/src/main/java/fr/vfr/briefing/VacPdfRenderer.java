package fr.vfr.briefing;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class VacPdfRenderer {
    private final Context context;
    private final Object lock = new Object();

    public VacPdfRenderer(Context context) {
        this.context = context.getApplicationContext();
    }

    private File getVacDir() {
        File dir = new File(context.getFilesDir(), "vac_pdfs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File getVacPdfFile(String vacId) {
        String safeId = vacId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(getVacDir(), safeId + ".pdf");
    }

    public String importVacPdf(String vacId, Uri uri) {
        File target = getVacPdfFile(vacId);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return null;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return getVacMetadataJson(vacId);
    }

    public String getVacMetadataJson(String vacId) {
        File pdf = getVacPdfFile(vacId);
        if (!pdf.exists()) return null;
        synchronized (lock) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                int pageCount = renderer.getPageCount();
                JSONArray pages = new JSONArray();
                for (int i = 0; i < pageCount; i++) {
                    try (PdfRenderer.Page p = renderer.openPage(i)) {
                        JSONObject item = new JSONObject();
                        item.put("page", i);
                        item.put("width", p.getWidth());
                        item.put("height", p.getHeight());
                        pages.put(item);
                    }
                }
                JSONObject root = new JSONObject();
                root.put("vacId", vacId);
                root.put("pageCount", pageCount);
                root.put("pages", pages);
                return root.toString();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    public WebResourceResponse serveVacPdfRaw(String vacId) {
        File pdf = getVacPdfFile(vacId);
        if (!pdf.exists()) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(pdf.toPath());
            WebResourceResponse resp = new WebResourceResponse("application/pdf", null, new ByteArrayInputStream(bytes));
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            resp.setResponseHeaders(headers);
            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    public WebResourceResponse serveVacPageImage(String vacId, int pageIndex, int targetWidthPx) {
        File pdf = getVacPdfFile(vacId);
        if (!pdf.exists()) return null;
        synchronized (lock) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) return null;
                try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                    int w = Math.min(2800, Math.max(512, targetWidthPx));
                    int h = Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth()));
                    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);

                    ByteArrayOutputStream out = new ByteArrayOutputStream(131072);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    bitmap.recycle();

                    WebResourceResponse resp = new WebResourceResponse("image/png", null, new ByteArrayInputStream(out.toByteArray()));
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    headers.put("Cache-Control", "no-cache");
                    resp.setResponseHeaders(headers);
                    return resp;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }
}
