package graphql.consulting.batched

import graphql.ErrorType
import graphql.nextgen.GraphQL
import spock.lang.Specification

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategyTestErrors extends Specification {


    def "TrivialDataFetcher throws Exception"() {
        def schema = schema("""
        type Query {
            foo: String
            bar: String
        }
        """)


        def query = """
        {foo bar}
        """
        when:


        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ throw new RuntimeException() }) as TrivialDataFetcher)
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "bar"), ({ "hello" }) as TrivialDataFetcher)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: null, bar: "hello"]
        result.errors.size() == 1
        result.errors[0].errorType == ErrorType.DataFetchingException
        result.errors[0].path == ['foo']
    }
}
