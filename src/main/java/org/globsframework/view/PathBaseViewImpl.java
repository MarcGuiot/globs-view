package org.globsframework.view;

import org.apache.logging.log4j.util.Strings;
import org.globsframework.metamodel.Field;
import org.globsframework.metamodel.GlobType;
import org.globsframework.metamodel.fields.*;
import org.globsframework.model.Glob;
import org.globsframework.model.MutableGlob;
import org.globsframework.utils.Ref;
import org.globsframework.utils.collections.Pair;
import org.globsframework.view.filter.Filter;
import org.globsframework.view.filter.FilterImpl;
import org.globsframework.view.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

public class PathBaseViewImpl implements View {
    private final static Logger LOGGER = LoggerFactory.getLogger(PathBaseViewImpl.class);
    private final Node rootNode;
    private final AggOutput agg;
    private final StringField nameField;
    private final StringField nodeNameField;
    private final SimpleGraph<Boolean> wanted = new SimpleGraph<>("", true);
    private final Map<String, Glob> aliasToDico;
    private final Glob dictionary;
    private final Glob viewRequestType;
    private final GlobType breakdownDownType;
    private final GlobType outputType;
    private final GlobField outputField;
    private final GlobArrayField childrenField;
    private Filter filter;

    public PathBaseViewImpl(Glob viewRequestType, GlobType breakdownDownType, GlobType outputType, Glob dictionary) {
        this.viewRequestType = viewRequestType;
        this.breakdownDownType = breakdownDownType;
        this.outputType = outputType;
        rootNode = new DefaultNode("root", null, "", outputType.getFields().length == 0 ? null : outputType.instantiate());
        agg = createAgg(outputType);
        outputField = (GlobField) breakdownDownType.getField(ViewBuilderImpl.OUTPUT);
        childrenField = (GlobArrayField) breakdownDownType.getField(ViewBuilderImpl.CHILD_FIELD_NAME);
        nameField = breakdownDownType.getField(ViewBuilderImpl.NAME).asStringField();
        nodeNameField = breakdownDownType.getField(ViewBuilderImpl.NODE_NAME).asStringField();
        this.dictionary = dictionary;
        aliasToDico = initWanted();
        registerWantedField(aliasToDico);
    }

    public Append getAppender(GlobType globType) {
        ArrayDeque<Path> stackType = new ArrayDeque<>();
        stackType.add(new Path(new String[0], globType));

        Glob[] breakdowns = viewRequestType.getOrEmpty(ViewRequestType.breakdowns);

        Glob globFilter = viewRequestType.get(ViewRequestType.filter);
        if (globFilter!= null) {
            filter = new FilterImpl(globType, globFilter, aliasToDico);
        }
        else {
            filter = source -> true;
        }

        NextPath nextPath = createNodeBuilder(aliasToDico, 0, breakdowns, stackType);
        if (nextPath == null) {
            return new Append() {
                public void add(Glob glob) {
                }

                public void complete() {
                }
            };
        }
        return new Append() {
            final Glob[] stack = new Glob[stackType.size()];

            public void add(Glob glob) {
                if (filter.isFiltered(glob)) {
                    stack[0] = glob;
                    nextPath.push(rootNode, stack);
                }
            }

            public void complete() {

            }
        };
    }

    private Map<String, Glob> initWanted() {
        Glob[] dico = dictionary.getOrEmpty(DictionaryType.breakdowns);
        Map<String, Glob> aliasToDico = new HashMap<>();
        for (Glob glob : dico) {
            if (aliasToDico.put(glob.get(SimpleBreakdown.uniqueName), glob) != null) {
                String message = "Duplicate alias : '" + glob.get(SimpleBreakdown.uniqueName) + "'";
                LOGGER.error(message);
                throw new RuntimeException(message);
            }
        }
        return aliasToDico;
    }

    private void registerWantedField(Map<String, Glob> aliasToDico) {
        Glob[] breakdowns = viewRequestType.getOrEmpty(ViewRequestType.breakdowns);
        for (Glob breakdown : breakdowns) {
            Ref<SimpleGraph<Boolean>> tmp = new Ref<>(wanted);
            Glob br = aliasToDico.get(breakdown.get(ViewBreakdown.uniqueName));
            if (br == null) {
                String message = "Breakdown " + breakdown.get(ViewBreakdown.uniqueName) + " not found.";
                LOGGER.error(message);
                throw new RuntimeException(message);
            }
            Stream.concat(Arrays.stream(br.getOrEmpty(SimpleBreakdown.path)), Stream.of(br.get(SimpleBreakdown.fieldName))).forEach(s -> {
                tmp.set(tmp.get().getOrCreate(s, Boolean.TRUE));
            });
        }
        Glob[] outs = viewRequestType.getOrEmpty(ViewRequestType.output);
        for (Glob out : outs) {
            Ref<SimpleGraph<Boolean>> tmp = new Ref<>(wanted);
            Glob brk = aliasToDico.get(out.get(ViewOutput.uniqueName));
            if (brk == null) {
                String message = "Output " + out.get(ViewBreakdown.uniqueName) + " not found.";
                LOGGER.error(message);
                throw new RuntimeException(message);
            }
            Stream.concat(Arrays.stream(brk.getOrEmpty(SimpleBreakdown.path)), Stream.of(brk.get(SimpleBreakdown.fieldName))).forEach(s -> {
                tmp.set(tmp.get().getOrCreate(s, Boolean.TRUE));
            });
        }
    }

    public Node getRootNode() {
        return rootNode;
    }

    public Glob toGlob() {
        return computeOutput(rootNode);
    }

    public Accepted getAccepter() {

        SimpleGraph.Visitor<Boolean> visitor = new SimpleGraph.Visitor<>(wanted, false);
        return new Accepted() {
            public void enter(String name) {
                visitor.enter(name);
            }

            public boolean wanted() {
                return visitor.getValue();
            }

            public void leave() {
                visitor.leave();
            }
        };
    }

    private Glob computeOutput(Node node) {
        MutableGlob gl = breakdownDownType.instantiate();
        gl.set(nodeNameField, node.getName());
        gl.set(nameField, node.getKeyAsString());
        MutableGlob output = node.getOutput();
        Map<Object, Node> children = node.getChildren();
        if (!children.isEmpty()) {
            agg.reset(output);
            Glob[] sub = new Glob[children.size()];
            int i = 0;
            Object[] key = children.keySet().toArray();
            Arrays.sort(key, (o1, o2) -> o1 != null && o2 != null ? ((Comparable) o1).compareTo(o2) : (o1 == null ? (o2 == null ? 0 : -1) : 1));

            for (Object k : key) {
                Glob glh = computeOutput(children.get(k));
                sub[i++] = glh;
                agg.fill(output, glh.get(outputField));
            }
            gl.set(childrenField, sub);
        }
        gl.set(outputField, output);
        return gl;
    }

    static class Path {
        GlobType globType;
        String[] path;

        public Path(String[] path, GlobType type) {
            this.path = path;
            globType = type;
        }

        public Path newPath(String name, GlobType currentType) {
            String[] ts = Arrays.copyOf(path, path.length + 1);
            ts[ts.length -1] = name;
            return new Path(ts, currentType);
        }
    }

    private NextPath createNodeBuilder(Map<String, Glob> dictionary, int index, Glob[] breakdowns, ArrayDeque<Path> stackType) {
        if (index == breakdowns.length) {
            FillOutput outputFiller = createOutputFiller(viewRequestType.getOrEmpty(ViewRequestType.output), outputType, stackType, dictionary);
            return new NextPath() {
                public void push(Node node, Glob[] stack) {
                    outputFiller.fill(node.getOutput(), stack);
                }
            };
        }
        String uniqueName = breakdowns[index].get(ViewBreakdown.uniqueName);
        Glob breakdown = dictionary.computeIfAbsent(uniqueName, s -> {
            String msg = "alias : '" + uniqueName + "' not found";
            LOGGER.error(msg);
            throw new RuntimeException(msg);
        });

        Path currentType = stackType.getLast();
        if (breakdown.getType() == SimpleBreakdown.TYPE) {
            String[] pathFromRoot = breakdown.get(SimpleBreakdown.path);
            String typeName = breakdown.get(SimpleBreakdown.typeName);
            String fieldName = breakdown.get(SimpleBreakdown.fieldName);
            if (Arrays.equals(currentType.path, pathFromRoot)) {
                Field field = currentType.globType.getField(fieldName);
                int stackIndex = stackType.size();
                NextPath nextPath = createNodeBuilder(dictionary, index + 1, breakdowns, stackType);
                NodeBuilder nodeBuilder;
                NodeCreator nodeCreator = createNodeCreator(breakdown, field);
                if (nextPath != null) {
                    nodeBuilder = new SimpleNodeBuilder(nextPath, nodeCreator, stackIndex - 1);
                } else {
                    FillOutput outputFiller = createOutputFiller(viewRequestType.getOrEmpty(ViewRequestType.output), outputType, stackType, dictionary);
                    nodeBuilder = new TerminalSimpleNodeBuilder(stackIndex - 1, outputFiller, nodeCreator);
                }
                return new DirectPath(nodeBuilder);
            }
            int i = 0;
            for (Path type : stackType) {
                if (Arrays.equals(type.path, pathFromRoot)) {
                    Field field = type.globType.getField(fieldName);
                    NextPath nextPath = createNodeBuilder(dictionary, index + 1, breakdowns, stackType);
                    NodeBuilder nodeBuilder;
                    NodeCreator nodeCreator = createNodeCreator(breakdown, field);
                    if (nextPath != null) {
                        nodeBuilder = new SimpleInStackNodeBuilder(nextPath, i, nodeCreator);
                    } else {
                        FillOutput outputFiller = createOutputFiller(viewRequestType.getOrEmpty(ViewRequestType.output), outputType, stackType, dictionary);
                        nodeBuilder = new TerminalSimpleInStackNodeBuilder(i, outputFiller, nodeCreator);
                    }
                    return new DirectPath(nodeBuilder);
                }
                i++;
            }
            {
                ArrayDeque<Pair<Field, Path>> path = new ArrayDeque<>();
                int globStartIndex = stackType.size();
                for (Iterator<Path> iterator = stackType.descendingIterator(); iterator.hasNext(); ) {
                    Path type = iterator.next();
                    findPathTo(type, pathFromRoot, path);
                    globStartIndex--;
                    if (!path.isEmpty()) {
                        break;
                    }
                }
                if (path.isEmpty()) {
                    return null;
                }
                Field[] fields = new Field[path.size()];
                int k = 0;
                for (Pair<Field, Path> typePair : path) {
                    stackType.add(typePair.getSecond());
                    fields[k] = typePair.getFirst();
                    ++k;
                }
                int stackIndex = stackType.size();
                Path type = stackType.getLast();
                NextPath nextPath = createNodeBuilder(dictionary, index + 1, breakdowns, stackType);
                NodeBuilder nodeBuilder;
                Field field = type.globType.getField(fieldName);
                NodeCreator nodeCreator = createNodeCreator(breakdown, field);
                if (nextPath != null) {
                    nodeBuilder = new SimpleNodeBuilder(nextPath, nodeCreator, stackIndex - 1);
                } else {
                    FillOutput outputFiller = createOutputFiller(viewRequestType.getOrEmpty(ViewRequestType.output), outputType, stackType, dictionary);
                    nodeBuilder = new TerminalSimpleNodeBuilder(stackIndex - 1, outputFiller, nodeCreator);
                }
                return new MultiLevelPath(globStartIndex, stackIndex - path.size(), fields, nodeBuilder);
            }
        } else {
            throw new RuntimeException("TODO : only simpleNodeBuilder implemented");
        }
    }

    private NodeCreator createNodeCreator(Glob breakdown, Field field) {
        String nodeName = breakdown.get(SimpleBreakdown.aliasName);
        String typeName = breakdown.get(SimpleBreakdown.typeName);
        String fieldName = breakdown.get(SimpleBreakdown.fieldName);
        return new DefaultNodeCreator(outputType, Strings.isEmpty(nodeName) ? typeName + ":" + fieldName : nodeName, field);
    }

    private FillOutput createOutputFiller(Glob[] viewOutput, GlobType outputType, ArrayDeque<Path> stackType, Map<String, Glob> dictionary) {
        List<FillOutput> fillOutputs = new ArrayList<>();
        for (Glob outputInfo : viewOutput) {
            String name = outputInfo.get(ViewOutput.name);
            Field outputTypeField = outputType.getField(name);
            Glob brk = dictionary.computeIfAbsent(outputInfo.get(ViewOutput.uniqueName), s -> {
                String msg = outputInfo.get(ViewOutput.uniqueName) + " not found ";
                LOGGER.error(msg);
                throw new RuntimeException(msg);
            });
            FillOutput e = extractOutput(brk.get(SimpleBreakdown.path), brk.get(SimpleBreakdown.typeName), brk.get(SimpleBreakdown.fieldName), stackType, outputTypeField);
            if (e != null) {
                fillOutputs.add(e);
            }
        }
        return new ArraysFillOutput(fillOutputs.toArray(FillOutput[]::new));
    }

    // si la stack index != du dernier => il faudrait fournir ces chifres qu'a ce niveau: sinon les totaux sont faux.


    interface FillDirectOutput {
        void fill(MutableGlob output, Glob data);
    }


    private FillOutput extractOutput(String[] pathTo, String typeName, String fieldName, ArrayDeque<Path> stackType, Field field) {
        int stackIndex = 0;
        for (Path globType : stackType) {
            if (Arrays.equals(globType.path, pathTo)) {
                Field sourceField = globType.globType.getField(fieldName);
//                for (Field sourceField : globType.getFields()) {
                    if (sourceField.getName().equals(fieldName)) {
                        if (field instanceof DoubleField) {
                            return new InStackDoubleFillOutput(stackIndex, sourceField.asDoubleField(), field.asDoubleField());
                        } else if (field instanceof IntegerField) {
                            return new InStackIntegerFillOutput(stackIndex, sourceField.asIntegerField(), field.asIntegerField());
                        } else if (field instanceof LongField) {
                            return new InStackLongFillOutput( stackIndex, sourceField.asLongField(), field.asLongField());
                        } else {
                            throw new RuntimeException("Aggregation on " + field.getDataType() + " not implemented.");
                        }
                    }
//                }
            }
            stackIndex++;
        }

        // on cherche comment accéder a l'object a partir de la stack
        ArrayDeque<Pair<Field, Path>> path = new ArrayDeque<>();
        int globStartIndex = stackType.size();
        for (Iterator<Path> iterator = stackType.descendingIterator(); iterator.hasNext(); ) {
            Path type = iterator.next();
            findPathTo(type, pathTo, path);
            globStartIndex--;
            if (!path.isEmpty()) {
                break;
            }
        }
        if (path.isEmpty()) {
            LOGGER.error("output " + field.getName() + " not found");
            return null;
        }

        OnOutputScan fillOutput;
        Field sourceField = path.getLast().getSecond().globType.getField(fieldName);
        if (sourceField instanceof DoubleField) {
            fillOutput = new OnOutputScan() {
                public void scan(MutableGlob output, Glob data) {
                    output.set(field.asDoubleField(), data.get(sourceField.asDoubleField(), 0.) + output.get(field.asDoubleField(), 0.));
                }
            };
        } else if (sourceField instanceof IntegerField) {
            fillOutput = new OnOutputScan() {
                public void scan(MutableGlob output, Glob data) {
                    output.set(field.asIntegerField(), data.get(sourceField.asIntegerField(), 0) + output.get(field.asIntegerField(), 0));
                }
            };
        } else if (sourceField instanceof LongField) {
            fillOutput = new OnOutputScan() {
                public void scan(MutableGlob output, Glob data) {
                    output.set(field.asLongField(), data.get(sourceField.asLongField(), 0) + output.get(field.asLongField(), 0));
                }
            };
        } else {
            throw new RuntimeException("Aggregation on " + field.getDataType() + " not implemented.");
        }

        Field[] fields = new Field[path.size()];
        int k = 0;
        for (Pair<Field, Path> typePair : path) {
            fields[k] = typePair.getFirst();
            ++k;
        }
        return new StartOnOutputScan(scanForOutput(fields, 0, fillOutput), globStartIndex);
    }

    interface OnField {
        void scan(Glob current, Node node, Glob[] stack);
    }

    interface OnOutputScan {

        void scan(MutableGlob output, Glob current);
    }

    static class StartOnOutputScan implements FillOutput {
        private final OnOutputScan onOutputScan;
        private final int from;

        StartOnOutputScan(OnOutputScan onOutputScan, int from) {
            this.onOutputScan = onOutputScan;
            this.from = from;
        }

        public void fill(MutableGlob output, Glob[] stack) {
            onOutputScan.scan(output, stack[from]);
        }
    }

    static class OnGlobFieldOutputScan implements OnOutputScan {
        private final GlobField field;
        private PathBaseViewImpl.OnOutputScan onOutputScan;

        OnGlobFieldOutputScan(OnOutputScan onOutputScan, GlobField field) {
            this.onOutputScan = onOutputScan;
            this.field = field;
        }

        public void scan(MutableGlob output, Glob current) {
            Glob glob = current.get(field);
            if (glob != null) {
                onOutputScan.scan(output, glob);
            }
        }
    }

    static class OnGlobArrayFieldOutputScan implements OnOutputScan {
        private final GlobArrayField field;
        private PathBaseViewImpl.OnOutputScan onOutputScan;

        OnGlobArrayFieldOutputScan(OnOutputScan onOutputScan, GlobArrayField field) {
            this.onOutputScan = onOutputScan;
            this.field = field;
        }

        public void scan(MutableGlob output, Glob current) {
            Glob[] glob = current.getOrEmpty(field);
            for (Glob glob1 : glob) {
                onOutputScan.scan(output, glob1);
            }
        }
    }


    private OnOutputScan scanForOutput(Field[] fields, int fieldLevel, OnOutputScan fillOutput) {
        if (fields.length == fieldLevel) {
            return fillOutput;
        } else {
            Field field = fields[fieldLevel];
            if (field instanceof GlobField) {
                return new OnGlobFieldOutputScan(scanForOutput(fields, fieldLevel + 1, fillOutput), (GlobField) field);
            } else if (field instanceof GlobArrayField) {
                return new OnGlobArrayFieldOutputScan(scanForOutput(fields, fieldLevel + 1, fillOutput), (GlobArrayField) field);
            } else {
                throw new RuntimeException("BUG");
            }
        }
    }


    private AggOutput createAgg(GlobType outputType) {
        List<AggOutput> aggOutputs = new ArrayList<>();
        for (Field field : outputType.getFields()) {
            if (field instanceof DoubleField) {
                aggOutputs.add(new DoubleAggOutput((DoubleField) field));
            } else if (field instanceof IntegerField) {
                aggOutputs.add(new IntegerAggOutput((IntegerField) field));
            } else if (field instanceof LongField) {
                aggOutputs.add(new LongAggOutput((LongField) field));
            } else {
                throw new RuntimeException("No aggregation for " + field.getName());
            }
        }
        return new ArrayAggOutput(aggOutputs.toArray(AggOutput[]::new));
    }

    private static boolean findPathTo(Path currentPath, String[] pathFromRoot, Deque<Pair<Field, Path>> path) {
        if (Arrays.equals(currentPath.path, 0, currentPath.path.length, pathFromRoot, 0, currentPath.path.length)) {

            GlobType currentType = currentPath.globType;
            Path previousPath = currentPath;

            for (int i = currentPath.path.length; i < pathFromRoot.length; i++) {
                String s = pathFromRoot[i];
                Field field = currentType.getField(s);
                currentType = field.safeVisit(new FieldVisitor.AbstractWithErrorVisitor(){
                    GlobType type;

                    public void visitGlob(GlobField field) throws Exception {
                        type = field.getTargetType();
                    }

                    public void visitGlobArray(GlobArrayField field) throws Exception {
                        type = field.getTargetType();
                    }
                }).type;
                previousPath = previousPath.newPath(field.getName(), currentType);
                path.addLast(Pair.makePair(field, previousPath));
            }
            return true;
        }
        else {
            return false;
        }
    }

    interface NodeBuilder {
        void push(Node rootNode, Glob[] stack);
    }

    private interface NextPath {
        void push(Node node, Glob[] stack);
    }

    interface FillOutput {
        void fill(MutableGlob output, Glob[] stack);
    }

    interface AggOutput {
        void reset(MutableGlob output);

        void fill(MutableGlob output, Glob input);
    }

    static class InStackDoubleFillOutput implements FillOutput {

        final int stackPos;
        final DoubleField field;
        final DoubleField outputField;

        InStackDoubleFillOutput(int stackPos, DoubleField field, DoubleField outputField) {
            this.stackPos = stackPos;
            this.field = field;
            this.outputField = outputField;
        }

        public void fill(MutableGlob output, Glob[] stack) {
            output.set(outputField, stack[stackPos].get(field, 0.) + output.get(outputField, 0.));
        }
    }

    static class InStackIntegerFillOutput implements FillOutput {
        final int stackPos;
        final IntegerField field;
        final IntegerField outputField;

        InStackIntegerFillOutput(int stackPos, IntegerField field, IntegerField outputField) {
            this.stackPos = stackPos;
            this.field = field;
            this.outputField = outputField;
        }

        public void fill(MutableGlob output, Glob[] stack) {
            output.set(outputField, stack[stackPos].get(field, 0) + output.get(outputField, 0));
        }
    }

    static class InStackLongFillOutput implements FillOutput {
        final int stackPos;
        final LongField field;
        final LongField outputField;

        InStackLongFillOutput(int stackPos, LongField field, LongField outputField) {
            this.stackPos = stackPos;
            this.field = field;
            this.outputField = outputField;
        }

        public void fill(MutableGlob output, Glob[] stack) {
            output.set(outputField, stack[stackPos].get(field, 0) + output.get(outputField, 0));
        }
    }

    private static class DirectPath implements NextPath {
        final NodeBuilder nodeBuilder;

        public DirectPath(NodeBuilder nodeBuilder) {
            this.nodeBuilder = nodeBuilder;
        }

        public void push(Node node, Glob[] stack) {
            nodeBuilder.push(node, stack);
        }
    }

    private static class MultiLevelPath implements NextPath {
        final int level;
        final Field[] fields;
        final NodeBuilder nodeBuilder;
        final PathBaseViewImpl.OnField onField;
        private int fromLevel;

        public MultiLevelPath(int fromLevel, int level, Field[] fields, NodeBuilder nodeBuilder) {
            this.fromLevel = fromLevel;
            this.level = level;
            this.fields = fields;
            this.nodeBuilder = nodeBuilder;
            onField = scan(fields, 0, level);
        }

        public void push(Node node, Glob[] stack) {
            Glob current = stack[fromLevel];
            onField.scan(current, node, stack);
            stack[level] = null;
        }

        private PathBaseViewImpl.OnField scan(Field[] fields, int fieldLevel, int stackLevel) {
            if (fields.length == fieldLevel) {
                return new GlobOnField(nodeBuilder);
            } else {
                Field field = fields[fieldLevel];
                if (field instanceof GlobField) {
                    return new PathBaseViewImpl.FieldOnField(scan(fields, fieldLevel + 1, stackLevel + 1), (GlobField) field, stackLevel);
                } else if (field instanceof GlobArrayField) {
                    return new PathBaseViewImpl.FieldArrayOnField(scan(fields, fieldLevel + 1, stackLevel + 1), (GlobArrayField) field, stackLevel);
                } else {
                    throw new RuntimeException("BUG");
                }
            }
        }

        static class GlobOnField implements PathBaseViewImpl.OnField {
            private final NodeBuilder nodeBuilder;

            GlobOnField(NodeBuilder nodeBuilder) {
                this.nodeBuilder = nodeBuilder;
            }

            public void scan(Glob current, Node node, Glob[] stack) {
                nodeBuilder.push(node, stack);
            }
        }

    }

    private static class SimpleNodeBuilder implements NodeBuilder {
        private final NextPath nextPath;
        private final NodeCreator nodeCreator;
        private final int stackIndex;

        public SimpleNodeBuilder(NextPath nextPath, NodeCreator nodeCreator, int stackIndex) {
            this.nodeCreator = nodeCreator;
            this.nextPath = nextPath;
            this.stackIndex = stackIndex;
        }

        public void push(Node parentNode, Glob[] stack) {
            Glob current = stack[stackIndex];
            Node node = nodeCreator.getOrCreate(parentNode, current);
            nextPath.push(node, stack);
        }
    }

    private static class TerminalSimpleNodeBuilder implements NodeBuilder {
        private final int stackIndex;
        private final FillOutput outputFiller;
        private final NodeCreator nodeCreator;

        public TerminalSimpleNodeBuilder(int stackIndex, FillOutput outputFiller, NodeCreator nodeCreator) {
            this.stackIndex = stackIndex;
            this.outputFiller = outputFiller;
            this.nodeCreator = nodeCreator;
        }

        public void push(Node parentNode, Glob[] stack) {
            Glob current = stack[stackIndex];
            Node node = nodeCreator.getOrCreate(parentNode, current);
            outputFiller.fill(node.getOutput(), stack);
        }
    }

    private static class SimpleInStackNodeBuilder implements NodeBuilder {
        private final NextPath nextPath;
        private final int indexInStack;
        private final NodeCreator nodeCreator;

        public SimpleInStackNodeBuilder(NextPath nextPath, int indexInStack, NodeCreator nodeCreator) {
            this.nextPath = nextPath;
            this.indexInStack = indexInStack;
            this.nodeCreator = nodeCreator;
        }

        public void push(Node parentNode, Glob[] stack) {
            Node node = nodeCreator.getOrCreate(parentNode, stack[indexInStack]);
            nextPath.push(node, stack);
        }
    }

    private static class TerminalSimpleInStackNodeBuilder implements NodeBuilder {
        private final int indexInStack;
        private final NodeCreator nodeCreator;
        private FillOutput outputFiller;

        public TerminalSimpleInStackNodeBuilder(int indexInStack, FillOutput outputFiller, NodeCreator nodeCreator) {
            this.indexInStack = indexInStack;
            this.outputFiller = outputFiller;
            this.nodeCreator = nodeCreator;
        }

        public void push(Node parentNode, Glob[] stack) {
            Node node = nodeCreator.getOrCreate(parentNode, stack[indexInStack]);
            outputFiller.fill(node.getOutput(), stack);
        }
    }

    private static class ArraysFillOutput implements FillOutput {
        private final FillOutput[] outputs;

        public ArraysFillOutput(FillOutput[] outputs) {
            this.outputs = outputs;
        }

        public void fill(MutableGlob output, Glob[] stack) {
            for (FillOutput fillOutput : outputs) {
                fillOutput.fill(output, stack);
            }
        }
    }

    static class DoubleAggOutput implements AggOutput {
        final DoubleField outputField;

        DoubleAggOutput(DoubleField outputField) {
            this.outputField = outputField;
        }

        public void reset(MutableGlob output) {
            output.set(outputField, 0);
        }

        public void fill(MutableGlob output, Glob input) {
            output.set(outputField, input.get(outputField, 0) + output.get(outputField, 0));
        }
    }

    static class IntegerAggOutput implements AggOutput {
        final IntegerField outputField;

        IntegerAggOutput(IntegerField outputField) {
            this.outputField = outputField;
        }

        public void reset(MutableGlob output) {
            output.set(outputField, 0);
        }

        public void fill(MutableGlob output, Glob input) {
            output.set(outputField, input.get(outputField, 0) + output.get(outputField, 0));
        }
    }

    static class LongAggOutput implements AggOutput {
        final LongField outputField;

        LongAggOutput(LongField outputField) {
            this.outputField = outputField;
        }


        public void reset(MutableGlob output) {
            output.set(outputField, 0);
        }

        public void fill(MutableGlob output, Glob input) {
            output.set(outputField, input.get(outputField, 0) + output.get(outputField, 0));
        }
    }

    private static class ArrayAggOutput implements AggOutput {
        private final AggOutput[] aggOutput;

        public ArrayAggOutput(AggOutput[] aggOutput) {
            this.aggOutput = aggOutput;
        }

        public void reset(MutableGlob output) {
            for (AggOutput add : aggOutput) {
                add.reset(output);
            }
        }

        public void fill(MutableGlob output, Glob input) {
            for (AggOutput agg : aggOutput) {
                agg.fill(output, input);
            }
        }
    }

    static class FieldOnField implements OnField {
        final OnField next;
        final GlobField field;
        final int stackLevel;

        FieldOnField(OnField next, GlobField field, int stackLevel) {
            this.next = next;
            this.field = field;
            this.stackLevel = stackLevel;
        }

        public void scan(Glob current, Node node, Glob[] stack) {
            Glob glob = current.get(field);
            if (glob == null) {
                return;
            }
            stack[stackLevel] = glob;
            next.scan(glob, node, stack);
            stack[stackLevel] = null;
        }
    }

    static class FieldArrayOnField implements OnField {
        final OnField next;
        final GlobArrayField field;
        final int stackLevel;

        FieldArrayOnField(OnField next, GlobArrayField field, int stackLevel) {
            this.next = next;
            this.field = field;
            this.stackLevel = stackLevel;
        }

        public void scan(Glob current, Node node, Glob[] stack) {
            Glob[] globs = current.getOrEmpty(field);
            for (Glob glob : globs) {
                stack[stackLevel] = glob;
                next.scan(glob, node, stack);
                stack[stackLevel] = null;
            }
        }
    }
}
