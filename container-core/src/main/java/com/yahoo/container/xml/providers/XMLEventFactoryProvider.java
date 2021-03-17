// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.xml.providers;

import com.yahoo.container.di.componentgraph.Provider;

import javax.xml.stream.XMLEventFactory;

/**
 * @author Einar M R Rosenvinge
 * @deprecated Do not use!
 */
@Deprecated // TODO: Remove on Vespa 8
public class XMLEventFactoryProvider implements Provider<XMLEventFactory> {

    public static final String FACTORY_CLASS = "com.sun.xml.internal.stream.events.XMLEventFactoryImpl";

    @Override
    public XMLEventFactory get() {
        System.setProperty("javax.xml.stream.XMLEventFactory", FACTORY_CLASS);
        // NOTE: In case the newFactory(String, ClassLoader) is used, XMLEventFactory treats the string as classname.
        return XMLEventFactory.newFactory();
    }

    @Override
    public void deconstruct() { }

}
