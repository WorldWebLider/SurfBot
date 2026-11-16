package uz.nurbek.surfbot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Small convolutional network that runs AND trains on the phone, no libraries.
 * Input 32x56 grayscale -> conv 5x5/2 (12) -> conv 3x3/2 (24) -> dense 64 -> 5 actions.
 * Not thread-safe: use one instance for playing and another for training.
 */
public final class Net {
    public static final int W = 32, H = 56, IN = W * H;
    static final int C1 = 12, W1 = 16, H1 = 28;
    static final int C2 = 24, W2 = 8, H2 = 14;
    static final int F = C2 * W2 * H2, D = 64, OUT = 5;
    private static final int MAGIC = 0x53424E31;

    final float[] w1 = new float[C1 * 25], b1 = new float[C1];
    final float[] w2 = new float[C2 * C1 * 9], b2 = new float[C2];
    final float[] w3 = new float[D * F], b3 = new float[D];
    final float[] w4 = new float[OUT * D], b4 = new float[OUT];
    private final float[][] params = {w1, b1, w2, b2, w3, b3, w4, b4};

    // activations
    private final float[] x = new float[IN];
    private final float[] a1 = new float[C1 * H1 * W1];
    private final float[] a2 = new float[F];
    private final float[] a3 = new float[D];
    private final float[] p = new float[OUT];
    // training buffers (allocated on first use)
    private float[][] grads, vel;
    private float[] d1, d2, d3;

    public Net() {
        Random r = new Random();
        init(w1, 25, r); init(w2, C1 * 9, r); init(w3, F, r); init(w4, D, r);
    }

    private static void init(float[] w, int fanIn, Random r) {
        float s = (float) Math.sqrt(2.0 / fanIn);
        for (int i = 0; i < w.length; i++) w[i] = (float) r.nextGaussian() * s;
    }

    /** 64x112 recorded frame -> 32x56 network input (2x2 average), written into dst at off. */
    public static void pool(byte[] small, byte[] dst, int off) {
        for (int y = 0; y < H; y++) {
            int r0 = (2 * y) * 64, r1 = r0 + 64;
            for (int xx = 0; xx < W; xx++) {
                int c = 2 * xx;
                int s = (small[r0 + c] & 255) + (small[r0 + c + 1] & 255)
                      + (small[r1 + c] & 255) + (small[r1 + c + 1] & 255);
                dst[off + y * W + xx] = (byte) (s >> 2);
            }
        }
    }

    /** Returns action probabilities. The returned array is reused between calls. */
    public float[] forward(byte[] in, int off) {
        for (int i = 0; i < IN; i++) x[i] = (in[off + i] & 255) * (1f / 255f);
        // conv1: 5x5, stride 2, pad 2
        for (int o = 0; o < C1; o++) {
            int wo = o * 25;
            for (int y = 0; y < H1; y++) {
                for (int xx = 0; xx < W1; xx++) {
                    float s = b1[o];
                    for (int ky = 0; ky < 5; ky++) {
                        int iy = 2 * y + ky - 2;
                        if (iy < 0 || iy >= H) continue;
                        for (int kx = 0; kx < 5; kx++) {
                            int ix = 2 * xx + kx - 2;
                            if (ix < 0 || ix >= W) continue;
                            s += w1[wo + ky * 5 + kx] * x[iy * W + ix];
                        }
                    }
                    a1[(o * H1 + y) * W1 + xx] = s > 0 ? s : 0;
                }
            }
        }
        // conv2: 3x3, stride 2, pad 1
        for (int o = 0; o < C2; o++) {
            for (int y = 0; y < H2; y++) {
                for (int xx = 0; xx < W2; xx++) {
                    float s = b2[o];
                    for (int c = 0; c < C1; c++) {
                        int wo = (o * C1 + c) * 9;
                        for (int ky = 0; ky < 3; ky++) {
                            int iy = 2 * y + ky - 1;
                            if (iy < 0 || iy >= H1) continue;
                            for (int kx = 0; kx < 3; kx++) {
                                int ix = 2 * xx + kx - 1;
                                if (ix < 0 || ix >= W1) continue;
                                s += w2[wo + ky * 3 + kx] * a1[(c * H1 + iy) * W1 + ix];
                            }
                        }
                    }
                    a2[(o * H2 + y) * W2 + xx] = s > 0 ? s : 0;
                }
            }
        }
        for (int j = 0; j < D; j++) {
            float s = b3[j];
            int wo = j * F;
            for (int i = 0; i < F; i++) s += w3[wo + i] * a2[i];
            a3[j] = s > 0 ? s : 0;
        }
        float mx = -1e30f;
        for (int k = 0; k < OUT; k++) {
            float s = b4[k];
            int wo = k * D;
            for (int j = 0; j < D; j++) s += w4[wo + j] * a3[j];
            p[k] = s;
            if (s > mx) mx = s;
        }
        float sum = 0;
        for (int k = 0; k < OUT; k++) { p[k] = (float) Math.exp(p[k] - mx); sum += p[k]; }
        for (int k = 0; k < OUT; k++) p[k] /= sum;
        return p;
    }

    private void ensureTrain() {
        if (grads != null) return;
        grads = new float[params.length][];
        vel = new float[params.length][];
        for (int i = 0; i < params.length; i++) {
            grads[i] = new float[params[i].length];
            vel[i] = new float[params[i].length];
        }
        d1 = new float[a1.length]; d2 = new float[F]; d3 = new float[D];
    }

    /** Forward + backward for one sample; gradients are accumulated. Returns the loss. */
    public float accumulate(byte[] in, int off, int label, float weight) {
        ensureTrain();
        forward(in, off);
        float loss = (float) -Math.log(Math.max(p[label], 1e-7f)) * weight;
        float[] g1 = grads[0], gb1 = grads[1], g2 = grads[2], gb2 = grads[3],
                g3 = grads[4], gb3 = grads[5], g4 = grads[6], gb4 = grads[7];
        java.util.Arrays.fill(d3, 0);
        for (int k = 0; k < OUT; k++) {
            float dz = (p[k] - (k == label ? 1f : 0f)) * weight;
            gb4[k] += dz;
            int wo = k * D;
            for (int j = 0; j < D; j++) { g4[wo + j] += dz * a3[j]; d3[j] += dz * w4[wo + j]; }
        }
        java.util.Arrays.fill(d2, 0);
        for (int j = 0; j < D; j++) {
            if (a3[j] <= 0) continue;
            float dz = d3[j];
            if (dz == 0) continue;
            gb3[j] += dz;
            int wo = j * F;
            for (int i = 0; i < F; i++) { g3[wo + i] += dz * a2[i]; d2[i] += dz * w3[wo + i]; }
        }
        java.util.Arrays.fill(d1, 0);
        for (int o = 0; o < C2; o++) {
            for (int y = 0; y < H2; y++) {
                for (int xx = 0; xx < W2; xx++) {
                    int oi = (o * H2 + y) * W2 + xx;
                    if (a2[oi] <= 0) continue;
                    float dz = d2[oi];
                    if (dz == 0) continue;
                    gb2[o] += dz;
                    for (int c = 0; c < C1; c++) {
                        int wo = (o * C1 + c) * 9;
                        for (int ky = 0; ky < 3; ky++) {
                            int iy = 2 * y + ky - 1;
                            if (iy < 0 || iy >= H1) continue;
                            for (int kx = 0; kx < 3; kx++) {
                                int ix = 2 * xx + kx - 1;
                                if (ix < 0 || ix >= W1) continue;
                                int ii = (c * H1 + iy) * W1 + ix;
                                g2[wo + ky * 3 + kx] += dz * a1[ii];
                                d1[ii] += dz * w2[wo + ky * 3 + kx];
                            }
                        }
                    }
                }
            }
        }
        for (int o = 0; o < C1; o++) {
            int wo = o * 25;
            for (int y = 0; y < H1; y++) {
                for (int xx = 0; xx < W1; xx++) {
                    int oi = (o * H1 + y) * W1 + xx;
                    if (a1[oi] <= 0) continue;
                    float dz = d1[oi];
                    if (dz == 0) continue;
                    gb1[o] += dz;
                    for (int ky = 0; ky < 5; ky++) {
                        int iy = 2 * y + ky - 2;
                        if (iy < 0 || iy >= H) continue;
                        for (int kx = 0; kx < 5; kx++) {
                            int ix = 2 * xx + kx - 2;
                            if (ix < 0 || ix >= W) continue;
                            g1[wo + ky * 5 + kx] += dz * x[iy * W + ix];
                        }
                    }
                }
            }
        }
        return loss;
    }

    /** SGD with momentum over the accumulated gradients of `batch` samples. */
    public void step(float lr, int batch) {
        float s = lr / batch;
        for (int i = 0; i < params.length; i++) {
            float[] w = params[i], g = grads[i], v = vel[i];
            for (int j = 0; j < w.length; j++) {
                float gj = g[j];
                if (gj > 5f * batch) gj = 5f * batch; else if (gj < -5f * batch) gj = -5f * batch;
                v[j] = 0.9f * v[j] - s * gj;
                w[j] += v[j];
                g[j] = 0;
            }
        }
    }

    float[] gradOf(int i) { return grads[i]; }
    float[] paramOf(int i) { return params[i]; }

    public void save(File f) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            o.writeInt(MAGIC);
            for (float[] w : params) for (float v : w) o.writeFloat(v);
        }
    }

    public static Net load(File f) throws IOException {
        Net n = new Net();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException("bad model file");
            for (float[] w : n.params) for (int i = 0; i < w.length; i++) w[i] = in.readFloat();
        }
        return n;
    }
}
