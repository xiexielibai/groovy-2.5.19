/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.transform;

import groovy.lang.GroovyClassLoader;
import groovy.transform.AnnotationCollector;
import groovy.transform.AnnotationCollectorMode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.trait.TraitASTTransformation;
import org.codehaus.groovy.transform.trait.Traits;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Walks the AST and collects references to annotations that are annotated
 * themselves by {@link GroovyASTTransformation}. Each such annotation is added.
 * <p>
 * This visitor is only intended to be executed once, during the
 * {@link CompilePhase#SEMANTIC_ANALYSIS} phase of compilation.
 */
public class ASTTransformationCollectorCodeVisitor extends ClassCodeVisitorSupport {

    private ClassNode classNode;
    private final SourceUnit source;
    private final GroovyClassLoader transformLoader;

    public ASTTransformationCollectorCodeVisitor(final SourceUnit source, final GroovyClassLoader transformLoader) {
        this.source = source;
        this.transformLoader = transformLoader;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return source;
    }

    @Override
    public void visitClass(final ClassNode classNode) {
        ClassNode lastClass = this.classNode;
        this.classNode = classNode;
        super.visitClass(classNode);
        this.classNode = lastClass;
    }

    @Override
    public void visitAnnotations(final AnnotatedNode node) {
        List<AnnotationNode> nodeAnnotations = node.getAnnotations();
        if (nodeAnnotations.isEmpty()) return;
        super.visitAnnotations(node);

        for (;;) {
            Map<Integer, List<AnnotationNode>> existing = new TreeMap<Integer, List<AnnotationNode>>();
            Map<Integer, List<AnnotationNode>> replacements = new LinkedHashMap<Integer, List<AnnotationNode>>();
            Map<Integer, AnnotationCollectorMode> modes = new LinkedHashMap<Integer, AnnotationCollectorMode>();
            int index = 0;
            for (AnnotationNode annotation : nodeAnnotations) {
                findCollectedAnnotations(annotation, node, index, modes, existing, replacements);
                index += 1;
            }
            for (Map.Entry<Integer, List<AnnotationNode>> entry : replacements.entrySet()) {
                Integer replacementIndex = entry.getKey();
                List<AnnotationNode> annotationNodeList = entry.getValue();
                mergeCollectedAnnotations(modes.get(replacementIndex), existing, annotationNodeList);
                existing.put(replacementIndex, annotationNodeList);
            }
            List<AnnotationNode> mergedList = new ArrayList<AnnotationNode>();
            for (List<AnnotationNode> next : existing.values()) {
                mergedList.addAll(next);
            }
            if (mergedList.equals(nodeAnnotations)) break;

            nodeAnnotations.clear();
            nodeAnnotations.addAll(mergedList);
            // GROOVY-9238: look again for collector annotations
        }

        for (AnnotationNode annotation : nodeAnnotations) {
            Annotation transformClassAnnotation = getTransformClassAnnotation(annotation.getClassNode());
            if (transformClassAnnotation == null) {
                // skip if there is no such annotation
                continue;
            }
            addTransformsToClassNode(annotation, transformClassAnnotation);
        }
    }

    private static void mergeCollectedAnnotations(final AnnotationCollectorMode mode, final Map<Integer, List<AnnotationNode>> existing, final List<AnnotationNode> replacements) {
        switch (mode) {
            case PREFER_COLLECTOR:
                deleteExisting(false, existing, replacements);
                break;
            case PREFER_COLLECTOR_MERGED:
                deleteExisting(true, existing, replacements);
                break;
            case PREFER_EXPLICIT:
                deleteReplacement(false, existing, replacements);
                break;
            case PREFER_EXPLICIT_MERGED:
                deleteReplacement(true, existing, replacements);
                break;
            default:
                // nothing to do
        }
    }

    private static void deleteExisting(final boolean mergeParams, final Map<Integer, List<AnnotationNode>> existing, final List<AnnotationNode> replacements) {
        for (AnnotationNode replacement : replacements) {
            for (Map.Entry<Integer, List<AnnotationNode>> entry : existing.entrySet()) {
                List<AnnotationNode> annotationNodes = new ArrayList<AnnotationNode>(entry.getValue());
                for (Iterator<AnnotationNode> iterator = annotationNodes.iterator(); iterator.hasNext();) {
                    AnnotationNode annotation = iterator.next();
                    if (replacement.getClassNode().getName().equals(annotation.getClassNode().getName())) {
                        if (mergeParams) {
                            mergeParameters(replacement, annotation);
                        }
                        iterator.remove();
                    }
                }
                entry.setValue(annotationNodes);
            }
        }
    }

    private static void deleteReplacement(final boolean mergeParams, final Map<Integer, List<AnnotationNode>> existing, final List<AnnotationNode> replacements) {
        for (Iterator<AnnotationNode> nodeIterator = replacements.iterator(); nodeIterator.hasNext();) {
            boolean remove = false;
            AnnotationNode replacement = nodeIterator.next();
            for (Map.Entry<Integer, List<AnnotationNode>> entry : existing.entrySet()) {
                for (AnnotationNode annotation : entry.getValue()) {
                    if (replacement.getClassNode().getName().equals(annotation.getClassNode().getName())) {
                        if (mergeParams) {
                            mergeParameters(annotation, replacement);
                        }
                        remove = true;
                    }
                }
            }
            if (remove) {
                nodeIterator.remove();
            }
        }
    }

    private static void mergeParameters(final AnnotationNode to, final AnnotationNode from) {
        for (String name : from.getMembers().keySet()) {
            if (to.getMember(name) == null) {
                to.setMember(name, from.getMember(name));
            }
        }
    }

    private void assertStringConstant(Expression exp) {
        if (exp == null) return;
        if (!(exp instanceof ConstantExpression)) {
            source.getErrorCollector().addErrorAndContinue(new SyntaxErrorMessage(new SyntaxException(
                    "Expected a String constant.", exp.getLineNumber(), exp.getColumnNumber()),
                    source));
        }
        ConstantExpression ce = (ConstantExpression) exp;
        if (!(ce.getValue() instanceof String)) {
            source.getErrorCollector().addErrorAndContinue(new SyntaxErrorMessage(new SyntaxException(
                    "Expected a String constant.", exp.getLineNumber(), exp.getColumnNumber()),
                    source));
        }
    }

    private void findCollectedAnnotations(AnnotationNode aliasNode, AnnotatedNode origin, Integer index, Map<Integer, AnnotationCollectorMode> modes, Map<Integer, List<AnnotationNode>> existing, Map<Integer, List<AnnotationNode>> replacements) {
        ClassNode classNode = aliasNode.getClassNode();
        for (AnnotationNode annotation : classNode.getAnnotations()) {
            if (annotation.getClassNode().getName().equals(AnnotationCollector.class.getName())) {
                AnnotationCollectorMode mode = getMode(annotation);
                if (mode == null) {
                    mode = AnnotationCollectorMode.DUPLICATE;
                }
                modes.put(index, mode);
                Expression processorExp = annotation.getMember("processor");
                AnnotationCollectorTransform act = null;
                assertStringConstant(processorExp);
                if (processorExp != null) {
                    String className = (String) ((ConstantExpression) processorExp).getValue();
                    Class<?> klass = loadTransformClass(className, aliasNode);
                    if (klass != null) {
                        try {
                            act = (AnnotationCollectorTransform) klass.getDeclaredConstructor().newInstance();
                        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                            source.getErrorCollector().addErrorAndContinue(new ExceptionMessage(e, true, source));
                        }
                    }
                } else {
                    act = new AnnotationCollectorTransform();
                }
                if (act != null) {
                    replacements.put(index, act.visit(annotation, aliasNode, origin, source));
                    return;
                }
            }
        }
        if (!replacements.containsKey(index)) {
            existing.put(index, Collections.singletonList(aliasNode));
        }
    }

    private static AnnotationCollectorMode getMode(AnnotationNode node) {
        final Expression member = node.getMember("mode");
        if (member instanceof PropertyExpression) {
            PropertyExpression prop = (PropertyExpression) member;
            Expression oe = prop.getObjectExpression();
            if (oe instanceof ClassExpression) {
                ClassExpression ce = (ClassExpression) oe;
                if (ce.getType().getName().equals("groovy.transform.AnnotationCollectorMode")) {
                    return AnnotationCollectorMode.valueOf(prop.getPropertyAsString());
                }
            }
        }
        return null;
    }

    private void addTransformsToClassNode(AnnotationNode annotation, Annotation transformClassAnnotation) {
        List<String> transformClassNames = getTransformClassNames(annotation, transformClassAnnotation);

        if (transformClassNames.isEmpty()) {
            source.getErrorCollector().addError(new SimpleMessage("@GroovyASTTransformationClass in " +
                    annotation.getClassNode().getName() + " does not specify any transform class names/classes", source));
        }

        for (String transformClass : transformClassNames) {
            Class<?> klass = loadTransformClass(transformClass, annotation);
            if (klass != null) {
                verifyAndAddTransform(annotation, klass);
            }
        }
    }

    private Class<?> loadTransformClass(String transformClass, AnnotationNode annotation) {
        try {
            return transformLoader.loadClass(transformClass, false, true, false);
        } catch (ClassNotFoundException e) {
            source.getErrorCollector().addErrorAndContinue(
                    new SimpleMessage(
                            "Could not find class for Transformation Processor " + transformClass
                                    + " declared by " + annotation.getClassNode().getName(),
                            source));
        }
        return null;
    }

    private void verifyAndAddTransform(AnnotationNode annotation, Class<?> klass) {
        verifyClass(annotation, klass);
        verifyCompilePhase(annotation, klass);
        addTransform(annotation, (Class<? extends ASTTransformation>) klass);
    }

    private void verifyCompilePhase(AnnotationNode annotation, Class<?> klass) {
        GroovyASTTransformation transformationClass = klass.getAnnotation(GroovyASTTransformation.class);
        if (transformationClass != null) {
            CompilePhase specifiedCompilePhase = transformationClass.phase();
            if (specifiedCompilePhase.getPhaseNumber() < CompilePhase.SEMANTIC_ANALYSIS.getPhaseNumber()) {
                source.getErrorCollector().addError(
                        new SimpleMessage(
                                annotation.getClassNode().getName() + " is defined to be run in compile phase " + specifiedCompilePhase + ". Local AST transformations must run in " + CompilePhase.SEMANTIC_ANALYSIS + " or later!",
                                source));
            }

        } else {
            source.getErrorCollector().addError(
                    new SimpleMessage("AST transformation implementation classes must be annotated with " + GroovyASTTransformation.class.getName() + ". " + klass.getName() + " lacks this annotation.", source));
        }
    }

    private void verifyClass(AnnotationNode annotation, Class<?> klass) {
        if (!ASTTransformation.class.isAssignableFrom(klass)) {
            source.getErrorCollector().addError(new SimpleMessage("Not an ASTTransformation: " +
                    klass.getName() + " declared by " + annotation.getClassNode().getName(), source));
        }
    }

    private void addTransform(AnnotationNode annotation, Class<? extends ASTTransformation> klass) {
        boolean apply = !Traits.isTrait(classNode) || klass == TraitASTTransformation.class;
        if (apply) {
            classNode.addTransform(klass, annotation);
        }
    }

    private static Annotation getTransformClassAnnotation(ClassNode annotatedType) {
        if (!annotatedType.isResolved()) return null;

        for (Annotation ann : annotatedType.getTypeClass().getAnnotations()) {
            // because compiler clients are free to choose any GroovyClassLoader for
            // resolving ClassNodeS such as annotatedType, we have to compare by name,
            // and cannot cast the return value to GroovyASTTransformationClass
            if (ann.annotationType().getName().equals(GroovyASTTransformationClass.class.getName())) {
                return ann;
            }
        }

        return null;
    }

    private List<String> getTransformClassNames(AnnotationNode annotation, Annotation transformClassAnnotation) {
        List<String> result = new ArrayList<String>();

        try {
            Method valueMethod = transformClassAnnotation.getClass().getMethod("value");
            String[] names = (String[]) valueMethod.invoke(transformClassAnnotation);
            Collections.addAll(result, names);

            Method classesMethod = transformClassAnnotation.getClass().getMethod("classes");
            Class<?>[] classes = (Class[]) classesMethod.invoke(transformClassAnnotation);
            for (Class<?> klass : classes) {
                result.add(klass.getName());
            }

            if (names.length > 0 && classes.length > 0) {
                source.getErrorCollector().addError(new SimpleMessage("@GroovyASTTransformationClass in " +
                        annotation.getClassNode().getName() +
                        " should specify transforms only by class names or by classes and not by both", source));
            }
        } catch (Exception e) {
            source.addException(e);
        }

        return result;
    }
}
