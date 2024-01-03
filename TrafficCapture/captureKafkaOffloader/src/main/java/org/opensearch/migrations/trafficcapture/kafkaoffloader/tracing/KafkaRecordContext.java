package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import lombok.Getter;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public class KafkaRecordContext extends DirectNestedSpanContext<IConnectionContext>
        implements IScopedInstrumentationAttributes {
    static final AttributeKey<String> TOPIC_ATTR = AttributeKey.stringKey("topic");
    static final AttributeKey<String> RECORD_ID_ATTR = AttributeKey.stringKey("recordId");
    static final AttributeKey<Long> RECORD_SIZE_ATTR = AttributeKey.longKey("recordSize");

    @Getter
    public final String topic;
    @Getter
    public final String recordId;
    @Getter
    public final int recordSize;

    public KafkaRecordContext(IConnectionContext enclosingScope, String topic, String recordId, int recordSize) {
        super(enclosingScope);
        this.topic = topic;
        this.recordId = recordId;
        this.recordSize = recordSize;
        initializeSpan();
    }

    @Override public String getScopeName() { return "KafkaCapture"; }

    @Override
    public String getActivityName() { return "stream_flush_called"; }

    @Override
    public AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(TOPIC_ATTR, getTopic())
                .put(RECORD_ID_ATTR, getRecordId())
                .put(RECORD_SIZE_ATTR, getRecordSize());
    }
}
