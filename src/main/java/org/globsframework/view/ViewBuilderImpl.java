package org.globsframework.view;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.impl.DefaultGlobTypeBuilder;
import org.globsframework.core.metamodel.type.DataType;
import org.globsframework.core.model.Glob;
import org.globsframework.view.model.DictionaryType;
import org.globsframework.view.model.SimpleBreakdown;
import org.globsframework.view.model.ViewOutput;
import org.globsframework.view.model.ViewRequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;

public class ViewBuilderImpl implements ViewBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ViewBuilderImpl.class);
    public static final String NAME = "name";
    public static final String NODE_NAME = "nodeName";
    public static final String OUTPUT = "output";
    public static final String CHILD_FIELD_NAME = "__children__";
    private final GlobType outputType;
    private final GlobType breakdownType;
    private final Glob dictionary;
    private final Glob viewRequestType;
    private final int maxNodeCount;

    public ViewBuilderImpl(Glob dictionary, Glob viewRequestType, int maxNodeCount) {
        this.dictionary = dictionary;
        this.viewRequestType = viewRequestType;
        this.maxNodeCount = maxNodeCount;
        Glob[] viewOutput = viewRequestType.getOrEmpty(ViewRequestType.output);
        GlobTypeBuilder outputTypeBuilder = new DefaultGlobTypeBuilder("output");
        Glob[] globs = dictionary.getOrEmpty(DictionaryType.breakdowns);
        for (Glob o : viewOutput) {
            String uniqueName = o.get(ViewOutput.uniqueName);
            Glob type = Arrays.stream(globs).filter(glob -> glob.get(SimpleBreakdown.uniqueName).equals(uniqueName)).findFirst()
                    .orElseThrow(() -> {
                        String msg = uniqueName + " no found";
                        LOGGER.error(msg);
                        return new RuntimeException(msg);
                    });
            String outputAlias = o.get(ViewOutput.name, o.get(ViewOutput.uniqueName));
            outputTypeBuilder.declare(outputAlias, DataType.valueOf(type.get(SimpleBreakdown.outputTypeName, type.get(SimpleBreakdown.nativeType))),
                    Collections.emptyList());
        }
        GlobTypeBuilder nodeTypeBuilder = new DefaultGlobTypeBuilder("Node");
        nodeTypeBuilder.declareStringField(NAME);
        nodeTypeBuilder.declareStringField(NODE_NAME);
        nodeTypeBuilder.declareGlobArrayField(CHILD_FIELD_NAME, nodeTypeBuilder.unCompleteType());
        nodeTypeBuilder.declareGlobField(OUTPUT, outputTypeBuilder.get());
        breakdownType = nodeTypeBuilder.get();
        outputType = outputTypeBuilder.get();
    }

    public View createView() {
        return new PathBaseViewImpl(viewRequestType, breakdownType, outputType, dictionary, maxNodeCount);
    }

    public GlobType getBreakdownType() {
        return breakdownType;
    }

    public GlobType getOutputType() {
        return outputType;
    }
}
