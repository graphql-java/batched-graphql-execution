package graphql.consulting.batched


import graphql.introspection.IntrospectionQuery
import graphql.nextgen.GraphQL
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import static graphql.ExecutionInput.newExecutionInput
import static graphql.consulting.batched.BatchedExecutionStrategy.NULL_VALUE
import static graphql.consulting.batched.TestUtil.schema
import static graphql.schema.FieldCoordinates.coordinates

class BatchedExecutionStrategyTest extends Specification {


    def "simple query"() {
        def schema = schema("""
        type Query {
            foo: Foo
        }
        type Foo {
            bar: String
        }
        """)


        def query = """
        {foo { bar } }
        """
        when:

        def fooData = [bar: "na"]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: fooData]
    }


    def "two level batching"() {
        def fooData = [[id: "fooId1"],
                       null,
                       [id: "fooId2"],
                       null]
        def bar1 = [id: "barId1"];
        def bar2 = [id: "barId2"];
        def bar3 = [id: "barId3"];
        def bar4 = [id: "barId4"];
        def bar5 = [id: "barId5"];

        def barName1 = "someBar1"
        def barName2 = "someBar2"
        def barName3 = "someBar3"
        def barName4 = "someBar4"
        def barName5 = "someBar5"

        def schema = schema("""
        type Query {
            foo: [Foo]
        }
        type Foo {
            id: ID
            bar: [Bar]
        }
        type Bar {
            id: ID
            name: String
        }
        """)


        def query = """
        {foo {
            id
            bar {
                id
                name
            }
        }}
        """

        when:

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)

        AtomicInteger barDFCount = new AtomicInteger()
        BatchedDataFetcher barDF = { env ->
            return Mono.fromSupplier({
                barDFCount.incrementAndGet();
                println "fetching bars with env sources: " + env.sources + " in thread " + Thread.currentThread()
                return new BatchedDataFetcherResult([[bar1, bar2], [bar3, bar4, bar5]]);
            });
        } as BatchedDataFetcher;

        TrivialDataFetcher barTrivialDF = { env ->
            if (env.source.id == "fooId1") {
                return [bar1, bar2];
            } else {
                return [bar3, bar4, bar5];
            }
        } as TrivialDataFetcher;

        AtomicInteger barNameDFCount = new AtomicInteger()
        BatchedDataFetcher barNameDF = { env ->
            return Mono.fromSupplier({
                barNameDFCount.incrementAndGet();
                println "fetching barNames with sources: " + env.sources + " in thread " + Thread.currentThread()
                return new BatchedDataFetcherResult([barName1, barName2, barName3, barName4, barName5])
            });
        } as BatchedDataFetcher;

        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Foo", "bar"), barDF)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Bar", "name"), barNameDF)


        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        barDFCount.get() == 1
        barNameDFCount.get() == 1
        result.getData() == [foo: [[id: "fooId1", bar: [[id: "barId1", name: "someBar1"], [id: "barId2", name: "someBar2"]]],
                                   null,
                                   [id: "fooId2", bar: [[id: "barId3", name: "someBar3"], [id: "barId4", name: "someBar4"], [id: "barId5", name: "someBar5"]]],
                                   null]]


    }

    def "batching with two dimensional lists"() {
        given:
        def schema = schema("""
        type Query {
            foo: [[Foo]]
        }
        type Foo {
            bar: [[Bar]]
        }
        type Bar {
            name: String!
        }
        """)

        def query = """
        {foo {
            bar {
                name
            }
        }}
        """
        def bar1 = [name: "bar1"]
        def bar2 = [name: "bar2"]
        def bar3 = [name: "bar3"]
        def bar4 = [name: "bar4"]
        def bar5 = [name: "bar5"]
        def bar6 = [name: "bar6"]
        def bar7 = [name: "bar7"]
        def bar8 = [name: "bar8"]
        def bar9 = [name: "bar9"]
        def bar10 = [name: "bar10"]
        def bar11 = [name: "bar11"]
        def bar12 = [name: "bar12"]
        def bar13 = [name: "bar13"]
        def bar14 = [name: "bar14"]
        def bar15 = [name: "bar15"]
        def bar16 = [name: "bar16"]
        def bar17 = [name: "bar17"]
        def bar18 = [name: "bar18"]
        def bar19 = [name: "bar19"]
        def bar20 = [name: "bar20"]
        def bar21 = [name: "bar21"]

        def foo1 = [bar: [[bar1, bar2, null], [null], [bar3], [bar4, bar5, null, null], null, [bar6, bar7, bar8]]]
        def foo2 = [bar: [[bar9], [bar10, bar11], null]]
        def foo3 = [bar: [[bar12, bar13, null], [null, null], null, [bar14, bar15, bar16], null, [bar17, null]]]
        def foo4 = [bar: [[bar18, bar19, bar20, null], null, [bar21, null]]]

        def fooData = [[foo1, null, null], [null], null, [foo2, foo3, foo4], null]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), { Mono.just(fooData) } as SingleDataFetcher)

        AtomicInteger invokedCounter = new AtomicInteger()
        AtomicInteger barsCount = new AtomicInteger()

        def nameDF = { env ->
            invokedCounter.getAndIncrement()
            def result = env.sources.collect({ it.name })
            barsCount.set(result.size())
            println "batched result: " + result
            Mono.just(new BatchedDataFetcherResult(result))
        } as BatchedDataFetcher;
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Bar", "name"), nameDF);

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        invokedCounter.get() == 1
        barsCount.get() == 21
        result.getData() == [foo: fooData]
        result.getErrors().size() == 0

    }

    def "two top level fields with aliases"() {
        given:
        def schema = schema("""
        type Query {
            foo: Foo!
        }
        type Foo {
            bar: Bar!
        }
        type Bar {
            name: String!
        }
        """)

        def query = """
        {foo1: foo {bar{name}}
            foo2: foo {bar{name}}
        }
        """
        def fooData1 = [bar: [name: "barName1"]]
        def fooData2 = [bar: [name: "barName1"]]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration();

        int counter = 1

        def fooDF = {
            if (counter == 1) {
                return Mono.just(fooData1)
            }
            return Mono.just(fooData2)
        } as SingleDataFetcher
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), fooDF)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo1: fooData1, foo2: fooData2]
        result.getErrors().size() == 0

    }

    def "same batched field at different normalized paths"() {
        given:
        def schema = schema("""
        type Query {
            issues: [Issue]
        }
        type Issue {
            authors: [User]     
            relatedTo: Issue
        }
        type User {
            name: String
        }
        """)

        def query = """
        { issues {
               authors {
                   name
               }
               relatedTo  {
                    authors {
                        name
                    }
               }
            }
        }
        """

        def issue1 = [authors: [[name: "author1"], [name: "author2"]], relatedTo: [authors: [[name: "author3"], [name: "author4"]]]]
        def issue2 = [authors: [[name: "author5"], [name: "author6"]], relatedTo: [authors: [[name: "author7"], [name: "author8"]]]]

        def issues = [issue1, issue2]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        AtomicInteger invokedCount = new AtomicInteger()
        List<List<String>> nameBatches = new CopyOnWriteArrayList<>();
        def nameDF = { env ->
            println "batched df with sources " + env.sources
            invokedCount.getAndIncrement();
            def names = env.sources.collect({ it.name })
            nameBatches.add(names)
            return Mono.just(new BatchedDataFetcherResult(names))
        } as BatchedDataFetcher

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "issues"), { issues } as TrivialDataFetcher)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("User", "name"), nameDF)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        invokedCount.get() == 2
        nameBatches == [["author1", "author2", "author5", "author6"], ["author3", "author4", "author7", "author8"]]
        result.getData() == [issues: issues]
        result.getErrors().size() == 0
    }

    def "same coordinates at different normalized paths but still batched"() {
        given:
        def schema = schema("""
        type Query {
            issues: [Issue]
        }
        type Issue {
            authors: [User]     
            relatedTo: Issue
        }
        type User {
            name: String
        }
        """)

        def query = """
        { issues {
               authors {
                   name
               }
               relatedTo  {
                    authors {
                        name
                    }
               }
            }
        }
        """

        def issue1 = [authors: [[name: "author1"], [name: "author2"]], relatedTo: [authors: [[name: "author3"], [name: "author4"]]]]
        def issue2 = [authors: [[name: "author5"], [name: "author6"]], relatedTo: [authors: [[name: "author7"], [name: "author8"]]]]

        def issues = [issue1, issue2]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        AtomicInteger invokedCount = new AtomicInteger()
        List<List<String>> nameBatches = new CopyOnWriteArrayList<>();
        def nameDF = { env ->
            println "batched df with sources " + env.sources
            invokedCount.getAndIncrement();
            def names = env.sources.collect({ it.name })
            nameBatches.add(names)
            return Mono.just(new BatchedDataFetcherResult(names))
        } as BatchedDataFetcher

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "issues"), { issues } as TrivialDataFetcher)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("User", "name"), nameDF, true)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        invokedCount.get() == 1
        nameBatches == [["author1", "author2", "author5", "author6", "author3", "author4", "author7", "author8"]]
        result.getData() == [issues: issues]
        result.getErrors().size() == 0
    }

    def "two top level fields returning null"() {
        given:
        def schema = schema("""
        type Query {
            foo: String
            bar: String
        }
        """)

        def query = """
        {  foo bar }
        """


        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()


        SingleDataFetcher fooDF = {
            Mono.fromCallable({
                println "wait in thread" + Thread.currentThread()
                Thread.sleep(10);
                return NULL_VALUE;
            })
        }
        SingleDataFetcher barDF = {
            Mono.fromCallable({
                println "wait in thread" + Thread.currentThread()
                Thread.sleep(200);
                return NULL_VALUE;
            })

        }

        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), fooDF)
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "bar"), barDF)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: null, bar: null]
        result.getErrors().size() == 0
    }

    def "deferred fetching on one level"() {
        given:
        def schema = schema("""
        type Query {
            foo: Foo
            bar: Bar
        }
        type Foo {
           subFoo: SubFoo 
        }
        type SubFoo {
            leafFoo: String
        } 
        type Bar {
            leafBar: String
        }
        """)

        def query = """
        {  foo {subFoo{leafFoo}} bar{leafBar} }
        """


        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()


        SingleDataFetcher fooDF = {
            Mono.fromCallable({
                return [subFoo: [leafFoo: "leafFoo"]]
            })
        }
        SingleDataFetcher barDF = {
            Mono.fromCallable({
                Thread.sleep(200);
                return [leafBar: "leafBar"]
            })

        }

        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "foo"), fooDF)
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Query", "bar"), barDF)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [foo: [subFoo: [leafFoo: "leafFoo"]], bar: [leafBar: "leafBar"]]
        result.getErrors().size() == 0
    }

    def "same coordinates at different normalized paths but still batched with one NF not fetched"() {
        given:
        def schema = schema("""
        type Query {
            issues: [Issue]
        }
        type Issue {
            authors: [User]     
            relatedTo: Issue
        }
        type User {
            name: String
        }
        """)

        def query = """
        { issues {
               authors {
                   name
               }
               relatedTo  {
                    authors {
                        name
                    }
               }
            }
        }
        """


        def issue1 = [authors: [[name: "author1"], [name: "author2"]], relatedTo: null]
        def issue2 = [authors: [[name: "author5"], [name: "author6"]], relatedTo: null]

        def issues = [issue1, issue2]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        AtomicInteger invokedCount = new AtomicInteger()
        List<List<String>> nameBatches = new CopyOnWriteArrayList<>();
        def nameDF = { env ->
            println "batched df names with sources " + env.sources
            invokedCount.getAndIncrement();
            def names = env.sources.collect({ it.name })
            nameBatches.add(names)
            return Mono.just(new BatchedDataFetcherResult(names))
        } as BatchedDataFetcher


        def relatedToDF = { env ->
            return Mono.fromCallable({
                println "RELATED TO WITH " + env.sources.size()
                println "sleeping"
                Thread.sleep(100)
                return new BatchedDataFetcherResult([null, null])
            })
        } as BatchedDataFetcher

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "issues"), { issues } as TrivialDataFetcher)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("User", "name"), nameDF, true)
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Issue", "relatedTo"), relatedToDF);

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        invokedCount.get() == 1
        nameBatches == [["author1", "author2", "author5", "author6"]]
        result.getData() == [issues: issues]
        result.getErrors().size() == 0
    }

    def "interface with two different implementations in list"() {
        given:
        def schema = schema("""
        type Query {
            pets: [Pet]
        }
        interface Pet {
            name: String
       }    
        type Dog implements Pet {
            name: String
        }
        type Cat implements Pet {
            name: String
        }
        """)

        def query = """
        { pets {__typename name }       
        }
        """


        def dog = [__typename: "Dog", name: "Oscar"]
        def cat = [__typename: "Cat", name: "Smokey"]

        def pets = [dog, cat]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "pets"), { pets } as TrivialDataFetcher)
        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [pets: pets]
        result.getErrors().size() == 0
    }

    def "interface with two different implementations in list but only one returned"() {
        given:
        def schema = schema("""
        type Query {
            pets: [Pet]
            cat: Cat
        }
        interface Pet {
            name: String
       }    
        type Dog implements Pet {
            name: String
        }
        type Cat implements Pet {
            name: String
            catProp: String
        }
        """)

        def query = """
        { pets {__typename name ...on Cat { catProp } }       
        cat {name}
        }
        """


        def dog = [__typename: "Dog", name: "Oscar"]
        def cat = [name: "Smokey"]

        def pets = [dog]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "pets"), { pets } as TrivialDataFetcher)
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "cat"), { cat } as TrivialDataFetcher)

        AtomicInteger called = new AtomicInteger();
        def bdf = { env ->
            called.incrementAndGet();
            Mono.just(new BatchedDataFetcherResult(env.sources.collect { it.name }))
        } as BatchedDataFetcher;

        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Cat", "name"), bdf, true)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        called.get() == 1
        result.getData() == [pets: pets, cat: cat]
        result.getErrors().size() == 0
    }

    def "interface with two different implementations but only one returned"() {
        given:
        def schema = schema("""
        type Query {
            pet: Pet
            cat: Cat
        }
        interface Pet {
            name: String
       }    
        type Dog implements Pet {
            name: String
        }
        type Cat implements Pet {
            name: String
            catProp: String
        }
        """)

        def query = """
        { pet {__typename name ...on Cat { catProp } }       
          cat { name }
        }
        """
        def dog = [__typename: "Dog", name: "Oscar"]
        def pet = dog

        def cat = [name: "Smokey"]

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "pet"), { pet } as TrivialDataFetcher)
        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "cat"), { cat } as TrivialDataFetcher)

        AtomicInteger called = new AtomicInteger();
        List<String> calledBatch = new CopyOnWriteArrayList<>();
        def bdf = { env ->
            called.incrementAndGet();
            def batch = env.sources.collect { it.name } as List<String>
            calledBatch.addAll(batch)
            Mono.just(new BatchedDataFetcherResult(batch))
        } as BatchedDataFetcher;

        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Cat", "name"), bdf, true)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [pet: pet, cat: cat]
        result.getErrors().size() == 0
        called.get() == 1
        calledBatch == ['Smokey']
    }

    def "batching of top level fields"() {
        given:
        def schema = schema("""
        type Query {
            echo: String
        }
        """)

        def query = """
        { echo echo1: echo echo2: echo  }
        """

        AtomicInteger called = new AtomicInteger()
        List<String> calledBatch = new CopyOnWriteArrayList<>()
        def bdf = { env ->
            called.incrementAndGet();
            calledBatch.addAll(env.sources)
            Mono.just(new BatchedDataFetcherResult(['hello1', 'hello2', 'hello3']))
        } as BatchedDataFetcher;

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()
        dataFetchingConfiguration.addBatchedDataFetcher(coordinates("Query", "echo"), bdf, true)

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        when:
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.getData() == [echo: 'hello1', echo1: 'hello2', echo2: 'hello3']
        called.get() == 1
        result.getErrors().size() == 0
        calledBatch == [null, null, null]

    }

    def "introspection query works"() {
        given:
        def schema = schema("""
        type Query {
            foo: Foo
        }
        type Foo {
            id: ID
        }
        """)

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def graphQL2 = graphql.GraphQL.newGraphQL(schema).build();
        when:
        def result = graphQL.execute(newExecutionInput(IntrospectionQuery.INTROSPECTION_QUERY))
        def result2 = graphQL2.execute(newExecutionInput(IntrospectionQuery.INTROSPECTION_QUERY))
        then:
        result.getErrors().size() == 0
        result.getData() == result2.getData()


    }

    def "two level not batched"() {
        def schema = schema("""
        type Query {
            foo: [Foo]
        }
        type Foo {
            id: ID
            bar: [Bar]
        }
        type Bar {
            id: ID
            name: String
        }
        """)


        def query = """
        {foo {
            id
            bar {
                id
                name
            }
        }}
        """

        def fooData = [[id: "fooId1"],
                       [id: "fooId2"]]

        def bar1 = [id: "barId1"]
        def bar2 = [id: "barId2"]
        def bar3 = [id: "barId3"]
        def bar4 = [id: "barId4"]
        def bar5 = [id: "barId5"]


        when:

        DataFetchingConfiguration dataFetchingConfiguration = new DataFetchingConfiguration()

        SingleDataFetcher barDF = { env ->
            return Mono.fromSupplier({
                println "CALLED WITH " + env.source
                if (env.source.id == "fooId1") {
                    return [bar1, bar2]
                }
                return [bar3, bar4, bar5]
            });
        } as SingleDataFetcher;

        SingleDataFetcher barNameDF = { env ->
            return Mono.fromSupplier({
                return env.source.id + "BAR-NAME"
            });
        } as SingleDataFetcher;

        dataFetchingConfiguration.addTrivialDataFetcher(coordinates("Query", "foo"), ({ fooData }) as TrivialDataFetcher)
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Foo", "bar"), barDF)
        dataFetchingConfiguration.addSingleDataFetcher(coordinates("Bar", "name"), barNameDF)


        def graphQL = GraphQL.newGraphQL(schema).executionStrategy(new BatchedExecutionStrategy(dataFetchingConfiguration)).build()
        def result = graphQL.execute(newExecutionInput(query))
        then:
        result.data == [foo: [[id: "fooId1", bar: [[id: "barId1", name: "barId1BAR-NAME"], [id: "barId2", name: "barId2BAR-NAME"]]],
                              [id: "fooId2", bar: [[id: "barId3", name: "barId3BAR-NAME"], [id: "barId4", name: "barId4BAR-NAME"], [id: "barId5", name: "barId5BAR-NAME"]]]]]

        result.extensions['batching-statistics'] == [
                "(Query.foo)/(Foo.bar)/(Bar.id)"  : 5,
                "(Query.foo)/(Foo.bar)/(Bar.name)": 5,
                "(Query.foo)/(Foo.bar)"           : 2,
                "(Query.foo)/(Foo.id)"            : 2,
                "(Query.foo)"                     : 1
        ]


    }

}
