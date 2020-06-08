package graphql.consulting.batched;

import graphql.Internal;
import graphql.TypeResolutionEnvironment;
import graphql.execution.ExecutionContext;
import graphql.execution.MergedField;
import graphql.execution.TypeResolutionParameters;
import graphql.execution.UnresolvedTypeException;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import reactor.core.publisher.Mono;

import java.util.Map;

@Internal
public class ResolveType {


    public Mono<GraphQLObjectType> resolveType(ExecutionContext executionContext,
                                               MergedField field,
                                               Object source,
                                               Map<String, Object> arguments,
                                               GraphQLType fieldType) {
        Mono<GraphQLObjectType> resolvedType;
        if (fieldType instanceof GraphQLInterfaceType) {
            TypeResolutionParameters resolutionParams = TypeResolutionParameters.newParameters()
                    .graphQLInterfaceType((GraphQLInterfaceType) fieldType)
                    .field(field)
                    .value(source)
                    .argumentValues(arguments)
                    .context(executionContext.getContext())
                    .schema(executionContext.getGraphQLSchema()).build();
            resolvedType = resolveTypeForInterface(resolutionParams);

        } else if (fieldType instanceof GraphQLUnionType) {
            TypeResolutionParameters resolutionParams = TypeResolutionParameters.newParameters()
                    .graphQLUnionType((GraphQLUnionType) fieldType)
                    .field(field)
                    .value(source)
                    .argumentValues(arguments)
                    .context(executionContext.getContext())
                    .schema(executionContext.getGraphQLSchema()).build();
            resolvedType = resolveTypeForUnion(resolutionParams);
        } else {
            resolvedType = Mono.just((GraphQLObjectType) fieldType);
        }

        return resolvedType;
    }

    public Mono<GraphQLObjectType> resolveTypeForInterface(TypeResolutionParameters params) {
        TypeResolutionEnvironment env = new TypeResolutionEnvironment(params.getValue(), params.getArgumentValues(), params.getField(), params.getGraphQLInterfaceType(), params.getSchema(), params.getContext());
        GraphQLInterfaceType abstractType = params.getGraphQLInterfaceType();
        TypeResolver typeResolver = params.getSchema().getCodeRegistry().getTypeResolver(abstractType);
        GraphQLObjectType result = typeResolver.getType(env);
        if (result == null) {
            return Mono.error(new UnresolvedTypeException(abstractType));
        }
        if (!params.getSchema().isPossibleType(abstractType, result)) {
            return Mono.error(new UnresolvedTypeException(abstractType, result));
        }

        return Mono.just(result);
    }

    public Mono<GraphQLObjectType> resolveTypeForUnion(TypeResolutionParameters params) {
        TypeResolutionEnvironment env = new TypeResolutionEnvironment(params.getValue(), params.getArgumentValues(), params.getField(), params.getGraphQLUnionType(), params.getSchema(), params.getContext());
        GraphQLUnionType abstractType = params.getGraphQLUnionType();
        TypeResolver typeResolver = params.getSchema().getCodeRegistry().getTypeResolver(abstractType);
        GraphQLObjectType result = typeResolver.getType(env);
        if (result == null) {
            return Mono.error(new UnresolvedTypeException(abstractType));
        }

        if (!params.getSchema().isPossibleType(abstractType, result)) {
            return Mono.error(new UnresolvedTypeException(abstractType, result));
        }

        return Mono.just(result);
    }

}
