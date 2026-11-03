/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

/**
 * ASM owner predicates for the structural fusion tests. The hot fused method must
 * read raw arrays / typed {@code *Vector}s and must not bounce through
 * {@code Block}/{@code *Block} or any compute-data {@code *Builder} per row.
 *
 * <p>The historical filters only matched the exact internal name
 * {@code org/elasticsearch/compute/data/Block} (or one nested
 * {@code *Block$Builder} / {@code BooleanBlock$Builder}), so a regression that
 * used {@code LongBlock.getLong} or {@code BooleanVectorBuilder.appendBoolean}
 * would have slipped through. These predicates are therefore intentionally
 * broad:
 * <ul>
 *   <li>builders — any owner under {@code org/elasticsearch/compute/data/} whose
 *       binary name ends with {@code Builder} ({@code LongBlock$Builder},
 *       {@code BooleanVectorBuilder}, {@code *VectorFixedBuilder},
 *       standalone {@code *BlockBuilder}, ...)</li>
 *   <li>blocks — {@code Block} itself and every typed {@code *Block} under that
 *       package ({@code LongBlock}, {@code IntBlock}, {@code BooleanBlock},
 *       {@code BytesRefBlock}, array/arrow implementations, ...).
 *       {@code BlockFactory} does not match (it does not end with {@code Block}).
 *       {@code *Vector} owners do not match, so the allowed output wrapping
 *       {@code new*ArrayVector(...).asBlock()} stays permitted.</li>
 * </ul>
 *
 * <p>Callers still scope these predicates to the fused method and to the
 * opcodes they already targeted ({@code INVOKEINTERFACE}/{@code INVOKEVIRTUAL}
 * for reads/writes inside the loop). That keeps {@code INVOKESTATIC
 * VectorUnsafe} and {@code INVOKEVIRTUAL BlockFactory.new*ArrayVector} allowed.
 */
final class ForbiddenHotPathOwners {

    private static final String COMPUTE_DATA_PREFIX = "org/elasticsearch/compute/data/";

    private ForbiddenHotPathOwners() {}

    /**
     * True when {@code owner} is a compute-data builder: nested {@code *Block$Builder},
     * standalone {@code *BlockBuilder}, {@code *VectorBuilder}, {@code *VectorFixedBuilder}.
     */
    static boolean isBuilderOwner(String owner) {
        return isComputeData(owner) && simpleName(owner).endsWith("Builder");
    }

    /**
     * True when {@code owner} is {@link org.elasticsearch.compute.data.Block} or a typed
     * {@code *Block} interface/class under compute-data. Does not match
     * {@code BlockFactory}, {@code *Builder}, or {@code *Vector}.
     */
    static boolean isBlockOwner(String owner) {
        if (isComputeData(owner) == false) {
            return false;
        }
        String simple = simpleName(owner);
        return simple.endsWith("Block");
    }

    private static boolean isComputeData(String internalName) {
        return internalName.startsWith(COMPUTE_DATA_PREFIX);
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }
}
