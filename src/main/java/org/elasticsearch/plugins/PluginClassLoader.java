/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.plugins;

import com.google.common.collect.Lists;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.common.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Dedicated class-loader for each plugin. It automatically detects the internal jars used by a plugin and performs plugin detection and loading.
 */
class PluginClassLoader extends URLClassLoader {

    private final URL url;

    PluginClassLoader(File pluginFile, Settings settings) throws IOException {
        super(new URL[] { pluginFile.toURI().toURL() }, settings.getClassLoader());
        url = pluginFile.toURI().toURL();
        initPluginClasspath(pluginFile);
    }

    String pluginClassName() throws IOException {
        InputStream in = getResourceAsStream("es-plugin.properties");
        if (in == null) {
            return null;
        }
        Properties pluginProps = new Properties();
        try {
            pluginProps.load(in);
            return pluginProps.getProperty("plugin");
        } finally {
            IOUtils.close(in);
        }
    }

    private void initPluginClasspath(File pluginFile) throws IOException {
        List<File> libFiles = Lists.newArrayList();
        File[] files = pluginFile.listFiles();
        if (files != null) {
            Collections.addAll(libFiles, files);
        }
        File libLocation = new File(pluginFile, "lib");
        if (libLocation.exists() && libLocation.isDirectory()) {
            files = libLocation.listFiles();
            if (files != null) {
                Collections.addAll(libFiles, files);
            }
        }

        // if there are jars in it, add it as well
        for (File libFile : libFiles) {
            if (!(libFile.getName().endsWith(".jar") || libFile.getName().endsWith(".zip"))) {
                continue;
            }
            addURL(libFile.toURI().toURL());
        }
    }

    String url() {
        return null;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PluginClassLoader for url [%s], classpath [%s]", url, Arrays.toString(getURLs()));
    }
}