package graphql.consulting.batched;

import graphql.ExecutionResult;
import graphql.Scalars;
import graphql.SerializationError;
import graphql.TypeMismatchError;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.consulting.batched.normalized.NormalizedQueryFactory;
import graphql.consulting.batched.normalized.NormalizedQueryFromAst;
import graphql.consulting.batched.result.ExecutionResultNode;
import graphql.consulting.batched.result.LeafExecutionResultNode;
import graphql.consulting.batched.result.ListExecutionResultNode;
import graphql.consulting.batched.result.NonNullableFieldWasNullError;
import graphql.consulting.batched.result.ResultNodesUtil;
import graphql.consulting.batched.result.RootExecutionResultNode;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionPath;
import graphql.execution.nextgen.ExecutionStrategy;
import graphql.execution.nextgen.FieldSubSelection;
import graphql.schema.CoercingSerializeException;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static graphql.consulting.batched.result.LeafExecutionResultNode.newLeafExecutionResultNode;
import static graphql.consulting.batched.result.ObjectExecutionResultNode.newObjectExecutionResultNode;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLTypeUtil.isList;

public class BatchedExecutionStrategy implements ExecutionStrategy {

    Scheduler fetchingScheduler = Schedulers.newParallel("data-fetching-scheduler");
    Scheduler processingScheduler = Schedulers.newSingle("processing-thread");

    private static final Logger log = LoggerFactory.getLogger(BatchedExecutionStrategy.class);

    private final Map<FieldCoordinates, Function<Object, Mono<Object>>> dataFetchers;

    public BatchedExecutionStrategy(Map<FieldCoordinates, Function<Object, Mono<Object>>> dataFetchers) {
        this.dataFetchers = dataFetchers;
    }

    private static class OneField {
        ExecutionPath executionPath;
        NormalizedField normalizedField;
        Object source;
        MonoProcessor<Object> resultMono;
//        Flux<Object> listener;

        public OneField(ExecutionPath executionPath, NormalizedField normalizedField, Object source) {
            this.executionPath = executionPath;
            this.normalizedField = normalizedField;
            this.source = source;
        }
    }

    private static class Tracker {
        private Deque<OneField> fieldsToFetch = new LinkedList<>();

        public Mono<Object> addFieldToFetch(ExecutionPath executionPath, NormalizedField normalizedField, Object source) {
            OneField oneField = new OneField(executionPath, normalizedField, source);
            fieldsToFetch.add(oneField);
            oneField.resultMono = MonoProcessor.create();
//            oneField.listener = oneField.resultMono.flux().publish().autoConnect(1);
//            System.out.println("added field to fetch. now size: " + fieldsToFetch.size());
//            return Mono.from(oneField.listener);
            return oneField.resultMono.doOnSubscribe(subscription -> {
                System.out.println("YYYYYYYY: Subscribed to resultMono for field " + executionPath);
            });
        }
    }

    @Override
    public CompletableFuture<graphql.execution.nextgen.result.RootExecutionResultNode> execute(
            ExecutionContext executionContext,
            FieldSubSelection fieldSubSelection) {

        NormalizedQueryFromAst normalizedQueryFromAst = NormalizedQueryFactory
                .createNormalizedQuery(executionContext.getGraphQLSchema(),
                        executionContext.getDocument(),
                        executionContext.getOperationDefinition().getName(),
                        executionContext.getVariables());

        Object data = null;

        Mono<RootExecutionResultNode> rootMono = fetchTopLevelFields(
                executionContext,
                data,
                normalizedQueryFromAst);

        return rootMono
                .map(value -> {
                    ExecutionResult executionResult = ResultNodesUtil.toExecutionResult(value);
                    System.out.println("execution result:" + executionResult.toSpecification());
                    return new graphql.execution.nextgen.result.RootExecutionResultNode(Collections.emptyList());
                })
                .toFuture();
    }


    private Mono<RootExecutionResultNode> fetchTopLevelFields(ExecutionContext executionContext,
                                                              Object data,
                                                              NormalizedQueryFromAst normalizedQueryFromAst) {
        return Mono.defer(() -> {
            System.out.println("Starting in thread " + Thread.currentThread());
            List<NormalizedField> topLevelFields = normalizedQueryFromAst.getTopLevelFields();
            ExecutionPath rootPath = ExecutionPath.rootPath();
            Tracker tracker = new Tracker();
            List<Mono<ExecutionResultNode>> monoChildren = new ArrayList<>(topLevelFields.size());
            for (NormalizedField topLevelField : topLevelFields) {
                ExecutionPath path = rootPath.segment(topLevelField.getResultKey());

                Mono<ExecutionResultNode> executionResultNode = fetchAndAnalyzeField(
                        executionContext,
                        tracker,
                        data,
                        topLevelField,
                        normalizedQueryFromAst,
                        path);
                System.out.println("SUBSCRIBE ON ERN");
                executionResultNode = executionResultNode.cache();
                executionResultNode.subscribe();
                System.out.println("END");
                monoChildren.add(executionResultNode);
            }

            MonoProcessor<String> result = MonoProcessor.create();
            fetchFields(tracker, result);

            return result.flatMap(ignored -> {
                System.out.println("finished overall");
                return Flux.concat(monoChildren).collectList().map(children -> RootExecutionResultNode.newRootExecutionResultNode().children(children).build());
            });
        }).subscribeOn(processingScheduler);
    }

    private void fetchFields(Tracker tracker, MonoProcessor<String> result) {
        while (!tracker.fieldsToFetch.isEmpty()) {
//            System.out.println("tracker size: " + tracker.fieldsToFetch.size());
            OneField oneField = tracker.fieldsToFetch.poll();
            FieldCoordinates coordinates = coordinates(oneField.normalizedField.getObjectType(), oneField.normalizedField.getFieldDefinition());
            Function<Object, Mono<Object>> objectMonoFunction = getDataFetcher(coordinates, oneField.normalizedField);
            Mono<Object> mono = objectMonoFunction.apply(oneField.source);
            mono.publishOn(fetchingScheduler).publishOn(processingScheduler).subscribe(resolvedObject -> {
                System.out.println("BRAIN: fetched " + oneField.executionPath + " " + Thread.currentThread());
                oneField.resultMono.onNext(resolvedObject);
                oneField.resultMono.subscribe(o -> {
                    System.out.println("fields to fetch :" + tracker.fieldsToFetch.size());
                    if (tracker.fieldsToFetch.size() == 0) {
                        result.onNext("finished");
                    } else {
                        fetchFields(tracker, result);
                    }
                });
            });
//            System.out.println("after one field subscribe");
        }
    }

    private Function<Object, Mono<Object>> getDataFetcher(FieldCoordinates coordinates, NormalizedField normalizedField) {
        Function<Object, Mono<Object>> objectMonoFunction = dataFetchers.get(coordinates);
        if (objectMonoFunction != null) {
            return objectMonoFunction;
        }
        return (source) -> {
            Map map = (Map) source;
            return Mono.just(map.get(normalizedField.getResultKey()));
        };
    }


    private Mono<ExecutionResultNode> fetchAndAnalyzeField(ExecutionContext context,
                                                           Tracker tracker,
                                                           Object source,
                                                           NormalizedField normalizedField,
                                                           NormalizedQueryFromAst normalizedQueryFromAst,
                                                           ExecutionPath executionPath) {
        // if should be batched we will add it to the list of sources that should be fetched
        return fetchValue(source, tracker, normalizedField, executionPath).flatMap(fetchedValue -> {
            return analyseValue(context, tracker, fetchedValue, normalizedField, normalizedQueryFromAst, executionPath);
        });
    }

    private Mono<Object> fetchValue(Object source, Tracker tracker, NormalizedField normalizedField, ExecutionPath executionPath) {
        System.out.println("BODY: ask for mono  for for " + executionPath + " with source " + source + " " + Thread.currentThread());
        return tracker.addFieldToFetch(executionPath, normalizedField, source).map(resolved -> {
            System.out.println("BODY: resolved value" + executionPath + " " + Thread.currentThread());
            return resolved;
        });

        // find out if we already have all source objects for a certain
//        if (isReadyForBatching(executionPath)) {
//
//        }
//        if (source == null) {
//            return null;
//        }
//        @SuppressWarnings("unchecked")
//        Map<String, Object> map = (Map<String, Object>) source;
//        return map.get(key);
//        return null;
    }

    private Mono<Object> fetchOneFieldImpl(Object source) {
        return null;
    }

    private Mono<List<Object>> batchFetch(List<Object> source) {
        return null;
    }


    private Mono<ExecutionResultNode> analyseValue(ExecutionContext executionContext,
                                                   Tracker tracker,
                                                   Object fetchedValue,
                                                   NormalizedField normalizedField,
                                                   NormalizedQueryFromAst normalizedQueryFromAst,
                                                   ExecutionPath executionPath) {
        return analyzeFetchedValueImpl(executionContext, tracker, fetchedValue, normalizedField, normalizedQueryFromAst, normalizedField.getFieldDefinition().getType(), executionPath);
    }

    private Mono<ExecutionResultNode> analyzeFetchedValueImpl(ExecutionContext executionContext,
                                                              Tracker tracker,
                                                              Object toAnalyze,
                                                              NormalizedField normalizedField,
                                                              NormalizedQueryFromAst normalizedQueryFromAst,
                                                              GraphQLOutputType curType,
                                                              ExecutionPath executionPath) {

        System.out.println("analyzed fetch value for " + executionPath);
        boolean isNonNull = GraphQLTypeUtil.isNonNull(curType);
        if (toAnalyze == null && isNonNull) {
            NonNullableFieldWasNullError nonNullableFieldWasNullError = new NonNullableFieldWasNullError((GraphQLNonNull) curType, executionPath);
            return Mono.just(createNullERNWithNullableError(normalizedField, executionPath, nonNullableFieldWasNullError));
        } else if (toAnalyze == null) {
            return Mono.just(createNullERN(normalizedField, executionPath));
        }

        curType = (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(curType);
        if (isList(curType)) {
            return analyzeList(executionContext, tracker, toAnalyze, (GraphQLList) curType, normalizedField, normalizedQueryFromAst, executionPath);
        } else if (curType instanceof GraphQLScalarType) {
            return Mono.just(analyzeScalarValue(toAnalyze, (GraphQLScalarType) curType, normalizedField, executionPath));
        } else if (curType instanceof GraphQLEnumType) {
            return Mono.just(analyzeEnumValue(toAnalyze, (GraphQLEnumType) curType, normalizedField, executionPath));
        }


        GraphQLObjectType resolvedObjectType = resolveType(executionContext, toAnalyze, curType);
        return resolveObject(executionContext, tracker, normalizedField, normalizedQueryFromAst, resolvedObjectType, toAnalyze, executionPath);
    }

    private Mono<ExecutionResultNode> resolveObject(ExecutionContext context,
                                                    Tracker tracker,
                                                    NormalizedField normalizedField,
                                                    NormalizedQueryFromAst normalizedQueryFromAst,
                                                    GraphQLObjectType resolvedType,
                                                    Object completedValue,
                                                    ExecutionPath executionPath) {

        List<Mono<ExecutionResultNode>> nodeChildrenMono = new ArrayList<>(normalizedField.getChildren().size());

        for (NormalizedField child : normalizedField.getChildren()) {
            if (child.getObjectType() == resolvedType) {
                ExecutionPath pathForChild = executionPath.segment(child.getResultKey());
                Mono<ExecutionResultNode> childNode = fetchAndAnalyzeField(context, tracker, completedValue, child, normalizedQueryFromAst, pathForChild);
                nodeChildrenMono.add(childNode);
            }
        }
        return Flux.concat(nodeChildrenMono).collectList().map(nodeChildren -> {
            return newObjectExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .objectType(normalizedField.getObjectType())
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .completedValue(completedValue)
                    .children(nodeChildren)
                    .build();


        });


    }


    private Mono<ExecutionResultNode> analyzeList(ExecutionContext executionContext,
                                                  Tracker tracker,
                                                  Object toAnalyze,
                                                  GraphQLList curType,
                                                  NormalizedField normalizedField,
                                                  NormalizedQueryFromAst normalizedQueryFromAst,
                                                  ExecutionPath executionPath) {

        if (toAnalyze instanceof List) {
            return createListImpl(executionContext, tracker, toAnalyze, (List<Object>) toAnalyze, curType, normalizedField, normalizedQueryFromAst, executionPath);
        } else {
            TypeMismatchError error = new TypeMismatchError(executionPath, curType);
            return Mono.just(newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .objectType(normalizedField.getObjectType())
                    .completedValue(null)
                    .addError(error)
                    .build());
        }
    }

    private LeafExecutionResultNode createNullERNWithNullableError(NormalizedField normalizedField,
                                                                   ExecutionPath executionPath,
                                                                   NonNullableFieldWasNullError nonNullableFieldWasNullError) {
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .fieldDefinition(normalizedField.getFieldDefinition())
                .objectType(normalizedField.getObjectType())
                .completedValue(null)
                .normalizedField(normalizedField)
                .nonNullableFieldWasNullError(nonNullableFieldWasNullError)
                .build();
    }

    private LeafExecutionResultNode createNullERN(NormalizedField normalizedField,
                                                  ExecutionPath executionPath) {
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .fieldDefinition(normalizedField.getFieldDefinition())
                .objectType(normalizedField.getObjectType())
                .completedValue(null)
                .normalizedField(normalizedField)
                .build();
    }

    private Mono<ExecutionResultNode> createListImpl(ExecutionContext executionContext,
                                                     Tracker tracker,
                                                     Object fetchedValue,
                                                     List<Object> iterableValues,
                                                     GraphQLList currentType,
                                                     NormalizedField normalizedField,
                                                     NormalizedQueryFromAst normalizedQueryFromAst,
                                                     ExecutionPath executionPath) {
        List<Mono<ExecutionResultNode>> children = new ArrayList<>();
        int index = 0;
        for (Object item : iterableValues) {
            ExecutionPath indexedPath = executionPath.segment(index);
            children.add(analyzeFetchedValueImpl(executionContext, tracker, item, normalizedField, normalizedQueryFromAst, (GraphQLOutputType) GraphQLTypeUtil.unwrapOne(currentType), indexedPath));
            index++;
        }
        return Flux.concat(children).collectList().map(c ->
                ListExecutionResultNode.newListExecutionResultNode()
                        .executionPath(executionPath)
                        .normalizedField(normalizedField)
                        .fieldDefinition(normalizedField.getFieldDefinition())
                        .objectType(normalizedField.getObjectType())
                        .completedValue(fetchedValue)
                        .children(c)
                        .build()
        );

    }


    private GraphQLObjectType resolveType(ExecutionContext executionContext, Object source, GraphQLType curType) {
        if (curType instanceof GraphQLObjectType) {
            return (GraphQLObjectType) curType;
        }
//        String underscoreTypeNameAlias = nadelContext.getUnderscoreTypeNameAlias();
//
//        assertTrue(source instanceof Map, "The Nadel result object MUST be a Map");
//
//        Map<String, Object> sourceMap = (Map<String, Object>) source;
//        assertTrue(sourceMap.containsKey(underscoreTypeNameAlias), "The Nadel result object for interfaces and unions MUST have an aliased __typename in them");
//
//        Object typeName = sourceMap.get(underscoreTypeNameAlias);
//        assertNotNull(typeName, "The Nadel result object for interfaces and unions MUST have an aliased__typename with a non null value in them");
//
//        GraphQLObjectType objectType = executionContext.getGraphQLSchema().getObjectType(typeName.toString());
//        assertNotNull(objectType, "There must be an underlying graphql object type called '%s'", typeName);
//        return objectType;
        return null;


    }


    private ExecutionResultNode analyzeScalarValue(Object toAnalyze,
                                                   GraphQLScalarType scalarType,
                                                   NormalizedField normalizedField,
                                                   ExecutionPath executionPath) {
        Object serialized;
        try {
            serialized = serializeScalarValue(toAnalyze, scalarType);
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            return newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .objectType(normalizedField.getObjectType())
                    .completedValue(null)
                    .addError(error)
                    .build();
        }

        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .normalizedField(normalizedField)
                .fieldDefinition(normalizedField.getFieldDefinition())
                .objectType(normalizedField.getObjectType())
                .completedValue(serialized)
                .build();

    }

    protected Object serializeScalarValue(Object toAnalyze, GraphQLScalarType scalarType) throws CoercingSerializeException {
        if (scalarType == Scalars.GraphQLString) {
            if (toAnalyze instanceof String) {
                return toAnalyze;
            } else {
                throw new CoercingSerializeException("Unexpected value '" + toAnalyze + "'. String expected");
            }
        }
        return scalarType.getCoercing().serialize(toAnalyze);
    }

    private ExecutionResultNode analyzeEnumValue(Object toAnalyze,
                                                 GraphQLEnumType enumType,
                                                 NormalizedField normalizedField,
                                                 ExecutionPath executionPath) {
        Object serialized;
        try {
            serialized = enumType.serialize(toAnalyze);
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            return newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .normalizedField(normalizedField)
                    .fieldDefinition(normalizedField.getFieldDefinition())
                    .objectType(normalizedField.getObjectType())
                    .completedValue(null)
                    .addError(error)
                    .build();
        }
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .normalizedField(normalizedField)
                .fieldDefinition(normalizedField.getFieldDefinition())
                .objectType(normalizedField.getObjectType())
                .completedValue(serialized)
                .build();
    }


}
