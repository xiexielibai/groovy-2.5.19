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
package org.codehaus.groovy.classgen;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isStatic;
import static org.apache.groovy.ast.tools.MethodNodeUtils.getPropertyName;
import static org.codehaus.groovy.ast.tools.GeneralUtils.getAllProperties;

/**
 * Initializes the variable scopes for an AST.
 */
public class VariableScopeVisitor extends ClassCodeVisitorSupport {

    private ClassNode currentClass;
    private VariableScope currentScope;
    private boolean inConstructor, inSpecialConstructorCall;

    private final SourceUnit source;
    private final boolean recurseInnerClasses;
    private final Deque<StateStackElement> stateStack = new LinkedList<>();

    private static class StateStackElement {
        final ClassNode clazz;
        final VariableScope scope;
        final boolean inConstructor;

        StateStackElement(final ClassNode currentClass, final VariableScope currentScope, final boolean inConstructor) {
            this.clazz = currentClass;
            this.scope = currentScope;
            this.inConstructor = inConstructor;
        }
    }

    public VariableScopeVisitor(final SourceUnit source, final boolean recurseInnerClasses) {
        this.source = source;
        this.currentScope = new VariableScope();
        this.recurseInnerClasses = recurseInnerClasses;
    }

    public VariableScopeVisitor(final SourceUnit source) {
        this(source, false);
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return source;
    }

    // ------------------------------
    // helper methods
    // ------------------------------

    private void pushState(final boolean isStatic) {
        stateStack.push(new StateStackElement(currentClass, currentScope, inConstructor));
        currentScope = new VariableScope(currentScope);
        currentScope.setInStaticContext(isStatic);
    }

    private void pushState() {
        pushState(currentScope.isInStaticContext());
    }

    private void popState() {
        StateStackElement state = stateStack.pop();
        this.currentClass  = state.clazz;
        this.currentScope  = state.scope;
        this.inConstructor = state.inConstructor;
    }

    private void declare(final VariableExpression variable) {
        variable.setInStaticContext(currentScope.isInStaticContext());
        declare(variable, variable);
        variable.setAccessedVariable(variable);
    }

    private void declare(final Variable variable, final ASTNode context) {
        String scopeType = "scope";
        String variableType = "variable";

        if (context.getClass() == FieldNode.class) {
            scopeType = "class";
            variableType = "field";
        } else if (context.getClass() == PropertyNode.class) {
            scopeType = "class";
            variableType = "property";
        }

        StringBuilder msg = new StringBuilder();
        msg.append("The current ").append(scopeType);
        msg.append(" already contains a ").append(variableType);
        msg.append(" of the name ").append(variable.getName());

        if (currentScope.getDeclaredVariable(variable.getName()) != null) {
            addError(msg.toString(), context);
            return;
        }

        for (VariableScope scope = currentScope.getParent(); scope != null; scope = scope.getParent()) {
            // if we are in a class and no variable is declared until
            // now, then we can break the loop, because we are allowed
            // to declare a variable of the same name as a class member
            if (scope.getClassScope() != null && !isAnonymous(scope.getClassScope())) break;

            if (scope.getDeclaredVariable(variable.getName()) != null) {
                // variable already declared
                addError(msg.toString(), context);
                break;
            }
        }
        // declare the variable even if there was an error to allow more checks
        currentScope.putDeclaredVariable(variable);
    }

    private Variable findClassMember(final ClassNode node, final String name) {
        final boolean abstractType = node.isAbstract();

        for (ClassNode cn = node; cn != null && !cn.equals(ClassHelper.OBJECT_TYPE); cn = cn.getSuperClass()) {
            for (FieldNode fn : cn.getFields()) {
                if (name.equals(fn.getName())) return fn;
            }

            for (PropertyNode pn : cn.getProperties()) {
                if (name.equals(pn.getName())) return pn;
            }

            for (MethodNode mn : cn.getMethods()) {
                if ((abstractType || !mn.isAbstract()) && name.equals(getPropertyName(mn))) {
                    // check for override of super class property
                    for (PropertyNode pn : getAllProperties(cn.getSuperClass())) {
                        if (name.equals(pn.getName())) return pn;
                    }

                    FieldNode fn = new FieldNode(name, mn.getModifiers() & 0xF, ClassHelper.DYNAMIC_TYPE, cn, null);
                    fn.setHasNoRealSourcePosition(true);
                    fn.setDeclaringClass(cn);
                    fn.setSynthetic(true);

                    PropertyNode pn = new PropertyNode(fn, fn.getModifiers(), null, null);
                    pn.setDeclaringClass(cn);
                    return pn;
                }
            }

            for (ClassNode in : cn.getAllInterfaces()) {
                FieldNode fn = in.getDeclaredField(name);
                if (fn != null) return fn;
                PropertyNode pn = in.getProperty(name);
                if (pn != null) return pn;
            }
        }

        return null;
    }

    private Variable findVariableDeclaration(final String name) {
        if ("super".equals(name) || "this".equals(name)) return null;

        Variable variable = null;
        VariableScope scope = currentScope;
        boolean crossingStaticContext = false;
        // try to find a declaration of a variable
        while (true) {
            crossingStaticContext = (crossingStaticContext || scope.isInStaticContext());

            Variable var = scope.getDeclaredVariable(name);
            if (var != null) {
                variable = var;
                break;
            }

            var = scope.getReferencedLocalVariable(name);
            if (var != null) {
                variable = var;
                break;
            }

            var = scope.getReferencedClassVariable(name);
            if (var != null) {
                variable = var;
                break;
            }

            ClassNode node = scope.getClassScope();
            if (node != null) {
                Variable member = findClassMember(node, name);
                boolean requireStatic = (crossingStaticContext || inSpecialConstructorCall);
                while (member == null && node.getOuterClass() != null && !isAnonymous(node)) {
                    requireStatic = requireStatic || isStatic(node.getModifiers());
                    member = findClassMember((node = node.getOuterClass()), name);
                }
                if (member != null) {
                    // prevent a static context (e.g. a static method) from accessing a non-static member (e.g. a non-static field)
                    if (requireStatic ? member.isInStaticContext() : !node.isScript()) {
                        variable = member;
                    }
                }

                if (!isAnonymous(scope.getClassScope())) break; // GROOVY-5961
            }
            scope = scope.getParent();
        }
        if (variable == null) {
            variable = new DynamicVariable(name, crossingStaticContext);
        }

        boolean isClassVariable = (scope.isClassScope() && !scope.isReferencedLocalVariable(name))
            || (scope.isReferencedClassVariable(name) && scope.getDeclaredVariable(name) == null);
        VariableScope end = scope;
        scope = currentScope;
        while (scope != end) {
            if (isClassVariable) {
                scope.putReferencedClassVariable(variable);
            } else {
                scope.putReferencedLocalVariable(variable);
            }
            scope = scope.getParent();
        }

        return variable;
    }

    private static boolean isAnonymous(final ClassNode node) {
        return (!node.isEnum() && node instanceof InnerClassNode && ((InnerClassNode) node).isAnonymous());
    }

    /**
     * a property on "this", like this.x is transformed to a
     * direct field access, so we need to check the
     * static context here
     *
     * @param pe the property expression to check
     */
    private void checkPropertyOnExplicitThis(PropertyExpression pe) {
        if (!currentScope.isInStaticContext()) return;
        Expression object = pe.getObjectExpression();
        if (!(object instanceof VariableExpression)) return;
        VariableExpression ve = (VariableExpression) object;
        if (!ve.getName().equals("this")) return;
        String name = pe.getPropertyAsString();
        if (name == null || name.equals("class")) return;
        Variable member = findClassMember(currentClass, name);
        if (member == null) return;
        checkVariableContextAccess(member, pe);
    }

    private void checkVariableContextAccess(Variable v, Expression expr) {
        if (v.isInStaticContext() || !currentScope.isInStaticContext()) return;

        String msg = v.getName() +
                " is declared in a dynamic context, but you tried to" +
                " access it from a static context.";
        addError(msg, expr);

        // declare a static variable to be able to continue the check
        DynamicVariable v2 = new DynamicVariable(v.getName(), currentScope.isInStaticContext());
        currentScope.putDeclaredVariable(v2);
    }

    // ------------------------------
    // code visit
    // ------------------------------

    public void visitBlockStatement(BlockStatement block) {
        pushState();
        block.setVariableScope(currentScope);
        super.visitBlockStatement(block);
        popState();
    }

    public void visitForLoop(ForStatement forLoop) {
        pushState();
        forLoop.setVariableScope(currentScope);
        Parameter p = forLoop.getVariable();
        p.setInStaticContext(currentScope.isInStaticContext());
        if (p != ForStatement.FOR_LOOP_DUMMY) declare(p, forLoop);
        super.visitForLoop(forLoop);
        popState();
    }

    public void visitIfElse(IfStatement ifElse) {
        ifElse.getBooleanExpression().visit(this);
        pushState();
        ifElse.getIfBlock().visit(this);
        popState();
        pushState();
        ifElse.getElseBlock().visit(this);
        popState();
    }

    public void visitDeclarationExpression(DeclarationExpression expression) {
        visitAnnotations(expression);
        // visit right side first to avoid the usage of a
        // variable before its declaration
        expression.getRightExpression().visit(this);

        if (expression.isMultipleAssignmentDeclaration()) {
            TupleExpression list = expression.getTupleExpression();
            for (Expression e : list.getExpressions()) {
                declare((VariableExpression) e);
            }
        } else {
            declare(expression.getVariableExpression());
        }
    }

    @Override
    public void visitBinaryExpression(BinaryExpression be) {
        super.visitBinaryExpression(be);
        switch (be.getOperation().getType()) {
            case Types.EQUAL: // = assignment
            case Types.BITWISE_AND_EQUAL:
            case Types.BITWISE_OR_EQUAL:
            case Types.BITWISE_XOR_EQUAL:
            case Types.PLUS_EQUAL:
            case Types.MINUS_EQUAL:
            case Types.MULTIPLY_EQUAL:
            case Types.DIVIDE_EQUAL:
            case Types.INTDIV_EQUAL:
            case Types.MOD_EQUAL:
            case Types.POWER_EQUAL:
            case Types.LEFT_SHIFT_EQUAL:
            case Types.RIGHT_SHIFT_EQUAL:
            case Types.RIGHT_SHIFT_UNSIGNED_EQUAL:
                checkFinalFieldAccess(be.getLeftExpression());
                break;
            default:
                break;
        }
    }

    private void checkFinalFieldAccess(Expression expression) {
        // currently not looking for PropertyExpression: dealt with at runtime using ReadOnlyPropertyException
        if (!(expression instanceof VariableExpression) && !(expression instanceof TupleExpression)) return;
        if (expression instanceof TupleExpression) {
            TupleExpression list = (TupleExpression) expression;
            for (Expression e : list.getExpressions()) {
                checkForFinal(expression, (VariableExpression) e);
            }
        } else {
            checkForFinal(expression, (VariableExpression) expression);
        }
    }

    // TODO handle local variables
    private void checkForFinal(final Expression expression, VariableExpression ve) {
        Variable v = ve.getAccessedVariable();
        if (v != null) {
            boolean isFinal = isFinal(v.getModifiers());
            boolean isParameter = v instanceof Parameter;
            if (isFinal && isParameter) {
                addError("Cannot assign a value to final variable '" + v.getName() + "'", expression);
            }
        }
    }

    public void visitVariableExpression(VariableExpression expression) {
        String name = expression.getName();
        Variable v = findVariableDeclaration(name);
        if (v == null) return;
        expression.setAccessedVariable(v);
        checkVariableContextAccess(v, expression);
    }

    public void visitPropertyExpression(PropertyExpression expression) {
        expression.getObjectExpression().visit(this);
        expression.getProperty().visit(this);
        checkPropertyOnExplicitThis(expression);
    }

    public void visitClosureExpression(ClosureExpression expression) {
        pushState();

        expression.setVariableScope(currentScope);

        if (expression.isParameterSpecified()) {
            for (Parameter parameter : expression.getParameters()) {
                parameter.setInStaticContext(currentScope.isInStaticContext());
                if (parameter.hasInitialExpression()) {
                    parameter.getInitialExpression().visit(this);
                }
                declare(parameter, expression);
            }
        } else if (expression.getParameters() != null) {
            Parameter var = new Parameter(ClassHelper.OBJECT_TYPE, "it");
            var.setInStaticContext(currentScope.isInStaticContext());
            currentScope.putDeclaredVariable(var);
        }

        super.visitClosureExpression(expression);
        markClosureSharedVariables();

        popState();
    }

    private void markClosureSharedVariables() {
        VariableScope scope = currentScope;
        for (Iterator<Variable> it = scope.getReferencedLocalVariablesIterator(); it.hasNext(); ) {
            it.next().setClosureSharedVariable(true);
        }
    }

    public void visitCatchStatement(CatchStatement statement) {
        pushState();
        Parameter p = statement.getVariable();
        p.setInStaticContext(currentScope.isInStaticContext());
        declare(p, statement);
        super.visitCatchStatement(statement);
        popState();
    }

    public void visitFieldExpression(FieldExpression expression) {
        String name = expression.getFieldName();
        //TODO: change that to get the correct scope
        Variable v = findVariableDeclaration(name);
        checkVariableContextAccess(v, expression);
    }

    // ------------------------------
    // class visit
    // ------------------------------

    public void visitClass(ClassNode node) {
        // AIC are already done, doing them here again will lead to wrong scopes
        if (isAnonymous(node)) return;

        pushState();

        prepareVisit(node);

        super.visitClass(node);
        if (recurseInnerClasses) {
            Iterator<InnerClassNode> innerClasses = node.getInnerClasses();
            while (innerClasses.hasNext()) {
                visitClass(innerClasses.next());
            }
        }
        popState();
    }

    /**
     * Sets the current class node context.
     */
    public void prepareVisit(ClassNode node) {
        currentClass = node;
        currentScope.setClassScope(node);
    }

    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        pushState(node.isStatic());
        inConstructor = isConstructor;
        node.setVariableScope(currentScope);
        visitAnnotations(node);

        // GROOVY-2156
        Parameter[] parameters = node.getParameters();
        for (Parameter parameter : parameters) {
            visitAnnotations(parameter);
        }
        for (Parameter parameter : parameters) {
            if (parameter.hasInitialExpression()) {
                parameter.getInitialExpression().visit(this);
            }
            declare(parameter, node);
        }
        visitClassCodeContainer(node.getCode());

        popState();
    }

    public void visitMethodCallExpression(MethodCallExpression call) {
        if (call.isImplicitThis() && call.getMethod() instanceof ConstantExpression) {
            ConstantExpression methodNameConstant = (ConstantExpression) call.getMethod();
            String methodName = methodNameConstant.getText();

            if (methodName == null) {
                throw new GroovyBugError("method name is null");
            }

            Variable v = findVariableDeclaration(methodName);
            if (v != null && !(v instanceof DynamicVariable)) {
                checkVariableContextAccess(v, call);
            }

            if (v instanceof VariableExpression || v instanceof Parameter) {
                VariableExpression object = new VariableExpression(v);
                object.setSourcePosition(methodNameConstant);
                call.setObjectExpression(object);
                ConstantExpression method = new ConstantExpression("call");
                method.setSourcePosition(methodNameConstant); // important for GROOVY-4344
                call.setImplicitThis(false);
                call.setMethod(method);
            }

        }
        super.visitMethodCallExpression(call);
    }

    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        boolean specialCtorFlag = inSpecialConstructorCall;
        inSpecialConstructorCall |= call.isSpecialCall();
        super.visitConstructorCallExpression(call);
        inSpecialConstructorCall = specialCtorFlag;

        if (!call.isUsingAnonymousInnerClass()) return;

        pushState();
        InnerClassNode innerClass = (InnerClassNode) call.getType();
        innerClass.setVariableScope(currentScope);
        currentScope.setClassScope(innerClass);
        currentScope.setInStaticContext(false);
        for (MethodNode method : innerClass.getMethods()) {
            visitAnnotations(method); // GROOVY-7033
            Parameter[] parameters = method.getParameters();
            for (Parameter p : parameters) visitAnnotations(p); // GROOVY-7033
            if (parameters.length == 0) parameters = null; // disable implicit "it"
            ClosureExpression cl = new ClosureExpression(parameters, method.getCode());
            visitClosureExpression(cl);
        }
        for (FieldNode field : innerClass.getFields()) {
            visitAnnotations(field); // GROOVY-7033
            Expression expression = field.getInitialExpression();
            if (expression != null
                    // GROOVY-6834: accessing a parameter which is not yet seen in scope
                    && !(expression.isSynthetic() && expression instanceof VariableExpression
                        && ((VariableExpression) expression).getAccessedVariable() instanceof Parameter)) {
                pushState(field.isStatic());
                expression.visit(this);
                popState();
            }
        }
        for (Statement statement : innerClass.getObjectInitializerStatements()) {
            statement.visit(this);
        }
        markClosureSharedVariables();
        popState();
    }

    public void visitProperty(PropertyNode node) {
        pushState(node.isStatic());
        super.visitProperty(node);
        popState();
    }

    public void visitField(FieldNode node) {
        pushState(node.isStatic());
        super.visitField(node);
        popState();
    }
}
