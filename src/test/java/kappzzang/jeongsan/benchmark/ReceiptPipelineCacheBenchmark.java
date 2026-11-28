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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
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
import kappzzang.jeongsan.global.config.CacheConfig;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * ReceiptPipelineBenchmark의 캐시 ON 버전.
 * <p>
 * - 재시도: OFF (ApiClientConfig/@EnableRetry를 컨텍스트에 포함하지 않음)
 * <p>
 * - 캐시: ON (CacheConfig/@EnableCaching 포함 → ClovaApiClient에 캐시 프록시만 적용)
 * <p>
 * - 적중 판정: Clova로 나간 실제 HTTP 요청 수를 인터셉터로 세어, OCR 호출 중 HTTP 요청이 0건이면 적중으로 판정
 * <p>
 * - 검증: "같은 이미지 바이트가 TTL 안에 OCR에 성공한 적이 있으면 적중"이라는 기대값과 실제 적중 여부를 건별로 비교하고,
 * 불일치가 있으면 결과 파일을 남긴 뒤 테스트를 실패시킴
 */
@EnabledIfEnvironmentVariable(named = "RECEIPT_BENCH_DIR", matches = ".+")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = {
        ClovaApiClient.class,
        OpenAiApiClient.class,
        GptPromptManager.class,
        CacheConfig.class,
        ReceiptPipelineCacheBenchmark.BenchmarkConfig.class
    })
@TestPropertySource(properties = {
    "logging.level.root=WARN",
    "logging.level.org.springframework=WARN",
    "logging.level.kappzzang=WARN"
})
class ReceiptPipelineCacheBenchmark {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int CONTROLLER_IMAGE_DATA_LIMIT = 1_048_576;
    private static final int DEFAULT_TIMEOUT_MS = 120_000;

    // CacheConfig.OCR_CACHE_TTL(private)과 같은 값으로 유지해야 함
    private static final Duration ASSUMED_OCR_CACHE_TTL = Duration.ofMinutes(30);
    // TTL 경계 근처의 건은 만료 여부가 애매하므로 기대값 비교에서 제외
    private static final Duration TTL_BOUNDARY_MARGIN = Duration.ofMinutes(1);

    // Clova로 실제 나간 HTTP 요청 수 (캐시 적중이면 증가하지 않음)
    private static final AtomicInteger CLOVA_HTTP_CALLS = new AtomicInteger();

    @Autowired
    private ClovaApiClient clovaApiClient;

    @Autowired
    private OpenAiApiClient openAiApiClient;

    @Autowired
    private CacheManager cacheManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 이미지 base64 → 캐시에 저장된 시각(nanoTime). 기대 적중 여부 계산용
    private final Map<String, Long> cachedAtNanos = new HashMap<>();

    @Test
    void measureOcrAndGptLatencyWithCache() throws Exception {
        verifyProxySetup();

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
        log("이미지 %d장 로드, %d회차, 워밍업 %d회, 딜레이 %dms, 타임아웃 %dms, 재시도 OFF, 캐시 ON",
            images.size(), rounds, warmup, delayMs, timeoutMs);

        for (int i = 0; i < warmup; i++) {
            runPipeline(0, images.get(i % images.size()));
            log("warm-up %d/%d 완료", i + 1, warmup);
        }
        // 워밍업으로 채워진 캐시가 본 측정에 섞이지 않도록 초기화
        resetCacheState();

        List<Sample> samples = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            for (SourceImage image : images) {
                Sample sample = runPipeline(round, image);
                samples.add(sample);
                log("[round %d] %-8s %-4s ocr=%9.1fms gpt=%9.1fms total=%9.1fms %s%s", round,
                    image.fileName(), sample.cacheLabel(), sample.ocrMs(), sample.gptMs(),
                    sample.totalMs(), sample.status(),
                    sample.error() == null ? "" : " (" + sample.error() + ")");
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            }
        }

        CacheVerification verification = verifyCache(samples, images);

        String stamp = LocalDateTime.now().format(TS);
        Path csv = outDir.resolve("receipt-cache-bench-" + stamp + ".csv");
        Path summary = outDir.resolve("receipt-cache-bench-" + stamp + "-summary.txt");
        writeCsv(csv, samples);
        String summaryText = buildSummary(samples, images.size(), rounds, timeoutMs,
            verification);
        Files.writeString(summary, summaryText, StandardCharsets.UTF_8);

        System.out.println(summaryText);
        log("raw CSV  : %s", csv.toAbsolutePath());
        log("summary  : %s", summary.toAbsolutePath());

        if (!verification.passed()) {
            throw new AssertionError("캐시 적중 검증 실패: " + verification.failureReason()
                + " (상세는 summary 파일의 [캐시 검증] 참고)");
        }
    }

    /**
     * OpenAiApiClient: 프록시 없음 (재시도 OFF)
     * <p>
     * ClovaApiClient: 캐시 프록시만 있음 (CacheInterceptor 존재, 재시도 어드바이스 없음)
     */
    private void verifyProxySetup() {
        if (AopUtils.isAopProxy(openAiApiClient)) {
            throw new IllegalStateException("OpenAiApiClient가 프록시되어 있습니다. 재시도 OFF 상태가 아닙니다.");
        }
        if (!(clovaApiClient instanceof Advised advised)) {
            throw new IllegalStateException("ClovaApiClient가 프록시되지 않았습니다. 캐시가 적용되지 않은 상태입니다.");
        }
        boolean hasCacheInterceptor = false;
        for (Advisor advisor : advised.getAdvisors()) {
            Advice advice = advisor.getAdvice();
            if (isRetryRelated(advisor) || isRetryRelated(advice)) {
                throw new IllegalStateException(
                    "ClovaApiClient에 재시도 어드바이스가 적용되어 있습니다. 재시도 OFF 상태가 아닙니다: "
                        + advice.getClass().getName());
            }
            if (advice instanceof CacheInterceptor) {
                hasCacheInterceptor = true;
            }
        }
        if (!hasCacheInterceptor) {
            throw new IllegalStateException("ClovaApiClient 프록시에 CacheInterceptor가 없습니다. 캐시가 적용되지 않은 상태입니다.");
        }
        log("프록시 검증 통과: ClovaApiClient=캐시만 적용, OpenAiApiClient=프록시 없음");
    }

    private static boolean isRetryRelated(Object target) {
        return target != null && target.getClass().getName().startsWith("org.springframework.retry");
    }

    private void resetCacheState() {
        Cache cache = cacheManager.getCache(CacheConfig.OCR_CACHE);
        if (cache == null) {
            throw new IllegalStateException("캐시를 찾을 수 없습니다: " + CacheConfig.OCR_CACHE);
        }
        cache.clear();
        cachedAtNanos.clear();
        CLOVA_HTTP_CALLS.set(0);
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

        Boolean expectedHit = expectedCacheHit(source.base64());
        int callsBefore = CLOVA_HTTP_CALLS.get();
        boolean ocrSucceeded = false;

        long t0 = System.nanoTime();
        long t1 = 0;
        try {
            GeneralOcrResponse ocr = clovaApiClient.requestClovaGeneralOcr(image);
            ocrMs = elapsedMs(t0);
            ocrSucceeded = true;
            if (CLOVA_HTTP_CALLS.get() > callsBefore) {
                // 미적중으로 새로 저장된 항목만 저장 시각을 기록 (expireAfterWrite이므로 적중은 시각을 갱신하지 않음)
                cachedAtNanos.put(source.base64(), System.nanoTime());
            }
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

        int clovaHttpCalls = CLOVA_HTTP_CALLS.get() - callsBefore;
        Boolean cacheHit = ocrSucceeded ? clovaHttpCalls == 0 : null;

        return new Sample(round, source.fileName(), source.format(), source.bytes(),
            source.base64().length(), ocrMs, gptMs, ocrFields, promptTokens, completionTokens,
            parsed, status, error, cacheHit, expectedHit, clovaHttpCalls);
    }

    /**
     * 같은 바이트의 이미지가 TTL 안에 OCR 성공(=캐시 저장)한 적이 있으면 적중이 기대됨.
     * TTL 경계 근처면 null(판정 제외)을 반환.
     */
    private Boolean expectedCacheHit(String base64) {
        Long cachedAt = cachedAtNanos.get(base64);
        if (cachedAt == null) {
            return false;
        }
        long age = System.nanoTime() - cachedAt;
        long ttl = ASSUMED_OCR_CACHE_TTL.toNanos();
        long margin = TTL_BOUNDARY_MARGIN.toNanos();
        if (age < ttl - margin) {
            return true;
        }
        if (age > ttl + margin) {
            return false;
        }
        return null;
    }

    private CacheVerification verifyCache(List<Sample> samples, List<SourceImage> images) {
        List<Sample> ocrOk = samples.stream().filter(s -> s.cacheHit() != null).toList();
        long hits = ocrOk.stream().filter(s -> s.cacheHit()).count();
        long misses = ocrOk.size() - hits;
        long ocrFailed = samples.size() - ocrOk.size();
        long uniqueImages = images.stream().map(SourceImage::base64).distinct().count();
        int totalHttpCalls = CLOVA_HTTP_CALLS.get();
        int sampledHttpCalls = samples.stream().mapToInt(Sample::clovaHttpCalls).sum();
        long skipped = ocrOk.stream().filter(s -> s.expectedHit() == null).count();
        List<Sample> mismatches = ocrOk.stream()
            .filter(s -> s.expectedHit() != null && !s.expectedHit().equals(s.cacheHit()))
            .toList();
        List<Sample> multiCalls = samples.stream().filter(s -> s.clovaHttpCalls() > 1).toList();

        long cacheSize = -1;
        Cache cache = cacheManager.getCache(CacheConfig.OCR_CACHE);
        if (cache != null
            && cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> c) {
            c.cleanUp();
            cacheSize = c.estimatedSize();
        }

        List<String> reasons = new ArrayList<>();
        if (!mismatches.isEmpty()) {
            reasons.add("기대값과 실제 적중 여부 불일치 " + mismatches.size() + "건");
        }
        if (totalHttpCalls != sampledHttpCalls) {
            reasons.add("측정 구간 밖의 Clova 호출 발생 (전체 " + totalHttpCalls + "회, 건별 합계 "
                + sampledHttpCalls + "회)");
        }
        if (!multiCalls.isEmpty()) {
            reasons.add("한 번의 OCR 호출에서 Clova 요청이 2회 이상 발생 " + multiCalls.size()
                + "건 (재시도가 켜져 있을 가능성)");
        }
        if (hits == 0 && samples.size() > uniqueImages) {
            reasons.add("같은 이미지를 반복 호출했는데 적중이 0건");
        }

        return new CacheVerification(hits, misses, ocrFailed, uniqueImages, totalHttpCalls,
            skipped, cacheSize, mismatches, reasons);
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
                + "total_ms,ocr_fields,prompt_tokens,completion_tokens,parsed,status,"
                + "cache_hit,expected_hit,clova_http_calls,error");
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
                    s.cacheHit() == null ? "" : s.cacheHit().toString(),
                    s.expectedHit() == null ? "" : s.expectedHit().toString(),
                    String.valueOf(s.clovaHttpCalls()),
                    s.error() == null ? "" : '"' + s.error().replace("\"", "'") + '"'));
            }
        }
    }

    private String buildSummary(List<Sample> samples, int imageCount, int rounds,
        int timeoutMs, CacheVerification v) {
        StringBuilder sb = new StringBuilder();
        long ok = samples.stream().filter(Sample::isOk).count();

        sb.append("==== Receipt OCR -> GPT 응답 시간 측정 결과 (캐시 ON) ====\n");
        sb.append(String.format("이미지 %d장 x %d회차 = %d회 호출 (재시도 OFF, 캐시 ON, 타임아웃 %dms)%n",
            imageCount, rounds, samples.size(), timeoutMs));
        sb.append(String.format("성공 %d / 실패 %d%n", ok, samples.size() - ok));
        samples.stream().collect(Collectors.groupingBy(Sample::status, Collectors.counting()))
            .forEach((k, cnt) -> sb.append(String.format("  - %s: %d%n", k, cnt)));

        // ---- 캐시 검증 ----
        sb.append("\n[캐시 검증] ").append(v.passed() ? "PASS" : "FAIL").append('\n');
        sb.append(String.format("  고유 이미지(바이트 기준): %d장 / 전체 OCR 호출: %d회%n",
            v.uniqueImages(), samples.size()));
        sb.append(String.format("  실제 Clova HTTP 요청: %d회 (캐시가 없었다면 %d회)%n",
            v.totalHttpCalls(), samples.size()));
        sb.append(String.format("  캐시 적중: %d회 / 미적중: %d회 / OCR 실패: %d회%n",
            v.hits(), v.misses(), v.ocrFailed()));
        sb.append(String.format("  기대값 불일치: %d건 / TTL 경계로 판정 제외: %d건%n",
            v.mismatches().size(), v.skipped()));
        sb.append(String.format("  종료 시점 캐시 항목 수(estimatedSize): %s%n",
            v.cacheSize() < 0 ? "확인 불가" : String.valueOf(v.cacheSize())));
        v.failureReasons().forEach(r -> sb.append("  ! ").append(r).append('\n'));
        v.mismatches().forEach(s -> sb.append(String.format(
            "    불일치: round %d %-8s 기대=%s 실제=%s%n", s.round(), s.image(),
            s.expectedHit() ? "HIT" : "MISS", s.cacheHit() ? "HIT" : "MISS")));

        sb.append("\n[회차별 캐시 적중]\n");
        for (int r = 1; r <= rounds; r++) {
            int round = r;
            List<Sample> list = samples.stream().filter(s -> s.round() == round).toList();
            long roundHits = list.stream().filter(s -> Boolean.TRUE.equals(s.cacheHit())).count();
            sb.append(String.format("  round %d: 적중 %d / %d%n", r, roundHits, list.size()));
        }

        List<Sample> ocrResponded = samples.stream()
            .filter(s -> !s.status().startsWith("OCR_")).toList();
        List<Sample> gptResponded = samples.stream()
            .filter(s -> "OK".equals(s.status()) || "PARSE_FAIL".equals(s.status())).toList();
        List<Sample> okList = samples.stream().filter(Sample::isOk).toList();

        sb.append("\n[적중 vs 미적중 응답 시간 (ms)]\n");
        sb.append(header());
        sb.append(statsRow("OCR-miss", filterHit(ocrResponded, false), Sample::ocrMs));
        sb.append(statsRow("OCR-hit", filterHit(ocrResponded, true), Sample::ocrMs));
        sb.append(statsRow("TOTAL-miss", filterHit(gptResponded, false), Sample::totalMs));
        sb.append(statsRow("TOTAL-hit", filterHit(gptResponded, true), Sample::totalMs));
        sb.append("  * OCR: OCR 응답 수신 건 / TOTAL: GPT 응답 수신 건\n");

        sb.append("\n[단계별 응답 시간 (ms, 적중·미적중 합산)]\n");
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
                long imageHits = list.stream().filter(s -> Boolean.TRUE.equals(s.cacheHit()))
                    .count();
                sb.append(String.format(
                    "  %-8s n=%d  hit=%d  %6.0fKB  fields=%3d  ocr=%9.1f  gpt=%9.1f  total=%9.1f"
                        + "  completion_tok=%.0f%n",
                    e.getKey(), list.size(), imageHits, first.fileBytes() / 1024.0,
                    first.ocrFields(), mean(list, Sample::ocrMs), mean(list, Sample::gptMs),
                    mean(list, Sample::totalMs),
                    mean(list, s -> s.completionTokens() == null ? Double.NaN
                        : s.completionTokens())));
            });

        List<Sample> failed = samples.stream().filter(s -> !s.isOk()).toList();
        if (!failed.isEmpty()) {
            sb.append("\n[실패 건]\n");
            failed.forEach(s -> sb.append(String.format(
                "  round %d %-8s %-12s %-4s ocr=%9.1f gpt=%9.1f  %s%n", s.round(), s.image(),
                s.status(), s.cacheLabel(), s.ocrMs(), s.gptMs(),
                s.error() == null ? "" : s.error())));
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

    private static List<Sample> filterHit(List<Sample> list, boolean hit) {
        return list.stream().filter(s -> Boolean.valueOf(hit).equals(s.cacheHit())).toList();
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
                .sorted(Comparator.comparingInt(ReceiptPipelineCacheBenchmark::numberOf))
                .map(ReceiptPipelineCacheBenchmark::toSourceImage)
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
        System.out.printf("[cache-bench] " + format + "%n", args);
    }

    private record SourceImage(String fileName, String name, String format, long bytes,
                               String base64) {

    }

    private record Sample(int round, String image, String format, long fileBytes,
                          int base64Len, double ocrMs, double gptMs, int ocrFields,
                          Integer promptTokens, Integer completionTokens, boolean parsed,
                          String status, String error, Boolean cacheHit, Boolean expectedHit,
                          int clovaHttpCalls) {

        double totalMs() {
            return ocrMs + gptMs;
        }

        boolean isOk() {
            return "OK".equals(status);
        }

        String cacheLabel() {
            if (cacheHit == null) {
                return "-";
            }
            return cacheHit ? "HIT" : "MISS";
        }
    }

    private record CacheVerification(long hits, long misses, long ocrFailed, long uniqueImages,
                                     int totalHttpCalls, long skipped, long cacheSize,
                                     List<Sample> mismatches, List<String> failureReasons) {

        boolean passed() {
            return failureReasons.isEmpty();
        }

        String failureReason() {
            return String.join(", ", failureReasons);
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
                .defaultHeader(CLOVA_OCR_SECRET_HEADER, properties.key())
                // 실제로 Clova에 나간 요청만 센다 (캐시 적중 시에는 여기까지 오지 않음)
                .requestInterceptor((request, body, execution) -> {
                    CLOVA_HTTP_CALLS.incrementAndGet();
                    return execution.execute(request, body);
                });
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
