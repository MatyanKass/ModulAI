package com.modulai.engine.imagesdcpp;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Test harness for modulai_sd.cpp. It shares its name and method signatures with
 * SdNative.kt on purpose: that is what makes the JNI symbols resolve.
 *
 * Args: <dir with libmodulai-sd.so> <dir with model.gguf, esrgan.pth, taesd.safetensors> <output dir>
 *       <steps> <cfg scale> <img2img steps, 0 for a one-step model> [taesd]
 */
public class SdNative {
    public native void init();
    public native boolean cpuSupported();
    public native String systemInfo();
    public native byte[] lastError();
    public native long load(String modelPath, String vaePath, String taesdPath, int nThreads);
    public native void free(long h);
    public native String modelVersion(long h);
    public native void cancel(long h);
    public native byte[] generate(long h, String prompt, String negative, int width, int height, int steps,
        float cfgScale, String sampler, String scheduler, long seed, byte[] initRgb, int initWidth, int initHeight,
        float strength, String[] loraPaths, float[] loraWeights, boolean vaeTiling, ProgressCallback cb,
        int[] out);
    public native long loadUpscaler(String modelPath, int nThreads);
    public native void freeUpscaler(long h);
    public native int upscalerScale(long h);
    public native void cancelUpscaler(long h);
    public native byte[] upscale(long h, byte[] rgb, int width, int height, ProgressCallback cb, int[] out);

    static final int OK = 0, CANCELLED = 1, STAGE_SAMPLING = 1, STAGE_TILES = 2;
    static final String PROMPT = "a red apple on a wooden table, photo";

    static int failures;
    static int samplingCalls, lastDone, lastTotal, tileCalls;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    String error() {
        return new String(lastError(), StandardCharsets.UTF_8).trim();
    }

    String errorLine() {
        return error().split("\n")[0];
    }

    byte[] square(long h, int steps, float cfg, long seed, byte[] init, float strength, int[] out, ProgressCallback cb) {
        return generate(h, PROMPT, "", 512, 512, steps, cfg, "", "", seed, init, 512, 512, strength,
            new String[0], new float[0], false, cb, out);
    }

    static ProgressCallback counting() {
        samplingCalls = lastDone = lastTotal = tileCalls = 0;
        return (stage, done, total) -> {
            if (stage == STAGE_SAMPLING) samplingCalls++;
            else if (stage == STAGE_TILES) tileCalls++;
            else return;
            lastDone = done;
            lastTotal = total;
        };
    }

    /** Standard deviation of the pixel values: zero for a flat image. */
    static double spread(byte[] rgb) {
        double sum = 0, sq = 0;
        for (byte b : rgb) {
            int v = b & 0xFF;
            sum += v;
            sq += (double) v * v;
        }
        double mean = sum / rgb.length;
        return Math.sqrt(sq / rgb.length - mean * mean);
    }

    static double meanDiff(byte[] a, byte[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        return sum / a.length;
    }

    static byte[] crop(byte[] rgb, int width, int x0, int y0, int w, int h) {
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) System.arraycopy(rgb, ((y0 + y) * width + x0) * 3, out, y * w * 3, w * 3);
        return out;
    }

    /** Averages every factor x factor block, undoing an enlargement for comparison. */
    static byte[] shrink(byte[] rgb, int width, int height, int factor) {
        int w = width / factor, h = height / factor;
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
            int sum = 0;
            for (int dy = 0; dy < factor; dy++) for (int dx = 0; dx < factor; dx++) {
                sum += rgb[((y * factor + dy) * width + x * factor + dx) * 3 + c] & 0xFF;
            }
            out[(y * w + x) * 3 + c] = (byte) (sum / (factor * factor));
        }
        return out;
    }

    static void savePng(byte[] rgb, int width, int height, File file) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = (y * width + x) * 3;
            img.setRGB(x, y, (rgb[i] & 0xFF) << 16 | (rgb[i + 1] & 0xFF) << 8 | rgb[i + 2] & 0xFF);
        }
        ImageIO.write(img, "png", file);
    }

    public static void main(String[] args) throws Exception {
        System.load(new File(args[0], "libmodulai-sd.so").getAbsolutePath());
        File models = new File(args[1]);
        File outDir = new File(args[2]);
        int steps = Integer.parseInt(args[3]);
        float cfg = Float.parseFloat(args[4]);
        int editSteps = Integer.parseInt(args[5]);
        int threads = Runtime.getRuntime().availableProcessors();
        SdNative n = new SdNative();
        n.init();
        check(n.cpuSupported(), "CPU is supported");
        System.out.println("      " + n.systemInfo().trim());
        int[] out = new int[3];

        long missing = n.load(new File(models, "no-such-model.gguf").getPath(), "", "", threads);
        check(missing == 0 && !n.error().isEmpty(), "missing model fails with a message: " + n.errorLine());

        File garbage = new File(outDir, "garbage.gguf");
        java.nio.file.Files.write(garbage.toPath(), new byte[4096]);
        long broken = n.load(garbage.getPath(), "", "", threads);
        check(broken == 0 && !n.error().isEmpty(), "a file that is not a model fails with a message: " + n.errorLine());

        long t0 = System.nanoTime();
        long h = n.load(new File(models, "model.gguf").getPath(), "", "", threads);
        check(h != 0, "model loads" + (h == 0 ? ": " + n.error() : " (" + n.modelVersion(h) + ")"));
        if (h == 0) System.exit(1);
        System.out.printf("      load %.1f s%n", (System.nanoTime() - t0) / 1e9);

        t0 = System.nanoTime();
        byte[] first = n.square(h, steps, cfg, 42, null, 0f, out, counting());
        double seconds = (System.nanoTime() - t0) / 1e9;
        check(first != null && out[0] == OK, "txt2img succeeds" + (first == null ? ": " + n.error() : ""));
        if (first == null) System.exit(1);
        check(out[1] == 512 && out[2] == 512 && first.length == 512 * 512 * 3, "image is 512x512 RGB");
        check(spread(first) > 10, String.format("image is not a flat color (spread %.1f)", spread(first)));
        check(samplingCalls >= steps && lastDone == steps && lastTotal == steps,
            "progress reached step " + lastDone + " of " + lastTotal + " in " + samplingCalls + " callbacks");
        System.out.printf("      %d step(s) in %.1f s%n", steps, seconds);
        savePng(first, 512, 512, new File(outDir, "txt2img.png"));

        byte[] again = n.square(h, steps, cfg, 42, null, 0f, out, counting());
        check(again != null && Arrays.equals(first, again), "the same seed gives the same image");
        byte[] other = n.square(h, steps, cfg, 43, null, 0f, out, counting());
        check(other != null && meanDiff(first, other) > 2, "another seed gives another image");

        byte[] wide = n.generate(h, PROMPT, "blurry", 384, 256, steps, cfg, "euler_a", "", 7, null, 0, 0, 0f,
            new String[0], new float[0], false, counting(), out);
        check(wide != null && out[1] == 384 && out[2] == 256, "384x256 with an explicit sampler and a negative prompt");

        byte[] edited = n.square(h, Math.max(editSteps, steps), cfg, 42, first, 0.5f, out, counting());
        check(edited != null && out[0] == OK && out[1] == 512 && spread(edited) > 5,
            "img2img runs and returns a picture" + (edited == null ? ": " + n.error() : ""));
        if (edited != null) savePng(edited, 512, 512, new File(outDir, "img2img.png"));
        // A one-step model always starts from pure noise, so only a multi-step one can
        // be expected to keep its source recognisable.
        if (edited != null && editSteps > 0) {
            double diff = meanDiff(first, edited);
            check(diff > 0.5 && diff < meanDiff(first, other), String.format(
                "img2img result is a variation of its source (diff %.1f, unrelated image %.1f)", diff, meanDiff(first, other)));
        }

        byte[] large = n.generate(h, PROMPT, "", 768, 768, steps, cfg, "", "", 42, null, 0, 0, 0f,
            new String[0], new float[0], true, counting(), out);
        check(large != null && out[1] == 768 && out[2] == 768 && spread(large) > 10,
            "768x768 decoded in tiles" + (large == null ? ": " + n.error() : ""));
        if (large != null) savePng(large, 768, 768, new File(outDir, "tiled.png"));

        byte[] bad = n.generate(h, PROMPT, "", 512, 512, steps, cfg, "no-such-sampler", "", 1, null, 0, 0, 0f,
            new String[0], new float[0], false, counting(), out);
        check(bad == null && out[0] < 0 && !n.error().isEmpty(), "unknown sampler is rejected: " + n.errorLine());

        final long handle = h;
        t0 = System.nanoTime();
        byte[] stopped = n.square(h, 30, cfg, 1, null, 0f, out, (stage, done, total) -> {
            if (stage == STAGE_SAMPLING && done == 2) n.cancel(handle);
        });
        check(stopped == null && out[0] == CANCELLED,
            String.format("cancel stops a 30-step run early (%.1f s)", (System.nanoTime() - t0) / 1e9));
        byte[] after = n.square(h, steps, cfg, 42, null, 0f, out, counting());
        check(after != null && Arrays.equals(first, after), "the session still works after a cancel");
        n.free(h);

        if (args.length > 6 && args[6].equals("taesd")) {
            h = n.load(new File(models, "model.gguf").getPath(), "", new File(models, "taesd.safetensors").getPath(), threads);
            byte[] fast = h == 0 ? null : n.square(h, steps, cfg, 42, null, 0f, out, counting());
            check(fast != null && spread(fast) > 10, "generation with TAESD as the decoder" + (fast == null ? ": " + n.error() : ""));
            if (fast != null) savePng(fast, 512, 512, new File(outDir, "taesd.png"));
            if (h != 0) n.free(h);
        }

        long missingUp = n.loadUpscaler(new File(models, "no-such-upscaler.pth").getPath(), threads);
        check(missingUp == 0 && !n.error().isEmpty(), "missing upscaler fails with a message: " + n.errorLine());
        File brokenPth = new File(outDir, "garbage.pth");
        java.nio.file.Files.write(brokenPth.toPath(), new byte[4096]);
        long brokenUp = n.loadUpscaler(brokenPth.getPath(), threads);
        check(brokenUp == 0 && !n.error().isEmpty(), "a file that is not an upscaler fails with a message: " + n.errorLine());

        long u = n.loadUpscaler(new File(models, "esrgan.pth").getPath(), threads);
        check(u != 0, "upscaler loads" + (u == 0 ? ": " + n.error() : " (x" + n.upscalerScale(u) + ")"));
        if (u == 0) System.exit(1);
        // Not a multiple of the block size, so edge blocks and seams are exercised.
        int sw = 230, sh = 150;
        byte[] small = crop(first, 512, 140, 180, sw, sh);
        t0 = System.nanoTime();
        byte[] big = n.upscale(u, small, sw, sh, counting(), out);
        check(big != null && out[0] == OK && out[1] == sw * 4 && out[2] == sh * 4 && big.length == sw * sh * 48,
            "upscale returns 4x dimensions (" + out[1] + "x" + out[2] + ")" + (big == null ? ": " + n.error() : ""));
        if (big != null) {
            System.out.printf("      %d blocks in %.1f s%n", lastTotal, (System.nanoTime() - t0) / 1e9);
            check(tileCalls == lastTotal + 1 && lastDone == lastTotal, "block progress reached " + lastDone + " of " + lastTotal);
            double diff = meanDiff(small, shrink(big, sw * 4, sh * 4, 4));
            check(diff < 6, String.format("the enlarged image matches its source when shrunk back (diff %.2f)", diff));
            savePng(small, sw, sh, new File(outDir, "upscale-source.png"));
            savePng(big, sw * 4, sh * 4, new File(outDir, "upscale.png"));
        }
        byte[] none = n.upscale(u, small, sw, sh, (stage, done, total) -> {
            if (done == 1) n.cancelUpscaler(u);
        }, out);
        check(none == null && out[0] == CANCELLED, "upscale stops between blocks when cancelled");
        n.freeUpscaler(u);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
