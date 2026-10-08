package com.zynelabs.stb;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Records the currently playing stream to a .ts file in Downloads/ZyneLabsSTB/.
 * HLS (.m3u8): downloads media segments sequentially and appends them.
 * Anything else: raw byte copy of the HTTP stream.
 * Runs on its own thread; call stop() to finish. Encrypted (AES-128) HLS
 * and DASH are not supported in this version.
 */
public class StreamRecorder {

    public interface Listener {
        void onStarted(String fileName);
        void onStopped(String fileName, long bytes);
        void onError(String msg);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    private volatile boolean stopFlag = false;
    private java.util.Map<String, String> extraHeaders = null;

    /** v5.4: Stalker streams need MAG headers (UA, Cookie, Referer). */
    public void setExtraHeaders(java.util.Map<String, String> headers) {
        this.extraHeaders = headers;
    }
    private volatile boolean recording = false;
    private Thread thread;

    public boolean isRecording() { return recording; }

    public static String fileNameFor(String channelName) {
        String safe = channelName.replaceAll("[^a-zA-Z0-9]+", "_");
        if (safe.length() > 40) safe = safe.substring(0, 40);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "REC_" + safe + "_" + ts + ".ts";
    }

    public void start(final Context ctx, final String url, final String fileName,
                      final Listener listener) {
        if (recording) return;
        recording = true;
        stopFlag = false;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                OutputStream out = null;
                long bytes = 0;
                try {
                    out = openOutput(ctx, fileName);
                    listener.onStarted(fileName);
                    if (url.toLowerCase(Locale.US).contains(".m3u8")) {
                        bytes = recordHls(url, out);
                    } else {
                        bytes = recordRaw(url, out);
                    }
                    if (!stopFlag) listener.onStopped(fileName, bytes);
                    else listener.onStopped(fileName, bytes);
                } catch (Exception e) {
                    listener.onError(e.getMessage() != null ? e.getMessage() : "Record failed");
                } finally {
                    recording = false;
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                }
            }
        });
        thread.start();
    }

    public void stop() {
        stopFlag = true;
        if (thread != null) thread.interrupt();
    }

    private OutputStream openOutput(Context ctx, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            v.put(MediaStore.Downloads.MIME_TYPE, "video/mp2t");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ZyneLabsIPTV/");
            ContentResolver r = ctx.getContentResolver();
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Cannot create file");
            OutputStream os = r.openOutputStream(uri);
            if (os == null) throw new Exception("Cannot open file");
            return os;
        } else {
            java.io.File dir = new java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ZyneLabsSTB");
            if (!dir.exists()) dir.mkdirs();
            return new java.io.FileOutputStream(new java.io.File(dir, fileName));
        }
    }

    private long recordRaw(String url, OutputStream out) throws Exception {
        HttpURLConnection c = open(url);
        InputStream in = c.getInputStream();
        byte[] buf = new byte[32768];
        long bytes = 0;
        int n;
        try {
            while (!stopFlag && (n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                bytes += n;
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            c.disconnect();
        }
        out.flush();
        return bytes;
    }

    private long recordHls(String url, OutputStream out) throws Exception {
        String playlistUrl = url;
        String pl = fetchText(playlistUrl);
        // master playlist? pick the highest-bandwidth variant
        if (pl.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = pickVariant(playlistUrl, pl);
            pl = fetchText(playlistUrl);
        }
        if (pl.contains("#EXT-X-KEY")) {
            throw new Exception("Encrypted stream — recording not supported");
        }
        Set<String> seen = new HashSet<String>();
        long bytes = 0;
        boolean first = true;
        while (!stopFlag) {
            if (!first) {
                try { Thread.sleep(pollMs(pl) * 1000L); } catch (InterruptedException ie) { break; }
                if (stopFlag) break;
                pl = fetchText(playlistUrl);
            }
            first = false;
            List<String> segs = segments(playlistUrl, pl);
            boolean endlist = pl.contains("#EXT-X-ENDLIST");
            for (String s : segs) {
                if (stopFlag) break;
                if (seen.contains(s)) continue;
                seen.add(s);
                byte[] data = fetchBytes(s);
                out.write(data);
                bytes += data.length;
            }
            out.flush();
            if (endlist) break; // VOD: everything downloaded
        }
        return bytes;
    }

    private int pollMs(String playlist) {
        for (String line : playlist.split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                try {
                    return Math.max(2,
                            Integer.parseInt(line.substring(23).trim()));
                } catch (Exception ignored) {}
            }
        }
        return 5;
    }

    private List<String> segments(String playlistUrl, String playlist) throws Exception {
        List<String> out = new ArrayList<String>();
        boolean wantNext = false;
        for (String raw : playlist.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) { wantNext = false; continue; }
            if (line.startsWith("#EXTINF")) { wantNext = true; continue; }
            if (line.startsWith("#")) { wantNext = false; continue; }
            if (wantNext && !line.toLowerCase(Locale.US).endsWith(".m3u8")) {
                out.add(resolve(playlistUrl, line));
            }
            wantNext = false;
        }
        return out;
    }

    private String pickVariant(String playlistUrl, String playlist) throws Exception {
        String best = null;
        int bestBw = -1;
        String pending = null;
        int pendingBw = -1;
        for (String raw : playlist.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingBw = -1;
                int i = line.indexOf("BANDWIDTH=");
                if (i >= 0) {
                    int j = i + 10, k = j;
                    while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                    try { pendingBw = Integer.parseInt(line.substring(j, k)); }
                    catch (Exception ignored) {}
                }
                pending = null;
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingBw >= 0 && pending == null) {
                pending = line;
                if (pendingBw > bestBw) { bestBw = pendingBw; best = pending; }
                pendingBw = -1;
            }
        }
        if (best == null) throw new Exception("No variant found");
        return resolve(playlistUrl, best);
    }

    private String resolve(String base, String rel) throws Exception {
        return new URL(new URL(base), rel).toString();
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (extraHeaders != null) {
            for (java.util.Map.Entry<String, String> e : extraHeaders.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        } else {
            c.setRequestProperty("User-Agent", UA);
        }
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private String fetchText(String url) throws Exception {
        HttpURLConnection c = open(url);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code / 100 != 2) { c.disconnect(); throw new Exception("HTTP " + code); }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (stopFlag) break;
            bos.write(buf, 0, n);
        }
        in.close();
        c.disconnect();
        return bos.toByteArray();
    }
}
