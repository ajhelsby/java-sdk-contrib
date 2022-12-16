package dev.openfeature.contrib.providers.flagsmith;

import com.flagsmith.FlagsmithClient;
import com.flagsmith.exceptions.FlagsmithApiError;
import com.flagsmith.exceptions.FlagsmithClientError;
import com.flagsmith.models.Flags;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.OpenFeatureError;
import dev.openfeature.sdk.exceptions.TypeMismatchError;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * FlagsmithProvider is the JAVA provider implementation for the feature flag solution Flagsmith.
 */
class FlagsmithProvider implements FeatureProvider {

    private static final String NAME = "Flagsmith Provider";
    private static FlagsmithClient flagsmith;

    public FlagsmithProvider(FlagsmithProviderOptions options) {
        FlagsmithClientConfigurer.validateOptions(options);
        flagsmith = FlagsmithClientConfigurer.initializeProvider(options);
    }

    @Override
    public Metadata getMetadata() {
        return () -> NAME;
    }

    @Override
    public List<Hook> getProviderHooks() {
        return FeatureProvider.super.getProviderHooks();
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
        String key, Boolean defaultValue, EvaluationContext evaluationContext) {
        return resolveFlagsmithEvaluation(key, defaultValue, evaluationContext, Boolean.class);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
        String key, String defaultValue, EvaluationContext evaluationContext) {
        return resolveFlagsmithEvaluation(key, defaultValue, evaluationContext, String.class);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
        String key, Integer defaultValue, EvaluationContext evaluationContext) {
        return resolveFlagsmithEvaluation(key, defaultValue, evaluationContext, Integer.class);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
        String key, Double defaultValue, EvaluationContext evaluationContext) {
        return resolveFlagsmithEvaluation(key, defaultValue, evaluationContext, Double.class);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
        String key, Value defaultValue, EvaluationContext evaluationContext) {
        return resolveFlagsmithEvaluation(key, defaultValue, evaluationContext, Object.class);
    }

    /**
     * Using the Flagsmith SDK this method resolves any type of flag into
     * a ProviderEvaluation. Since Flagsmith's sdk is agnostic of type
     * the flag needs to be cast to the correct type for OpenFeature's
     * ProviderEvaluation object.
     *
     * @param key          the string identifier for the flag being resolved
     * @param defaultValue the backup value if the flag can't be resolved
     * @param ctx          an EvaluationContext object with flag evaluation options
     * @param expectedType the expected data type of the flag as a class
     * @param <T>          the data type of the flag
     * @return a ProviderEvaluation object for the given flag type
     * @throws OpenFeatureError
     */
    private <T> ProviderEvaluation<T> resolveFlagsmithEvaluation(
        String key, T defaultValue, EvaluationContext ctx, Class<?> expectedType
    ) throws OpenFeatureError {
        T flagValue = null;
        ErrorCode errorCode = null;
        Reason reason = null;
        String variationType = "";
        try {

            Flags flags = Objects.isNull(ctx.getTargetingKey()) || ctx.getTargetingKey().isEmpty()
                ? flagsmith.getEnvironmentFlags()
                : flagsmith.getIdentityFlags(ctx.getTargetingKey());
            // Check if the flag is enabled, return default value if not
            if (!flags.isFeatureEnabled(key)) {
                return ProviderEvaluation.<T>builder()
                                         .value(defaultValue)
                                         .reason(Reason.DISABLED.name())
                                         .build();
            }

            Object value = flags.getFeatureValue(key);
            // Convert the value received from Flagsmith.
            flagValue = convertValue(value, expectedType);

            if (flagValue.getClass() != expectedType) {
                throw new TypeMismatchError("Flag value " + key + " had unexpected type "
                    + flagValue.getClass() + ", expected " + expectedType + ".");
            }

        } catch (FlagsmithApiError flagsmithApiError) {
            flagValue = defaultValue;
            reason = Reason.ERROR;
            errorCode = ErrorCode.PARSE_ERROR;
        } catch (FlagsmithClientError flagsmithClientError) {
            flagValue = defaultValue;
            reason = Reason.ERROR;
            errorCode = ErrorCode.GENERAL;
        }

        return buildEvaluation(flagValue, errorCode, reason, variationType);
    }

    /**
     * Build a ProviderEvaluation object from the results provided by the
     * Flagsmith sdk.
     *
     * @param flagValue     the resolved flag either retrieved or set to the default
     * @param errorCode     error type for failed flag resolution, null if no issue
     * @param reason        description of issue resolving flag, null if no issue
     * @param variationType contains the name of the variation used for this flag
     * @param <T>           the data type of the flag
     * @return a ProviderEvaluation object for the given flag type
     */
    private <T> ProviderEvaluation<T> buildEvaluation(
        T flagValue, ErrorCode errorCode, Reason reason, String variationType) {
        ProviderEvaluation.ProviderEvaluationBuilder providerEvaluationBuilder =
            ProviderEvaluation.<T>builder()
                              .value(flagValue);

        if (errorCode != null) {
            providerEvaluationBuilder.errorCode(errorCode);
        }
        if (reason != null) {
            providerEvaluationBuilder.reason(reason.name());
        }
        if (variationType != null) {
            providerEvaluationBuilder.variant(variationType);
        }

        return providerEvaluationBuilder.build();
    }

    /**
     * The method convertValue is converting the object return by the Flagsmith client.
     *
     * @param value        the value we have received
     * @param expectedType the type we expect for this value
     * @param <T>          the type we want to convert to
     * @return A converted object
     */
    private <T> T convertValue(Object value, Class<?> expectedType) {
        boolean isPrimitive = expectedType == Boolean.class
            || expectedType == String.class
            || expectedType == Integer.class
            || expectedType == Double.class;

        if (isPrimitive) {
            return (T) value;
        }
        return (T) objectToValue(value);
    }

    /**
     * The method objectToValue is wrapping an object into a Value.
     *
     * @param object the object you want to wrap
     * @return the wrapped object
     */
    private Value objectToValue(Object object) {
        if (object instanceof Value) {
            return (Value) object;
        } else if (object == null) {
            return null;
        } else if (object instanceof String) {
            return new Value((String) object);
        } else if (object instanceof Boolean) {
            return new Value((Boolean) object);
        } else if (object instanceof Integer) {
            return new Value((Integer) object);
        } else if (object instanceof Double) {
            return new Value((Double) object);
        } else if (object instanceof Structure) {
            return new Value((Structure) object);
        } else if (object instanceof List) {
            // need to translate each elem in list to a value
            return new Value(((List<Object>) object).stream()
                                                    .map(this::objectToValue)
                                                    .collect(Collectors.toList()));
        } else if (object instanceof Instant) {
            return new Value((Instant) object);
        } else if (object instanceof Map) {
            return new Value(mapToStructure((Map<String, Object>) object));
        } else {
            throw new TypeMismatchError("Flag value " + object + " had unexpected type "
                + object.getClass() + ".");
        }
    }

    /**
     * The method mapToStructure transform a map coming from a JSON Object to a Structure type.
     *
     * @param map a JSON object return by the SDK
     * @return a Structure object in the SDK format
     */
    private Structure mapToStructure(Map<String, Object> map) {
        return new MutableStructure(
            map.entrySet().stream()
               .filter(e -> e.getValue() != null)
               .collect(Collectors.toMap(Map.Entry::getKey, e -> objectToValue(e.getValue()))));
    }
}