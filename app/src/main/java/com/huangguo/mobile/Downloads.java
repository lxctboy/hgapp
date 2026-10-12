package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.hongguotv.core.MediaCrypto;
import com.hongguotv.core.VendorConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Multi-threaded episode downloads for both the encrypted-MP4 (hongguo) and HLS (huangguo) sources. */
final class Downloads {
    private static final String TAG = "HuangGuoDownload";
    private static final int WORKERS = 4;
    private static final int MP4_CHUNK = 4 * 1024 * 1024;

    interface Listener { void onProgress(String source, String id, int episode, int percent, String state, String error); }

    private static Downloads instance;
    private final Context app;
    private final SharedPreferences index;
    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    private final ExecutorService orchestrators = Executors.newCachedThreadPool();
    private final Map<String, Job> active = new ConcurrentHashMap<>();
    private volatile Listener listener;

    private Downloads(Context context) {
        app = context.getApplicationContext();
        index = app.getSharedPreferences("downloads-index", Context.MODE_PRIVATE);
    }
    static synchronized Downloads get(Context context) {
        if (instance == null) instance = new Downloads(context);
        return instance;
    }
    void setListener(Listener value) { listener = value; }

    private static String key(String source, String id, int episode) { return source + "|" + id + "|" + episode; }

    /** State of every episode of a work the detail page is showing: episode number -> {state, percent}. */
    synchronized JSONObject status(String source, String id) {
        JSONObject result = new JSONObject();
        try {
            for (Map.Entry<String, Job> entry : active.entrySet()) {
                Job job = entry.getValue();
                if (!job.source.equals(source) || !job.id.equals(id)) continue;
                result.put(String.valueOf(job.episode), new JSONObject().put("state", "downloading").put("percent", job.percent));
            }
            String prefix = source + "|" + id + "|";
            for (Map.Entry<String, ?> entry : index.getAll().entrySet()) {
                if (!entry.getKey().startsWith(prefix)) continue;
                String episode = entry.getKey().substring(prefix.length());
                if (result.has(episode)) continue;
                File file = new File(new JSONObject(String.valueOf(entry.getValue())).optString("path"));
                if (file.isFile()) result.put(episode, new JSONObject().put("state", "done").put("percent", 100));
            }
        } catch (Exception error) { Log.w(TAG, "status failed", error); }
        return result;
    }

    /** Local playable source for a finished download, or null. Used by the player for offline playback. */
    synchronized JSONObject localSource(String source, String id, int episode) {
        String stored = index.getString(key(source, id, episode), null);
        if (stored == null) return null;
        try {
            JSONObject record = new JSONObject(stored);
            File file = new File(record.optString("path"));
            if (!file.isFile()) return null;
            return new JSONObject().put("source", android.net.Uri.fromFile(file).toString())
                    .put("mimeType", record.optString("mime")).put("local", true);
        } catch (Exception error) { return null; }
    }

    void cancel(String source, String id, int episode) {
        Job job = active.get(key(source, id, episode));
        if (job != null) job.cancel();
    }

    synchronized void remove(String source, String id, int episode) {
        cancel(source, id, episode);
        String stored = index.getString(key(source, id, episode), null);
        if (stored != null) {
            try { File file = new File(new JSONObject(stored).optString("path")); deleteTree(file.getParentFile(), file); }
            catch (Exception ignored) { }
        }
        index.edit().remove(key(source, id, episode)).apply();
    }

    /** Begins (or no-ops if already running/finished) a download. type is "mp4" (encrypted) or "hls". */
    void start(String source, String id, int episode, String type, String url, byte[] cencKey, String title, String cover) {
        String jobKey = key(source, id, episode);
        if (active.containsKey(jobKey) || index.contains(jobKey)) return;
        Job job = new Job(source, id, episode, type, url, cencKey, title, cover);
        active.put(jobKey, job);
        job.launch();
    }

    private void finish(Job job, File file, boolean ok, String error) {
        active.remove(key(job.source, job.id, job.episode));
        if (ok && file != null && file.isFile()) {
            try {
                index.edit().putString(key(job.source, job.id, job.episode), new JSONObject()
                        .put("path", file.getAbsolutePath()).put("mime", job.mime)
                        .put("title", job.title).put("cover", job.cover)
                        .put("at", System.currentTimeMillis()).toString()).apply();
            } catch (Exception ignored) { }
            report(job, 100, "done", null);
        } else {
            if (file != null) deleteTree(file.getParentFile(), file);
            report(job, job.percent, job.cancelled ? "idle" : "error", error);
        }
    }

    private void report(Job job, int percent, String state, String error) {
        Listener current = listener;
        if (current != null) current.onProgress(job.source, job.id, job.episode, percent, state, error);
    }

    private static String message(Throwable error) {
        String text = error == null ? null : error.getMessage();
        return text == null || text.isEmpty() ? "下载失败" : text;
    }

    private static void deleteTree(File dir, File file) {
        if (file != null) file.delete();
        if (dir != null && dir.isDirectory()) { File[] rest = dir.listFiles(); if (rest == null || rest.length == 0) dir.delete(); }
    }

    private File episodeDir(Job job) {
        File root = app.getExternalFilesDir(null);
        if (root == null) root = app.getFilesDir();
        File base = new File(root, "downloads/" + job.source + "/" + job.id);
        base.mkdirs();
        return base;
    }

    private static final class RangeResult { final byte[] bytes; final long total; RangeResult(byte[] b, long t) { bytes = b; total = t; } }

    private static final class Segment { int index; String uri; byte[] key; byte[] iv; }

    private final class Job {
        final String source, id, type, url, title, cover;
        final int episode;
        final byte[] cencKey;
        String mime = "video/mp4";
        volatile int percent;
        volatile boolean cancelled;
        private final List<okhttp3.Call> calls = new ArrayList<>();

        Job(String source, String id, int episode, String type, String url, byte[] cencKey, String title, String cover) {
            this.source = source; this.id = id; this.episode = episode; this.type = type;
            this.url = url; this.cencKey = cencKey; this.title = title; this.cover = cover;
            this.mime = "video/mp4"; // 两种来源最终都产出单个 mp4
        }

        void launch() {
            report(this, 0, "downloading", null);
            orchestrators.execute(() -> {
                File target = null;
                try {
                    target = "hls".equals(type) ? runHls() : runMp4();
                    finish(this, target, !cancelled, null);
                } catch (Exception error) {
                    if (!cancelled) Log.e(TAG, "download failed " + id + ":" + episode, error);
                    finish(this, target, false, cancelled ? null : message(error));
                }
            });
        }

        void cancel() {
            cancelled = true;
            abortCalls();
        }
        private void abortCalls() {
            synchronized (calls) { for (okhttp3.Call call : calls) call.cancel(); }
        }

        private void bump(long done, long total) {
            if (total <= 0) return;
            int value = (int) Math.min(99, done * 100 / total);
            if (value > percent) { percent = value; report(this, value, "downloading", null); }
        }

        private void track(okhttp3.Call call, boolean add) { synchronized (calls) { if (add) calls.add(call); else calls.remove(call); } }

        private File runMp4() throws Exception {
            RangeResult probe = range(0, 256 * 1024 - 1);
            long total = probe.total;
            if (total <= 0) throw new IllegalStateException("无法确定视频大小");
            int headerEnd = MediaCrypto.INSTANCE.headerEnd(probe.bytes, total);
            byte[] header;
            if (headerEnd > probe.bytes.length) {
                byte[] more = range(probe.bytes.length, headerEnd - 1).bytes;
                header = new byte[headerEnd];
                System.arraycopy(probe.bytes, 0, header, 0, probe.bytes.length);
                System.arraycopy(more, 0, header, probe.bytes.length, more.length);
            } else {
                header = java.util.Arrays.copyOf(probe.bytes, headerEnd);
            }
            MediaCrypto.Prepared prepared = MediaCrypto.INSTANCE.prepare(header, cencKey);
            File out = new File(episodeDir(this), outputName(title, episode));
            File part = new File(episodeDir(this), "." + episode + ".mp4.part");
            AtomicLong done = new AtomicLong();
            AtomicReference<Exception> failure = new AtomicReference<>();
            try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                raf.setLength(total);
                List<Future<?>> futures = new ArrayList<>();
                for (long start = 0; start < total; start += MP4_CHUNK) {
                    final long s = start, e = Math.min(total, start + MP4_CHUNK) - 1;
                    futures.add(pool.submit(() -> {
                        if (cancelled || failure.get() != null) return;
                        try {
                            byte[] plain = MediaCrypto.INSTANCE.decrypt(range(s, e).bytes, s, cencKey, prepared);
                            synchronized (raf) { raf.seek(s); raf.write(plain); }
                            bump(done.addAndGet(plain.length), total);
                        } catch (Exception error) { failure.compareAndSet(null, error); abortCalls(); }
                    }));
                }
                await(futures, failure);
            }
            if (cancelled) { part.delete(); return null; }
            if (!part.renameTo(out)) throw new IllegalStateException("保存失败");
            return out;
        }

        private RangeResult range(long start, long end) throws Exception {
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", VendorConstants.VIDEO_UA).header("Referer", "https://novel.snssdk.com/")
                    .header("Range", "bytes=" + start + "-" + end).header("Accept-Encoding", "identity").build();
            okhttp3.Call call = http.newCall(request); track(call, true);
            try (Response response = call.execute()) {
                if (response.code() != 206 || response.body() == null) throw new IllegalStateException("服务器不支持分段下载（HTTP " + response.code() + "）");
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(String.valueOf(response.header("Content-Range")));
                if (!match.matches()) throw new IllegalStateException("分段响应异常");
                long full = Long.parseLong(match.group(3));
                int n = (int) (Math.min(end, full - 1) - start + 1);
                return new RangeResult(response.body().source().readByteArray(n), full);
            } finally { track(call, false); }
        }

        private void await(List<Future<?>> futures, AtomicReference<Exception> failure) throws Exception {
            for (Future<?> future : futures) { try { future.get(); } catch (Exception ignored) { } }
            if (failure.get() != null && !cancelled) throw failure.get();
        }

        private File runHls() throws Exception {
            String playlist = fetchText(url);
            String mediaUrl = url;
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                String variant = null;
                String[] lines = playlist.split("\\r?\\n");
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.length) { variant = lines[i + 1].trim(); break; }
                }
                if (variant == null || variant.isEmpty()) throw new IllegalStateException("无法解析播放列表");
                mediaUrl = resolve(url, variant);
                playlist = fetchText(mediaUrl);
            }
            File dir = episodeDir(this);
            List<Segment> segments = new ArrayList<>();
            Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
            byte[] currentKey = null, currentIv = null;
            long sequence = 0;
            for (String raw : playlist.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) { sequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); continue; }
                if (line.startsWith("#EXT-X-KEY")) {
                    String method = attribute(line, "METHOD");
                    if (method == null || method.equalsIgnoreCase("NONE")) { currentKey = null; currentIv = null; }
                    else {
                        if (!method.equalsIgnoreCase("AES-128")) throw new IllegalStateException("暂不支持的加密方式：" + method);
                        String keyUri = attribute(line, "URI"); String ivText = attribute(line, "IV");
                        byte[] cached = keyCache.get(keyUri);
                        if (cached == null) { cached = fetchBytes(resolve(mediaUrl, keyUri)); keyCache.put(keyUri, cached); }
                        currentKey = cached;
                        currentIv = ivText != null ? hex(ivText) : null;
                    }
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) continue;
                Segment segment = new Segment();
                segment.index = segments.size();
                segment.uri = resolve(mediaUrl, line);
                segment.key = currentKey;
                segment.iv = currentKey == null ? null : currentIv != null ? currentIv : sequenceIv(sequence + segment.index);
                segments.add(segment);
            }
            if (segments.isEmpty()) throw new IllegalStateException("播放列表没有可下载的分段");
            // 分片下到临时目录,拼成一条 ts,再无损重封装为单个 mp4
            File parts = new File(dir, "." + episode + ".parts");
            parts.mkdirs();
            try {
                downloadSegments(parts, segments);
                if (cancelled) return null;
                // 逐个片段分别读取写入,避免直接字节拼接导致 PTS 不连续(MediaExtractor 读到拼接边界会提前停止)
                File tmpOut = new File(parts, "out.mp4");
                remuxSegmentsToMp4(parts, segments.size(), tmpOut);
                if (cancelled) return null;
                File out = new File(dir, outputName(title, episode));
                out.delete();
                if (!tmpOut.renameTo(out)) throw new IllegalStateException("保存失败");
                return out;
            } finally {
                deleteRecursively(parts);
            }
        }

        /**
         * 将目录中 seg0000.ts ... segNNNN.ts 逐片段重封装为单个 mp4。
         * 每个 ts 片段的 PTS 通常独立从 0 开始,必须累加偏移写入,否则时间轴重叠会导致 muxer 停止或播放异常。
         */
        private void remuxSegmentsToMp4(File dir, int count, File out) throws Exception {
            android.media.MediaMuxer muxer = null;
            android.media.MediaExtractor extractor = null;
            try {
                muxer = new android.media.MediaMuxer(out.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int[] trackMap = null;
                int maxInput = 1 << 20;
                long ptsOffsetUs = 0;
                boolean muxerStarted = false;
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(maxInput);
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();

                for (int segIdx = 0; segIdx < count && !cancelled; segIdx++) {
                    File seg = new File(dir, String.format(java.util.Locale.ROOT, "seg%04d.ts", segIdx));
                    if (!seg.isFile()) continue;

                    extractor = new android.media.MediaExtractor();
                    extractor.setDataSource(seg.getAbsolutePath());
                    int trackCount = extractor.getTrackCount();
                    if (trackCount == 0) { extractor.release(); continue; }

                    if (!muxerStarted) {
                        // 第一个片段:建立轨道映射
                        trackMap = new int[trackCount];
                        java.util.Arrays.fill(trackMap, -1);
                        for (int t = 0; t < trackCount; t++) {
                            android.media.MediaFormat format = extractor.getTrackFormat(t);
                            String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                            if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                                trackMap[t] = muxer.addTrack(format);
                                if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                                    int mis = format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE);
                                    if (mis > maxInput) { maxInput = mis; buffer = java.nio.ByteBuffer.allocate(maxInput); }
                                }
                            }
                        }
                        muxer.start();
                        muxerStarted = true;
                    }

                    for (int t = 0; t < trackCount; t++) extractor.selectTrack(t);

                    long segMaxPts = 0;
                    while (!cancelled) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        int track = extractor.getSampleTrackIndex();
                        if (track < 0) break;

                        long pts = extractor.getSampleTime();
                        if (pts < 0) pts = 0;

                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = pts + ptsOffsetUs;
                        info.flags = extractor.getSampleFlags();
                        if (pts > segMaxPts) segMaxPts = pts;

                        if (track < trackMap.length && trackMap[track] >= 0)
                            muxer.writeSampleData(trackMap[track], buffer, info);
                        extractor.advance();
                    }

                    // 下一个片段的 PTS 接在本片段最大 PTS 之后,留 1ms 间隙避免重叠
                    ptsOffsetUs += segMaxPts + 1000;

                    extractor.release();
                    extractor = null;
                }

                if (muxerStarted && !cancelled) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
            } finally {
                try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
                try { if (extractor != null) extractor.release(); } catch (Exception ignored) { }
            }
        }

        private void downloadSegments(File dir, List<Segment> segments) throws Exception {
            AtomicLong done = new AtomicLong();
            long total = segments.size();
            AtomicReference<Exception> failure = new AtomicReference<>();
            List<Future<?>> futures = new ArrayList<>();
            for (Segment segment : segments) {
                futures.add(pool.submit(() -> {
                    if (cancelled || failure.get() != null) return;
                    try {
                        byte[] bytes = fetchBytes(segment.uri);
                        if (segment.key != null) bytes = decryptAes(bytes, segment.key, segment.iv);
                        File file = new File(dir, String.format(java.util.Locale.ROOT, "seg%04d.ts", segment.index));
                        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) { stream.write(bytes); }
                        bump(done.incrementAndGet(), total);
                    } catch (Exception error) { failure.compareAndSet(null, error); abortCalls(); }
                }));
            }
            await(futures, failure);
        }

        private String fetchText(String target) throws Exception {
            return new String(fetchBytes(target), java.nio.charset.StandardCharsets.UTF_8);
        }
        private byte[] fetchBytes(String target) throws Exception {
            Request request = new Request.Builder().url(target)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) HuangGuo/0.1").build();
            okhttp3.Call call = http.newCall(request); track(call, true);
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("下载失败（HTTP " + response.code() + "）");
                return response.body().bytes();
            } finally { track(call, false); }
        }

        private byte[] decryptAes(byte[] data, byte[] key, byte[] iv) throws Exception {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(iv == null ? new byte[16] : iv));
            int usable = data.length - data.length % 16;
            return cipher.doFinal(data, 0, usable);
        }
    }

    private static String resolve(String base, String ref) throws Exception {
        return java.net.URI.create(base).resolve(ref.trim()).toString();
    }
    private static String attribute(String line, String name) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(name + "=(\"[^\"]*\"|[^,]*)").matcher(line);
        if (!match.find()) return null;
        String value = match.group(1);
        return value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
    private static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (sequence >>> (8 * i));
        return iv;
    }
    private static byte[] hex(String value) {
        String clean = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    /** 文件名 = 短剧名称 + 集数,非法字符剔除;名称为空时退回仅集数。 */
    private static String outputName(String title, int episode) {
        String base = sanitize(title);
        return (base.isEmpty() ? "" : base + " ") + episode + ".mp4";
    }
    private static String sanitize(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }
    private static void deleteRecursively(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
