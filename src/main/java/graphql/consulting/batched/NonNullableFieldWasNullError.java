package graphql.consulting.batched;

import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorHelper;
import graphql.PublicApi;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.execution.ExecutionPath;
import graphql.language.SourceLocation;

import java.util.List;

/**
 * This is the base error that indicates that a non null field value was in fact null.
 */
@PublicApi
public class NonNullableFieldWasNullError extends RuntimeException implements GraphQLError {

    private final String message;
    private final List<Object> path;

    public NonNullableFieldWasNullError(NormalizedField normalizedField, ExecutionPath executionPath) {

        this.path = executionPath.toList();
        this.message = String.format("Cannot return null for non-nullable field: '%s.%s' (%s)",
                normalizedField.getObjectType().getName(), normalizedField.getFieldDefinition().getName(), path);
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public List<Object> getPath() {
        return path;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.NullValueInNonNullableField;
    }

    @Override
    public String toString() {
        return "NonNullableFieldWasNullError{" +
                "message='" + message + '\'' +
                ", path=" + path +
                '}';
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(Object o) {
        return GraphqlErrorHelper.equals(this, o);
    }

    @Override
    public int hashCode() {
        return GraphqlErrorHelper.hashCode(this);
    }
}
