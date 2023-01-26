import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "test", mixinStandardHelpOptions = true)
public class TestOtlpCommand implements Runnable {

    private final static List<String> noteworthyConfigurationPropertyNames = Arrays.asList(
            "otel.resource.attributes", "otel.service.name",
            "otel.traces.exporter", "otel.metrics.exporter", "otel.logs.exporter",
            "otel.exporter.otlp.endpoint", "otel.exporter.otlp.traces.endpoint", "otel.exporter.otlp.metrics.endpoint",
            "otel.exporter.jaeger.endpoint", "otel.exporter.prometheus.port");

    public static void prettyPrintNoteworthyConfigProperties(ConfigProperties config, PrintStream out) {
        Map<String, String> message = new LinkedHashMap<>();
        for (String attributeName : noteworthyConfigurationPropertyNames) {
            String attributeValue = config.getString(attributeName);
            if (attributeValue != null) {
                message.put(attributeName, attributeValue);
            }
        }
        message.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> out.println("\t" + entry.getKey() + ": " + entry.getValue()));
    }

    public static void prettyPrintResource(Resource resource, PrintStream out) {
        resource.getAttributes().forEach((key, value) -> out.println("\t" + key + ": " + Objects.toString(value, "#null#")));
    }

    public static void prettyPrintSpan(Span span, PrintStream out) {
        if (span instanceof ReadableSpan) {
            ReadableSpan readableSpan = (ReadableSpan) span;
            out.println("\tName: " + readableSpan.getName());
        }
        SpanContext spanContext = span.getSpanContext();
        out.println("\tSpanId: " + spanContext.getSpanId());
        out.println("\tTraceId: " + spanContext.getTraceId());
    }

    /**
     * grpc, http/protobuf, http/json
     */
    String otlpProtocol;

    String otlpHeaders;

    String otlpBasicAuthUsername;
    String otlpBasicAuthPassword;

    @Override
    public void run() {
        AutoConfiguredOpenTelemetrySdkBuilder sdkBuilder = AutoConfiguredOpenTelemetrySdk.builder();
        Map<String, String> properties = new HashMap<>();
        properties.put("otel.service.name", "otlp-test-cli");
        properties.put("otel.metrics.exporter", "otlp");
        properties.put("otel.logs.exporter", "otlp");

        if (Optional.of(otlpProtocol).map(protocol -> !protocol.trim().isEmpty()).orElse(Boolean.FALSE)) {
            properties.put("otel.exporter.otlp.protocol", otlpProtocol);
        }

        if (Optional.of(otlpBasicAuthUsername).map(username -> !username.trim().isEmpty()).orElse(Boolean.FALSE)) {
           String authorizationHeader = "Authorization=Basic " + Base64.getEncoder().encodeToString((otlpBasicAuthUsername + ":" + otlpBasicAuthPassword).getBytes(StandardCharsets.UTF_8));
        }
        AutoConfiguredOpenTelemetrySdk autoConfiguredOpenTelemetrySdk = sdkBuilder
                .addPropertiesSupplier(() -> properties)
                .build();

        System.out.println("## OpenTelemetry SDK noteworthy configuration");
        prettyPrintNoteworthyConfigProperties(autoConfiguredOpenTelemetrySdk.getConfig(), System.out);
        System.out.println("## OpenTelemetry Resource");
        prettyPrintResource(autoConfiguredOpenTelemetrySdk.getResource(), System.out);

        OpenTelemetrySdk openTelemetrySdk = autoConfiguredOpenTelemetrySdk.getOpenTelemetrySdk();

        // TRACE
        System.out.println("# Span");
        SdkTracerProvider sdkTracerProvider = openTelemetrySdk.getSdkTracerProvider();
        Tracer tracer = sdkTracerProvider.get("io.opentelemetry.contrib.otlptestcli");
        Span span = tracer.spanBuilder("test span").startSpan();
        try (Scope scope = span.makeCurrent()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } finally {
            span.end();
        }
        prettyPrintSpan(span, System.out);
        System.out.println("## Export span");
        System.out.flush();
        CompletableResultCode result = sdkTracerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS);

        // METRIC
        System.out.println("# Metric - Counter");
        SdkMeterProvider sdkMeterProvider = openTelemetrySdk.getSdkMeterProvider();
        MeterBuilder meterBuilder = sdkMeterProvider.meterBuilder("io.opentelemetry.contrib.otlptestcli");
        Meter meter = meterBuilder.build();
        LongCounter longCounter = meter.counterBuilder("otlptestcli.testcounter").build();
        longCounter.add(1);

        System.out.println("## Export metric");
        System.out.flush();
        sdkMeterProvider.forceFlush().join(500, TimeUnit.MILLISECONDS);

        // LOG
        SdkLoggerProvider sdkLoggerProvider = openTelemetrySdk.getSdkLoggerProvider();
        Logger logger = sdkLoggerProvider.get("io.opentelemetry.contrib.otlptestcli");
        LogRecordBuilder logRecordBuilder = logger.logRecordBuilder();
        logRecordBuilder
                .setSeverity(Severity.WARN)
                .setEpoch(Instant.now())
                .setBody("Lorem ipsum dolor sit amet, consectetur adipiscing elit.")
                .emit();

        System.out.println("## Export log entry");
        System.out.flush();
        sdkLoggerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS);

    }

    public static void main(String[] args) {
        new TestOtlpCommand().run();
    }
}
