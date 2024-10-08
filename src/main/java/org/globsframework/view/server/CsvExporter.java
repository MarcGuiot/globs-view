package org.globsframework.view.server;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobTypeBuilder;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.utils.collections.Pair;
import org.globsframework.csv.ExportBySize;
import org.globsframework.view.ViewBuilderImpl;
import org.globsframework.view.model.ViewBreakdown;
import org.globsframework.view.model.ViewOutput;
import org.globsframework.view.model.ViewRequestType;

import java.util.*;
import java.util.function.Consumer;

public class CsvExporter {
    final Consumer<Glob> export;
    final StringField[] breakdownFields;
    final List<Pair<Field, Field>> copy;
    final GlobField outputField;
    final StringField breakdownField;
    final GlobArrayField children;
    final boolean leafOnly;

    CsvExporter(Consumer<Glob> export, StringField[] breakdownFields, List<Pair<Field, Field>> copy, GlobField outputField,
                StringField breakdownField, GlobArrayField children, boolean leafOnly) {
        this.export = export;
        this.breakdownFields = breakdownFields;
        this.copy = copy;
        this.outputField = outputField;
        this.breakdownField = breakdownField;
        this.children = children;
        this.leafOnly = leafOnly;
    }

    public static void toCsv(Glob request, Glob root, boolean leafOnly, ExportBySize.LineWriter writer, ExportBySize exportBySize, boolean withHeader) {
        Glob[] breakdowns = request.getOrEmpty(ViewRequestType.breakdowns);
        Glob[] output = request.getOrEmpty(ViewRequestType.output);
        GlobTypeBuilder globTypeBuilder = new DefaultGlobTypeBuilder("CSV");
        StringField[] breakdownFields = new StringField[breakdowns.length];
        Set<String> uniqueName = new HashSet<>();
        for (int i = 0; i < breakdowns.length; i++) {
            Glob breakdown = breakdowns[i];
//            breakdownFields[i] = globTypeBuilder.declareStringField(breakdown.get(ViewBreakdown.aliasName, breakdown.get(ViewBreakdown.uniqueName)));
            StringField useField;
            if (breakdown.getOptNotEmpty(ViewBreakdown.aliasName).isPresent() && uniqueName.add(breakdown.get(ViewBreakdown.aliasName))) {
                useField = ViewBreakdown.aliasName;
            } else {
                useField = ViewBreakdown.uniqueName;
            }
            breakdownFields[i] = globTypeBuilder.declareStringField(breakdown.get(useField));
        }
        GlobType viewType = root.getType();
        GlobField outputField = (GlobField) viewType.getField(ViewBuilderImpl.OUTPUT);
        GlobType outputType = outputField.getTargetType();
        List<Pair<Field, Field>> copy = new ArrayList<>();
        for (Glob o : output) {
            Field field = outputType.getField(o.get(ViewOutput.name));
            Field targetCsvOutput = globTypeBuilder.declare(field.getName(), field.getDataType(), Collections.emptyList());
            copy.add(Pair.makePair(field, targetCsvOutput));
        }
        GlobType csvOutput = globTypeBuilder.get();
        if (withHeader) {
            exportBySize.exportHeader(csvOutput, writer);
        }
        Consumer<Glob> export = exportBySize.export(writer);
        CsvExporter csvExporter = new CsvExporter(export, breakdownFields, copy, outputField,
                viewType.getField(ViewBuilderImpl.NAME).asStringField(),
                (GlobArrayField) viewType.getField(ViewBuilderImpl.CHILD_FIELD_NAME), leafOnly);
        csvExporter.scan(csvOutput.instantiate(), root, -1);
    }

    private void scan(MutableGlob current, Glob node, int level) {
        MutableGlob sub = current; //.duplicate();
        if (level >= 0) {
            Object value = node.getValue(breakdownField);
            if (value != null) {
                sub.setValue(breakdownFields[level], value);
            } else {
                sub.unset(breakdownFields[level]);
            }
        }
        if (!leafOnly || breakdownFields.length == level + 1) {
            Glob output = node.get(outputField);
            if (output != null) {
                for (Pair<Field, Field> fieldFieldPair : copy) {
                    Object value = output.getValue(fieldFieldPair.getFirst());
                    if (value != null) {
                        sub.setValue(fieldFieldPair.getSecond(), value);
                    } else {
                        sub.unset(fieldFieldPair.getSecond());
                    }
                }
            } else {
                for (Pair<Field, Field> fieldFieldPair : copy) {
                    sub.unset(fieldFieldPair.getSecond());
                }
            }
            export.accept(sub);
        }
        for (Glob glob : node.getOrEmpty(children)) {
            scan(sub, glob, level + 1);
        }
        if (level >= 0) {
            sub.unset(breakdownFields[level]);
        }
    }
}
