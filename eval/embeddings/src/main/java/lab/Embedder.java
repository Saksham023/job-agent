package lab;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** One sentence-embedding model run with ONNX Runtime: tokenize, run, pool (mean or CLS), L2-normalize. */
final class Embedder implements AutoCloseable {
    final String name;
    final boolean clsPooling;
    final String queryPrefix;
    private final HuggingFaceTokenizer tokenizer;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    Embedder(String name, Path dir, int maxLength, boolean clsPooling, String queryPrefix) throws Exception {
        this.name = name;
        this.clsPooling = clsPooling;
        this.queryPrefix = queryPrefix;
        this.tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"),
                Map.of("maxLength", String.valueOf(maxLength), "truncation", "true", "padding", "false"));
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        this.session = env.createSession(dir.resolve("model.onnx").toString(), options);
    }

    float[] embed(String text) throws Exception {
        Encoding enc = tokenizer.encode(text);
        long[] ids = enc.getIds(), mask = enc.getAttentionMask(), types = enc.getTypeIds();
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][]{ids}));
        inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][]{mask}));
        if (session.getInputNames().contains("token_type_ids")) {
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[][]{types}));
        }
        try (OrtSession.Result result = session.run(inputs)) {
            float[][] hidden = ((float[][][]) result.get(0).getValue())[0];
            int dim = hidden[0].length;
            float[] v = new float[dim];
            if (clsPooling) {
                System.arraycopy(hidden[0], 0, v, 0, dim);
            } else {
                int n = 0;
                for (int t = 0; t < hidden.length; t++) {
                    if (mask[t] == 0) continue;
                    for (int d = 0; d < dim; d++) v[d] += hidden[t][d];
                    n++;
                }
                for (int d = 0; d < dim; d++) v[d] /= n;
            }
            double norm = 0;
            for (float x : v) norm += x * x;
            norm = Math.sqrt(norm);
            for (int d = 0; d < dim; d++) v[d] /= (float) norm;
            return v;
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    static double cosine(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;                                   // both are unit vectors
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
