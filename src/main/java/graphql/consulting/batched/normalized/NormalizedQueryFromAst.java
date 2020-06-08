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
    private final Map<NormalizedField, MergedField> normalizedFieldToMergedField;

    public NormalizedQueryFromAst(List<NormalizedField> topLevelFields,
                                  Map<Field, List<NormalizedField>> normalizedFieldsByFieldNode,
                                  Map<NormalizedField, MergedField> normalizedFieldToMergedField) {
        this.topLevelFields = topLevelFields;
        this.normalizedFieldsByFieldNode = normalizedFieldsByFieldNode;
        this.normalizedFieldToMergedField = normalizedFieldToMergedField;
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

    public Map<NormalizedField, MergedField> getNormalizedFieldToMergedField() {
        return normalizedFieldToMergedField;
    }

    public MergedField getMergedField(NormalizedField normalizedField) {
        return normalizedFieldToMergedField.get(normalizedField);
    }

}
