package kappzzang.jeongsan.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kappzzang.jeongsan.dto.Image;
import kappzzang.jeongsan.dto.response.ParsedReceiptResponse;
import kappzzang.jeongsan.global.client.clova.ClovaApiClient;
import kappzzang.jeongsan.global.client.clova.ClovaOcrProperties;
import kappzzang.jeongsan.global.client.dto.response.ChatGptResponse;
import kappzzang.jeongsan.global.client.dto.response.GeneralOcrResponse;
import kappzzang.jeongsan.global.client.openai.GptPromptManager;
import kappzzang.jeongsan.global.client.openai.OpenAiApiClient;
import kappzzang.jeongsan.global.client.openai.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "RECEIPT_BENCH_DIR", matches = ".+")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = {
        ClovaApiClient.class,
        OpenAiApiClient.class,
        GptPromptManager.class,
        ReceiptPipelineBenchmark.BenchmarkConfig.class
    })
@TestPropertySource(properties = {
    "logging.level.root=WARN",
    "logging.level.org.springframework=WARN",
    "logging.level.kappzzang=WARN"
})
class ReceiptPipelineBenchmark {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int CONTROLLER_IMAGE_DATA_LIMIT = 1_048_576;
    private static final int DEFAULT_TIMEOUT_MS = 120_000;

    @Autowired
    private ClovaApiClient clovaApiClient;

    @Autowired
    private OpenAiApiClient openAiApiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void measureOcrAndGptLatency() throws Exception {
        if (AopUtils.isAopProxy(clovaApiClient) || AopUtils.isAopProxy(openAiApiClient)) {
            throw new IllegalStateException("클라이언트가 프록시(재시도 활성)되어 있습니다. 재시도 OFF 상태가 아닙니다.");
        }
        Path imageDir = Path.of(System.getenv("RECEIPT_BENCH_DIR"));
        int rounds = envInt("RECEIPT_BENCH_ROUNDS", 4);
        int warmup = envInt("RECEIPT_BENCH_WARMUP", 1);
        long delayMs = envInt("RECEIPT_BENCH_DELAY_MS", 300);
        int timeoutMs = envInt("RECEIPT_BENCH_TIMEOUT_MS", DEFAULT_TIMEOUT_MS);
        Path outDir = Path.of(Objects.requireNonNullElse(System.getenv("RECEIPT_BENCH_OUT"),
            "benchmark/results"));
        Files.createDirectories(outDir);

        List<SourceImage> images = loadImages(imageDir);
        if (images.isEmpty()) {
            throw new IllegalStateException("이미지가 없습니다: " + imageDir.toAbsolutePath());
        }
        log("이미지 %d장 로드, %d회차, 워밍업 %d회, 딜레이 %dms, 타임아웃 %dms, 재시도 OFF",
            images.size(), rounds, warmup, delayMs, timeoutMs);

        for (int i = 0; i < warmup; i++) {
            runPipeline(0, images.get(i % images.size()));
            log("warm-up %d/%d 완료", i + 1, warmup);
        }

        List<Sample> samples = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            for (SourceImage image : images) {
                Sample sample = runPipeline(round, image);
                samples.add(sample);
                log("[round %d] %-8s ocr=%9.1fms gpt=%9.1fms total=%9.1fms %s%s", round,
                    image.fileName(), sample.ocrMs(), sample.gptMs(), sample.totalMs(),
                    sample.status(), sample.error() == null ? "" : " (" + sample.error() + ")");
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            }
        }

        String stamp = LocalDateTime.now().format(TS);
        Path csv = outDir.resolve("receipt-bench-" + stamp + ".csv");
        Path summary = outDir.resolve("receipt-bench-" + stamp + "-summary.txt");
        writeCsv(csv, samples);
        String summaryText = buildSummary(samples, images.size(), rounds, timeoutMs);
        Files.writeString(summary, summaryText, StandardCharsets.UTF_8);

        System.out.println(summaryText);
        log("raw CSV  : %s", csv.toAbsolutePath());
        log("summary  : %s", summary.toAbsolutePath());
    }

    private Sample runPipeline(int round, SourceImage source) {
        Image image = new Image(source.format(), null, source.base64(), source.name());
        double ocrMs = Double.NaN;
        double gptMs = Double.NaN;
        int ocrFields = -1;
        Integer promptTokens = null;
        Integer completionTokens = null;
        boolean parsed = false;
        String status = "OK";
        String error = null;

        long t0 = System.nanoTime();
        long t1 = 0;
        try {
            GeneralOcrResponse ocr = clovaApiClient.requestClovaGeneralOcr(image);
            ocrMs = elapsedMs(t0);
            GeneralOcrResponse.ImageResult ocrImage = ocr.images().getFirst();
            ocrFields = ocrImage.fields() == null ? 0 : ocrImage.fields().size();

            String gptInput = objectMapper.writeValueAsString(ocrImage);

            t1 = System.nanoTime();
            ChatGptResponse gpt = openAiApiClient.extractDataUsingGPT(gptInput);
            gptMs = elapsedMs(t1);
            if (gpt.usage() != null) {
                promptTokens = gpt.usage().promptTokens();
                completionTokens = gpt.usage().completionTokens();
            }

            try {
                objectMapper.readValue(gpt.choices().getFirst().message().content(),
                    ParsedReceiptResponse.class);
                parsed = true;
            } catch (Exception e) {
                status = "PARSE_FAIL";
            }
        } catch (Exception e) {
            boolean ocrStage = Double.isNaN(ocrMs);
            boolean timeout = isTimeout(e);
            status = (ocrStage ? "OCR_" : "GPT_") + (timeout ? "TIMEOUT" : "FAIL");
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (ocrStage) {
                ocrMs = elapsedMs(t0);
            } else if (t1 != 0) {
                gptMs = elapsedMs(t1);
            }
        }

        return new Sample(round, source.fileName(), source.format(), source.bytes(),
            source.base64().length(), ocrMs, gptMs, ocrFields, promptTokens, completionTokens,
            parsed, status, error);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private void writeCsv(Path path, List<Sample> samples) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.print('﻿');
            w.println("round,image,format,file_bytes,base64_len,over_1mb_limit,ocr_ms,gpt_ms,"
                + "total_ms,ocr_fields,prompt_tokens,completion_tokens,parsed,status,error");
            for (Sample s : samples) {
                w.println(String.join(",",
                    String.valueOf(s.round()),
                    s.image(),
                    s.format(),
                    String.valueOf(s.fileBytes()),
                    String.valueOf(s.base64Len()),
                    String.valueOf(s.base64Len() > CONTROLLER_IMAGE_DATA_LIMIT),
                    fmt(s.ocrMs()),
                    fmt(s.gptMs()),
                    fmt(s.totalMs()),
                    String.valueOf(s.ocrFields()),
                    s.promptTokens() == null ? "" : s.promptTokens().toString(),
                    s.completionTokens() == null ? "" : s.completionTokens().toString(),
                    String.valueOf(s.parsed()),
                    s.status(),
                    s.error() == null ? "" : '"' + s.error().replace("\"", "'") + '"'));
            }
        }
    }

    private String buildSummary(List<Sample> samples, int imageCount, int rounds,
        int timeoutMs) {
        StringBuilder sb = new StringBuilder();
        long ok = samples.stream().filter(Sample::isOk).count();

        sb.append("==== Receipt OCR -> GPT 응답 시간 측정 결과 ====\n");
        sb.append(String.format("이미지 %d장 x %d회차 = %d회 호출 (재시도 OFF, 타임아웃 %dms)%n",
            imageCount, rounds, samples.size(), timeoutMs));
        sb.append(String.format("성공 %d / 실패 %d%n", ok, samples.size() - ok));
        samples.stream().collect(Collectors.groupingBy(Sample::status, Collectors.counting()))
            .forEach((k, v) -> sb.append(String.format("  - %s: %d%n", k, v)));

        List<Sample> ocrResponded = samples.stream()
            .filter(s -> !s.status().startsWith("OCR_")).toList();
        List<Sample> gptResponded = samples.stream()
            .filter(s -> "OK".equals(s.status()) || "PARSE_FAIL".equals(s.status())).toList();
        List<Sample> okList = samples.stream().filter(Sample::isOk).toList();

        sb.append("\n[단계별 응답 시간 (ms)]\n");
        sb.append(header());
        sb.append(statsRow("OCR", ocrResponded, Sample::ocrMs));
        sb.append(statsRow("GPT", gptResponded, Sample::gptMs));
        sb.append(statsRow("TOTAL", gptResponded, Sample::totalMs));
        sb.append("  * OCR: OCR 응답 수신 건 / GPT·TOTAL: GPT 응답 수신 건 (실패·타임아웃 건은 제외, 아래 별도 표기)\n");

        sb.append("\n[포맷별 (OK 건)]\n");
        sb.append(header());
        okList.stream().collect(Collectors.groupingBy(Sample::format, LinkedHashMap::new,
                Collectors.toList()))
            .forEach((fmt, list) -> {
                sb.append(statsRow("OCR-" + fmt, list, Sample::ocrMs));
                sb.append(statsRow("GPT-" + fmt, list, Sample::gptMs));
            });

        sb.append("\n[회차별 평균 (OK 건)]\n");
        for (int r = 1; r <= rounds; r++) {
            int round = r;
            List<Sample> list = okList.stream().filter(s -> s.round() == round).toList();
            sb.append(String.format("  round %d: n=%3d  ocr=%9.1f  gpt=%9.1f  total=%9.1f%n",
                r, list.size(), mean(list, Sample::ocrMs), mean(list, Sample::gptMs),
                mean(list, Sample::totalMs)));
        }

        sb.append("\n[이미지별 평균 (OK 건, total 내림차순)]\n");
        Map<String, List<Sample>> byImage = okList.stream()
            .collect(Collectors.groupingBy(Sample::image));
        byImage.entrySet().stream()
            .sorted(Comparator.comparingDouble(
                (Map.Entry<String, List<Sample>> e) -> mean(e.getValue(), Sample::totalMs))
                .reversed())
            .forEach(e -> {
                List<Sample> list = e.getValue();
                Sample first = list.getFirst();
                sb.append(String.format(
                    "  %-8s n=%d  %6.0fKB  fields=%3d  ocr=%9.1f  gpt=%9.1f  total=%9.1f"
                        + "  completion_tok=%.0f%n",
                    e.getKey(), list.size(), first.fileBytes() / 1024.0, first.ocrFields(),
                    mean(list, Sample::ocrMs), mean(list, Sample::gptMs),
                    mean(list, Sample::totalMs),
                    mean(list, s -> s.completionTokens() == null ? Double.NaN
                        : s.completionTokens())));
            });

        List<Sample> failed = samples.stream().filter(s -> !s.isOk()).toList();
        if (!failed.isEmpty()) {
            sb.append("\n[실패 건]\n");
            failed.forEach(s -> sb.append(String.format(
                "  round %d %-8s %-12s ocr=%9.1f gpt=%9.1f  %s%n", s.round(), s.image(),
                s.status(), s.ocrMs(), s.gptMs(), s.error() == null ? "" : s.error())));
        }

        List<String> oversize = samples.stream()
            .filter(s -> s.base64Len() > CONTROLLER_IMAGE_DATA_LIMIT)
            .map(Sample::image).distinct().toList();
        if (!oversize.isEmpty()) {
            sb.append("\n※ base64 길이가 컨트롤러 제한(1MB)을 넘는 이미지 (실서비스에선 400 응답): ")
                .append(oversize).append('\n');
        }
        return sb.toString();
    }

    private static String header() {
        return String.format("  %-10s %5s %9s %9s %9s %9s %9s %9s %9s %9s%n",
            "stage", "n", "mean", "std", "min", "p50", "p90", "p95", "p99", "max");
    }

    private static String statsRow(String label, List<Sample> list,
        ToDoubleFunction<Sample> fn) {
        double[] v = list.stream().mapToDouble(fn).filter(d -> !Double.isNaN(d)).sorted()
            .toArray();
        if (v.length == 0) {
            return String.format("  %-10s %5d  (no data)%n", label, 0);
        }
        double mean = Arrays.stream(v).average().orElse(Double.NaN);
        double var = Arrays.stream(v).map(d -> (d - mean) * (d - mean)).sum()
            / Math.max(1, v.length - 1);
        return String.format(Locale.ROOT,
            "  %-10s %5d %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f%n",
            label, v.length, mean, Math.sqrt(var), v[0], pct(v, 50), pct(v, 90), pct(v, 95),
            pct(v, 99), v[v.length - 1]);
    }

    private static double pct(double[] v, double p) {
        double rank = p / 100.0 * (v.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        return v[lo] + (v[hi] - v[lo]) * (rank - lo);
    }

    private static double mean(List<Sample> list, ToDoubleFunction<Sample> fn) {
        return list.stream().mapToDouble(fn).filter(d -> !Double.isNaN(d)).average()
            .orElse(Double.NaN);
    }

    private List<SourceImage> loadImages(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                    .matches("^\\d+\\.(png|jpe?g)$"))
                .sorted(Comparator.comparingInt(ReceiptPipelineBenchmark::numberOf))
                .map(ReceiptPipelineBenchmark::toSourceImage)
                .toList();
        }
    }

    private static int numberOf(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.substring(0, name.indexOf('.')));
    }

    private static SourceImage toSourceImage(Path p) {
        try {
            String fileName = p.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            byte[] bytes = Files.readAllBytes(p);
            return new SourceImage(fileName, fileName.substring(0, dot), ext, bytes.length,
                Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("이미지 읽기 실패: " + p, e);
        }
    }

    private static int envInt(String key, int defaultValue) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v.trim());
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.1f", d);
    }

    private static void log(String format, Object... args) {
        System.out.printf("[bench] " + format + "%n", args);
    }

    private record SourceImage(String fileName, String name, String format, long bytes,
                               String base64) {

    }

    private record Sample(int round, String image, String format, long fileBytes,
                          int base64Len, double ocrMs, double gptMs, int ocrFields,
                          Integer promptTokens, Integer completionTokens, boolean parsed,
                          String status, String error) {

        double totalMs() {
            return ocrMs + gptMs;
        }

        boolean isOk() {
            return "OK".equals(status);
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties({ClovaOcrProperties.class, OpenAiProperties.class})
    static class BenchmarkConfig {

        private static final String CLOVA_OCR_SECRET_HEADER = "X-OCR-SECRET";
        private static final String AUTHORIZATION_HEADER = "Authorization";

        @Bean
        @Qualifier("clovaOcrClientBuilder")
        RestClient.Builder clovaOcrClientBuilder(ClovaOcrProperties properties) {
            return benchmarkRestClientBuilder()
                .defaultHeader(CLOVA_OCR_SECRET_HEADER, properties.key());
        }

        @Bean
        @Qualifier("openAiClientBuilder")
        RestClient.Builder openAiClientBuilder(OpenAiProperties properties) {
            return benchmarkRestClientBuilder()
                .defaultHeader(AUTHORIZATION_HEADER,
                    properties.authType() + " " + properties.key());
        }

        private RestClient.Builder benchmarkRestClientBuilder() {
            Duration timeout = Duration.ofMillis(
                envInt("RECEIPT_BENCH_TIMEOUT_MS", DEFAULT_TIMEOUT_MS));
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
            return RestClient.builder()
                .requestFactory(
                    ClientHttpRequestFactories.get(SimpleClientHttpRequestFactory.class, settings));
        }
    }
}
