package org.globsframework.view.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.StringArrayField;
import org.globsframework.core.metamodel.fields.StringField;

public class SimpleBreakdown {
    public static GlobType TYPE;

    public static StringArrayField path; // list of fieldName until

    public static StringField uniqueName;

    public static StringField aliasName;

    public static StringField typeName;

    public static StringField outputTypeName;

    public static StringField fieldName;

    public static StringField nativeType;

    public static BooleanField isSensibleData;

    static {
        GlobTypeLoaderFactory.create(SimpleBreakdown.class).load();
    }
}
