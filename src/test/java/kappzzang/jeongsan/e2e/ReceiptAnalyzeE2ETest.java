package kappzzang.jeongsan.e2e;


import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.common.mapper.TypeRef;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import kappzzang.jeongsan.domain.KakaoPayInfo;
import kappzzang.jeongsan.domain.Member;
import kappzzang.jeongsan.domain.Team;
import kappzzang.jeongsan.dto.Image;
import kappzzang.jeongsan.dto.ItemSummary;
import kappzzang.jeongsan.dto.response.ParsedReceiptResponse;
import kappzzang.jeongsan.global.client.clova.ClovaOcrProperties;
import kappzzang.jeongsan.global.client.clova.ClovaOcrProperties.GeneralOcr;
import kappzzang.jeongsan.global.client.dto.response.ChatGptResponse;
import kappzzang.jeongsan.global.client.dto.response.GeneralOcrResponse;
import kappzzang.jeongsan.global.client.openai.OpenAiProperties;
import kappzzang.jeongsan.global.common.dto.JeongsanApiResponse;
import kappzzang.jeongsan.global.common.enumeration.ErrorType;
import kappzzang.jeongsan.global.common.enumeration.SuccessType;
import kappzzang.jeongsan.global.common.util.JwtUtil;
import kappzzang.jeongsan.repository.TestDataUtil;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(MockitoExtension.class)
@TestPropertySource(locations = "classpath:application-test.properties")
public class ReceiptAnalyzeE2ETest {

    private static final String TEST_RECEIPT_TITLE = "김밥천국";
    private static final String TEST_ITEM_NAME_A = "참치김밥";
    private static final String TEST_ITEM_NAME_B = "라면";
    private static final Integer TEST_ITEM_QUANTITY = 1;
    private static final Integer TEST_ITEM_PRICE = 1;
    private static final String TEST_PAYMENT_TIME = "2024-02-15 15:30:00";
    private Image image;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClovaOcrProperties clovaOcrProperties;

    @Autowired
    private OpenAiProperties openAiProperties;

    @PersistenceContext
    private EntityManager entityManager;

    private MockWebServer mockWebServer;

    private GeneralOcrResponse ocrResponse;
    private ChatGptResponse gptResponse;
    private String baseUrl;
    private String token;

    @BeforeEach
    void setUp() throws IOException {
        TestDataUtil testDataUtil = new TestDataUtil(entityManager);

        Member member = testDataUtil.createAndPersistMember("MemberA", new KakaoPayInfo());
        Team team = testDataUtil.createAndPersistTeam();
        team.addMember(member, true, true);

        mockWebServer = new MockWebServer();
        mockWebServer.start();
        baseUrl = mockWebServer.url("").toString();

        RestAssured.port = port;
        RestAssured.basePath = "/api";
        RestAssured.defaultParser = Parser.JSON;

        token = jwtUtil.createAccessToken(member.getId());

        image = new Image("jpg", null, UUID.randomUUID().toString(), "TEST_IMAGE_NAME");

        ocrResponse = getGeneralOcrResponse();
        gptResponse = getChatGptResponse();
    }

    @DisplayName("영수증 분석 요청할 때, 유효한 이미지로 요청한다면, 분석 결과를 반환 받는다")
    @Test
    void receiptAnalysis_whenValidInput_ResponseParsedResult() throws JsonProcessingException {

        //given
        given(clovaOcrProperties.general().url()).willReturn(baseUrl);
        given(openAiProperties.url()).willReturn(baseUrl);

        mockWebServer.enqueue(createJsonResponse(ocrResponse, 200));
        mockWebServer.enqueue(createJsonResponse(gptResponse, 200));

        //when
        JeongsanApiResponse<ParsedReceiptResponse> response = RestAssured
            .given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(image)
            .when()
            .post("/receipts/analyze")
            .then()
            .statusCode(SuccessType.RECEIPT_ANALYSIS_SUCCESS.getHttpStatusCode().value())
            .extract()
            .as(new TypeRef<>() {
            });

        //then
        ParsedReceiptResponse expected = new ParsedReceiptResponse(TEST_RECEIPT_TITLE,
            TEST_PAYMENT_TIME,
            List.of(
                new ItemSummary(TEST_ITEM_NAME_A, TEST_ITEM_QUANTITY, TEST_ITEM_PRICE),
                new ItemSummary(TEST_ITEM_NAME_B, TEST_ITEM_QUANTITY, TEST_ITEM_PRICE)
            ));

        assertThat(response.getMessage()).isEqualTo(
            SuccessType.RECEIPT_ANALYSIS_SUCCESS.getMessage());
        assertThat(response.getData()).isEqualTo(expected);
    }

    @DisplayName("영수증 분석을 요청할 때, OCR API 에러가 발생한다면, 외부 API 에러 응답을 반환 받는다")
    @Test
    void receiptAnalysis_whenOcrApiFails_thenReturnExternalApiError()
        throws JsonProcessingException {
        given(clovaOcrProperties.general().url()).willReturn(baseUrl);
        given(openAiProperties.url()).willReturn(baseUrl);

        for (int i = 0; i < 2; i++) {
            mockWebServer.enqueue(createJsonResponse(ocrResponse, 500));
        }

        assertErrorResponse(postAnalyze(), ErrorType.EXTERNAL_API_GENERAL_ERROR);
    }

    @DisplayName("영수증 분석을 요청할 때, GPT API 에러가 발생한다면, 외부 API 에러 응답을 반환 받는다")
    @Test
    void receiptAnalysis_whenGptApiFails_thenReturnExternalApiError()
        throws JsonProcessingException {
        given(clovaOcrProperties.general().url()).willReturn(baseUrl);
        given(openAiProperties.url()).willReturn(baseUrl);

        mockWebServer.enqueue(createJsonResponse(ocrResponse, 200));
        for (int i = 0; i < 3; i++) {
            mockWebServer.enqueue(createJsonResponse(gptResponse, 500));
        }

        assertErrorResponse(postAnalyze(), ErrorType.EXTERNAL_API_GENERAL_ERROR);
    }

    @ParameterizedTest(name = "OCR 인식 결과가 {0}(fields 비어있음: {1})이면, {2} 에러를 반환하고 GPT는 호출하지 않는다")
    @CsvSource({
        "FAILURE, false, RECEIPT_RECOGNITION_FAILED",
        "SUCCESS, true, RECEIPT_RECOGNITION_FAILED",
        "ERROR, false, EXTERNAL_API_GENERAL_ERROR"
    })
    void receiptAnalysis_whenOcrInferNotSuccess_thenReturnError(String inferResult,
        boolean emptyFields, ErrorType expectedError) throws JsonProcessingException {
        given(clovaOcrProperties.general().url()).willReturn(baseUrl);
        given(openAiProperties.url()).willReturn(baseUrl);

        mockWebServer.enqueue(
            createJsonResponse(createOcrResponse(inferResult, emptyFields), 200));

        assertErrorResponse(postAnalyze(), expectedError);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "OCR 인식 결과가 {0}(fields 비어있음: {1})이면, 캐싱하지 않아 재요청 시 OCR을 다시 호출한다")
    @CsvSource({
        "FAILURE, false",
        "SUCCESS, true",
        "ERROR, false"
    })
    void receiptAnalysis_whenOcrInferNotSuccess_thenNotCached(String inferResult,
        boolean emptyFields) throws JsonProcessingException {
        given(clovaOcrProperties.general().url()).willReturn(baseUrl);
        given(openAiProperties.url()).willReturn(baseUrl);

        mockWebServer.enqueue(
            createJsonResponse(createOcrResponse(inferResult, emptyFields), 200));
        mockWebServer.enqueue(createJsonResponse(ocrResponse, 200));
        mockWebServer.enqueue(createJsonResponse(gptResponse, 200));

        postAnalyze();
        postAnalyze().statusCode(SuccessType.RECEIPT_ANALYSIS_SUCCESS.getHttpStatusCode().value());

        assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
    }

    private void assertErrorResponse(io.restassured.response.ValidatableResponse response,
        ErrorType errorType) {
        response
            .statusCode(errorType.getHttpStatusCode().value())
            .assertThat()
            .body("status", equalTo("failure"))
            .body("errorCode", equalTo(errorType.getErrorCode()))
            .body("data", equalTo(null));
    }

    private GeneralOcrResponse createOcrResponse(String inferResult, boolean emptyFields) {
        List<GeneralOcrResponse.ImageResult.Field> fields = emptyFields
            ? List.of()
            : ocrResponse.images().getFirst().fields();
        String message = "SUCCESS".equals(inferResult) ? "SUCCESS" : "TEST_ERROR_MESSAGE";
        return new GeneralOcrResponse("V2", "TEST_REQUEST_ID",
            List.of(new GeneralOcrResponse.ImageResult(inferResult, message, fields)));
    }

    @DisplayName("GPT 호출이 실패한 뒤 같은 이미지로 재요청하면, OCR은 다시 호출하지 않는다")
    @Test
    void receiptAnalysis_whenRetryAfterGptFailure_thenReuseCachedOcr()
        throws JsonProcessingException {
        given(clovaOcrProperties.general().url()).willReturn(baseUrl);
        given(openAiProperties.url()).willReturn(baseUrl);

        mockWebServer.enqueue(createJsonResponse(ocrResponse, 200));
        for (int i = 0; i < 3; i++) {
            mockWebServer.enqueue(createJsonResponse(gptResponse, 500));
        }
        mockWebServer.enqueue(createJsonResponse(gptResponse, 200));

        assertErrorResponse(postAnalyze(), ErrorType.EXTERNAL_API_GENERAL_ERROR);
        postAnalyze().statusCode(SuccessType.RECEIPT_ANALYSIS_SUCCESS.getHttpStatusCode().value());

        assertThat(mockWebServer.getRequestCount()).isEqualTo(5);
    }

    private io.restassured.response.ValidatableResponse postAnalyze() {
        return RestAssured
            .given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(image)
            .when()
            .post("/receipts/analyze")
            .then();
    }

    private MockResponse createJsonResponse(Object body, int status)
        throws JsonProcessingException {
        return new MockResponse()
            .setResponseCode(status)
            .setBody(objectMapper.writeValueAsString(body))
            .addHeader("Content-Type", "application/json");
    }

    private GeneralOcrResponse getGeneralOcrResponse() {
        return new GeneralOcrResponse(
            "V2",
            "c7f11ece-432f-4dda-bf86-630345845eb6",
            List.of(new GeneralOcrResponse.ImageResult(
                "SUCCESS",
                "SUCCESS",
                List.of(
                    new GeneralOcrResponse.ImageResult.Field("김밥천국", 0.9999f, true),
                    new GeneralOcrResponse.ImageResult.Field("2024-02-15 15:30:00", 0.9998f, true),
                    new GeneralOcrResponse.ImageResult.Field("참치김밥", 0.9995f, false),
                    new GeneralOcrResponse.ImageResult.Field("4500", 0.9990f, false),
                    new GeneralOcrResponse.ImageResult.Field("2", 0.9999f, false),
                    new GeneralOcrResponse.ImageResult.Field("9000", 0.9995f, true),
                    new GeneralOcrResponse.ImageResult.Field("라면", 0.9997f, false),
                    new GeneralOcrResponse.ImageResult.Field("4000", 0.9993f, false),
                    new GeneralOcrResponse.ImageResult.Field("1", 0.9999f, false),
                    new GeneralOcrResponse.ImageResult.Field("4000", 0.9994f, true)
                )
            ))
        );
    }

    private ChatGptResponse getChatGptResponse() throws JsonProcessingException {
        return new ChatGptResponse(
            List.of(new ChatGptResponse.Choice(
                0,
                new ChatGptResponse.ResponseMessage(
                    "assistant",
                    objectMapper.writeValueAsString(new ParsedReceiptResponse(
                        TEST_RECEIPT_TITLE,
                        TEST_PAYMENT_TIME,
                        List.of(
                            new ItemSummary(
                                TEST_ITEM_NAME_A,
                                TEST_ITEM_QUANTITY,
                                TEST_ITEM_PRICE
                            ),
                            new ItemSummary(
                                TEST_ITEM_NAME_B,
                                TEST_ITEM_QUANTITY,
                                TEST_ITEM_PRICE
                            )
                        )
                    ))
                )
            )),
            new ChatGptResponse.Usage(150, 100, 250)
        );
    }

    @AfterEach
    void terminate() throws IOException {
        mockWebServer.shutdown();
        RestAssured.reset();
    }

    @TestConfiguration
    static class TestPropertiesConfig {

        @Bean
        @Primary
        public ClovaOcrProperties clovaOcrProperties() {
            ClovaOcrProperties.GeneralOcr mockGeneral = mock(GeneralOcr.class);
            return new ClovaOcrProperties("TEST_KEY", mockGeneral);
        }

        @Bean
        @Primary
        public OpenAiProperties openAiProperties() {
            OpenAiProperties mockProperties = mock(OpenAiProperties.class);
            given(mockProperties.key()).willReturn("TEST_KEY");
            given(mockProperties.model()).willReturn("TEST_MODEL");
            given(mockProperties.authType()).willReturn("TEST_AUTH_TYPE");
            return mockProperties;
        }

    }

}
