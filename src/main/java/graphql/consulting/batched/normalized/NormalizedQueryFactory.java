package graphql.consulting.batched.normalized;

import graphql.Internal;
import graphql.consulting.batched.normalized.FieldCollectorNormalizedQuery.CollectFieldResult;
import graphql.execution.MergedField;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.NodeUtil;
import graphql.schema.FieldCoordinates;
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

        CollectFieldResult topLevelFields = fieldCollector.collectFromOperation(parameters, getOperationResult.operationDefinition, graphQLSchema.getQueryType());

        Map<Field, List<NormalizedField>> fieldToNormalizedField = new LinkedHashMap<>();
        Map<NormalizedField, MergedField> normalizedFieldToMergedField = new LinkedHashMap<>();
        Map<FieldCoordinates, List<NormalizedField>> coordinatesToNormalizedFields = new LinkedHashMap<>();

        List<NormalizedField> realRoots = new ArrayList<>();

        for (NormalizedField topLevel : topLevelFields.getChildren()) {

            MergedField mergedField = topLevelFields.getMergedFieldByNormalized().get(topLevel);
            NormalizedField realTopLevel = buildFieldWithChildren(topLevel, mergedField, fieldCollector, parameters, fieldToNormalizedField, normalizedFieldToMergedField, coordinatesToNormalizedFields, 1);
            fixUpParentReference(realTopLevel);

            normalizedFieldToMergedField.put(realTopLevel, mergedField);
            FieldCoordinates coordinates = FieldCoordinates.coordinates(realTopLevel.getObjectType(), realTopLevel.getFieldDefinition());
            coordinatesToNormalizedFields.computeIfAbsent(coordinates, k -> new ArrayList<>()).add(realTopLevel);
            updateByAstFieldMap(realTopLevel, mergedField, fieldToNormalizedField);
            realRoots.add(realTopLevel);
        }
        return new NormalizedQueryFromAst(realRoots, fieldToNormalizedField, normalizedFieldToMergedField, coordinatesToNormalizedFields);
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
                                                   Map<Field, List<NormalizedField>> fieldToMergedField,
                                                   Map<NormalizedField, MergedField> normalizedFieldToMergedField,
                                                   Map<FieldCoordinates, List<NormalizedField>> coordinatesToNormalizedFields,
                                                   int curLevel) {
        CollectFieldResult fieldsWithoutChildren = fieldCollector.collectFields(fieldCollectorNormalizedQueryParams, field, mergedField, curLevel + 1);
        List<NormalizedField> realChildren = new ArrayList<>();
        for (NormalizedField fieldWithoutChildren : fieldsWithoutChildren.getChildren()) {
            MergedField mergedFieldForChild = fieldsWithoutChildren.getMergedFieldByNormalized().get(fieldWithoutChildren);
            NormalizedField realChild = buildFieldWithChildren(fieldWithoutChildren, mergedFieldForChild, fieldCollector, fieldCollectorNormalizedQueryParams, fieldToMergedField, normalizedFieldToMergedField, coordinatesToNormalizedFields, curLevel + 1);
            fixUpParentReference(realChild);

            normalizedFieldToMergedField.put(realChild, mergedFieldForChild);
            FieldCoordinates coordinates = FieldCoordinates.coordinates(realChild.getObjectType(), realChild.getFieldDefinition());
            coordinatesToNormalizedFields.computeIfAbsent(coordinates, k -> new ArrayList<>()).add(realChild);

            realChildren.add(realChild);

            updateByAstFieldMap(realChild, mergedFieldForChild, fieldToMergedField);
        }
        return field.transform(builder -> builder.children(realChildren));
    }

    private void updateByAstFieldMap(NormalizedField normalizedField, MergedField mergedField, Map<Field, List<NormalizedField>> fieldToNormalizedField) {
        for (Field astField : mergedField.getFields()) {
            fieldToNormalizedField.computeIfAbsent(astField, ignored -> new ArrayList<>()).add(normalizedField);
        }
    }
}
