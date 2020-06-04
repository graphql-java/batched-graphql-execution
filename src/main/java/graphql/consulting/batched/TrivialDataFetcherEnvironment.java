package graphql.consulting.batched;

import graphql.consulting.batched.normalized.NormalizedField;

public class TrivialDataFetcherEnvironment {

    private NormalizedField normalizedField;
    private Object source;

    public TrivialDataFetcherEnvironment(NormalizedField normalizedField, Object source) {
        this.normalizedField = normalizedField;
        this.source = source;
    }

    public NormalizedField getNormalizedField() {
        return normalizedField;
    }

    public Object getSource() {
        return source;
    }
}
