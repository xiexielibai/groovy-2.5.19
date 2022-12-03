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
package org.codehaus.groovy.ast.tools;

import antlr.RecognitionException;
import antlr.TokenStreamException;
import groovy.transform.stc.IncorrectTypeHintException;
import org.apache.groovy.internal.util.Predicate;
import org.apache.groovy.util.SystemUtil;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.antlr.AntlrParserPlugin;
import org.codehaus.groovy.antlr.parser.GroovyLexer;
import org.codehaus.groovy.antlr.parser.GroovyRecognizer;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.decompiled.DecompiledClassNode;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.ResolveVisitor;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.memoize.ConcurrentSoftCache;
import org.codehaus.groovy.runtime.memoize.EvictableCache;
import org.codehaus.groovy.syntax.ParserException;
import org.codehaus.groovy.syntax.Reduction;

import java.io.StringReader;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.codehaus.groovy.runtime.DefaultGroovyMethods.plus;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.getCorrectedClassNode;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.implementsInterfaceOrIsSubclassOf;

/**
 * Utility methods to deal with generic types.
 */
public class GenericsUtils {
    public static final GenericsType[] EMPTY_GENERICS_ARRAY = GenericsType.EMPTY_ARRAY;
    public static final String JAVA_LANG_OBJECT = ClassHelper.OBJECT;

    /**
     * Given a parameterized type and a generic type information, aligns actual type parameters. For example, if a
     * class uses generic type <pre>&lt;T,U,V&gt;</pre> (redirectGenericTypes), is used with actual type parameters
     * <pre>&lt;java.lang.String, U,V&gt;</pre>, then a class or interface using generic types <pre>&lt;T,V&gt;</pre>
     * will be aligned to <pre>&lt;java.lang.String,V&gt;</pre>
     *
     * @param redirectGenericTypes the type arguments or the redirect class node
     * @param parameterizedTypes   the actual type arguments used on this class node
     * @param alignmentTarget      the generic type arguments to which we want to align to
     * @return aligned type arguments
     * @deprecated You shouldn't call this method because it is inherently unreliable
     */
    @Deprecated
    public static GenericsType[] alignGenericTypes(final GenericsType[] redirectGenericTypes, final GenericsType[] parameterizedTypes, final GenericsType[] alignmentTarget) {
        if (alignmentTarget == null) return EMPTY_GENERICS_ARRAY;
        if (parameterizedTypes == null || parameterizedTypes.length == 0) return alignmentTarget;
        GenericsType[] generics = new GenericsType[alignmentTarget.length];
        for (int i = 0, scgtLength = alignmentTarget.length; i < scgtLength; i++) {
            final GenericsType currentTarget = alignmentTarget[i];
            GenericsType match = null;
            if (redirectGenericTypes != null) {
                for (int j = 0; j < redirectGenericTypes.length && match == null; j++) {
                    GenericsType redirectGenericType = redirectGenericTypes[j];
                    if (redirectGenericType.isCompatibleWith(currentTarget.getType())) {
                        if (currentTarget.isPlaceholder() && redirectGenericType.isPlaceholder() && !currentTarget.getName().equals(redirectGenericType.getName())) {
                            // check if there's a potential better match
                            boolean skip = false;
                            for (int k = j + 1; k < redirectGenericTypes.length && !skip; k++) {
                                GenericsType ogt = redirectGenericTypes[k];
                                if (ogt.isPlaceholder() && ogt.isCompatibleWith(currentTarget.getType()) && ogt.getName().equals(currentTarget.getName())) {
                                    skip = true;
                                }
                            }
                            if (skip) continue;
                        }
                        match = parameterizedTypes[j];
                        if (currentTarget.isWildcard()) {
                            // if alignment target is a wildcard type
                            // then we must make best effort to return a parameterized
                            // wildcard
                            ClassNode lower = currentTarget.getLowerBound() != null ? match.getType() : null;
                            ClassNode[] currentUpper = currentTarget.getUpperBounds();
                            ClassNode[] upper = currentUpper != null ? new ClassNode[currentUpper.length] : null;
                            if (upper != null) {
                                for (int k = 0; k < upper.length; k++) {
                                    upper[k] = currentUpper[k].isGenericsPlaceHolder() ? match.getType() : currentUpper[k];
                                }
                            }
                            match = new GenericsType(ClassHelper.makeWithoutCaching("?"), upper, lower);
                            match.setWildcard(true);
                        }
                    }
                }
            }
            if (match == null) {
                match = currentTarget;
            }
            generics[i] = match;
        }
        return generics;
    }

    /**
     * Generates a wildcard generic type in order to be used for checks against class nodes.
     * See {@link GenericsType#isCompatibleWith(org.codehaus.groovy.ast.ClassNode)}.
     *
     * @param types the type to be used as the wildcard upper bound
     * @return a wildcard generics type
     */
    public static GenericsType buildWildcardType(final ClassNode... types) {
        ClassNode base = ClassHelper.makeWithoutCaching("?");
        GenericsType gt = new GenericsType(base, types, null);
        gt.setWildcard(true);
        return gt;
    }

    /**
     * Returns the type parameter/argument relationships of the specified type.
     *
     * @param type the class node to check
     */
    public static Map<GenericsType.GenericsTypeName, GenericsType> extractPlaceholders(final ClassNode type) {
        Map<GenericsType.GenericsTypeName, GenericsType> placeholders = new HashMap<>();
        extractPlaceholders(type, placeholders);
        return placeholders;
    }

    /**
     * Populates the supplied map with the type parameter/argument relationships
     * of the specified type.
     *
     * @param type the class node to check
     * @param placeholders the generics type information collector
     */
    public static void extractPlaceholders(final ClassNode type, final Map<GenericsType.GenericsTypeName, GenericsType> placeholders) {
        if (type == null) return;

        if (type.isArray()) {
            extractPlaceholders(type.getComponentType(), placeholders);
            return;
        }

        if (!type.isUsingGenerics() || !type.isRedirectNode()) return;
        GenericsType[] parameterized = type.getGenericsTypes(); int n;
        if (parameterized == null || (n = parameterized.length) == 0) return;

        // GROOVY-8609
        if (type.isGenericsPlaceHolder()) {
            GenericsType gt = parameterized[0];
            GenericsType.GenericsTypeName name = new GenericsType.GenericsTypeName(gt.getName());
            if (!placeholders.containsKey(name)) placeholders.put(name, gt);
            return;
        }

        GenericsType[] redirectGenericsTypes = type.redirect().getGenericsTypes();
        if (redirectGenericsTypes == null) {
            redirectGenericsTypes = parameterized;
        } else if (redirectGenericsTypes.length != n) {
            throw new GroovyBugError("Expected earlier checking to detect generics parameter arity mismatch" +
                    "\nExpected: " + type.getName() + toGenericTypesString(redirectGenericsTypes) +
                    "\nSupplied: " + type.getName() + toGenericTypesString(parameterized));
        }

        List<GenericsType> typeArguments = new ArrayList<>(n);
        for (int i = 0; i < n; i += 1) {
            GenericsType rgt = redirectGenericsTypes[i];
            if (rgt.isPlaceholder()) {
                GenericsType.GenericsTypeName name = new GenericsType.GenericsTypeName(rgt.getName());
                if (!placeholders.containsKey(name)) {
                    GenericsType typeArgument = parameterized[i];
                    placeholders.put(name, typeArgument);
                    typeArguments.add(typeArgument);
                }
            }
        }

        // examine non-placeholder type args
        for (GenericsType gt : typeArguments) {
            if (gt.isWildcard()) {
                ClassNode lowerBound = gt.getLowerBound();
                if (lowerBound != null) {
                    extractPlaceholders(lowerBound, placeholders);
                } else {
                    ClassNode[] upperBounds = gt.getUpperBounds();
                    if (upperBounds != null) {
                        for (ClassNode upperBound : upperBounds) {
                            extractPlaceholders(upperBound, placeholders);
                        }
                    }
                }
            } else if (!gt.isPlaceholder()) {
                extractPlaceholders(gt.getType(), placeholders);
            }
        }
    }

    public static String toGenericTypesString(GenericsType[] genericsTypes) {
        if (genericsTypes == null) return "";
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0, n = genericsTypes.length; i < n; i++) {
            sb.append(genericsTypes[i].toString());
            if (i < n - 1) {
                sb.append(",");
            }
        }
        sb.append("> ");
        return sb.toString();
    }

    /**
     * Interface class nodes retrieved from {@link org.codehaus.groovy.ast.ClassNode#getInterfaces()}
     * or {@link org.codehaus.groovy.ast.ClassNode#getAllInterfaces()} are returned with generic type
     * arguments. This method allows returning a parameterized interface given the parameterized class
     * node which implements this interface.
     *
     * @param hint   the class node where generics types are parameterized
     * @param target the interface we want to parameterize generics types
     * @return a parameterized interface class node
     * @deprecated Use #parameterizeType instead
     */
    @Deprecated
    public static ClassNode parameterizeInterfaceGenerics(final ClassNode hint, final ClassNode target) {
        return parameterizeType(hint, target);
    }

    /**
     * Interface class nodes retrieved from {@link org.codehaus.groovy.ast.ClassNode#getInterfaces()}
     * or {@link org.codehaus.groovy.ast.ClassNode#getAllInterfaces()} are returned with generic type
     * arguments. This method allows returning a parameterized interface given the parameterized class
     * node which implements this interface.
     *
     * @param hint   the class node where generics types are parameterized
     * @param target the interface we want to parameterize generics types
     * @return a parameterized interface class node
     */
    public static ClassNode parameterizeType(final ClassNode hint, final ClassNode target) {
        if (hint.isArray()) {
            if (target.isArray()) {
                return parameterizeType(hint.getComponentType(), target.getComponentType()).makeArray();
            }
            return target;
        }
        if (!target.equals(hint) && implementsInterfaceOrIsSubclassOf(target, hint)) {
            ClassNode nextSuperClass = ClassHelper.getNextSuperClass(target, hint);
            if (!hint.equals(nextSuperClass)) {
                Map<String, ClassNode> genericsSpec = createGenericsSpec(hint);
                extractSuperClassGenerics(hint, nextSuperClass, genericsSpec);
                ClassNode result = correctToGenericsSpecRecurse(genericsSpec, nextSuperClass);
                return parameterizeType(result, target);
            }
        }
        Map<String, ClassNode> genericsSpec = createGenericsSpec(hint);
        ClassNode targetRedirect = target.redirect();
        genericsSpec = createGenericsSpec(targetRedirect, genericsSpec);
        extractSuperClassGenerics(hint, targetRedirect, genericsSpec);
        return correctToGenericsSpecRecurse(genericsSpec, targetRedirect);
    }

    public static ClassNode nonGeneric(final ClassNode type) {
        int dims = 0;
        ClassNode temp = type;
        while (temp.isArray()) { dims += 1;
            temp = temp.getComponentType();
        }
        if (temp instanceof DecompiledClassNode // GROOVY-10461: check without resolving supers
                        ? ((DecompiledClassNode) temp).isParameterized() : temp.isUsingGenerics()) {
            ClassNode result = ClassHelper.makeWithoutCaching(temp.getName());
            result.setRedirect(temp);
            result.setGenericsTypes(null);
            result.setUsingGenerics(false);
            while (dims > 0) { dims -= 1;
                result = result.makeArray();
            }
            return result;
        }
        return type;
    }

    public static ClassNode newClass(ClassNode type) {
        return type.getPlainNodeReference();
    }

    public static ClassNode makeClassSafe(Class klass) {
        return makeClassSafeWithGenerics(ClassHelper.make(klass));
    }

    public static ClassNode makeClassSafeWithGenerics(Class klass, ClassNode genericsType) {
        GenericsType[] genericsTypes = new GenericsType[1];
        genericsTypes[0] = new GenericsType(genericsType);
        return makeClassSafeWithGenerics(ClassHelper.make(klass), genericsTypes);
    }

    public static ClassNode makeClassSafe0(ClassNode type, GenericsType... genericTypes) {
        ClassNode plainNodeReference = newClass(type);
        if (genericTypes != null && genericTypes.length > 0) {
            plainNodeReference.setGenericsTypes(genericTypes);
            if (type.isGenericsPlaceHolder()) plainNodeReference.setGenericsPlaceHolder(true);
        }
        return plainNodeReference;
    }

    public static ClassNode makeClassSafeWithGenerics(ClassNode type, GenericsType... genericTypes) {
        if (type.isArray()) {
            return makeClassSafeWithGenerics(type.getComponentType(), genericTypes).makeArray();
        }
        int nTypes = (genericTypes == null ? 0 : genericTypes.length);
        GenericsType[] gTypes;
        if (nTypes == 0) {
            gTypes = EMPTY_GENERICS_ARRAY;
        } else {
            gTypes = new GenericsType[nTypes];
            System.arraycopy(genericTypes, 0, gTypes, 0, nTypes);
        }
        return makeClassSafe0(type, gTypes);
    }

    public static MethodNode correctToGenericsSpec(Map<String, ClassNode> genericsSpec, MethodNode mn) {
        if (genericsSpec == null) return mn;
        if (mn.getGenericsTypes() != null) genericsSpec = addMethodGenerics(mn, genericsSpec);
        ClassNode returnType = correctToGenericsSpecRecurse(genericsSpec, mn.getReturnType());
        Parameter[] oldParameters = mn.getParameters(); int nParameters= oldParameters.length;
        Parameter[] newParameters = new Parameter[nParameters];
        for (int i = 0; i < nParameters; i += 1) {
            Parameter oldParameter = oldParameters[i];
            newParameters[i] = new Parameter(correctToGenericsSpecRecurse(genericsSpec, oldParameter.getType()), oldParameter.getName(), oldParameter.getInitialExpression());
        }
        MethodNode newMethod = new MethodNode(mn.getName(), mn.getModifiers(), returnType, newParameters, mn.getExceptions(), mn.getCode());
        newMethod.setGenericsTypes(mn.getGenericsTypes());
        return newMethod;
    }

    public static ClassNode correctToGenericsSpecRecurse(Map<String, ClassNode> genericsSpec, ClassNode type) {
        return correctToGenericsSpecRecurse(genericsSpec, type, Collections.<String>emptyList());
    }

    /**
     * @since 2.4.1
     */
    public static ClassNode[] correctToGenericsSpecRecurse(Map<String, ClassNode> genericsSpec, ClassNode[] types) {
        if (types == null || types.length == 1) return types;
        ClassNode[] newTypes = new ClassNode[types.length];
        boolean modified = false;
        for (int i = 0; i < types.length; i++) {
            newTypes[i] = correctToGenericsSpecRecurse(genericsSpec, types[i], Collections.<String>emptyList());
            modified = modified || (types[i] != newTypes[i]);
        }
        if (!modified) return types;
        return newTypes;
    }

    public static ClassNode correctToGenericsSpecRecurse(Map<String, ClassNode> genericsSpec, ClassNode type, List<String> exclusions) {
        if (type.isArray()) {
            return correctToGenericsSpecRecurse(genericsSpec, type.getComponentType(), exclusions).makeArray();
        }
        if (type.isGenericsPlaceHolder() && !exclusions.contains(type.getUnresolvedName())) {
            String name = type.getGenericsTypes()[0].getName();
            exclusions = plus(exclusions, name); // GROOVY-7722
            type = genericsSpec.get(name);
            if (type != null && type.isGenericsPlaceHolder()) {
                if (type.getGenericsTypes() == null) {
                    ClassNode placeholder = ClassHelper.makeWithoutCaching(type.getUnresolvedName());
                    placeholder.setGenericsPlaceHolder(true);
                    return makeClassSafeWithGenerics(type, new GenericsType(placeholder));
                } else if (!name.equals(type.getUnresolvedName())) {
                    return correctToGenericsSpecRecurse(genericsSpec, type, exclusions);
                }
            }
        }
        if (type == null) type = ClassHelper.OBJECT_TYPE;
        GenericsType[] oldgTypes = type.getGenericsTypes();
        GenericsType[] newgTypes = EMPTY_GENERICS_ARRAY;
        if (oldgTypes != null) {
            newgTypes = new GenericsType[oldgTypes.length];
            for (int i = 0; i < newgTypes.length; i++) {
                GenericsType oldgType = oldgTypes[i];
                if (oldgType.isPlaceholder()) {
                    if (genericsSpec.get(oldgType.getName()) != null) {
                        newgTypes[i] = new GenericsType(genericsSpec.get(oldgType.getName()));
                    } else {
                        newgTypes[i] = new GenericsType(ClassHelper.OBJECT_TYPE);
                    }
                } else if (oldgType.isWildcard()) {
                    ClassNode oldLower = oldgType.getLowerBound();
                    ClassNode lower = oldLower != null ? correctToGenericsSpecRecurse(genericsSpec, oldLower, exclusions) : null;
                    ClassNode[] oldUpper = oldgType.getUpperBounds();
                    ClassNode[] upper = null;
                    if (oldUpper != null) {
                        upper = new ClassNode[oldUpper.length];
                        for (int j = 0; j < oldUpper.length; j++) {
                            upper[j] = correctToGenericsSpecRecurse(genericsSpec, oldUpper[j], exclusions);
                        }
                    }
                    GenericsType fixed = new GenericsType(oldgType.getType(), upper, lower);
                    fixed.setName(oldgType.getName());
                    fixed.setWildcard(true);
                    newgTypes[i] = fixed;
                } else {
                    newgTypes[i] = new GenericsType(correctToGenericsSpecRecurse(genericsSpec, correctToGenericsSpec(genericsSpec, oldgType), exclusions));
                }
            }
        }
        return makeClassSafeWithGenerics(type, newgTypes);
    }

    public static ClassNode correctToGenericsSpec(Map<String, ClassNode> genericsSpec, GenericsType type) {
        ClassNode ret = null;
        if (type.isPlaceholder()) {
            String name = type.getName();
            ret = genericsSpec.get(name);
        } else if (type.isWildcard()) { // GROOVY-9891
            ret = type.getLowerBound(); // use lower or upper
            if (ret == null && type.getUpperBounds() != null) {
                ret = type.getUpperBounds()[0]; // ? supports 1
            }
        }
        if (ret == null) ret = type.getType();
        return ret;
    }

    public static ClassNode correctToGenericsSpec(Map<String, ClassNode> genericsSpec, ClassNode type) {
        if (type.isArray()) {
            return correctToGenericsSpec(genericsSpec, type.getComponentType()).makeArray();
        }
        if (type.isGenericsPlaceHolder() && type.getGenericsTypes() != null) {
            String name = type.getGenericsTypes()[0].getName();
            type = genericsSpec.get(name);
            if (type != null && type.isGenericsPlaceHolder()
                    && !name.equals(type.getUnresolvedName())) {
                return correctToGenericsSpec(genericsSpec, type);
            }
        }
        if (type == null) type = ClassHelper.OBJECT_TYPE;
        return type;
    }

    public static Map<String, ClassNode> createGenericsSpec(ClassNode current) {
        return createGenericsSpec(current, Collections.<String,ClassNode>emptyMap());
    }

    public static Map<String, ClassNode> createGenericsSpec(ClassNode current, Map<String, ClassNode> oldSpec) {
        Map<String, ClassNode> ret = new HashMap<String, ClassNode>(oldSpec);
        // ret contains the type specs, what we now need is the type spec for the
        // current class. To get that we first apply the type parameters to the
        // current class and then use the type names of the current class to reset
        // the map. Example:
        //   class A<V,W,X>{}
        //   class B<T extends Number> extends A<T,Long,String> {}
        // first we have:    T->Number
        // we apply it to A<T,Long,String> -> A<Number,Long,String>
        // resulting in:     V->Number,W->Long,X->String

        GenericsType[] sgts = current.getGenericsTypes();
        if (sgts != null) {
            ClassNode[] spec = new ClassNode[sgts.length];
            for (int i = 0; i < spec.length; i++) {
                spec[i] = correctToGenericsSpec(ret, sgts[i]);
            }
            GenericsType[] newGts = current.redirect().getGenericsTypes();
            if (newGts == null) return ret;
            ret.clear();
            for (int i = 0; i < spec.length; i++) {
                ret.put(newGts[i].getName(), spec[i]);
            }
        }
        return ret;
    }

    public static Map<String, ClassNode> addMethodGenerics(MethodNode current, Map<String, ClassNode> oldSpec) {
        Map<String, ClassNode> newSpec = new HashMap<String, ClassNode>(oldSpec);
        GenericsType[] gts = current.getGenericsTypes();
        if (gts != null) {
            for (GenericsType gt : gts) {
                String name = gt.getName();
                ClassNode type = gt.getType();
                if (gt.isPlaceholder()) {
                    ClassNode redirect;
                    if (gt.getUpperBounds() != null) {
                        redirect = gt.getUpperBounds()[0];
                    } else if (gt.getLowerBound() != null) {
                        redirect = gt.getLowerBound();
                    } else {
                        redirect = ClassHelper.OBJECT_TYPE;
                    }
                    if (redirect.isGenericsPlaceHolder()) {
                        // "T extends U" or "T super U"
                        type = redirect; // GROOVY-9059
                    } else {
                        // "T" or "T extends Type" or "T super Type"
                        type = ClassHelper.makeWithoutCaching(name);
                        type.setGenericsPlaceHolder(true);
                        type.setRedirect(redirect);
                    }
                }
                newSpec.put(name, type);
            }
        }
        return newSpec;
    }

    public static void extractSuperClassGenerics(ClassNode type, ClassNode target, Map<String, ClassNode> spec) {
        // TODO: this method is very similar to StaticTypesCheckingSupport#extractGenericsConnections,
        // but operates on ClassNodes instead of GenericsType
        if (target == null || type == target) return;
        if (type.isArray() && target.isArray()) {
            extractSuperClassGenerics(type.getComponentType(), target.getComponentType(), spec);
        } else if (type.isArray() && JAVA_LANG_OBJECT.equals(target.getName())) {
            // Object is superclass of arrays but no generics involved
        } else if (target.isGenericsPlaceHolder() || type.equals(target) || !implementsInterfaceOrIsSubclassOf(type, target)) {
            // structural match route
            if (target.isGenericsPlaceHolder()) {
                spec.put(target.getGenericsTypes()[0].getName(), type);
            } else {
                extractSuperClassGenerics(type.getGenericsTypes(), target.getGenericsTypes(), spec);
            }
        } else {
            // have first to find matching super class or interface
            ClassNode superClass = getSuperClass(type, target);

            if (superClass != null) {
                ClassNode corrected = getCorrectedClassNode(type, superClass, false);
                extractSuperClassGenerics(corrected, target, spec);
            } else {
                // if we reach here, we have an unhandled case
                throw new GroovyBugError("The type " + type + " seems not to normally extend " + target + ". Sorry, I cannot handle this.");
            }
        }
    }

    public static ClassNode getSuperClass(final ClassNode type, final ClassNode target) {
        return ClassHelper.getNextSuperClass(ClassHelper.isPrimitiveType(type) ? ClassHelper.getWrapper(type) : type, target);
    }

    private static void extractSuperClassGenerics(final GenericsType[] usage, final GenericsType[] declaration, final Map<String, ClassNode> spec) {
        // if declaration does not provide generics, there is no connection to make
        if (declaration == null || declaration.length == 0) return;

        // if usage is a raw type, remove type parameters from spec
        if (usage == null) {
            for (GenericsType dt : declaration) {
                String name = dt.getName();
                ClassNode type = spec.get(name);
                if (type != null && type.isGenericsPlaceHolder()
                        && type.getUnresolvedName().equals(name)) {
                    type = type.asGenericsType().getUpperBounds()[0];
                    spec.put(name, type);
                }
            }
            return;
        }

        if (usage.length != declaration.length) return;

        for (int i = 0, n = usage.length; i < n; i += 1) {
            GenericsType ui = usage[i];
            GenericsType di = declaration[i];
            if (di.isPlaceholder()) {
                spec.put(di.getName(), ui.getType());
            } else if (di.isWildcard()) {
                if (ui.isWildcard()) {
                    extractSuperClassGenerics(ui.getLowerBound(), di.getLowerBound(), spec);
                    extractSuperClassGenerics(ui.getUpperBounds(), di.getUpperBounds(), spec);
                } else {
                    ClassNode cu = ui.getType();
                    extractSuperClassGenerics(cu, di.getLowerBound(), spec);
                    ClassNode[] upperBounds = di.getUpperBounds();
                    if (upperBounds != null) {
                        for (ClassNode cn : upperBounds) {
                            extractSuperClassGenerics(cu, cn, spec);
                        }
                    }
                }
            } else {
                extractSuperClassGenerics(ui.getType(), di.getType(), spec);
            }
        }
    }

    private static void extractSuperClassGenerics(ClassNode[] usage, ClassNode[] declaration, Map<String, ClassNode> spec) {
        if (usage == null || declaration == null || declaration.length == 0) return;
        // both have generics
        for (int i = 0; i < usage.length; i++) {
            ClassNode ui = usage[i];
            ClassNode di = declaration[i];
            if (di.isGenericsPlaceHolder()) {
                spec.put(di.getGenericsTypes()[0].getName(), di);
            } else if (di.isUsingGenerics()) {
                extractSuperClassGenerics(ui.getGenericsTypes(), di.getGenericsTypes(), spec);
            }
        }
    }

    public static ClassNode[] parseClassNodesFromString(
            final String option,
            final SourceUnit sourceUnit,
            final CompilationUnit compilationUnit,
            final MethodNode mn,
            final ASTNode usage) {
        GroovyLexer lexer = new GroovyLexer(new StringReader("DummyNode<" + option + ">"));
        final GroovyRecognizer rn = GroovyRecognizer.make(lexer);
        try {
            rn.classOrInterfaceType(true);
            final AtomicReference<ClassNode> ref = new AtomicReference<ClassNode>();
            AntlrParserPlugin plugin = new AntlrParserPlugin() {
                @Override
                public ModuleNode buildAST(final SourceUnit sourceUnit, final ClassLoader classLoader, final Reduction cst) throws ParserException {
                    ref.set(makeTypeWithArguments(rn.getAST()));
                    return null;
                }
            };
            plugin.buildAST(null, null, null);
            ClassNode parsedNode = ref.get();
            // the returned node is DummyNode<Param1, Param2, Param3, ...)
            GenericsType[] parsedNodeGenericsTypes = parsedNode.getGenericsTypes();
            if (parsedNodeGenericsTypes == null) {
                return null;
            }
            ClassNode[] signature = new ClassNode[parsedNodeGenericsTypes.length];
            for (int i = 0; i < parsedNodeGenericsTypes.length; i++) {
                final GenericsType genericsType = parsedNodeGenericsTypes[i];
                signature[i] = resolveClassNode(sourceUnit, compilationUnit, mn, usage, genericsType.getType());
            }
            return signature;
        } catch (RecognitionException | ParserException | TokenStreamException e) {
            sourceUnit.addError(new IncorrectTypeHintException(mn, e, usage.getLineNumber(), usage.getColumnNumber()));
        }
        return null;
    }

    private static ClassNode resolveClassNode(final SourceUnit sourceUnit, final CompilationUnit compilationUnit, final MethodNode mn, final ASTNode usage, final ClassNode parsedNode) {
        ClassNode dummyClass = new ClassNode("dummy", 0, ClassHelper.OBJECT_TYPE);
        dummyClass.setModule(new ModuleNode(sourceUnit));
        dummyClass.setGenericsTypes(mn.getDeclaringClass().getGenericsTypes());
        MethodNode dummyMN = new MethodNode(
                "dummy",
                0,
                parsedNode,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                EmptyStatement.INSTANCE
        );
        dummyMN.setGenericsTypes(mn.getGenericsTypes());
        dummyClass.addMethod(dummyMN);
        ResolveVisitor visitor = new ResolveVisitor(compilationUnit) {
            @Override
            public void addError(final String msg, final ASTNode expr) {
                sourceUnit.addError(new IncorrectTypeHintException(mn, msg, usage.getLineNumber(), usage.getColumnNumber()));
            }
        };
        visitor.startResolving(dummyClass, sourceUnit);
        return dummyMN.getReturnType();
    }

    /**
     * Transforms generics types from an old context to a new context using the
     * given spec. This method assumes all generics types will be placeholders.
     * WARNING: The resulting generics types may or may not be placeholders
     * after the transformation.
     *
     * @param genericsSpec    the generics context information spec
     * @param oldPlaceHolders the old placeholders
     * @return the new generics types
     */
    public static GenericsType[] applyGenericsContextToPlaceHolders(Map<String, ClassNode> genericsSpec, GenericsType[] oldPlaceHolders) {
        if (oldPlaceHolders == null || oldPlaceHolders.length == 0) return oldPlaceHolders;
        if (genericsSpec.isEmpty()) return oldPlaceHolders;
        GenericsType[] newTypes = new GenericsType[oldPlaceHolders.length];
        for (int i = 0; i < oldPlaceHolders.length; i++) {
            GenericsType old = oldPlaceHolders[i];
            if (!old.isPlaceholder())
                throw new GroovyBugError("Given generics type " + old + " must be a placeholder!");
            ClassNode fromSpec = genericsSpec.get(old.getName());
            if (fromSpec != null) {
                if (fromSpec.isGenericsPlaceHolder()) {
                    ClassNode[] upper = new ClassNode[]{fromSpec.redirect()};
                    newTypes[i] = new GenericsType(fromSpec, upper, null);
                } else {
                    newTypes[i] = new GenericsType(fromSpec);
                }
            } else {
                ClassNode[] upper = old.getUpperBounds();
                ClassNode[] newUpper = upper;
                if (upper != null && upper.length > 0) {
                    ClassNode[] upperCorrected = new ClassNode[upper.length];
                    for (int j = 0; j < upper.length; j++) {
                        upperCorrected[i] = correctToGenericsSpecRecurse(genericsSpec, upper[j]);
                    }
                    upper = upperCorrected;
                }
                ClassNode lower = old.getLowerBound();
                ClassNode newLower = correctToGenericsSpecRecurse(genericsSpec, lower);
                if (lower == newLower && upper == newUpper) {
                    newTypes[i] = oldPlaceHolders[i];
                } else {
                    ClassNode newPlaceHolder = ClassHelper.make(old.getName());
                    GenericsType gt = new GenericsType(newPlaceHolder, newUpper, newLower);
                    gt.setPlaceholder(true);
                    newTypes[i] = gt;
                }
            }
        }
        return newTypes;
    }

    /**
     * Get the parameterized type by search the whole class hierarchy according to generics class and actual receiver.
     * {@link #findParameterizedTypeFromCache(ClassNode, ClassNode)} is strongly recommended for better performance.
     *
     * @param genericsClass the generics class
     * @param actualType the actual type
     * @return the parameterized type
     */
    public static ClassNode findParameterizedType(final ClassNode genericsClass, final ClassNode actualType) {
        return findParameterizedType(genericsClass, actualType, false);
    }

    // Backported from 3.0.8
    private static ClassNode findParameterizedType(final ClassNode genericsClass, final ClassNode actualType, final boolean tryToFindExactType) {
        final GenericsType[] genericsTypes = genericsClass.getGenericsTypes();
        if (genericsTypes == null || genericsClass.isGenericsPlaceHolder()) {
            return null;
        }

        if (actualType.equals(genericsClass)) {
            return actualType;
        }

        java.util.Set<ClassNode> done = new java.util.HashSet<>();
        LinkedList<ClassNode> todo = new LinkedList<>();
        todo.add(actualType);
        ClassNode type;

        while ((type = todo.poll()) != null) {
            if (type.equals(genericsClass)) {
                return type;
            }
            if (done.add(type)) {
                boolean parameterized = (type.getGenericsTypes() != null);
                for (ClassNode cn : type.getInterfaces()) {
                    if (parameterized)
                        cn = parameterizeType(type, cn);
                    todo.add(cn);
                }
                if (!actualType.isInterface()) {
                    ClassNode cn = type.getUnresolvedSuperClass();
                    if (cn != null && cn.redirect() != ClassHelper.OBJECT_TYPE) {
                        if (parameterized)
                            cn = parameterizeType(type, cn);
                        todo.add(cn);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Checks for any placeholder (aka unresolved) generics.
     * <p>
     * Backported from 3.0.8
     */
    public static boolean hasUnresolvedGenerics(final ClassNode type) {
        if (type.isGenericsPlaceHolder()) return true;
        if (type.isArray()) {
            return hasUnresolvedGenerics(type.getComponentType());
        }
        GenericsType[] genericsTypes = type.getGenericsTypes();
        if (genericsTypes != null) {
            for (GenericsType genericsType : genericsTypes) {
                if (genericsType.isPlaceholder()) return true;
                ClassNode lowerBound = genericsType.getLowerBound();
                ClassNode[] upperBounds = genericsType.getUpperBounds();
                if (lowerBound != null) {
                    if (hasUnresolvedGenerics(lowerBound)) return true;
                } else if (upperBounds != null) {
                    for (ClassNode upperBound : upperBounds) {
                        if (hasUnresolvedGenerics(upperBound)) return true;
                    }
                } else {
                    if (hasUnresolvedGenerics(genericsType.getType())) return true;
                }
            }
        }
        return false;
    }

    /**
     * map declaring generics type to actual generics type, e.g. GROOVY-7204:
     * declaring generics types:      T,      S extends Serializable
     * actual generics types   : String,      Long
     *
     * the result map is [
     *  T: String,
     *  S: Long
     * ]
     *
     * The resolved types can not help us to choose methods correctly if the argument is a string:  T: Object, S: Serializable
     * so we need actual types:  T: String, S: Long
     */
    public static Map<GenericsType, GenericsType> makeDeclaringAndActualGenericsTypeMap(final ClassNode declaringClass, final ClassNode actualReceiver) {
        return doMakeDeclaringAndActualGenericsTypeMap(declaringClass, actualReceiver, false);
    }

    /**
     * The method is similar with {@link GenericsUtils#makeDeclaringAndActualGenericsTypeMap(ClassNode, ClassNode)},
     * The main difference is that the method will try to map all placeholders found to the relevant exact types,
     * but the other will not try even if the parameterized type has placeholders
     * <p>
     * Backported from 3.0.0
     *
     * @param declaringClass the generics class node declaring the generics types
     * @param actualReceiver the sub-class class node
     * @return the placeholder-to-actualtype mapping
     *
     * @since 2.5.9
     */
    public static Map<GenericsType, GenericsType> makeDeclaringAndActualGenericsTypeMapOfExactType(final ClassNode declaringClass, final ClassNode actualReceiver) {
        return doMakeDeclaringAndActualGenericsTypeMap(declaringClass, actualReceiver, true);
    }

    private static Map<GenericsType, GenericsType> doMakeDeclaringAndActualGenericsTypeMap(final ClassNode declaringClass, final ClassNode actualReceiver, final boolean tryToFindExactType) {
        Map<GenericsType, GenericsType> map = Collections.<GenericsType, GenericsType>emptyMap();
        ClassNode parameterizedType = findParameterizedTypeFromCache(declaringClass, actualReceiver, tryToFindExactType);
        if (parameterizedType != null && parameterizedType.isRedirectNode() && !parameterizedType.isGenericsPlaceHolder()) { // GROOVY-10166
            // declaringClass may be "List<T> -> List<E>" and parameterizedType may be "List<String> -> List<E>" or "List<> -> List<E>"
            GenericsType[] targetGenericsTypes = parameterizedType.redirect().getGenericsTypes();
            if (targetGenericsTypes != null) {
                GenericsType[] sourceGenericsTypes = parameterizedType.getGenericsTypes();
                if (sourceGenericsTypes == null) sourceGenericsTypes = EMPTY_GENERICS_ARRAY;
                map = new LinkedHashMap<GenericsType, GenericsType>();
                for (int i = 0, m = sourceGenericsTypes.length, n = targetGenericsTypes.length; i < n; i += 1) {
                    map.put(targetGenericsTypes[i], i < m ? sourceGenericsTypes[i] : targetGenericsTypes[i]);
                }
            }
        }
        return map;
    }

    /**
     * Checks if the type has any placeholder (aka unresolved) generics.
     *
     * @since 2.5.10
     */
    public static boolean hasPlaceHolders(final ClassNode type) {
        return checkPlaceHolders(type, new Predicate<GenericsType>() {
            @Override
            public boolean test(GenericsType genericsType) {
                return genericsType.isPlaceholder();
            }
        });
    }

    private static boolean checkPlaceHolders(final ClassNode type, final Predicate<GenericsType> p) {
        if (type != null) {
            GenericsType[] genericsTypes = type.getGenericsTypes();
            if (genericsTypes != null) {
                for (GenericsType genericsType : genericsTypes) {
                    if (p.test(genericsType)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Gets the actual type according to the placeholder name.
     *
     * @param placeholderName the placeholder name (i.e. "T", "E", etc.)
     * @param genericsPlaceholderAndTypeMap the result of {@link #makeDeclaringAndActualGenericsTypeMap(ClassNode, ClassNode)}
     * @return the actual type
     */
    public static ClassNode findActualTypeByGenericsPlaceholderName(final String placeholderName, final Map<GenericsType, GenericsType> genericsPlaceholderAndTypeMap) {
        for (Map.Entry<GenericsType, GenericsType> entry : genericsPlaceholderAndTypeMap.entrySet()) {
            if (placeholderName.equals(entry.getKey().getName())) {
                GenericsType gt = entry.getValue();
                if (gt.isWildcard()) {
                    if (gt.getLowerBound() != null) {
                        return gt.getLowerBound();
                    }
                    if (gt.getUpperBounds() != null) {
                        return gt.getUpperBounds()[0];
                    }
                }
                return gt.getType();
            }
        }
        return null;
    }

    //--------------------------------------------------------------------------

    /**
     * Clears the parameterized type cache.
     * <p>
     * It is useful to IDE as the type being compiled are continuously being edited/altered, see GROOVY-8675
     */
    public static void clearParameterizedTypeCache() {
        PARAMETERIZED_TYPE_CACHE.clearAll();
    }

    /**
     * Try to get the parameterized type from the cache. If no cached item found,
     * cache and return the result of {@link #findParameterizedType(ClassNode, ClassNode)}
     */
    public static ClassNode findParameterizedTypeFromCache(final ClassNode genericsClass, final ClassNode actualType) {
        return findParameterizedType(genericsClass, actualType, false);
    }

    private static ClassNode findParameterizedTypeFromCache(final ClassNode genericsClass, final ClassNode actualType, final boolean tryToFindExactType) {
        if (!PARAMETERIZED_TYPE_CACHE_ENABLED) {
            return findParameterizedType(genericsClass, actualType, tryToFindExactType);
        }

        SoftReference<ClassNode> sr = PARAMETERIZED_TYPE_CACHE.getAndPut(new ParameterizedTypeCacheKey(genericsClass, actualType), new EvictableCache.ValueProvider<ParameterizedTypeCacheKey, SoftReference<ClassNode>>() {
            @Override
            public SoftReference<ClassNode> provide(ParameterizedTypeCacheKey key) {
                return new SoftReference<>(findParameterizedType(key.getGenericsClass(), key.getActualType(), tryToFindExactType));
            }
        });

        return sr == null ? null : sr.get();
    }

    private static final EvictableCache<ParameterizedTypeCacheKey, SoftReference<ClassNode>> PARAMETERIZED_TYPE_CACHE = new ConcurrentSoftCache<>(64);

    private static final boolean PARAMETERIZED_TYPE_CACHE_ENABLED = Boolean.parseBoolean(SystemUtil.getSystemPropertySafe("groovy.enable.parameterized.type.cache", "true"));

    private static class ParameterizedTypeCacheKey {
        private ClassNode genericsClass;
        private ClassNode actualType;

        public ParameterizedTypeCacheKey(ClassNode genericsClass, ClassNode actualType) {
            this.genericsClass = genericsClass;
            this.actualType = actualType;
        }

        public ClassNode getGenericsClass() {
            return genericsClass;
        }

        @SuppressWarnings("unused")
        public void setGenericsClass(ClassNode genericsClass) {
            this.genericsClass = genericsClass;
        }

        public ClassNode getActualType() {
            return actualType;
        }

        @SuppressWarnings("unused")
        public void setActualType(ClassNode actualType) {
            this.actualType = actualType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ParameterizedTypeCacheKey cacheKey = (ParameterizedTypeCacheKey) o;
            return genericsClass == cacheKey.genericsClass && actualType == cacheKey.actualType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(genericsClass, actualType);
        }
    }
}
