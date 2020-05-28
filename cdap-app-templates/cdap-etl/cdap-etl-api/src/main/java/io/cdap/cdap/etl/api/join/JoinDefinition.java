/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.etl.api.join;

import io.cdap.cdap.api.annotation.Beta;
import io.cdap.cdap.api.data.schema.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Specifies how a join should be executed.
 */
@Beta
public class JoinDefinition {
  private final List<JoinField> selectedFields;
  private final List<JoinStage> stages;
  private final JoinCondition condition;
  private final Schema outputSchema;

  private JoinDefinition(List<JoinField> selectedFields, List<JoinStage> stages,
                         JoinCondition condition, Schema outputSchema) {
    this.stages = Collections.unmodifiableList(stages);
    this.selectedFields = Collections.unmodifiableList(new ArrayList<>(selectedFields));
    this.condition = condition;
    this.outputSchema = outputSchema;
  }

  public List<JoinField> getSelectedFields() {
    return selectedFields;
  }

  public List<JoinStage> getStages() {
    return stages;
  }

  public JoinCondition getCondition() {
    return condition;
  }

  public Schema getOutputSchema() {
    return outputSchema;
  }

  /**
   * @return a Builder to create a JoinSpecification.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builds a JoinSpecification.
   */
  public static class Builder {
    private final List<JoinStage> stages;
    private final List<JoinField> selectedFields;
    private JoinCondition condition;
    private String schemaName;
    private Schema outputSchema;

    private Builder() {
      stages = new ArrayList<>();
      selectedFields = new ArrayList<>();
      schemaName = null;
      condition = null;
    }

    public Builder select(List<JoinField> selectedFields) {
      this.selectedFields.clear();
      this.selectedFields.addAll(selectedFields);
      return this;
    }

    public Builder select(JoinField... fields) {
      return select(Arrays.asList(fields));
    }

    public Builder from(Collection<JoinStage> stages) {
      this.stages.clear();
      this.stages.addAll(stages);
      return this;
    }

    public Builder from(JoinStage... stages) {
      return from(Arrays.asList(stages));
    }

    public Builder on(JoinCondition condition) {
      this.condition = condition;
      return this;
    }

    public Builder setOutputSchemaName(@Nullable String name) {
      schemaName = name;
      return this;
    }

    /**
     * Set the output schema for the join. This should only be set if the input JoinStages do not contain known
     * schemas. The most common scenario here is when the input schemas are not known when the pipeline is deployed
     * due to macros.
     *
     * When all input schemas are known, if the expected output schema differs from this schema, an error
     * will be thrown.
     */
    public Builder setOutputSchema(Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    /**
     * @return a valid JoinDefinition
     *
     * @throws InvalidJoinException if the join is invalid
     */
    public JoinDefinition build() {
      if (selectedFields.isEmpty()) {
        throw new InvalidJoinException("At least one field must be selected.");
      }

      // validate the join stages
      if (stages.size() < 2) {
        throw new InvalidJoinException("At least two stages must be specified.");
      }

      if (stages.stream().allMatch(JoinStage::isBroadcast)) {
        throw new InvalidJoinException("Cannot broadcast all stages.");
      }

      // validate the join condition
      if (condition == null) {
        throw new InvalidJoinException("A join condition must be specified.");
      }
      condition.validate(stages);

      Schema generatedOutputSchema = getOutputSchema();
      if (generatedOutputSchema != null && outputSchema != null) {
        // verify that the plugin defined output schema is compatible with the actual output schema
        validateSchemaCompatibility(generatedOutputSchema, outputSchema);
      }

      return new JoinDefinition(selectedFields, stages, condition,
                                outputSchema == null ? generatedOutputSchema : outputSchema);
    }

    @Nullable
    private Schema getOutputSchema() {
      Set<String> outputFieldNames = new HashSet<>();
      List<Schema.Field> outputFields = new ArrayList<>(selectedFields.size());
      Map<String, JoinStage> stageMap = stages.stream()
        .collect(Collectors.toMap(JoinStage::getStageName, s -> s));

      for (JoinField field : selectedFields) {
        JoinStage joinStage = stageMap.get(field.getStageName());
        if (joinStage == null) {
          throw new InvalidJoinException(String.format(
            "Selected field '%s'.'%s' is invalid because stage '%s' is not part of the join.",
            field.getStageName(), field.getFieldName(), field.getStageName()));
        }
        Schema stageSchema = joinStage.getSchema();
        // schema is null if the schema is unknown
        // for example, when the pipeline is being deployed, the schema might not yet be known due to
        // macros not being evaluated yet.
        if (stageSchema == null) {
          return null;
        }
        Schema.Field schemaField = stageSchema.getField(field.getFieldName());
        if (schemaField == null) {
          throw new InvalidJoinException(String.format(
            "Selected field '%s'.'%s' is invalid because stage '%s' does not contain field '%s'.",
            field.getStageName(), field.getFieldName(), field.getStageName(), field.getFieldName()));
        }

        String outputFieldName = field.getAlias() == null ? field.getFieldName() : field.getAlias();
        if (!outputFieldNames.add(outputFieldName)) {
          throw new InvalidJoinException(String.format(
            "Field '%s' from stage '%s' is a duplicate. Set an alias to make it unique.",
            outputFieldName, field.getStageName()));
        }
        Schema outputFieldSchema = schemaField.getSchema();
        if (!joinStage.isRequired() && !outputFieldSchema.isNullable()) {
          outputFieldSchema = Schema.nullableOf(outputFieldSchema);
        }
        outputFields.add(Schema.Field.of(outputFieldName, outputFieldSchema));
      }

      return Schema.recordOf(schemaName == null ? "joined" : schemaName, outputFields);
    }

    /**
     * Validate that the first schema is compatible with the second. Schema s1 is compatible with schema s2 if all
     * the fields in s1 are present in s2 with the same type, or with the nullable version of the type. In addition,
     * s2 cannot contain any fields that are not present in s1.
     *
     * @param expected the expected schema
     * @param provided the provided schema
     */
    private static void validateSchemaCompatibility(Schema expected, Schema provided) {
      Set<String> missingFields = new HashSet<>();
      for (Schema.Field expectedField : expected.getFields()) {
        Schema.Field providedField = provided.getField(expectedField.getName());
        if (providedField == null) {
          missingFields.add(expectedField.getName());
          continue;
        }
        Schema expectedFieldSchema = expectedField.getSchema();
        Schema providedFieldSchema = providedField.getSchema();
        boolean expectedIsNullable = expectedFieldSchema.isNullable();
        boolean providedIsNullable = providedFieldSchema.isNullable();
        expectedFieldSchema = expectedIsNullable ? expectedFieldSchema.getNonNullable() : expectedFieldSchema;
        providedFieldSchema = providedIsNullable ? providedFieldSchema.getNonNullable() : providedFieldSchema;
        if (expectedFieldSchema.getType() != providedFieldSchema.getType()) {
          throw new InvalidJoinException(String.format(
            "Provided schema does not match expected schema. Field '%s' is a '%s' but is expected to be a '%s'",
            expectedField.getName(), expectedFieldSchema.getDisplayName(), providedFieldSchema.getDisplayName()));
        }
        if (expectedIsNullable && !providedIsNullable) {
          throw new InvalidJoinException(String.format(
            "Provided schema does not match expected schema. Field '%s' should be nullable",
            expectedField.getName()));
        }
      }

      if (!missingFields.isEmpty()) {
        throw new InvalidJoinException("Provided schema is missing fields: " + String.join(", ", missingFields));
      }

      Set<String> extraFields = new HashSet<>();
      for (Schema.Field providedField : provided.getFields()) {
        if (expected.getField(providedField.getName()) == null) {
          extraFields.add(providedField.getName());
        }
      }

      if (!extraFields.isEmpty()) {
        throw new InvalidJoinException("Provided schema has extra fields: " + String.join(", ", missingFields));
      }
    }
  }

}
