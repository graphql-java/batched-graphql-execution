package graphql.consulting.batched;

import graphql.schema.FieldCoordinates;

import java.util.LinkedHashMap;
import java.util.Map;

public class DataFetchingConfiguration {

    private Map<FieldCoordinates, BatchedDataFetcher> batchedDataFetcherMap = new LinkedHashMap<>();
    private Map<FieldCoordinates, TrivialDataFetcher> trivialDataFetcherMap = new LinkedHashMap<>();
    private Map<FieldCoordinates, SingleDataFetcher> singleDataFetcherMap = new LinkedHashMap<>();

    private Map<FieldCoordinates, Boolean> batchOnCoordinates = new LinkedHashMap<>();

    public void addTrivialDataFetcher(FieldCoordinates fieldCoordinates, TrivialDataFetcher trivialDataFetcher) {
        trivialDataFetcherMap.put(fieldCoordinates, trivialDataFetcher);
    }

    public void addBatchedDataFetcher(FieldCoordinates fieldCoordinates, BatchedDataFetcher batchedDataFetcher) {
        addBatchedDataFetcher(fieldCoordinates, batchedDataFetcher, false);
    }

    public void addBatchedDataFetcher(FieldCoordinates fieldCoordinates, BatchedDataFetcher batchedDataFetcher, boolean acrossCoordinates) {
        batchedDataFetcherMap.put(fieldCoordinates, batchedDataFetcher);
        batchOnCoordinates.put(fieldCoordinates, acrossCoordinates);
    }

    public boolean isBatchedOnCoordinates(FieldCoordinates coordinates) {
        return batchOnCoordinates.get(coordinates);
    }

    public void addSingleDataFetcher(FieldCoordinates fieldCoordinates, SingleDataFetcher singleDataFetcher) {
        singleDataFetcherMap.put(fieldCoordinates, singleDataFetcher);
    }

    public boolean isSingleFetch(FieldCoordinates fieldCoordinates) {
        return singleDataFetcherMap.containsKey(fieldCoordinates);
    }

    public boolean isFieldBatched(FieldCoordinates fieldCoordinates) {
        return batchedDataFetcherMap.containsKey(fieldCoordinates);
    }

    public BatchedDataFetcher getBatchedDataFetcher(FieldCoordinates fieldCoordinates) {
        return batchedDataFetcherMap.get(fieldCoordinates);
    }

    public SingleDataFetcher<?> getSingleDataFetcher(FieldCoordinates fieldCoordinates) {
        return singleDataFetcherMap.get(fieldCoordinates);
    }

    public TrivialDataFetcher getTrivialDataFetcher(FieldCoordinates fieldCoordinates) {
        if (trivialDataFetcherMap.containsKey(fieldCoordinates)) {
            return trivialDataFetcherMap.get(fieldCoordinates);
        }
        return new DefaultTrivialDataFetcher();
    }
}
