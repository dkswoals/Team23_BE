package kappzzang.jeongsan.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import kappzzang.jeongsan.dto.Image;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableCaching
@Configuration
public class CacheConfig {

    public static final String OCR_CACHE = "ocr";
    public static final String IMAGE_HASH_KEY_GENERATOR = "imageHashKeyGenerator";

    private static final Duration OCR_CACHE_TTL = Duration.ofMinutes(30);
    private static final long OCR_CACHE_MAX_SIZE = 1_000;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(OCR_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(OCR_CACHE_TTL)
            .maximumSize(OCR_CACHE_MAX_SIZE));
        return cacheManager;
    }

    @Bean(IMAGE_HASH_KEY_GENERATOR)
    public KeyGenerator imageHashKeyGenerator() {
        return (target, method, params) -> hash(((Image) params[0]).data());
    }

    private static String hash(String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
