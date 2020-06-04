package graphql.consulting.batched;

import java.util.Map;

public class DefaultTrivialDataFetcher implements TrivialDataFetcher {

    public Object get(TrivialDataFetcherEnvironment environment) {
        Object source = environment.getSource();
        if (source instanceof Map) {
            return ((Map) source).get(environment.getNormalizedField().getResultKey());
        }
        return null;
    }
}
