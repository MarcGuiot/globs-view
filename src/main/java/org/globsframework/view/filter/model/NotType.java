package org.globsframework.view.filter.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.Targets;
import org.globsframework.core.metamodel.fields.GlobUnionField;
import org.globsframework.core.model.Glob;
import org.globsframework.view.filter.FilterBuilder;
import org.globsframework.view.filter.FilterImpl;
import org.globsframework.view.filter.Rewrite;
import org.globsframework.view.filter.WantedField;

public class NotType {
    public static GlobType TYPE;

    @Targets({OrFilterType.class, AndFilterType.class, EqualType.class, NotEqualType.class,
            GreaterOrEqualType.class, StrictlyGreaterType.class,
            NotType.class,
            StrictlyLessType.class, LessOrEqualType.class, ContainsType.class, NotContainsType.class, IsNullType.class, IsNotNullType.class})
    public static GlobUnionField filter;

    static {
        GlobTypeLoaderFactory.create(NotType.class)
                .register(WantedField.class, (filter, wantedUniqueName) -> filter.getOpt(NotType.filter)
                        .ifPresent(glob -> glob.getType().getRegistered(WantedField.class)
                                .wanted(glob, wantedUniqueName)))
                .register(Rewrite.class, new Rewrite() {
                    public Glob rewriteOrInAnd(Glob glob) {
                        final Glob gl = glob.get(filter);
                        if (gl != null) {
                            Glob rewriteGl = gl.getType().getRegistered(Rewrite.class)
                                    .rewriteOrInAnd(gl);
                            if (rewriteGl == null) {
                                return null;
                            }
                            if (rewriteGl.getType() == NotType.TYPE) {
                                return rewriteGl.get(NotType.filter);
                            }
                            return TYPE.instantiate().set(filter, rewriteGl);
                        } else {
                            return null;
                        }
                    }
                })
                .register(FilterBuilder.class, (filter, rootType, dico, fullQuery) -> {
                    Glob glFilter = filter.get(NotType.filter);
                    final FilterImpl.IsSelected selected = glFilter.getType().getRegistered(FilterBuilder.class)
                            .create(glFilter, rootType, dico, fullQuery);
                    if (selected == null) {
                        return null;
                    } else {
                        return glob -> !selected.isSelected(glob);
                    }
                }).load();
    }
}
