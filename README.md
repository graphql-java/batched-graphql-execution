# Batched GraphQL execution

## Note: This is not an open source project. It is not licensed under an open source compatible license at this moment. 


This is a custom execution engine for [GraphQL Java](https://github.com/graphql-java/graphql-java/) which is optimized for
batching to avoid the N+1 problem.

This is an alternative approach to using [batched data loading](https://www.graphql-java.com/documentation/v16/batching/)

It is fully reactive using [Reactor](https://projectreactor.io/).


## Overview

The N+1 problem occurs when one Object can lead to N queries per child. For example:

```graphql
type Query {
    people: [Person]
}
type Person {
    id: ID
    friends: [Person]
}
```
A query like this `{people{friends{name}}` needs to load the friends for each person. If we have 5 people and each 
person has 5 friends we need to load 25 persons overall. 

Instead of making 25 calls (to a DB or another service) we would like to make 1 call which loads all 25 people at once.

In order to make this as easy as possible this engine provides a `BatchedDataFetcher`:

```java
public interface BatchedDataFetcher {
    Mono<BatchedDataFetcherResult> get(BatchedDataFetcherEnvironment environment);
}
```
A `BatchedDataFetcherEnvironment` contains the list of fields and sources which should be loaded:

```java
    List<Object> sources;
    List<NormalizedField> normalizedFields;
    List<ExecutionPath> executionPaths;
```








