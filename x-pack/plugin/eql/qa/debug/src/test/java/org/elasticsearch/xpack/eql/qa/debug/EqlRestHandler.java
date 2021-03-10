/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.eql.qa.debug;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.rest.action.RestMainAction;
import org.elasticsearch.xpack.eql.plugin.RestEqlSearchAction;
import org.elasticsearch.xpack.ql.qa.debug.QlHandler;
import org.elasticsearch.xpack.ql.qa.debug.QlNodeClient;

import java.io.IOException;
@SuppressForbidden(reason = "use http server")
public class EqlRestHandler implements HttpHandler {

    private final QlHandler main, search;
    
    public EqlRestHandler(QlNodeClient client) {
        this.main = new QlHandler(client, new RestMainAction());
        this.search = new QlHandler(client, new RestEqlSearchAction());
    }

    @Override
    public void handle(HttpExchange http) throws IOException {
        // fan out the requests
        String path = http.getRequestURI().getPath();
        if ("/".equals(path)) {
            main.handle(http);
        } else {
            search.handle(http);
        }
    }
}
