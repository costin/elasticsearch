/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

/**
 * The two local-variable slots the block DFS emitter assigns to one fused tree node: {@code present} (the boolean flag
 * that is {@code true} iff every operand of the node was non-null/single-valued, so the node produced a value) and
 * {@code value} (the node's computed result, read only on the present path). Extracted from {@link Stitcher} (it was a
 * one-line private nested record) so the emitter's slot contract has a named home; behaviour is unchanged.
 *
 * @param present the local slot holding the node's present flag
 * @param value   the local slot holding the node's computed value (valid only when {@code present})
 */
record NodeSlots(int present, int value) {}
