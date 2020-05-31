package graphql.consulting.batched.normalized;

import graphql.Internal;
import graphql.execution.MergedField;
import graphql.language.Field;

import java.util.List;
import java.util.Map;

@Internal
public class NormalizedQueryFromAst {

    private final List<NormalizedField> topLevelFields;
    private final Map<Field, List<NormalizedField>> normalizedFieldsByFieldNode;
    private final Map<NormalizedField, MergedField> mergedFieldByNormalizedFields;

    public NormalizedQueryFromAst(List<NormalizedField> topLevelFields,
                                  Map<Field, List<NormalizedField>> normalizedFieldsByFieldNode,
                                  Map<NormalizedField, MergedField> mergedFieldByNormalizedFields) {
        this.topLevelFields = topLevelFields;
        this.normalizedFieldsByFieldNode = normalizedFieldsByFieldNode;
        this.mergedFieldByNormalizedFields = mergedFieldByNormalizedFields;
    }

    public List<NormalizedField> getTopLevelFields() {
        return topLevelFields;
    }

    public Map<Field, List<NormalizedField>> getNormalizedFieldsByFieldNode() {
        return normalizedFieldsByFieldNode;
    }

    public List<NormalizedField> getNormalizedFieldsByFieldNode(Field field) {
        return normalizedFieldsByFieldNode.get(field);
    }

    public Map<NormalizedField, MergedField> getMergedFieldByNormalizedFields() {
        return mergedFieldByNormalizedFields;
    }

//    public List<String> getFieldIds(NormalizedField NormalizedField) {
//        MergedField mergedField = mergedFieldByNormalizedFields.get(NormalizedField);
//        return NodeId.getIds(mergedField);
//    }
}
