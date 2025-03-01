package graphql.consulting.batched

import graphql.TypeResolutionEnvironment
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.DataFetcher
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.PropertyDataFetcher
import graphql.schema.TypeResolver
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.ScalarWiringEnvironment
import graphql.schema.idl.UnionWiringEnvironment
import graphql.schema.idl.WiringFactory

class MockedWiringFactory implements WiringFactory {

    @Override
    boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
        return true
    }

    @Override
    TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
        new TypeResolver() {
            @Override
            GraphQLObjectType getType(TypeResolutionEnvironment env) {
                if (env.object instanceof Map) {
                    def typename = env.object.__typename
                    if (typename != null) {
                        return env.schema.getObjectType(typename);
                    }
                }
                throw new UnsupportedOperationException("Not implemented")
            }
        }
    }

    @Override
    boolean providesTypeResolver(UnionWiringEnvironment environment) {
        return true
    }

    @Override
    TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
        new TypeResolver() {
            @Override
            GraphQLObjectType getType(TypeResolutionEnvironment env) {
                if (env.object instanceof Map) {
                    def typename = env.object.__typename
                    if (typename != null) {
                        return env.schema.getObjectType(typename);
                    }
                }
                throw new UnsupportedOperationException("Not implemented")
            }
        }
    }

    @Override
    boolean providesDataFetcher(FieldWiringEnvironment environment) {
        return true
    }

    @Override
    DataFetcher getDataFetcher(FieldWiringEnvironment environment) {
        return new PropertyDataFetcher(environment.getFieldDefinition().getName())
    }

    @Override
    boolean providesScalar(ScalarWiringEnvironment environment) {
        if (ScalarInfo.isGraphqlSpecifiedScalar(environment.getScalarTypeDefinition().getName())) {
            return false
        }
        return true
    }

    GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
        return GraphQLScalarType.newScalar().name(environment.getScalarTypeDefinition().getName()).coercing(new Coercing() {
            @Override
            Object serialize(Object dataFetcherResult) throws CoercingSerializeException {
                throw new UnsupportedOperationException("Not implemented");
            }

            @Override
            Object parseValue(Object input) throws CoercingParseValueException {
                throw new UnsupportedOperationException("Not implemented");
            }

            @Override
            Object parseLiteral(Object input) throws CoercingParseLiteralException {
                throw new UnsupportedOperationException("Not implemented");
            }
        }).build()
    }
}
