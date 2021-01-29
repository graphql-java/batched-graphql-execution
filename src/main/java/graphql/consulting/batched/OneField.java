package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ResultPath;
import reactor.core.publisher.MonoProcessor;

public class OneField {
    public ResultPath resultPath;
    public NormalizedField normalizedField;
    public Object source;
    public MonoProcessor<Object> resultMono;

    public OneField(ResultPath resultPath, NormalizedField normalizedField, Object source) {
        this.resultPath = resultPath;
        this.normalizedField = normalizedField;
        this.source = source;
    }

    @Override
    public String toString() {
        return "OneField{" +
                "resultPath=" + resultPath +
                '}';
    }
}
