/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.gen;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import org.elasticsearch.core.SuppressForbidden;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;

/**
 * Enforces the inlinable-kernel contract at compile time: a {@code @Fusable} kernel is inlined into the fused class by
 * splicing its single terminal {@code return} expression, so a body with more than one {@code return} cannot be inlined
 * and the stitcher would refuse it at runtime. Rejecting it here — with the offending method named — turns that latent
 * runtime refusal into a clear build failure.
 * <p>
 * This logic is isolated in its own class because it depends on the javac source-tree API ({@code com.sun.source.*}),
 * which is flagged by forbidden-apis as a non-portable runtime class. Keeping it here lets the {@link SuppressForbidden}
 * exemption cover only this single-purpose class rather than the whole {@link EvaluatorProcessor}.
 */
@SuppressForbidden(
    reason = "javac com.sun.source Tree API inspects @Fusable method bodies to enforce the single-return inlinable-kernel contract"
)
final class InlinableKernelValidator {

    private final ProcessingEnvironment env;
    /**
     * The javac compilation-unit tree accessor. Null when the processor is not running on a source-tree-backed
     * environment (e.g. some IDE/incremental processors), in which case the contract is only checked at stitch time by
     * the compute tests and this validator is a no-op.
     */
    private final Trees trees;

    InlinableKernelValidator(ProcessingEnvironment env) {
        this.env = env;
        Trees resolved;
        try {
            resolved = Trees.instance(env);
        } catch (IllegalArgumentException e) {
            resolved = null;
        }
        this.trees = resolved;
    }

    /** Emits a compile error if the {@code @Fusable} {@code method}'s body has more than one own {@code return}. */
    void validate(ExecutableElement method) {
        if (trees == null) {
            return;
        }
        MethodTree methodTree = trees.getTree(method);
        if (methodTree == null || methodTree.getBody() == null) {
            // No source body available (e.g. an abstract/native declaration); nothing to inline, nothing to check.
            return;
        }
        int returns = countOwnReturns(methodTree.getBody());
        if (returns > 1) {
            env.getMessager()
                .printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Fusable kernel ["
                        + method.getEnclosingElement().getSimpleName()
                        + "#"
                        + method.getSimpleName()
                        + "] must have exactly one return statement to be inlinable, but has "
                        + returns
                        + "; refactor the body to a single terminal return (guards may throw instead of returning)",
                    method
                );
        }
    }

    /** Counts {@code return} statements in {@code body}, not descending into nested lambda or class bodies. */
    private static int countOwnReturns(Tree body) {
        ReturnCounter counter = new ReturnCounter();
        counter.scan(body, null);
        return counter.count;
    }

    /**
     * Scans a method body counting its own {@code return} statements, skipping nested lambda and anonymous-class bodies
     * (which are not inlined and so do not count against the single-return contract). Annotated with
     * {@link SuppressForbidden} because, as a separate class file, it is not covered by the outer class's exemption.
     */
    @SuppressForbidden(reason = "the javac com.sun.source Tree API is the only way to walk a method body at compile time")
    private static final class ReturnCounter extends TreeScanner<Void, Void> {
        private int count;

        @Override
        public Void visitReturn(ReturnTree node, Void unused) {
            count++;
            return super.visitReturn(node, unused);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            return null;
        }
    }
}
