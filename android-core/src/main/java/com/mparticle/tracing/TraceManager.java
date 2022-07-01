package com.mparticle.tracing;

import androidx.annotation.Nullable;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.extension.aws.AwsXrayPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class TraceManager {
    public static final String TraceTriggerHeader = "X-MP-Trace";
    public static final String TraceResponseHeader = "X-MP-Trace-Id";
    public static final String TraceParentHeader = "TraceParent";

    private Boolean Initialized = false;
    private OpenTelemetrySdk OpenTelemetry = null;


    public void InitializeTracing(String otelCollectorHost) {
        if (Initialized) return;

        SpanExporter zipkinSpanExporter = ZipkinSpanExporter
                .builder()
                .setEndpoint(otelCollectorHost)
                .build();

        OpenTelemetry = OpenTelemetrySdk.builder()
                .setPropagators(
                        ContextPropagators.create(
                                TextMapPropagator.composite(
                                        W3CTraceContextPropagator.getInstance(), AwsXrayPropagator.getInstance()
                                )
                        )
                ).setTracerProvider(
                        SdkTracerProvider.builder()
                                .addSpanProcessor(
                                        BatchSpanProcessor.builder(zipkinSpanExporter).build()
                                ).setIdGenerator(AwsXrayIdGenerator.getInstance())
                                .build())
                .buildAndRegisterGlobal();

        this.Initialized = true;
    }

    public Tracer GetTracer() {
        return this.OpenTelemetry.getTracer("MP-Android-SDK");
    }

    public Span StartSpan(String segmentName) {
        Tracer tracer = GetTracer();
        return tracer.spanBuilder(String.valueOf(SpanKind.INTERNAL))
                .startSpan()
                .setAttribute("service.name", "MP-Android-SDK");
    }

    public static Span GetCurrentSpan() {
        return Span.current();
    }

    @Nullable
    public static String GetCurrentSpanId() {
        Span span = GetCurrentSpan();
        if (span != null) {
            return span.getSpanContext().getSpanId();
        }
        return null;
    }

    @Nullable
    public static String GetCurrentTraceId() {
        Span span = GetCurrentSpan();
        if (span != null) {
            return span.getSpanContext().getTraceId();
        }
        return null;
    }
}
