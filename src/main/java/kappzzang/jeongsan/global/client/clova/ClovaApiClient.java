package kappzzang.jeongsan.global.client.clova;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import kappzzang.jeongsan.dto.Image;
import kappzzang.jeongsan.global.client.dto.request.GeneralOcrRequest;
import kappzzang.jeongsan.global.client.dto.response.GeneralOcrResponse;
import kappzzang.jeongsan.global.client.dto.response.GeneralOcrResponse.ImageResult;
import kappzzang.jeongsan.global.common.enumeration.ErrorType;
import kappzzang.jeongsan.global.config.CacheConfig;
import kappzzang.jeongsan.global.exception.JeongsanException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class ClovaApiClient {

    private static final int MAX_ATTEMPTS = 2;
    private static final int BACK_OFF_DELAY = 500;
    private static final String INFER_SUCCESS = "SUCCESS";
    private static final String INFER_FAILURE = "FAILURE";

    private final ClovaOcrProperties clovaOcrProperties;
    private final RestClient clovaOcrClient;

    public ClovaApiClient(ClovaOcrProperties clovaOcrProperties,
        @Qualifier("clovaOcrClientBuilder") RestClient.Builder clovaOcrClientBuilder) {
        this.clovaOcrProperties = clovaOcrProperties;
        this.clovaOcrClient = clovaOcrClientBuilder.build();
    }

    @Cacheable(
        cacheNames = CacheConfig.OCR_CACHE,
        keyGenerator = CacheConfig.IMAGE_HASH_KEY_GENERATOR
    )

    @Retryable(
        retryFor = RestClientException.class,
        maxAttempts = MAX_ATTEMPTS,
        backoff = @Backoff(delay = BACK_OFF_DELAY)
    )
    public GeneralOcrResponse requestClovaGeneralOcr(Image image) {
        GeneralOcrRequest body = new GeneralOcrRequest(image);
        GeneralOcrResponse response = clovaOcrClient.post()
            .uri(clovaOcrProperties.general().url())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
            .body(GeneralOcrResponse.class);
        validateInferResult(response);
        return response;
    }

    private void validateInferResult(GeneralOcrResponse response) {
        if (response == null || response.images() == null || response.images().isEmpty()) {
            log.error("Clova OCR returned empty response");
            throw new JeongsanException(ErrorType.EXTERNAL_API_GENERAL_ERROR);
        }
        ImageResult result = response.images().getFirst();
        String inferResult = result.inferResult();
        boolean hasFields = result.fields() != null && !result.fields().isEmpty();

        if (INFER_SUCCESS.equals(inferResult) && hasFields) {
            return;
        }
        if (INFER_SUCCESS.equals(inferResult) || INFER_FAILURE.equals(inferResult)) {
            log.warn("Clova OCR recognition failed: inferResult={}, message={}", inferResult,
                result.message());
            throw new JeongsanException(ErrorType.RECEIPT_RECOGNITION_FAILED);
        }
        log.error("Clova OCR infer error: inferResult={}, message={}", inferResult,
            result.message());
        throw new JeongsanException(ErrorType.EXTERNAL_API_GENERAL_ERROR);
    }

    private void handleErrorResponse(HttpRequest request, ClientHttpResponse response)
        throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        response.getBody().close();
        log.error("Clova Api error: status={}, body={}", statusCode, responseBody);
        if (statusCode.is5xxServerError()) {
            throw new RestClientException("Server error: " + statusCode);
        }
        throw new JeongsanException(ErrorType.EXTERNAL_API_GENERAL_ERROR);
    }

    @Recover
    public GeneralOcrResponse recoverFromRestClientException(RestClientException e, Image image) {
        log.error("Failed to connect Clova Api after retries");
        if (e.getCause() instanceof SocketTimeoutException
            || e.getCause() instanceof ConnectException) {
            throw new JeongsanException(ErrorType.EXTERNAL_API_REQUEST_TIMEOUT);
        }
        throw new JeongsanException(ErrorType.EXTERNAL_API_GENERAL_ERROR);
    }

    @Recover
    public GeneralOcrResponse recoverFromJeongsanException(JeongsanException e, Image image) {
        throw e;
    }

}
