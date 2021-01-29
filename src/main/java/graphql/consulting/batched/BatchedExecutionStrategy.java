package graphql.consulting.batched;

import graphql.Assert;
import graphql.ExceptionWhileDataFetching;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.SerializationError;
import graphql.TypeMismatchError;
import graphql.consulting.batched.normalized.NormalizedField;
import graphql.consulting.batched.normalized.NormalizedQueryFactory;
import graphql.consulting.batched.normalized.NormalizedQueryFromAst;
import graphql.execution.ExecutionContext;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.execution.nextgen.ExecutionStrategy;
import graphql.introspection.Introspection;
import graphql.language.SourceLocation;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.PropertyDataFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.FieldCoordinates.systemCoordinates;
import static graphql.schema.GraphQLTypeUtil.isList;

public class BatchedExecutionStrategy implements ExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(BatchedExecutionStrategy.class);

    Scheduler fetchingScheduler = Schedulers.newParallel("data-fetching-scheduler");
    List<Scheduler> processingSchedulers;

    private final DataFetchingConfiguration dataFetchingConfiguration;
    private ResolveType resolveType = new ResolveType();

    public static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "NULL_VALUE";
        }
    };

    public BatchedExecutionStrategy(DataFetchingConfiguration dataFetchingConfiguration) {
        this.dataFetchingConfiguration = dataFetchingConfiguration;

        processingSchedulers = new ArrayList<>();
        for (int i = 1; i <= Runtime.getRuntime().availableProcessors(); i++) {
            processingSchedulers.add(Schedulers.newSingle("processing-thread-" + i));
        }
    }


    @Override
    public CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext) {
        NormalizedQueryFromAst normalizedQueryFromAst = NormalizedQueryFactory
                .createNormalizedQuery(executionContext.getGraphQLSchema(),
                        executionContext.getDocument(),
                        executionContext.getOperationDefinition().getName(),
                        executionContext.getVariables());

        Object data = null;

        Mono<Tuple2<Map<String, Object>, Tracker>> rootMono = fetchTopLevelFields(
                executionContext,
                data,
                normalizedQueryFromAst);

        return rootMono
                .map(value -> {
                    return ExecutionResultImpl.newExecutionResult()
                            .addErrors(new ArrayList<>(value.getT2().getErrors().values()))
                            .data(value.getT1())
                            .addExtension("batching-statistics", createBatchingStatistics(value.getT2()))
                            .build();
                })
                .onErrorResume(NonNullableFieldWasNullError.class::isInstance,
                        throwable -> Mono.just(ExecutionResultImpl.newExecutionResult()
                                .addError((NonNullableFieldWasNullError) throwable)
                                .build()))
                .cast(ExecutionResult.class)
                .toFuture();
    }

    private Map<String, Object> createBatchingStatistics(Tracker tracker) {
        Map<NormalizedField, Integer> fetchCountByNF = tracker.getFetchCountByNF();
        Map<String, Object> result = new LinkedHashMap<>();
        for (NormalizedField nf : fetchCountByNF.keySet()) {
            result.put(nf.printFullPath(), fetchCountByNF.get(nf));
        }
        return result;
    }


    private Mono<Tuple2<Map<String, Object>, Tracker>> fetchTopLevelFields(ExecutionContext executionContext,
                                                                           Object data,
                                                                           NormalizedQueryFromAst normalizedQueryFromAst) {
        Scheduler scheduler = processingSchedulers.get(ThreadLocalRandom.current().nextInt(processingSchedulers.size()));
        Tracker tracker = new Tracker(scheduler);

        return Mono.defer(() -> {
            List<NormalizedField> topLevelFields = normalizedQueryFromAst.getTopLevelFields();
            ResultPath rootPath = ResultPath.rootPath();
            List<Mono<Tuple2<String, Object>>> monoChildren = new ArrayList<>(topLevelFields.size());
            for (NormalizedField topLevelField : topLevelFields) {
                ResultPath path = rootPath.segment(topLevelField.getResultKey());

                Mono<Tuple2<String, Object>> executionResultNode = fetchAndAnalyzeField(
                        executionContext,
                        tracker,
                        data,
                        topLevelField,
                        normalizedQueryFromAst,
                        path)
                        .map(resolvedValue -> Tuples.of(topLevelField.getResultKey(), resolvedValue));
                monoChildren.add(executionResultNode);
            }

            monoChildren.add(Mono.defer(() -> {
                fetchFields(executionContext, normalizedQueryFromAst, tracker);
                return Mono.empty();
            }));
            Flux<Tuple2<String, Object>> flux = Flux.mergeSequential(monoChildren);
            Mono<Map<String, Object>> resultMapMono = flux.collect(mapCollector());
            return resultMapMono.zipWith(Mono.just(tracker));
        }).subscribeOn(tracker.getScheduler());
    }

    private void fetchFields(ExecutionContext executionContext,
                             NormalizedQueryFromAst normalizedQueryFromAst,
                             Tracker tracker) {
        if (tracker.getFieldsFinishedBecauseNullParents() == null) {
            tracker.setFieldsFinishedBecauseNullParents((doneFields) -> {
                for (NormalizedField doneField : doneFields) {
                    FieldCoordinates coordinates = coordinates(doneField.getObjectType(), doneField.getFieldDefinition());
                    checkBatchOnCoordinates(executionContext, coordinates, normalizedQueryFromAst, tracker);
                }
            });
        }

        if (tracker.getFieldsToFetch().size() == 0 && tracker.getPendingAsyncDataFetcher() == 0) {
            // this means we are at the end
        }
        while (!tracker.getFieldsToFetch().isEmpty()) {
            OneField oneField = tracker.getFieldsToFetch().poll();
            NormalizedField normalizedField = oneField.normalizedField;
            tracker.fetchingStarted(normalizedField);

            FieldCoordinates coordinates = coordinates(normalizedField.getObjectType(), normalizedField.getFieldDefinition());
            if (normalizedField.isIntrospectionField()) {
                fetchIntrospectionField(executionContext, tracker, oneField, normalizedField, normalizedQueryFromAst, coordinates);
            } else if (dataFetchingConfiguration.isSingleFetch(coordinates)) {
                singleFetchField(executionContext, normalizedQueryFromAst, tracker, oneField, normalizedField, coordinates);
            } else if (dataFetchingConfiguration.isFieldBatched(coordinates)) {
                batchFetchField(executionContext, normalizedQueryFromAst, tracker, oneField, normalizedField, coordinates);
            } else if (dataFetchingConfiguration.getTrivialDataFetcher(coordinates) != null) {
                trivialFetchField(tracker, oneField, normalizedField, normalizedQueryFromAst, coordinates);
            } else {
                propertyBasedFetcher(executionContext, tracker, oneField, normalizedField, normalizedQueryFromAst, coordinates);
            }
        }

    }

    private boolean isSpecialIntrospectionField(GraphQLFieldDefinition fieldDefinition) {
        return fieldDefinition == Introspection.TypeMetaFieldDef ||
                fieldDefinition == Introspection.SchemaMetaFieldDef ||
                fieldDefinition == Introspection.TypeNameMetaFieldDef;
    }

    private void fetchIntrospectionField(ExecutionContext executionContext,
                                         Tracker tracker,
                                         OneField oneField,
                                         NormalizedField normalizedField,
                                         NormalizedQueryFromAst normalizedQueryFromAst,
                                         FieldCoordinates coordinates) {
        GraphQLFieldDefinition fieldDefinition = normalizedField.getFieldDefinition();
        String name = fieldDefinition.getName();
        DataFetcher<?> dataFetcher;
        if (isSpecialIntrospectionField(fieldDefinition)) {
            dataFetcher = executionContext.getGraphQLSchema().getCodeRegistry().getDataFetcher(systemCoordinates(name), normalizedField.getFieldDefinition());
        } else {
            dataFetcher = executionContext.getGraphQLSchema().getCodeRegistry().getDataFetcher(coordinates, normalizedField.getFieldDefinition());
        }
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .source(oneField.source)
                .graphQLSchema(executionContext.getGraphQLSchema())
                .arguments(oneField.normalizedField.getArguments())
                .variables(executionContext.getVariables())
                .fieldType(oneField.normalizedField.getFieldDefinition().getType())
                .parentType(oneField.normalizedField.getObjectType())
                .build();
        Object fetchedValue = callIntrospectionDataFetcher(dataFetcher, env);
        oneField.resultMono.onNext(replaceNullValue(fetchedValue));
        tracker.fetchingFinished(normalizedField, oneField.resultPath);
    }

    private Object callIntrospectionDataFetcher(DataFetcher<?> dataFetcher, DataFetchingEnvironment env) {
        try {
            return dataFetcher.get(env);
        } catch (Exception e) {
            return Assert.assertShouldNeverHappen("exception thrown by inspection function %s", e);
        }
    }


    private void trivialFetchField(Tracker tracker,
                                   OneField oneField,
                                   NormalizedField normalizedField,
                                   NormalizedQueryFromAst normalizedQueryFromAst,
                                   FieldCoordinates coordinates) {
        TrivialDataFetcher trivialDataFetcher = this.dataFetchingConfiguration.getTrivialDataFetcher(coordinates);
        Supplier<Object> supplier = () -> trivialDataFetcher.get(new TrivialDataFetcherEnvironment(normalizedField, oneField.source));
        syncFetchFieldImpl(tracker, oneField, normalizedField, normalizedQueryFromAst, supplier);
    }

    private void propertyBasedFetcher(ExecutionContext executionContext,
                                      Tracker tracker,
                                      OneField oneField,
                                      NormalizedField normalizedField,
                                      NormalizedQueryFromAst normalizedQueryFromAst,
                                      FieldCoordinates coordinates) {
        DataFetchingEnvironment dfEnvironment = createDFEnvironment(executionContext, oneField.source, normalizedField, normalizedQueryFromAst);
        PropertyDataFetcher<Object> fetching = PropertyDataFetcher.fetching(normalizedField.getResultKey());
        Supplier<Object> supplier = () -> fetching.get(dfEnvironment);
        syncFetchFieldImpl(tracker, oneField, normalizedField, normalizedQueryFromAst, supplier);
    }

    private void syncFetchFieldImpl(Tracker tracker,
                                    OneField oneField,
                                    NormalizedField normalizedField,
                                    NormalizedQueryFromAst normalizedQueryFromAst,
                                    Supplier<Object> actualCall
    ) {
        Object fetchedValue;
        try {
            fetchedValue = actualCall.get();
        } catch (Throwable e) {
            MergedField mergedField = normalizedQueryFromAst.getMergedField(normalizedField);
            SourceLocation sourceLocation = mergedField.getFields().get(0).getSourceLocation();
            ExceptionWhileDataFetching exceptionWhileDataFetching =
                    new ExceptionWhileDataFetching(oneField.resultPath, e, sourceLocation);
            tracker.addError(oneField.resultPath, exceptionWhileDataFetching);
            fetchedValue = NULL_VALUE;
        }
        fetchedValue = replaceNullValue(fetchedValue);
        oneField.resultMono.onNext(fetchedValue);
        tracker.fetchingFinished(normalizedField, oneField.resultPath);

    }

    private Object replaceNullValue(Object fetchedValue) {
        return fetchedValue == null ? NULL_VALUE : fetchedValue;
    }

    private void singleFetchField(ExecutionContext executionContext,
                                  NormalizedQueryFromAst normalizedQueryFromAst,
                                  Tracker tracker,
                                  OneField oneField,
                                  NormalizedField normalizedField,
                                  FieldCoordinates coordinates) {
        SingleDataFetcher singleDataFetcher = dataFetchingConfiguration.getSingleDataFetcher(coordinates);
        SingleDataFetcherEnvironment singleDataFetcherEnvironment = new SingleDataFetcherEnvironment(oneField.source, oneField.normalizedField, oneField.resultPath);

        tracker.incrementPendingAsyncDataFetcher();
        singleDataFetcher
                .get(singleDataFetcherEnvironment)
                .publishOn(fetchingScheduler)
                .publishOn(tracker.getScheduler())
                .subscribe(fetchedValue -> {
                    tracker.decrementPendingAsyncDataFetcher();
                    fetchedValue = replaceNullValue(fetchedValue);
                    oneField.resultMono.onNext(fetchedValue);
                    tracker.fetchingFinished(normalizedField, oneField.resultPath);
                    fetchFields(executionContext, normalizedQueryFromAst, tracker);
                });
    }

    private void checkBatchOnCoordinates(ExecutionContext executionContext, FieldCoordinates coordinates, NormalizedQueryFromAst normalizedQueryFromAst, Tracker tracker) {
        if (!dataFetchingConfiguration.isBatchedOnCoordinates(coordinates)) {
            return;
        }
        List<OneField> oneFields;
        List<NormalizedField> fieldsWithSameCoordinates = normalizedQueryFromAst.getCoordinatesToNormalizedFields().get(coordinates);
        oneFields = new ArrayList<>();
        for (NormalizedField nf : fieldsWithSameCoordinates) {
            if (!tracker.isReadyForBatching(nf)) {
                return;
            }
            List<OneField> batch = tracker.getBatch(nf);
            // batch can be null for NormalizedFields which are never fetched
            if (batch != null) {
                oneFields.addAll(batch);
            }
        }
        batchImpl(executionContext, normalizedQueryFromAst, tracker, coordinates, oneFields);

    }

    private void batchFetchField(ExecutionContext executionContext,
                                 NormalizedQueryFromAst normalizedQueryFromAst,
                                 Tracker tracker,
                                 OneField oneField,
                                 NormalizedField normalizedField,
                                 FieldCoordinates coordinates) {
        boolean isReady;
        List<OneField> oneFields;
        tracker.addBatch(normalizedField, oneField);

        if (dataFetchingConfiguration.isBatchedOnCoordinates(coordinates)) {
            List<NormalizedField> fieldsWithSameCoordinates = normalizedQueryFromAst.getCoordinatesToNormalizedFields().get(coordinates);
            oneFields = new ArrayList<>();
            for (NormalizedField nf : fieldsWithSameCoordinates) {
                if (!tracker.isReadyForBatching(nf)) {
                    return;
                }
                List<OneField> batch = tracker.getBatch(nf);
                // batch can be null for NormalizedFields which are never fetched
                if (batch != null) {
                    oneFields.addAll(batch);
                }
            }
            isReady = true;
        } else {
            oneFields = tracker.getBatch(normalizedField);
            isReady = tracker.isReadyForBatching(normalizedField);
        }

        if (isReady) {
            batchImpl(executionContext, normalizedQueryFromAst, tracker, coordinates, oneFields);
        }
    }

    private void batchImpl(ExecutionContext executionContext,
                           NormalizedQueryFromAst normalizedQueryFromAst,
                           Tracker tracker,
                           FieldCoordinates coordinates,
                           List<OneField> oneFields) {
        BatchedDataFetcher batchedDataFetcher = dataFetchingConfiguration.getBatchedDataFetcher(coordinates);
        List<Object> sources = FpKit.map(oneFields, f -> f.source);
        List<NormalizedField> normalizedFields = FpKit.map(oneFields, f -> f.normalizedField);
        List<ResultPath> resultPaths = FpKit.map(oneFields, f -> f.resultPath);
        BatchedDataFetcherEnvironment env = new BatchedDataFetcherEnvironment(sources, normalizedFields, resultPaths);
        Mono<BatchedDataFetcherResult> batchedDataFetcherResultMono = batchedDataFetcher.get(env);
        tracker.incrementPendingAsyncDataFetcher();
        batchedDataFetcherResultMono
                .publishOn(fetchingScheduler)
                .publishOn(tracker.getScheduler())
                .subscribe(batchedDataFetcherResult -> {
                    tracker.decrementPendingAsyncDataFetcher();
                    for (int i = 0; i < batchedDataFetcherResult.getValues().size(); i++) {
                        Object fetchedValue = batchedDataFetcherResult.getValues().get(i);
                        fetchedValue = replaceNullValue(fetchedValue);
                        oneFields.get(i).resultMono.onNext(fetchedValue);
                    }
                    for (OneField onFieldFetched : oneFields) {
                        tracker.fetchingFinished(onFieldFetched.normalizedField, onFieldFetched.resultPath);
                    }
                    fetchFields(executionContext, normalizedQueryFromAst, tracker);
                });
    }

    private Mono<Object> fetchAndAnalyzeField(ExecutionContext context,
                                              Tracker tracker,
                                              Object source,
                                              NormalizedField normalizedField,
                                              NormalizedQueryFromAst normalizedQueryFromAst,
                                              ResultPath resultPath) {
        // if should be batched we will add it to the list of sources that should be fetched
        return fetchValue(source, tracker, normalizedField, resultPath).flatMap(fetchedValue -> {
            // analysis can lead to 0-n non null values at this execution path
            // the execution path always ends with a Name
            return analyseValue(context, tracker, fetchedValue, normalizedField, normalizedQueryFromAst, resultPath).map(resolvedValue -> {
                return resolvedValue;
            });
        });
    }

    private Mono<Object> fetchValue(Object source, Tracker tracker, NormalizedField normalizedField, ResultPath resultPath) {
        return tracker.addFieldToFetch(resultPath, normalizedField, source).map(resolved -> {
            return resolved;
        });
    }


    private Mono<Object> analyseValue(ExecutionContext executionContext,
                                      Tracker tracker,
                                      Object fetchedValue,
                                      NormalizedField normalizedField,
                                      NormalizedQueryFromAst normalizedQueryFromAst,
                                      ResultPath resultPath) {
        return analyzeFetchedValueImpl(executionContext, tracker, fetchedValue, normalizedField, normalizedQueryFromAst, normalizedField.getFieldDefinition().getType(), resultPath);
    }

    private Mono<Object> analyzeFetchedValueImpl(ExecutionContext executionContext,
                                                 Tracker tracker,
                                                 Object toAnalyze,
                                                 NormalizedField normalizedField,
                                                 NormalizedQueryFromAst normalizedQueryFromAst,
                                                 GraphQLOutputType curType,
                                                 ResultPath resultPath) {

        boolean isNonNull = GraphQLTypeUtil.isNonNull(curType);

        if ((toAnalyze == NULL_VALUE || toAnalyze == null) && isNonNull) {
            NonNullableFieldWasNullError nonNullableFieldWasNullError = new NonNullableFieldWasNullError(normalizedField, resultPath);
            return Mono.error(nonNullableFieldWasNullError);
        } else if (toAnalyze == NULL_VALUE || toAnalyze == null) {
            return Mono.just(NULL_VALUE);
        }

        //TODO: handle serialized errors correctly with respect to non null count: currently they count as non null, but
        // should not

        GraphQLOutputType curTypeWithoutNonNull = (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(curType);
        if (isList(curTypeWithoutNonNull)) {
            return analyzeList(executionContext, tracker, toAnalyze, (GraphQLList) curTypeWithoutNonNull, isNonNull, normalizedField, normalizedQueryFromAst, resultPath);
        } else if (curTypeWithoutNonNull instanceof GraphQLScalarType) {
            tracker.incrementNonNullCount(normalizedField, resultPath);
            return analyzeScalarValue(toAnalyze, (GraphQLScalarType) curTypeWithoutNonNull, isNonNull, normalizedField, resultPath, tracker);
        } else if (curTypeWithoutNonNull instanceof GraphQLEnumType) {
            tracker.incrementNonNullCount(normalizedField, resultPath);
            return analyzeEnumValue(toAnalyze, (GraphQLEnumType) curTypeWithoutNonNull, isNonNull, normalizedField, resultPath, tracker);
        }
        tracker.incrementNonNullCount(normalizedField, resultPath);

        return resolveType(executionContext, toAnalyze, curTypeWithoutNonNull, normalizedField, normalizedQueryFromAst)
                .flatMap(resolvedObjectType -> {
                    tracker.addChildType(normalizedField, resolvedObjectType);
                    return resolveObject(executionContext, tracker, normalizedField, normalizedQueryFromAst, resolvedObjectType, isNonNull, toAnalyze, resultPath);
                });
    }

    private Mono<Object> resolveObject(ExecutionContext context,
                                       Tracker tracker,
                                       NormalizedField normalizedField,
                                       NormalizedQueryFromAst normalizedQueryFromAst,
                                       GraphQLObjectType resolvedType,
                                       boolean isNonNull,
                                       Object completedValue,
                                       ResultPath resultPath) {

        List<Mono<Tuple2<String, Object>>> nodeChildrenMono = new ArrayList<>(normalizedField.getChildren().size());
        for (NormalizedField child : normalizedField.getChildren()) {
            if (child.getObjectType() == resolvedType) {
                ResultPath pathForChild = resultPath.segment(child.getResultKey());
                Mono<Tuple2<String, Object>> childNode = fetchAndAnalyzeField(context, tracker, completedValue, child, normalizedQueryFromAst, pathForChild)
                        .map(object -> Tuples.of(child.getResultKey(), object));
                nodeChildrenMono.add(childNode);
            }
        }
        return Flux.fromIterable(nodeChildrenMono)
                .flatMapSequential(Function.identity())
                .collect(mapCollector())
                .cast(Object.class)
                .onErrorResume(error -> error instanceof NonNullableFieldWasNullError,
                        throwable -> {
                            if (isNonNull) {
                                return Mono.error(throwable);
                            } else {
                                tracker.addError(resultPath, (GraphQLError) throwable);
                                return Mono.just(NULL_VALUE);
                            }
                        });

    }


    private Mono<Object> analyzeList(ExecutionContext executionContext,
                                     Tracker tracker,
                                     Object toAnalyze,
                                     GraphQLList curType,
                                     boolean isNonNull,
                                     NormalizedField normalizedField,
                                     NormalizedQueryFromAst normalizedQueryFromAst,
                                     ResultPath resultPath) {

        if (toAnalyze instanceof Iterable) {
            return createListImpl(executionContext, tracker, toAnalyze, (Iterable<Object>) toAnalyze, curType, isNonNull, normalizedField, normalizedQueryFromAst, resultPath);
        } else {
            TypeMismatchError error = new TypeMismatchError(resultPath, curType);
            tracker.addError(resultPath, error);
            if (isNonNull) {
                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(normalizedField, resultPath);
                return Mono.error(nonNullError);
            } else {
                return Mono.just(NULL_VALUE);
            }
        }
    }


    private Mono<Object> createListImpl(ExecutionContext executionContext,
                                        Tracker tracker,
                                        Object fetchedValue,
                                        Iterable<Object> iterableValues,
                                        GraphQLList currentType,
                                        boolean isNonNull,
                                        NormalizedField normalizedField,
                                        NormalizedQueryFromAst normalizedQueryFromAst,
                                        ResultPath resultPath) {
        List<Mono<Object>> children = new ArrayList<>();
        int index = 0;
        for (Object item : iterableValues) {
            ResultPath indexedPath = resultPath.segment(index);
            children.add(analyzeFetchedValueImpl(executionContext, tracker, item, normalizedField, normalizedQueryFromAst, (GraphQLOutputType) GraphQLTypeUtil.unwrapOne(currentType), indexedPath));
            index++;
        }


        return Flux.fromIterable(children).flatMapSequential(Function.identity())
                .collect(listCollector())
                .cast(Object.class)
                .onErrorResume(NonNullableFieldWasNullError.class::isInstance,
                        throwable -> {
                            if (isNonNull) {
                                return Mono.error(throwable);
                            } else {
                                tracker.addError(resultPath, (GraphQLError) throwable);
                                return Mono.just(NULL_VALUE);
                            }
                        });
    }


    private Mono<GraphQLObjectType> resolveType(ExecutionContext executionContext,
                                                Object source,
                                                GraphQLType curType,
                                                NormalizedField normalizedField,
                                                NormalizedQueryFromAst normalizedQueryFromAst) {
        MergedField mergedField = normalizedQueryFromAst.getMergedField(normalizedField);
        return resolveType.resolveType(executionContext, mergedField, source, Collections.emptyMap(), curType);
    }


    private Mono<Object> analyzeScalarValue(Object toAnalyze,
                                            GraphQLScalarType scalarType,
                                            boolean isNonNull,
                                            NormalizedField normalizedField,
                                            ResultPath resultPath,
                                            Tracker tracker) {
        try {
            return Mono.just(serializeScalarValue(toAnalyze, scalarType));
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(resultPath, e);
            tracker.addError(resultPath, error);
            if (isNonNull) {
                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(normalizedField, resultPath);
                return Mono.error(nonNullError);
            } else {
                return Mono.just(NULL_VALUE);
            }
        }


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

    private Mono<Object> analyzeEnumValue(Object toAnalyze,
                                          GraphQLEnumType enumType,
                                          boolean isNonNull,
                                          NormalizedField normalizedField,
                                          ResultPath resultPath,
                                          Tracker tracker) {
        try {
            return Mono.just(enumType.serialize(toAnalyze));
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(resultPath, e);
            tracker.addError(resultPath, error);
            if (isNonNull) {
                NonNullableFieldWasNullError nonNullError = new NonNullableFieldWasNullError(normalizedField, resultPath);
                return Mono.error(nonNullError);
            } else {
                return Mono.just(NULL_VALUE);
            }
        }
    }

    private Collector<Tuple2<String, Object>, Map<String, Object>, Map<String, Object>> mapCollector() {
        Supplier<Map<String, Object>> supplier = LinkedHashMap::new;
        BiConsumer<Map<String, Object>, Tuple2<String, Object>> acc = (map, tuple) -> {
            map.put(tuple.getT1(), maybeNullValue(tuple.getT2()));
        };

        return Collector.of(supplier, acc, throwingMerger());
    }

    private static <T> BinaryOperator<T> throwingMerger() {
        return (u, v) -> {
            throw new IllegalStateException(String.format("Duplicate key %s", u));
        };
    }

    private Object maybeNullValue(Object o) {
        return o == NULL_VALUE ? null : o;
    }

    private Collector<Object, List<Object>, List<Object>> listCollector() {
        Collector<Object, List<Object>, List<Object>> result = Collector.of((Supplier<List<Object>>) ArrayList::new,
                (list, o) -> list.add(maybeNullValue(o)),
                (left, right) -> {
                    left.addAll(right);
                    return left;
                });
        return result;
    }


    private DataFetchingEnvironment createDFEnvironment(ExecutionContext executionContext,
                                                        Object source,
                                                        NormalizedField normalizedField,
                                                        NormalizedQueryFromAst normalizedQueryFromAst) {
        DataFetchingEnvironment environment = newDataFetchingEnvironment(executionContext)
                .source(source)
                .arguments(normalizedField.getArguments())
                .fieldDefinition(normalizedField.getFieldDefinition())
                .mergedField(normalizedQueryFromAst.getMergedField(normalizedField))
                .fieldType(normalizedField.getFieldDefinition().getType())
//                .executionStepInfo(executionStepInfo)
                .parentType(normalizedField.getObjectType())
                .build();
        return environment;
    }


}
