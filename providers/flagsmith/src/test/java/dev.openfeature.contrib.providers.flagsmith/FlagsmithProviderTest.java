package dev.openfeature.contrib.providers.flagsmith;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfeature.contrib.providers.flagsmith.exceptions.InvalidCacheOptions;
import dev.openfeature.contrib.providers.flagsmith.exceptions.InvalidOptions;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FlagsmithProviderTest {

    public static MockWebServer mockFlagsmithClient;
    public static FlagsmithProvider flagsmithProvider;

    final QueueDispatcher dispatcher = new QueueDispatcher() {
        @SneakyThrows
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if (request.getPath().startsWith("/flags/")) {
                return new MockResponse()
                    .setBody(readMockResponse("valid_flags_response.json"))
                    .addHeader("Content-Type", "application/json");
            }
            if (request.getPath().startsWith("/identities/")) {
                return new MockResponse()
                    .setBody(readMockResponse("valid_identity_response.json"))
                    .addHeader("Content-Type", "application/json");
            }
            return new MockResponse().setResponseCode(404);
        }
    };

    private static Stream<Arguments> provideKeysForFlagResolution() {
        return Stream.of(
            Arguments.of("true_key", "getBooleanEvaluation", Boolean.class, "true"),
            Arguments.of("false_key", "getBooleanEvaluation", Boolean.class, "false"),
            Arguments.of("string_key", "getStringEvaluation", String.class, "string_value"),
            Arguments.of("int_key", "getIntegerEvaluation", Integer.class, "1"),
            Arguments.of("double_key", "getDoubleEvaluation", Double.class, "3.141"),
            Arguments.of("object_key", "getObjectEvaluation", Value.class, "{\"name\":\"json\"}")
        );
    }

    private static Stream<Arguments> provideDisabledKeysForFlagResolution() {
        return Stream.of(
            Arguments.of("true_key_disabled", "getBooleanEvaluation", Boolean.class, "false"),
            Arguments.of("false_key_disabled", "getBooleanEvaluation", Boolean.class, "true"),
            Arguments
                .of("string_key_disabled", "getStringEvaluation", String.class, "no_string_value"),
            Arguments.of("int_key_disabled", "getIntegerEvaluation", Integer.class, "2"),
            Arguments.of("double_key_disabled", "getDoubleEvaluation", Double.class, "1.47"),
            Arguments
                .of("object_key_disabled", "getObjectEvaluation", Value.class, "{\"name\":\"not_json\"}")
        );
    }

    private static Stream<Arguments> invalidOptions() {
        return Stream.of(
            null,
            Arguments.of(FlagsmithProviderOptions.builder().build()),
            Arguments.of(FlagsmithProviderOptions.builder().apiKey("").build())
        );
    }

    private static Stream<Arguments> invalidCacheOptions() {
        return Stream.of(
            Arguments
                .of(FlagsmithProviderOptions.builder().apiKey("API_KEY").expireCacheAfterAccess(1)
                                            .build()),
            Arguments
                .of(FlagsmithProviderOptions.builder().apiKey("API_KEY").maxCacheSize(1).build()),
            Arguments
                .of(FlagsmithProviderOptions.builder().apiKey("API_KEY").expireCacheAfterWrite(1)
                                            .build()),
            Arguments.of(FlagsmithProviderOptions.builder().apiKey("API_KEY").recordCacheStats(true)
                                                 .build())
        );
    }

    @BeforeEach
    void setUp() throws IOException {
        mockFlagsmithClient = new MockWebServer();
        mockFlagsmithClient.setDispatcher(this.dispatcher);
        mockFlagsmithClient.start();

        FlagsmithProviderOptions options = FlagsmithProviderOptions.builder()
                                                                   .apiKey("API_KEY")
                                                                   .baseUri(String
                                                                       .format("http://localhost:%s",
                                                                           mockFlagsmithClient
                                                                               .getPort()))
                                                                   .build();
        flagsmithProvider = new FlagsmithProvider(options);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockFlagsmithClient.shutdown();
    }

    @Test
    void shouldGetMetadataAndValidateName() {
        assertEquals("Flagsmith Provider", new FlagsmithProvider(FlagsmithProviderOptions.builder()
                                                                                         .apiKey("API_KEY")
                                                                                         .build())
            .getMetadata().getName());
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void shouldThrowAnExceptionWhenOptionsInvalid(FlagsmithProviderOptions options) {
        assertThrows(InvalidOptions.class, () -> new FlagsmithProvider(options));
    }

    @ParameterizedTest
    @MethodSource("invalidCacheOptions")
    void shouldThrowAnExceptionWhenCacheOptionsInvalid(FlagsmithProviderOptions options) {
        assertThrows(InvalidCacheOptions.class, () -> new FlagsmithProvider(options));
    }

    @SneakyThrows
    @Test
    void shouldInitializeProviderWhenAllOptionsSet() {
        HashMap<String, String> headers =
            new HashMap<String, String>() {{
                put("header", "string");
            }};
        FlagsmithProviderOptions options =
            FlagsmithProviderOptions.builder()
                                    .apiKey("ser.API_KEY")
                                    .baseUri("http://localhost.com")
                                    .headers(headers)
                                    .envFlagsCacheKey("CACHE_KEY")
                                    .expireCacheAfterWriteTimeUnit(TimeUnit.MINUTES)
                                    .expireCacheAfterWrite(1)
                                    .expireCacheAfterAccessTimeUnit(TimeUnit.MINUTES)
                                    .expireCacheAfterAccess(1)
                                    .maxCacheSize(1)
                                    .httpInterceptor(null)
                                    .recordCacheStats(true)
                                    .connectTimeout(1)
                                    .writeTimeout(1)
                                    .readTimeout(1)
                                    .retries(1)
                                    .localEvaluation(true)
                                    .environmentRefreshIntervalSeconds(1)
                                    .enableAnalytics(true)
                                    .build();
        assertDoesNotThrow(() -> new FlagsmithProvider(options));
    }

    @SneakyThrows
    @ParameterizedTest
    @MethodSource("provideKeysForFlagResolution")
    void shouldResolveFlagCorrectlyWithCorrectFlagType(
        String key, String methodName, Class<?> expectedType, String flagsmithResult) {
        // Given
        Object result = null;
        EvaluationContext evaluationContext = new MutableContext();

        // When
        Method method = flagsmithProvider.getClass()
                                         .getMethod(methodName, String.class, expectedType, EvaluationContext.class);
        result = method.invoke(flagsmithProvider, key, null, evaluationContext);

        // Then
        ProviderEvaluation<Object> evaluation = (ProviderEvaluation<Object>) result;
        String resultString = getResultString(evaluation.getValue(), expectedType);

        assertEquals(flagsmithResult, resultString);
        assertNull(evaluation.getErrorCode());
        assertNull(evaluation.getReason());
    }

    @SneakyThrows
    @ParameterizedTest
    @MethodSource("provideKeysForFlagResolution")
    void shouldResolveIdentityFlagCorrectlyWithCorrectFlagType(
        String key, String methodName, Class<?> expectedType, String flagsmithResult) {
        // Given
        Object result = null;
        EvaluationContext evaluationContext = new MutableContext();
        evaluationContext.setTargetingKey("my-identity");

        // When
        Method method = flagsmithProvider.getClass()
                                         .getMethod(methodName, String.class, expectedType, EvaluationContext.class);
        result = method.invoke(flagsmithProvider, key, null, evaluationContext);

        // Then
        ProviderEvaluation<Object> evaluation = (ProviderEvaluation<Object>) result;
        String resultString = getResultString(evaluation.getValue(), expectedType);

        assertEquals(flagsmithResult, resultString);
        assertNull(evaluation.getErrorCode());
        assertNull(evaluation.getReason());
    }

    @SneakyThrows
    @ParameterizedTest
    @MethodSource("provideDisabledKeysForFlagResolution")
    void shouldNotResolveFlagIfFlagIsInactiveInFlagsmithInsteadUsingDefaultValue(
        String key, String methodName, Class<?> expectedType, String defaultValueString) {
        // Given
        Object defaultValue;
        if (expectedType == String.class) {
            defaultValue = defaultValueString;
        } else if (expectedType == Value.class) {
            Map<String, Value> map = new ObjectMapper()
                .readValue(defaultValueString, HashMap.class);
            defaultValue = new Value(new MutableStructure(map));
        } else {
            Method castMethod = expectedType.getMethod("valueOf", String.class);
            defaultValue = castMethod.invoke(expectedType, defaultValueString);
        }

        Object result = null;
        EvaluationContext evaluationContext = new MutableContext();

        // When
        Method method = flagsmithProvider.getClass()
                                         .getMethod(methodName, String.class, expectedType, EvaluationContext.class);
        result = method.invoke(flagsmithProvider, key, defaultValue, evaluationContext);

        // Then
        ProviderEvaluation<Object> evaluation = (ProviderEvaluation<Object>) result;
        String resultString = getResultString(evaluation.getValue(), expectedType);

        assertEquals(defaultValueString, resultString);
        assertNull(evaluation.getErrorCode());
        assertEquals(Reason.DISABLED.name(), evaluation.getReason());
    }

    @SneakyThrows
    @Test
    void shouldNotResolveFlagIfExceptionThrownInFlagsmithInsteadUsingDefaultValue() {
        // Given
        String key = "missing_key";
        EvaluationContext evaluationContext = new MutableContext();

        // When
        ProviderEvaluation<Boolean> result = flagsmithProvider.getBooleanEvaluation(key, true, new MutableContext());

        // Then
        String resultString = getResultString(result.getValue(), Boolean.class);

        assertEquals("true", resultString);
        assertEquals(ErrorCode.FLAG_NOT_FOUND, result.getErrorCode());
        assertEquals(Reason.ERROR.name(), result.getReason());
    }

    private String readMockResponse(String filename) throws IOException {
        String file = getClass().getClassLoader().getResource("mock_responses/" + filename)
                                .getFile();
        byte[] bytes = Files.readAllBytes(Paths.get(file));
        return new String(bytes);
    }

    private String getResultString(Object responseValue, Class<?> expectedType)
        throws JsonProcessingException {
        String resultString = "";
        if (expectedType == Value.class) {
            Value value = (Value) responseValue;
            try {
                Map<String, Object> structure = value.asStructure().asObjectMap();
                return new ObjectMapper().writeValueAsString(structure);
            } catch (ClassCastException cce) {
                Map<String, Value> structure = value.asStructure().asMap();
                return new ObjectMapper().writeValueAsString(structure);
            }
        } else {
            return responseValue.toString();
        }
    }
}
