// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.xml.providers;

import com.yahoo.container.di.componentgraph.Provider;

import javax.xml.XMLConstants;
import javax.xml.validation.SchemaFactory;

/**
 * @author Einar M R Rosenvinge
 * @deprecated Do not use!
 */
@Deprecated // TODO: Remove on Vespa 8
public class SchemaFactoryProvider implements Provider<SchemaFactory> {

    public static final String FACTORY_CLASS = "com.sun.org.apache.xerces.internal.jaxp.validation.XMLSchemaFactory";

    @Override
    public SchemaFactory get() {
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI, FACTORY_CLASS, this.getClass().getClassLoader());
    }

    @Override
    public void deconstruct() { }

}
