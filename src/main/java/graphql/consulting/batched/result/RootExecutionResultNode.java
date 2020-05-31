package graphql.consulting.batched.result;

import graphql.Internal;
import graphql.execution.ExecutionPath;

import java.util.function.Consumer;

@Internal
public class RootExecutionResultNode extends ObjectExecutionResultNode {


    private RootExecutionResultNode(Builder builder) {
        super(builder, null);
    }

    public static Builder newRootExecutionResultNode() {
        return new Builder();
    }

    @Override
    public <T extends BuilderBase<T>> RootExecutionResultNode transform(Consumer<T> builderConsumer) {
        Builder builder = new Builder(this);
        builderConsumer.accept((T) builder);
        return builder.build();
    }

    public static class Builder extends BuilderBase<Builder> {

        public Builder() {

        }

        public Builder(RootExecutionResultNode existing) {
            super(existing);
        }

        @Override
        public RootExecutionResultNode build() {
            super.executionPath(ExecutionPath.rootPath());
            return new RootExecutionResultNode(this);
        }
    }
}
