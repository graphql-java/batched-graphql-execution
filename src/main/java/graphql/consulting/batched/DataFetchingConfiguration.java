package graphql.consulting.batched;

import graphql.schema.FieldCoordinates;

import java.util.LinkedHashMap;
import java.util.Map;

public class DataFetchingConfiguration {

    private Map<FieldCoordinates, BatchedDataFetcher> batchedDataFetcherMap = new LinkedHashMap<>();
    private Map<FieldCoordinates, TrivialDataFetcher> trivialDataFetcherMap = new LinkedHashMap<>();

    public void addTrivialDataFetcher(FieldCoordinates fieldCoordinates, TrivialDataFetcher trivialDataFetcher) {
        trivialDataFetcherMap.put(fieldCoordinates, trivialDataFetcher);
    }

    public void addBatchedDataFetcher(FieldCoordinates fieldCoordinates, BatchedDataFetcher batchedDataFetcher) {
        batchedDataFetcherMap.put(fieldCoordinates, batchedDataFetcher);
    }


    public boolean isFieldBatched(FieldCoordinates fieldCoordinates) {
        return batchedDataFetcherMap.containsKey(fieldCoordinates);
    }

    public BatchedDataFetcher getBatchedDataFetcher(FieldCoordinates fieldCoordinates) {
        return batchedDataFetcherMap.get(fieldCoordinates);
    }

    public TrivialDataFetcher getTrivialDataFetcher(FieldCoordinates fieldCoordinates) {
        if (trivialDataFetcherMap.containsKey(fieldCoordinates)) {
            return trivialDataFetcherMap.get(fieldCoordinates);
        }
        return new DefaultTrivialDataFetcher();
    }
}
