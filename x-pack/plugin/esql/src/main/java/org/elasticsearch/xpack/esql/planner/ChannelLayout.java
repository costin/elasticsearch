/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.expression.AttributeMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Layout of attributes mapped to the channels for a page across operators.
 */
public class ChannelLayout {

    /**
     * Attributes are unique across a plan. And thus so are the associated channels (at least at the moment).
     */
    private final AttributeMap<Integer> attributeToChannels = new AttributeMap<>();

    /**
     * Alias map to help resolve the source attribute and thus its channel.
     * eval x = a | eval y = x | eval z = y --> aliases.resolve(z) = a
     */
    private final AttributeMap<Attribute> aliases = new AttributeMap<>();

    private int channels = 0;

    public class Stage {
        private final String name;
        private final List<Attribute> attributes;

        Stage(String name, List<Attribute> attributes) {
            this.name = name;
            this.attributes = attributes;
        }

        public int[] layout() {
            int[] layout = new int[aliases.size()];
            for (int i = 0, size = attributes.size(); i < size; i++) {
                var attr = attributes.get(i);
                var source = aliases.resolve(attr, attr);
                // return -1 if no attribute was found since this method is used for debugging also
                var channel = attributeToChannels.getOrDefault(source, -1);
                layout[i] = channel;
            }
            return layout;
        }

        public String toString() {
            return name + attributes + " => " + Arrays.toString(layout());
        }
    }

    private List<Stage> stages = new ArrayList<>();
    private int currentStage = 0;

    public void addStage(PhysicalPlan plan) {
        addStage(plan.nodeName(), plan.output());
    }

    public void addStage(String name) {
        addStage(name, new ArrayList<>());
    }

    public void addStage(String name, List<Attribute> attributes) {
        stages.add(new Stage(name, attributes));
    }

    public void assignChannels(List<Attribute> attributes) {
        if (stages.isEmpty()) {
            throw new EsqlIllegalArgumentException("Add at least one stage before adding any attributes");
        }
        for (var attr : attributes) {
            // check aliasing if any
            attr = aliases.resolve(attr, attr);
            // assign the channel only if the source does NOT exist
            attributeToChannels.computeIfAbsent(attr, k -> channels++);
        }

        // append attributes to the current layer
        stages.get(stages.size() - 1).attributes.addAll(attributes);
    }

    /**
     * Associates the duplicate with the target channel.
     * To be called *before* appending the duplicate to the layout.
     */
    public void duplicateChannel(Attribute duplicate, Attribute target) {
        if (attributeToChannels.containsKey(duplicate)) {
            throw new EsqlIllegalArgumentException(
                "Duplicate attribute was already assigned a channel; add duplicates before assigned not after"
            );
        }
        aliases.put(duplicate, target);
    }

    public int getChannel(Attribute attr) {
        return attributeToChannels.getOrDefault(aliases.resolve(attr, attr), -1);
    }

    public int layerSize() {
        return stages.size();
    }

    public int channels() {
        return channels;
    }

    public int[] nextStage() {
        if (currentStage == stages.size()) {
            throw new EsqlIllegalArgumentException("No more stages in the stack");
        }
        return stages.get(currentStage++).layout();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("Layout stages");

        for (int i = 0, size = stages.size(); i < size; i++) {
            sb.append("\n");
            sb.append("Stage [").append(i).append("] ");
            sb.append(stages.get(i));
        }
        return sb.toString();
    }
}
