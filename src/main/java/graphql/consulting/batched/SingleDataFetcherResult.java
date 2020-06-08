package graphql.consulting.batched;

import graphql.GraphQLError;
import graphql.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static graphql.Assert.assertNotNull;
import static java.util.Collections.unmodifiableList;


@PublicApi
public class SingleDataFetcherResult<T> {

    private final T data;
    private final List<GraphQLError> errors;


    private SingleDataFetcherResult(Builder<T> builder) {
        this.data = builder.data;
        this.errors = unmodifiableList(assertNotNull(builder.errors));
    }

    /**
     * @return The data fetched. May be null.
     */
    public T getData() {
        return data;
    }

    /**
     * @return errors encountered when fetching data.  This will be non null but possibly empty.
     */
    public List<GraphQLError> getErrors() {
        return errors;
    }

    /**
     * @return true if there are any errors present
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * This helps you transform the current DataFetcherResult into another one by starting a builder with all
     * the current values and allows you to transform it how you want.
     *
     * @param builderConsumer the consumer code that will be given a builder to transform
     *
     * @return a new instance produced by calling {@code build} on that builder
     */
    public SingleDataFetcherResult<T> transform(Consumer<Builder<T>> builderConsumer) {
        Builder<T> builder = new Builder<>(this);
        builderConsumer.accept(builder);
        return builder.build();
    }

    /**
     * Creates a new data fetcher result builder
     *
     * @param <T> the type of the result
     *
     * @return a new builder
     */
    public static <T> Builder<T> newResult() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private T data;
        private final List<GraphQLError> errors = new ArrayList<>();

        public Builder(SingleDataFetcherResult<T> existing) {
            data = existing.getData();
            errors.addAll(existing.getErrors());
        }

        public Builder(T data) {
            this.data = data;
        }

        public Builder() {
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> errors(List<GraphQLError> errors) {
            this.errors.addAll(errors);
            return this;
        }

        public Builder<T> error(GraphQLError error) {
            this.errors.add(error);
            return this;
        }

        /**
         * @return true if there are any errors present
         */
        public boolean hasErrors() {
            return !errors.isEmpty();
        }


        public SingleDataFetcherResult<T> build() {
            return new SingleDataFetcherResult<>(this);
        }
    }
}
