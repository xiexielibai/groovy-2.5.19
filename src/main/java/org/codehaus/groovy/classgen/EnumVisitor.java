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

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.EnumConstantClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.apache.groovy.ast.tools.ClassNodeUtils.addGeneratedMethod;
import static org.codehaus.groovy.ast.tools.GeneralUtils.localVarX;

public class EnumVisitor extends ClassCodeVisitorSupport {
    // some constants for modifiers
    private static final int FS = Opcodes.ACC_FINAL | Opcodes.ACC_STATIC;
    private static final int PS = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    private static final int PUBLIC_FS = Opcodes.ACC_PUBLIC | FS;
    private static final int PRIVATE_FS = Opcodes.ACC_PRIVATE | FS;

    private final SourceUnit sourceUnit;

    public EnumVisitor(CompilationUnit cu, SourceUnit su) {
        sourceUnit = su;
    }

    public void visitClass(ClassNode node) {
        if (!node.isEnum()) return;
        completeEnum(node);
    }

    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    private void completeEnum(ClassNode enumClass) {
        boolean isAic = isAnonymousInnerClass(enumClass);
        // create MIN_VALUE, MAX_VALUE and $VALUES fields
        FieldNode minValue = null, maxValue = null, values = null;

        if (!isAic) {
            ClassNode enumRef = enumClass.getPlainNodeReference();
            minValue = new FieldNode("MIN_VALUE", PUBLIC_FS, enumRef, enumClass, null);
            maxValue = new FieldNode("MAX_VALUE", PUBLIC_FS, enumRef, enumClass, null);
            values = new FieldNode("$VALUES", PRIVATE_FS | Opcodes.ACC_SYNTHETIC, enumRef.makeArray(), enumClass, null);
            values.setSynthetic(true);

            for (ConstructorNode ctor : enumClass.getDeclaredConstructors()) {
                if (ctor.isSyntheticPublic()) {
                    ctor.setSyntheticPublic(false);
                    ctor.setModifiers((ctor.getModifiers() | Opcodes.ACC_PRIVATE) & ~Opcodes.ACC_PUBLIC);
                } else if (!ctor.isPrivate()) {
                    addError(ctor, "Illegal modifier for the enum constructor; only private is permitted.");
                }
            }

            addMethods(enumClass, values);
            checkForAbstractMethods(enumClass);
        }
        addInit(enumClass, minValue, maxValue, values, isAic);
    }

    private static void checkForAbstractMethods(ClassNode enumClass) {
        List<MethodNode> methods = enumClass.getMethods();
        for (MethodNode m : methods) {
            if (m.isAbstract()) {
                // make the class abstract also see Effective Java p.152
                enumClass.setModifiers(enumClass.getModifiers() | Opcodes.ACC_ABSTRACT);
                break;
            }
        }
    }

    private static void addMethods(ClassNode enumClass, FieldNode values) {
        List<MethodNode> methods = enumClass.getMethods();

        boolean hasNext = false;
        boolean hasPrevious = false;
        for (MethodNode m : methods) {
            if (m.getName().equals("next") && m.getParameters().length == 0) hasNext = true;
            if (m.getName().equals("previous") && m.getParameters().length == 0) hasPrevious = true;
            if (hasNext && hasPrevious) break;
        }

        ClassNode enumRef = enumClass.getPlainNodeReference();

        {
            // create values() method
            MethodNode valuesMethod = new MethodNode("values", PUBLIC_FS, enumRef.makeArray(), Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
            valuesMethod.setSynthetic(true);
            BlockStatement code = new BlockStatement();
            MethodCallExpression cloneCall = new MethodCallExpression(new FieldExpression(values), "clone", MethodCallExpression.NO_ARGUMENTS);
            cloneCall.setMethodTarget(values.getType().getMethod("clone", Parameter.EMPTY_ARRAY));
            code.addStatement(new ReturnStatement(cloneCall));
            valuesMethod.setCode(code);
            addGeneratedMethod(enumClass, valuesMethod);
        }

        if (!hasNext) {
            // create next() method, code:
            //     Day next() {
            //        int ordinal = ordinal().next()
            //        if (ordinal >= values().size()) ordinal = 0
            //        return values()[ordinal]
            //     }
            Token assign = Token.newSymbol(Types.ASSIGN, -1, -1);
            Token ge = Token.newSymbol(Types.COMPARE_GREATER_THAN_EQUAL, -1, -1);
            MethodNode nextMethod = new MethodNode("next", Opcodes.ACC_PUBLIC, enumRef, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
            nextMethod.setSynthetic(true);
            BlockStatement code = new BlockStatement();
            BlockStatement ifStatement = new BlockStatement();
            ifStatement.addStatement(
                    new ExpressionStatement(
                            new BinaryExpression(new VariableExpression("ordinal"), assign, new ConstantExpression(0))
                    )
            );

            code.addStatement(
                    new ExpressionStatement(
                            new DeclarationExpression(
                                    localVarX("ordinal"),
                                    assign,
                                    new MethodCallExpression(
                                            new MethodCallExpression(
                                                    VariableExpression.THIS_EXPRESSION,
                                                    "ordinal",
                                                    MethodCallExpression.NO_ARGUMENTS),
                                            "next",
                                            MethodCallExpression.NO_ARGUMENTS
                                    )
                            )
                    )
            );
            code.addStatement(
                    new IfStatement(
                            new BooleanExpression(new BinaryExpression(
                                    new VariableExpression("ordinal"),
                                    ge,
                                    new MethodCallExpression(
                                            new FieldExpression(values),
                                            "size",
                                            MethodCallExpression.NO_ARGUMENTS
                                    )
                            )),
                            ifStatement,
                            EmptyStatement.INSTANCE
                    )
            );
            code.addStatement(
                    new ReturnStatement(
                            new MethodCallExpression(new FieldExpression(values), "getAt", new VariableExpression("ordinal"))
                    )
            );
            nextMethod.setCode(code);
            addGeneratedMethod(enumClass, nextMethod);
        }

        if (!hasPrevious) {
            // create previous() method, code:
            //    Day previous() {
            //        int ordinal = ordinal().previous()
            //        if (ordinal < 0) ordinal = values().size() - 1
            //        return values()[ordinal]
            //    }
            Token assign = Token.newSymbol(Types.ASSIGN, -1, -1);
            Token lt = Token.newSymbol(Types.COMPARE_LESS_THAN, -1, -1);
            MethodNode prevMethod = new MethodNode("previous", Opcodes.ACC_PUBLIC, enumRef, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
            prevMethod.setSynthetic(true);
            BlockStatement code = new BlockStatement();
            BlockStatement ifStatement = new BlockStatement();
            ifStatement.addStatement(
                    new ExpressionStatement(
                            new BinaryExpression(new VariableExpression("ordinal"), assign,
                                    new MethodCallExpression(
                                            new MethodCallExpression(
                                                    new FieldExpression(values),
                                                    "size",
                                                    MethodCallExpression.NO_ARGUMENTS
                                            ),
                                            "minus",
                                            new ConstantExpression(1)
                                    )
                            )
                    )
            );

            code.addStatement(
                    new ExpressionStatement(
                            new DeclarationExpression(
                                    localVarX("ordinal"),
                                    assign,
                                    new MethodCallExpression(
                                            new MethodCallExpression(
                                                    VariableExpression.THIS_EXPRESSION,
                                                    "ordinal",
                                                    MethodCallExpression.NO_ARGUMENTS),
                                            "previous",
                                            MethodCallExpression.NO_ARGUMENTS
                                    )
                            )
                    )
            );
            code.addStatement(
                    new IfStatement(
                            new BooleanExpression(new BinaryExpression(
                                    new VariableExpression("ordinal"),
                                    lt,
                                    new ConstantExpression(0)
                            )),
                            ifStatement,
                            EmptyStatement.INSTANCE
                    )
            );
            code.addStatement(
                    new ReturnStatement(
                            new MethodCallExpression(new FieldExpression(values), "getAt", new VariableExpression("ordinal"))
                    )
            );
            prevMethod.setCode(code);
            addGeneratedMethod(enumClass, prevMethod);
        }

        {
            // create valueOf
            Parameter stringParameter = new Parameter(ClassHelper.STRING_TYPE, "name");
            MethodNode valueOfMethod = new MethodNode("valueOf", PS, enumRef, new Parameter[]{stringParameter}, ClassNode.EMPTY_ARRAY, null);
            ArgumentListExpression callArguments = new ArgumentListExpression();
            callArguments.addExpression(new ClassExpression(enumClass));
            callArguments.addExpression(new VariableExpression("name"));

            BlockStatement code = new BlockStatement();
            code.addStatement(
                    new ReturnStatement(
                            new MethodCallExpression(new ClassExpression(ClassHelper.Enum_Type), "valueOf", callArguments)
                    )
            );
            valueOfMethod.setCode(code);
            valueOfMethod.setSynthetic(true);
            enumClass.addMethod(valueOfMethod);
        }
    }

    private void addInit(ClassNode enumClass, FieldNode minValue,
                         FieldNode maxValue, FieldNode values,
                         boolean isAic) {
        // constructor helper
        // This method is used instead of calling the constructor as
        // calling the constructor may require a table with MetaClass
        // selecting the constructor for each enum value. So instead we
        // use this method to have a central point for constructor selection
        // and only one table. The whole construction is needed because
        // Reflection forbids access to the enum constructor.
        // code:
        // def $INIT(Object[] para) {
        //  return this(*para)
        // }
        ClassNode enumRef = enumClass.getPlainNodeReference();
        Parameter[] parameter = new Parameter[]{new Parameter(ClassHelper.OBJECT_TYPE.makeArray(), "para")};
        MethodNode initMethod = new MethodNode("$INIT", PUBLIC_FS | Opcodes.ACC_SYNTHETIC, enumRef, parameter, ClassNode.EMPTY_ARRAY, null);
        initMethod.setSynthetic(true);
        ConstructorCallExpression cce = new ConstructorCallExpression(
                ClassNode.THIS,
                new ArgumentListExpression(
                        new SpreadExpression(new VariableExpression("para"))
                )
        );
        BlockStatement code = new BlockStatement();
        code.addStatement(new ReturnStatement(cce));
        initMethod.setCode(code);
        addGeneratedMethod(enumClass, initMethod);

        // static init
        List<FieldNode> fields = enumClass.getFields();
        List<Expression> arrayInit = new ArrayList<Expression>();
        int value = -1;
        Token assign = Token.newSymbol(Types.ASSIGN, -1, -1);
        List<Statement> block = new ArrayList<Statement>();
        FieldNode tempMin = null;
        FieldNode tempMax = null;
        for (FieldNode field : fields) {
            if ((field.getModifiers() & Opcodes.ACC_ENUM) == 0) continue;
            value++;
            if (tempMin == null) tempMin = field;
            tempMax = field;

            ClassNode enumBase = enumClass;
            ArgumentListExpression args = new ArgumentListExpression();
            args.addExpression(new ConstantExpression(field.getName()));
            args.addExpression(new ConstantExpression(value));
            if (field.getInitialExpression() == null) {
                if ((enumClass.getModifiers() & Opcodes.ACC_ABSTRACT) != 0) {
                    addError(field, "The enum constant " + field.getName() + " must override abstract methods from " + enumBase.getName() + ".");
                    continue;
                }
            } else {
                ListExpression oldArgs = (ListExpression) field.getInitialExpression();
                List<MapEntryExpression> savedMapEntries = new ArrayList<MapEntryExpression>();
                for (Expression exp : oldArgs.getExpressions()) {
                    if (exp instanceof MapEntryExpression) {
                        savedMapEntries.add((MapEntryExpression) exp);
                        continue;
                    }

                    InnerClassNode inner = null;
                    if (exp instanceof ClassExpression) {
                        ClassExpression clazzExp = (ClassExpression) exp;
                        ClassNode ref = clazzExp.getType();
                        if (ref instanceof EnumConstantClassNode) {
                            inner = (InnerClassNode) ref;
                        }
                    }
                    if (inner != null) {
                        List<MethodNode> baseMethods = enumBase.getMethods();
                        for (MethodNode methodNode : baseMethods) {
                            if (!methodNode.isAbstract()) continue;
                            MethodNode enumConstMethod = inner.getMethod(methodNode.getName(), methodNode.getParameters());
                            if (enumConstMethod == null || (enumConstMethod.getModifiers() & Opcodes.ACC_ABSTRACT) != 0) {
                                addError(field, "Can't have an abstract method in enum constant " + field.getName() + ". Implement method '" + methodNode.getTypeDescriptor() + "'.");
                            }
                        }
                        if (inner.getVariableScope() == null) {
                            enumBase = inner;
                            /*
                             * GROOVY-3985: Remove the final modifier from $INIT method in this case
                             * so that subclasses of enum generated for enum constants (aic) can provide
                             * their own $INIT method
                             */
                            initMethod.setModifiers(initMethod.getModifiers() & ~Opcodes.ACC_FINAL);
                            continue;
                        }
                    }
                    args.addExpression(exp);
                }
                if (!savedMapEntries.isEmpty()) {
                    args.getExpressions().add(2, new MapExpression(savedMapEntries));
                }
            }
            field.setInitialValueExpression(null);
            block.add(
                    new ExpressionStatement(
                            new BinaryExpression(
                                    new FieldExpression(field),
                                    assign,
                                    new StaticMethodCallExpression(enumBase, "$INIT", args)
                            )
                    )
            );
            arrayInit.add(new FieldExpression(field));
        }

        if (!isAic) {
            if (tempMin != null) {
                block.add(
                        new ExpressionStatement(
                                new BinaryExpression(
                                        new FieldExpression(minValue),
                                        assign,
                                        new FieldExpression(tempMin)
                                )
                        )
                );
                block.add(
                        new ExpressionStatement(
                                new BinaryExpression(
                                        new FieldExpression(maxValue),
                                        assign,
                                        new FieldExpression(tempMax)
                                )
                        )
                );
                enumClass.addField(minValue);
                enumClass.addField(maxValue);
            }

            block.add(
                    new ExpressionStatement(
                            new BinaryExpression(new FieldExpression(values), assign, new ArrayExpression(enumClass, arrayInit))
                    )
            );
            enumClass.addField(values);
        }
        enumClass.addStaticInitializerStatements(block, true);
    }

    private void addError(AnnotatedNode node, String msg) {
        sourceUnit.addErrorAndContinue(new SyntaxException(msg + '\n', node));
    }

    static boolean isAnonymousInnerClass(ClassNode enumClass) {
        if (!(enumClass instanceof EnumConstantClassNode)) return false;
        InnerClassNode ic = (InnerClassNode) enumClass;
        return ic.getVariableScope() == null;
    }
}
