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
package org.codehaus.groovy.classgen.asm.sc;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.classgen.asm.ClosureWriter;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.List;

import static org.apache.groovy.ast.tools.ClassNodeUtils.addGeneratedMethod;
import static org.codehaus.groovy.ast.tools.GeneralUtils.args;
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.constX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.nullX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.returnS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.varX;

/**
 * Writer responsible for generating closure classes in statically compiled mode.
 */
public class StaticTypesClosureWriter extends ClosureWriter {
    public StaticTypesClosureWriter(WriterController wc) {
        super(wc);
    }

    @Override
    protected ClassNode createClosureClass(final ClosureExpression expression, final int mods) {
        ClassNode closureClass = super.createClosureClass(expression, mods);
        List<MethodNode> methods = closureClass.getDeclaredMethods("call");
        List<MethodNode> doCall = closureClass.getMethods("doCall");
        if (doCall.size() != 1) {
            throw new GroovyBugError("Expected to find one (1) doCall method on generated closure, but found " + doCall.size());
        }
        MethodNode doCallMethod = doCall.get(0);
        if (methods.isEmpty() && doCallMethod.getParameters().length == 1) {
            createDirectCallMethod(closureClass, doCallMethod);
        }
        MethodTargetCompletionVisitor visitor = new MethodTargetCompletionVisitor(doCallMethod);
        Object dynamic = expression.getNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION);
        if (dynamic != null) {
            doCallMethod.putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, dynamic);
        }
        for (MethodNode method : methods) {
            visitor.visitMethod(method);
        }
        closureClass.putNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE, Boolean.TRUE);
        return closureClass;
    }

    private static void createDirectCallMethod(final ClassNode closureClass, final MethodNode doCallMethod) {
        // in case there is no "call" method on the closure, create a "fast invocation" path
        // to avoid going through ClosureMetaClass by call(Object...) method

        // we can't have a specialized version of call(Object...) because the dispatch logic
        // in ClosureMetaClass is too complex!

        // call(Object)
        Parameter doCallParam = doCallMethod.getParameters()[0];
        Parameter args = new Parameter(doCallParam.getType(), "args");
        addGeneratedCallMethod(closureClass, doCallMethod, varX(args), new Parameter[]{args});

        // call()
        addGeneratedCallMethod(closureClass, doCallMethod, defaultArgument(doCallParam), Parameter.EMPTY_ARRAY);
    }

    private static Expression defaultArgument(final Parameter parameter) {
        Expression argument;
        if (parameter.hasInitialExpression()) {
            argument = parameter.getInitialExpression(); // GROOVY-10072
        } else if (parameter.getType().isArray()) {
            ClassNode elementType = parameter.getType().getComponentType();
            argument = new ArrayExpression(elementType, null, Collections.<Expression>singletonList(constX(0, true)));
        } else {
            argument = nullX();
        }
        return argument;
    }

    private static void addGeneratedCallMethod(ClassNode closureClass, MethodNode doCallMethod, Expression expression, Parameter[] params) {
        MethodCallExpression doCallCall = callX(varX("this", closureClass), "doCall", args(expression));
        doCallCall.setImplicitThis(true);
        doCallCall.setMethodTarget(doCallMethod);
        MethodNode call = new MethodNode("call",
                Opcodes.ACC_PUBLIC,
                ClassHelper.OBJECT_TYPE,
                params,
                ClassNode.EMPTY_ARRAY,
                returnS(doCallCall));
        addGeneratedMethod(closureClass, call, true);
    }

    private static final class MethodTargetCompletionVisitor extends ClassCodeVisitorSupport {

        private final MethodNode doCallMethod;

        private MethodTargetCompletionVisitor(final MethodNode doCallMethod) {
            this.doCallMethod = doCallMethod;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitMethodCallExpression(final MethodCallExpression call) {
            super.visitMethodCallExpression(call);
            MethodNode mn = call.getMethodTarget();
            if (mn == null) {
                call.setMethodTarget(doCallMethod);
            }
        }
    }
}
