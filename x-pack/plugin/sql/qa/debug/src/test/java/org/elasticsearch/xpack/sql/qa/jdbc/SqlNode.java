/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import org.elasticsearch.cli.MockTerminal;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.transport.Netty4Plugin;

import java.util.Arrays;

public class SqlNode extends Node {

    public SqlNode(Settings settings) {
        super(InternalSettingsPreparer.prepareEnvironment(settings, new MockTerminal()), Arrays.asList(Netty4Plugin.class), false);
    }

    @Override
    protected void registerDerivedNodeNameWithLogger(String nodeName) {}
}
