package org.globsframework.view.filter.model;

import org.globsframework.metamodel.GlobType;
import org.globsframework.metamodel.GlobTypeLoaderFactory;
import org.globsframework.metamodel.annotations.Targets;
import org.globsframework.metamodel.fields.GlobArrayUnionField;
import org.globsframework.model.Glob;
import org.globsframework.view.filter.FilterBuilder;
import org.globsframework.view.filter.FilterImpl;

import java.util.Map;

public class OrFilterType {
    public static GlobType TYPE;

    @Targets({OrFilterType.class, AndFilterType.class, EqualType.class, NotEqualType.class,
            GreaterOrEqualType.class, StrictlyGreaterType.class,
            StrictlyLessType.class, LessOrEqualType.class, ContainsType.class})
    public static GlobArrayUnionField filters;

    static {
        GlobTypeLoaderFactory.create(OrFilterType.class)
                .register(FilterBuilder.class, new FilterBuilder() {
                    public FilterImpl.IsSelected create(Glob filter, GlobType rootType, Map<String, Glob> dico){
                        Glob[] globs = filter.get(filters);
                        FilterImpl.IsSelected or[] = new FilterImpl.IsSelected[globs.length];
                        for (int i = 0, globsLength = globs.length; i < globsLength; i++) {
                            Glob glob = globs[i];
                            or[i] = glob.getType().getRegistered(FilterBuilder.class)
                                    .create(glob, rootType, dico);
                        }
                        return glob -> {
                            for (FilterImpl.IsSelected isSelected : or) {
                                if (isSelected.isSelected(glob)) {
                                    return true;
                                }
                            }
                            return false;
                        };
                    }
                }).load();
    }
}
