package com.modulai.engine.llamacpp;

import java.nio.charset.StandardCharsets;

/**
 * Test harness for modulai_llama.cpp. It shares its name and method signatures with
 * LlamaNative.kt on purpose: that is what makes the JNI symbols resolve.
 *
 * Args: <dir with libmodulai-llama.so> <dir with smol.gguf and qwen.gguf> [bench model path]
 */
public class LlamaNative {
    public native void init(String dir);
    public native String systemInfo();
    public native long load(String path, int nCtx, int nThreads, int nGpuLayers, boolean mlock, boolean mmap);
    public native void free(long h);
    public native int contextSize(long h);
    public native void cancel(long h);
    public native int countTokens(long h, String[] roles, String[] contents, boolean thinking);
    public native int generate(long h, String[] roles, String[] contents, float temp, float topP, int topK,
        float minP, float rep, int maxTokens, int seed, boolean thinking, int budget, PieceCallback cb);

    static int pieces;
    static int failures;
    static double seconds;
    static long firstPieceAt;

    static String run(LlamaNative n, long h, String[] r, String[] c, int max, boolean think, int budget, int[] code) {
        StringBuilder sb = new StringBuilder();
        pieces = 0;
        firstPieceAt = 0;
        code[0] = n.generate(h, r, c, 0.7f, 0.95f, 40, 0.05f, 1.1f, max, 1, think, budget, b -> {
            if (pieces++ == 0) firstPieceAt = System.nanoTime();
            sb.append(new String(b, StandardCharsets.UTF_8));
        });
        seconds = firstPieceAt == 0 ? 0 : (System.nanoTime() - firstPieceAt) / 1e9;
        return sb.toString();
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static void show(String text) {
        String flat = text.replace("\n", "\\n");
        System.out.println("  > " + (flat.length() > 300 ? flat.substring(0, 300) + "…" : flat));
    }

    public static void main(String[] a) {
        System.load(a[0] + "/libmodulai-llama.so");
        LlamaNative n = new LlamaNative();
        n.init(a[0]);
        System.out.println("SYSTEM " + n.systemInfo());
        int[] code = new int[1];
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());

        long h = n.load(a[1] + "/smol.gguf", 2048, threads, 0, false, true);
        check(h != 0, "load smol");
        check(n.contextSize(h) == 2048, "context size");
        check(n.load("/nonexistent.gguf", 2048, threads, 0, false, true) == 0, "missing file returns 0");

        String[] r1 = {"system", "user"};
        String[] c1 = {"You are a helpful assistant.", "Привет! Tell me one sentence about cats 😀"};
        int n1 = n.countTokens(h, r1, c1, false);
        check(n1 > 10 && n1 < 100, "countTokens = " + n1);

        String out1 = run(n, h, r1, c1, 40, false, 0, code);
        show(out1);
        check(code[0] == 0 || code[0] == 1, "first turn finishes");
        check(!out1.isEmpty() && !out1.contains("�"), "output is valid UTF-8");
        check(!out1.contains("<|im_"), "no control tokens leak");

        String[] r2 = {"system", "user", "assistant", "user"};
        String out2 = run(n, h, r2, new String[]{c1[0], c1[1], out1, "Now one about dogs."}, 40, false, 0, code);
        check(!out2.isEmpty(), "second turn (cache append)");

        String[] c3 = {c1[0], "Say the word banana.", out1, "Repeat it."};
        String out3 = run(n, h, r2, c3, 20, false, 0, code);
        check(!out3.isEmpty(), "edited history (cache rewind)");
        check(run(n, h, r2, c3, 20, false, 0, code).equals(out3), "same prompt and seed is deterministic");

        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            n.cancel(h);
        }).start();
        run(n, h, r1, new String[]{c1[0], "Write a very long story about a dragon."}, 0, false, 0, code);
        check(code[0] == 2, "cancel stops generation");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append("word ");
        run(n, h, r1, new String[]{c1[0], big.toString()}, 10, false, 0, code);
        check(code[0] == -2, "oversized prompt rejected");
        check(!run(n, h, r1, c1, 10, false, 0, code).isEmpty(), "session usable after rejection");
        n.free(h);

        long q = n.load(a[1] + "/qwen.gguf", 2048, threads, 0, false, true);
        check(q != 0, "load qwen");
        String[] rq = {"user"};
        String noThink = run(n, q, rq, new String[]{"What is 17 * 3?"}, 60, false, 0, code);
        show(noThink);
        check(!noThink.isEmpty() && noThink.replace("<think>", "").replace("</think>", "").trim().length() > 0, "thinking off");
        for (int budget : new int[]{15, 40}) {
            String out = run(n, q, rq, new String[]{"Why is the sky blue? Answer briefly."}, 200, true, budget, code);
            show(out);
            int s = out.indexOf("<think>"), e = out.indexOf("</think>");
            check(s >= 0 && e > s && out.substring(e + 8).trim().length() > 0, "budget " + budget + ": block closed, answer follows");
        }
        check(run(n, q, rq, new String[]{"What is 17 * 3?"}, 60, true, 0, code).contains("<think>"), "unlimited thinking");
        n.free(q);

        long tiny = n.load(a[1] + "/qwen.gguf", 256, threads, 0, false, true);
        run(n, tiny, rq, new String[]{"Write a very long story about a dragon."}, 0, true, 0, code);
        check(code[0] == 1, "context exhaustion returns LENGTH");
        check(!run(n, tiny, rq, new String[]{"Say hi."}, 20, false, 0, code).isEmpty(), "usable after exhaustion");
        n.free(tiny);

        // Generation speed per thread count; this is what picks the app's default.
        String benchModel = a.length > 2 ? a[2] : a[1] + "/qwen.gguf";
        int cores = Runtime.getRuntime().availableProcessors();
        for (int t : new int[]{2, 4, 6, 8}) {
            if (t > cores) break;
            long b = n.load(benchModel, 2048, t, 0, false, true);
            if (b == 0) { check(false, "bench load " + benchModel); break; }
            run(n, b, rq, new String[]{"Write a long story about a dragon."}, 128, false, 0, code);
            System.out.printf("BENCH threads=%d tokens=%d tok/s=%.1f model=%s%n",
                t, pieces, pieces > 1 ? (pieces - 1) / seconds : 0, benchModel.substring(benchModel.lastIndexOf('/') + 1));
            n.free(b);
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
