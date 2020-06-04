package graphql.consulting.batched;

import java.util.List;

public class BatchedDataFetcherResult {

    private final List<Object> values;

    public BatchedDataFetcherResult(List<Object> values) {
        this.values = values;
    }

    public List<Object> getValues() {
        return values;
    }
}
