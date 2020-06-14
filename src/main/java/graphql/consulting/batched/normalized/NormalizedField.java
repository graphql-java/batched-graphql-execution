package graphql.consulting.batched.normalized;

import graphql.Internal;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnmodifiedType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static graphql.Assert.assertNotNull;
import static graphql.schema.GraphQLTypeUtil.simplePrint;
import static java.util.Collections.singletonList;

@Internal
public class NormalizedField {
private final String alias;
    private final Map<String, Object> arguments;
    private final GraphQLObjectType objectType;
    private final GraphQLFieldDefinition fieldDefinition;
    private final List<NormalizedField> children;
    private final boolean isConditional;
    private final int level;
    private final List<String> path;
    private NormalizedField parent;


    private NormalizedField(Builder builder) {
        this.alias = builder.alias;
        this.arguments = builder.arguments;
        this.objectType = builder.objectType;
        this.fieldDefinition = assertNotNull(builder.fieldDefinition);
        this.children = builder.children;
        this.level = builder.level;
        this.parent = builder.parent;
        // can be null for the top level fields
        if (parent == null) {
            this.isConditional = false;
            this.path = singletonList(getResultKey());
        } else {
            GraphQLUnmodifiedType parentType = GraphQLTypeUtil.unwrapAll(parent.getFieldDefinition().getType());
            this.isConditional = parentType != this.objectType;
            this.path = new ArrayList<>(parent.getPath());
            this.path.add(getResultKey());
        }
    }

    /**
     * All merged fields have the same name.
     *
     * WARNING: This is not always the key in the execution result, because of possible aliases. See {@link #getResultKey()}
     *
     * @return the name of of the merged fields.
     */
    public String getName() {
        return getFieldDefinition().getName();
    }

    /**
     * Returns the key of this MergedFieldWithType for the overall result.
     * This is either an alias or the FieldWTC name.
     *
     * @return the key for this MergedFieldWithType.
     */
    public String getResultKey() {
        if (alias != null) {
            return alias;
        }
        return getName();
    }

    public String getAlias() {
        return alias;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }


    public static Builder newQueryExecutionField() {
        return new Builder();
    }


    public GraphQLFieldDefinition getFieldDefinition() {
        return fieldDefinition;
    }


    public NormalizedField transform(Consumer<Builder> builderConsumer) {
        Builder builder = new Builder(this);
        builderConsumer.accept(builder);
        return builder.build();
    }

    public GraphQLObjectType getObjectType() {
        return objectType;
    }


    public String print() {
        StringBuilder result = new StringBuilder();
        if (getAlias() != null) {
            result.append(getAlias()).append(": ");
        }
        return result + objectType.getName() + "." + fieldDefinition.getName() + ": " + simplePrint(fieldDefinition.getType()) +
                " (conditional: " + this.isConditional + ")";
    }

    public List<NormalizedField> getChildren() {
        return children;
    }

    public int getLevel() {
        return level;
    }

    public NormalizedField getParent() {
        return parent;
    }

    public void replaceParent(NormalizedField newParent) {
        this.parent = newParent;
    }

    public List<String> getPath() {
        return path;
    }

    public boolean isIntrospectionField() {
        return getFieldDefinition().getName().startsWith("__") || getObjectType().getName().startsWith("__");
    }

    @Override
    public String toString() {
        return "NormalizedField{" +
                "alias='" + alias + '\'' +
                ", objectType.name=" + objectType.getName() +
                ", fieldDefinition.name=" + fieldDefinition.getName() +
                ", level=" + level +
                ", path=" + path +
                '}';
    }

    public void traverseSubTree(Consumer<NormalizedField> consumer) {
        this.getChildren().forEach(child -> {
            traverseImpl(child, consumer);
        });
    }

    private void traverseImpl(NormalizedField root, Consumer<NormalizedField> consumer) {
        consumer.accept(root);
        root.getChildren().forEach(child -> {
            traverseImpl(child, consumer);
        });
    }

    public static class Builder {
        private GraphQLObjectType objectType;
        private GraphQLFieldDefinition fieldDefinition;
        private List<NormalizedField> children = new ArrayList<>();
        private int level;
        private NormalizedField parent;
        private String alias;
        private Map<String, Object> arguments = new LinkedHashMap<>();

        private Builder() {

        }

        private Builder(NormalizedField existing) {
            this.alias = existing.alias;
            this.arguments = existing.arguments;
            this.objectType = existing.getObjectType();
            this.fieldDefinition = existing.getFieldDefinition();
            this.children = existing.getChildren();
            this.level = existing.getLevel();
            this.parent = existing.getParent();
        }

        public Builder objectType(GraphQLObjectType objectType) {
            this.objectType = objectType;
            return this;
        }


        public Builder alias(String alias) {
            this.alias = alias;
            return this;
        }

        public Builder arguments(Map<String, Object> arguments) {
            this.arguments = arguments;
            return this;
        }


        public Builder fieldDefinition(GraphQLFieldDefinition fieldDefinition) {
            this.fieldDefinition = fieldDefinition;
            return this;
        }


        public Builder children(List<NormalizedField> children) {
            this.children.clear();
            this.children.addAll(children);
            return this;
        }

        public Builder level(int level) {
            this.level = level;
            return this;
        }

        public Builder parent(NormalizedField parent) {
            this.parent = parent;
            return this;
        }

        public NormalizedField build() {
            return new NormalizedField(this);
        }


    }

}
