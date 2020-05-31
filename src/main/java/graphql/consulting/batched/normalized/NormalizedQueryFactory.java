package graphql.consulting.batched.normalized;

import graphql.Internal;
import graphql.consulting.batched.normalized.FieldCollectorNormalizedQuery.CollectFieldResult;
import graphql.execution.MergedField;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.NodeUtil;
import graphql.schema.GraphQLSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Internal
public class NormalizedQueryFactory {

    public static NormalizedQueryFromAst createNormalizedQuery(GraphQLSchema graphQLSchema,
                                                               Document document,
                                                               String operationName,
                                                               Map<String, Object> variables) {
        return new NormalizedQueryFactory().createNormalizedQueryImpl(graphQLSchema, document, operationName, variables);
    }

    /**
     * Creates a new Query execution tree for the provided query
     */
    private NormalizedQueryFromAst createNormalizedQueryImpl(GraphQLSchema graphQLSchema, Document document, String operationName, Map<String, Object> variables) {

        NodeUtil.GetOperationResult getOperationResult = NodeUtil.getOperation(document, operationName);

        FieldCollectorNormalizedQuery fieldCollector = new FieldCollectorNormalizedQuery();
        FieldCollectorNormalizedQueryParams parameters = FieldCollectorNormalizedQueryParams
                .newParameters()
                .fragments(getOperationResult.fragmentsByName)
                .schema(graphQLSchema)
                .variables(variables)
                .build();

        CollectFieldResult roots = fieldCollector.collectFromOperation(parameters, getOperationResult.operationDefinition, graphQLSchema.getQueryType());

        Map<Field, List<NormalizedField>> normalizedFieldsByFieldId = new LinkedHashMap<>();
        Map<NormalizedField, MergedField> mergedFieldsByNormalizedField = new LinkedHashMap<>();
        List<NormalizedField> realRoots = new ArrayList<>();

        for (NormalizedField root : roots.getChildren()) {

            MergedField mergedField = roots.getMergedFieldByNormalized().get(root);
            NormalizedField realRoot = buildFieldWithChildren(root, mergedField, fieldCollector, parameters, normalizedFieldsByFieldId, mergedFieldsByNormalizedField, 1);
            fixUpParentReference(realRoot);

            updateByIdMap(realRoot, mergedField, normalizedFieldsByFieldId);
            realRoots.add(realRoot);
        }

        return new NormalizedQueryFromAst(realRoots, normalizedFieldsByFieldId, mergedFieldsByNormalizedField);
    }

    private void fixUpParentReference(NormalizedField rootNormalizedField) {
        for (NormalizedField child : rootNormalizedField.getChildren()) {
            child.replaceParent(rootNormalizedField);
        }
    }


    private NormalizedField buildFieldWithChildren(NormalizedField field,
                                                   MergedField mergedField,
                                                   FieldCollectorNormalizedQuery fieldCollector,
                                                   FieldCollectorNormalizedQueryParams fieldCollectorNormalizedQueryParams,
                                                   Map<Field, List<NormalizedField>> normalizedFieldsByFieldId,
                                                   Map<NormalizedField, MergedField> mergedFieldsByNormalizedField,
                                                   int curLevel) {
        CollectFieldResult fieldsWithoutChildren = fieldCollector.collectFields(fieldCollectorNormalizedQueryParams, field, mergedField, curLevel + 1);
        List<NormalizedField> realChildren = new ArrayList<>();
        for (NormalizedField fieldWithoutChildren : fieldsWithoutChildren.getChildren()) {
            MergedField mergedFieldForChild = fieldsWithoutChildren.getMergedFieldByNormalized().get(fieldWithoutChildren);
            NormalizedField realChild = buildFieldWithChildren(fieldWithoutChildren, mergedFieldForChild, fieldCollector, fieldCollectorNormalizedQueryParams, normalizedFieldsByFieldId, mergedFieldsByNormalizedField, curLevel + 1);
            fixUpParentReference(realChild);
            mergedFieldsByNormalizedField.put(realChild, mergedFieldForChild);
            realChildren.add(realChild);

            updateByIdMap(realChild, mergedFieldForChild, normalizedFieldsByFieldId);
        }
        return field.transform(builder -> builder.children(realChildren));
    }

    private void updateByIdMap(NormalizedField normalizedField, MergedField mergedField, Map<Field, List<NormalizedField>> normalizedFieldsByFieldId) {
        for (Field astField : mergedField.getFields()) {
//            String id = NodeId.getId(astField);
//            String id = String.astField.hashCode();
            normalizedFieldsByFieldId.computeIfAbsent(astField, ignored -> new ArrayList<>());
            normalizedFieldsByFieldId.get(astField).add(normalizedField);
        }
    }
}
