/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.eql.qa.debug;

import org.elasticsearch.xpack.ql.qa.debug.QlTransportClient;
import org.elasticsearch.xpack.eql.plugin.EqlPlugin;

import java.util.List;

class EqlTransportClient extends QlTransportClient {

    EqlTransportClient() {
        super(List.of(
            // EQL
            EqlPlugin.class));
    }
}