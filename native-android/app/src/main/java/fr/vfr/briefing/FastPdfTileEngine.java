package fr.vfr.briefing;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FastPdfTileEngine {
    private static final int TILE_PX = 512;
    private static final int RAM_CACHE_BYTES = 160 * 1024 * 1024;

    private final Context context;
    private final Object pdfLock = new Object();
    private PdfRenderer renderer;
    private ParcelFileDescriptor fileDescriptor;
    private int pageWidth = 0;
    private int pageHeight = 0;
    private float worldSpan = 1f;

    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor();
    private final ExecutorService prefetcher = Executors.newSingleThreadExecutor();
    private final LruCache<String, byte[]> ramCache;
    private byte[] cachedEmptyTile;

    public FastPdfTileEngine(Context context) {
        this.context = context.getApplicationContext();
        this.ramCache = new LruCache<String, byte[]>(RAM_CACHE_BYTES) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value.length;
            }
        };
    }

    public File getPdfFile() {
        return new File(context.getFilesDir(), "carte_oaci.pdf");
    }

    private File getDiskCacheDir() {
        File dir = new File(context.getCacheDir(), "oaci_tiles_hq");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public boolean importFromUri(Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            File tmp = new File(context.getFilesDir(), "carte_oaci_tmp.pdf");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            synchronized (pdfLock) {
                closeInternal();
                File target = getPdfFile();
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) {
                    return false;
                }
            }
            return loadOrReloadPdf() != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void closeInternal() {
        try {
            if (renderer != null) renderer.close();
        } catch (Exception ignored) {}
        try {
            if (fileDescriptor != null) fileDescriptor.close();
        } catch (Exception ignored) {}
        renderer = null;
        fileDescriptor = null;
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        file.delete();
    }

    public int[] loadOrReloadPdf() {
        synchronized (pdfLock) {
            closeInternal();
            ramCache.evictAll();
            File cacheDir = getDiskCacheDir();
            deleteRecursive(cacheDir);
            cacheDir.mkdirs();

            File pdf = getPdfFile();
            if (!pdf.exists()) return null;

            try {
                fileDescriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(fileDescriptor);
                try (PdfRenderer.Page page = renderer.openPage(0)) {
                    pageWidth = page.getWidth();
                    pageHeight = page.getHeight();
                    worldSpan = Math.max(pageWidth, pageHeight);
                }
            } catch (Exception e) {
                e.printStackTrace();
                closeInternal();
                return null;
            }
        }

        prefetcher.execute(() -> {
            for (int z = 0; z <= 2; z++) {
                int count = 1 << z;
                for (int x = 0; x < count; x++) {
                    for (int y = 0; y < count; y++) {
                        getOrRenderTileBytes(z, x, y);
                    }
                }
            }
        });

        return new int[]{pageWidth, pageHeight};
    }

    public int[] getDimensions() {
        synchronized (pdfLock) {
            if (renderer == null && getPdfFile().exists()) {
                return loadOrReloadPdf();
            }
            if (pageWidth > 0 && pageHeight > 0) {
                return new int[]{pageWidth, pageHeight};
            }
            return null;
        }
    }

    public WebResourceResponse serveTile(int z, int x, int y) {
        byte[] bytes = getOrRenderTileBytes(z, x, y);
        if (bytes == null) return null;
        WebResourceResponse resp = new WebResourceResponse("image/webp", null, new ByteArrayInputStream(bytes));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "max-age=31536000, immutable");
        headers.put("Access-Control-Allow-Origin", "*");
        resp.setResponseHeaders(headers);
        return resp;
    }

    private byte[] getOrRenderTileBytes(int z, int x, int y) {
        String key = z + "_" + x + "_" + y;
        byte[] cached = ramCache.get(key);
        if (cached != null) return cached;

        File diskFile = new File(getDiskCacheDir(), key + ".webp");
        if (diskFile.exists()) {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(diskFile.toPath());
                ramCache.put(key, bytes);
                return bytes;
            } catch (Exception ignored) {}
        }

        byte[] rendered;
        synchronized (pdfLock) {
            cached = ramCache.get(key);
            if (cached != null) return cached;
            if (renderer == null && getPdfFile().exists()) {
                loadOrReloadPdf();
            }
            if (renderer == null) return null;

            int tilesPerAxis = 1 << z;
            float tilePdfSpan = worldSpan / tilesPerAxis;
            float pdfLeft = x * tilePdfSpan;
            float pdfTop = y * tilePdfSpan;

            if (pdfLeft >= pageWidth || pdfTop >= pageHeight) {
                return getEmptyTransparentTile();
            }

            Bitmap bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            float scale = TILE_PX / tilePdfSpan;
            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            matrix.postTranslate(-pdfLeft * scale, -pdfTop * scale);

            try (PdfRenderer.Page page = renderer.openPage(0)) {
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            }

            ByteArrayOutputStream stream = new ByteArrayOutputStream(65536);
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, stream);
            bitmap.recycle();
            rendered = stream.toByteArray();
        }

        ramCache.put(key, rendered);
        diskWriter.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(diskFile)) {
                fos.write(rendered);
            } catch (Exception ignored) {}
        });

        return rendered;
    }

    private byte[] getEmptyTransparentTile() {
        if (cachedEmptyTile != null) return cachedEmptyTile;
        Bitmap bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out);
        bmp.recycle();
        cachedEmptyTile = out.toByteArray();
        return cachedEmptyTile;
    }
}
